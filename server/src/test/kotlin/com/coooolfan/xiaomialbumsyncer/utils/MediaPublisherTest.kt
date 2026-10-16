package com.coooolfan.xiaomialbumsyncer.utils

import com.coooolfan.xiaomialbumsyncer.icloud.*
import com.coooolfan.xiaomialbumsyncer.model.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO

class MediaPublisherTest {
    @TempDir lateinit var directory: Path
    private val jpeg by lazy { ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray() }
    private val mov = ByteBuffer.allocate(25).putInt(9).put("mdat".toByteArray()).put(1).putInt(8).put("moov".toByteArray()).putInt(8).put("wide".toByteArray()).array()
    private val mapper = jacksonObjectMapper()
    private fun detail(role: String = "resOriginal", version: String = "v1", extension: String = if (role == "resOriginalVidCompl") "mov" else "jpg", checked: Boolean = true): CrontabHistoryDetail {
        val provider = ProviderAccount { id = 1; this.provider = CloudProvider.ICLOUD }
        val album = Album { id = 1; accountId = 1; account = provider; remoteKey = ICloudAlbumKey("PrimarySync", "album").encode(); name = "album" }
        val asset = Asset {
            id = if (role == "resOriginalVidCompl") 2 else 1
            this.album = album; remoteKey = ICloudResourceKey("record", role).encode(); checksum = version
            fileName = "photo.$extension"; mimeType = if (role == "resOriginalVidCompl") "video/quicktime" else "image/jpeg"
            type = if (role == "resOriginalVidCompl") AssetType.VIDEO else AssetType.IMAGE
            size = if (role == "resOriginalVidCompl") mov.size.toLong() else jpeg.size.toLong()
            dateTaken = Instant.EPOCH; motionPhotoHint = false
        }
        val task = Crontab { id = 1; accountId = 1; name = "test"; config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve("out").toString(), true, false, false, null, checkSha1 = checked, synologyPhotos = true) }
        val history = CrontabHistory { id = 1; crontab = task; startTime = Instant.EPOCH }
        return CrontabHistoryDetail(CrontabHistoryDetail.init(history, asset)) { id = asset.id; filePath = directory.resolve("out/photo.$extension").toString() }
    }
    private fun publish(details: List<CrontabHistoryDetail>, fingerprint: String = "v1", download: (Asset, Path) -> Boolean = { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform: (CrontabHistoryDetail, Path) -> Unit = { _, _ -> }) =
        MediaPublisher(directory.resolve("archive")).publish("group", directory.resolve("out").toString(), fingerprint, details, download, transform)

    @Test fun completeGroupReusesRetainedOriginalsAndRepairsMissingOrCorruptOutput() {
        val pair = listOf(detail(), detail("resOriginalVidCompl"))
        var downloads = 0
        val download: (Asset, Path) -> Boolean = { asset, path -> downloads++; Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }
        val first = publish(pair, download = download)
        assertTrue(first.complete); assertEquals(2, downloads)
        assertTrue(first.outputs.all { Files.isRegularFile(Path.of(it.archive)) })
        publish(pair, download = download); assertEquals(2, downloads)
        Files.delete(Path.of(pair[0].filePath)); Files.write(Path.of(pair[1].filePath), byteArrayOf(0))
        publish(pair, download = download)
        assertEquals(2, downloads)
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(pair[0].filePath)))
        assertArrayEquals(mov, Files.readAllBytes(Path.of(pair[1].filePath)))
    }

    @Test fun missingRequiredCompanionNeverPublishesPrimaryAndRetryReusesPrimaryArchive() {
        val pair = listOf(detail(), detail("resOriginalVidCompl"))
        assertThrows(IllegalArgumentException::class.java) { publish(pair, download = { asset, path -> if (asset.type == AssetType.VIDEO) false else { Files.write(path, jpeg); true } }) }
        assertFalse(Files.exists(Path.of(pair[0].filePath))); assertFalse(Files.exists(Path.of(pair[1].filePath)))
        var downloads = 0
        publish(pair, download = { _, path -> downloads++; Files.write(path, mov); true })
        assertEquals(1, downloads)
    }

    @Test fun incompleteOrInvalidOriginalFailsEvenWhenSourceCheckDisabledAndKeepsPreviousExport() {
        val original = detail(); publish(listOf(original))
        val replacement = detail(version = "v2", checked = false)
        assertThrows(IllegalArgumentException::class.java) { publish(listOf(replacement), "v2", download = { _, path -> Files.write(path, jpeg.dropLast(1).toByteArray()); true }) }
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(original.filePath)))
        assertThrows(IllegalArgumentException::class.java) { publish(listOf(replacement), "v2", download = { _, path -> Files.write(path, ByteArray(jpeg.size)); true }) }
    }

    @Test fun copyOnExportKeepsOriginalAndSeparatesHashes() {
        val result = publish(listOf(detail()), transform = { _, copy ->
            // A well formed APP15 segment changes bytes without damaging the JPEG structure.
            val changed = jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xef.toByte(), 0, 3, 7) + jpeg.drop(2)
            Files.write(copy, changed)
        })
        val output = result.outputs.single()
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(output.archive)))
        assertNotEquals(output.originalSha256, output.outputSha256)
    }

    @Test fun configurationVersionChangeReprocessesWithoutDownloading() {
        val input = listOf(detail()); publish(input)
        var transforms = 0
        publish(input, "new-time-settings", download = { _, _ -> fail("archive must be reused") }, transform = { _, _ -> transforms++ })
        assertEquals(1, transforms)
    }

    @Test fun corruptOrMissingArchiveCannotReuseSuccessfulReceipt() {
        val input = listOf(detail()); val first = publish(input)
        Files.write(Path.of(first.outputs.single().archive), ByteArray(jpeg.size))
        var downloads = 0
        publish(input, download = { _, path -> downloads++; Files.write(path, jpeg); true })
        assertEquals(1, downloads)
    }

    @Test fun partialPublicationPersistsPendingReceiptAndRepairsAfterRestart() {
        val pair = listOf(detail(), detail("resOriginalVidCompl"))
        Files.createDirectories(Path.of(pair[0].filePath))
        assertThrows(Exception::class.java) { publish(pair) }
        val receipt = Files.list(directory.resolve("archive/receipts")).use { it.findFirst().orElseThrow() }
        assertFalse(mapper.readTree(Files.readString(receipt)).path("complete").asBoolean())
        assertTrue(Files.isRegularFile(Path.of(pair[1].filePath)))
        Files.delete(Path.of(pair[0].filePath))
        val repaired = publish(pair, download = { _, _ -> fail("archive must be reused") })
        assertTrue(repaired.complete)
    }

    @Test fun extensionAndSelectedRolesReconcileOnlyReceiptOwnedFiles() {
        val pair = listOf(detail(), detail("resOriginalVidCompl")); publish(pair)
        val unowned = directory.resolve("out/user-photo.jpg"); Files.write(unowned, jpeg)
        val changed = detail(version = "v2", extension = "jpeg")
        publish(listOf(changed), "still-v2")
        assertFalse(Files.exists(Path.of(pair[0].filePath)))
        assertFalse(Files.exists(Path.of(pair[1].filePath)))
        assertTrue(Files.exists(unowned)); assertTrue(Files.exists(Path.of(changed.filePath)))
        assertEquals(3, Files.list(directory.resolve("archive/originals")).use { paths -> paths.filter { it.fileName.toString().endsWith(".original") }.count() })
    }

    @Test fun externallyModifiedObsoleteOwnedExportIsPreservedAndReported() {
        val original = detail(); publish(listOf(original))
        val changed = jpeg + byteArrayOf(42)
        Files.write(Path.of(original.filePath), changed)
        assertThrows(IllegalArgumentException::class.java) {
            publish(listOf(detail(version = "v2", extension = "jpeg")), "v2")
        }
        assertArrayEquals(changed, Files.readAllBytes(Path.of(original.filePath)))
    }

    @Test fun finalFilesystemTimeIsAppliedAndAlteredTimeTriggersRepair() {
        val base = detail()
        val timed = CrontabHistoryDetail(base) {
            crontabHistory = CrontabHistory(base.crontabHistory) {
                crontab = Crontab(base.crontabHistory.crontab) { config = base.crontabHistory.crontab.config.copy(rewriteFileSystemTime = true) }
            }
        }
        publish(listOf(timed), transform = { item, copy -> rewriteFSTime(copy, item.asset.dateTaken) })
        val path = Path.of(timed.filePath)
        assertEquals(Instant.EPOCH, Files.getLastModifiedTime(path).toInstant())
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now()))
        var transforms = 0
        publish(listOf(timed), download = { _, _ -> fail("archive must be reused") }, transform = { item, copy ->
            transforms++; rewriteFSTime(copy, item.asset.dateTaken)
        })
        assertEquals(1, transforms)
        assertEquals(Instant.EPOCH, Files.getLastModifiedTime(path).toInstant())
    }

    @Test fun disablingSkipExistingForcesExportProcessingButReusesValidatedOriginal() {
        val base = detail()
        val forced = CrontabHistoryDetail(base) {
            crontabHistory = CrontabHistory(base.crontabHistory) {
                crontab = Crontab(base.crontabHistory.crontab) { config = base.crontabHistory.crontab.config.copy(skipExistingFile = false) }
            }
        }
        publish(listOf(forced))
        var transforms = 0
        publish(listOf(forced), download = { _, _ -> fail("archive must be reused") }, transform = { _, _ -> transforms++ })
        assertEquals(1, transforms)
    }

    @Test fun originalValidationFailureReportsTheAffectedResourceWhileUnavailableDownloadDoesNot() {
        val input = detail(checked = false)
        val failed = mutableListOf<Long>()
        val publisher = MediaPublisher(directory.resolve("archive"))
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publish("group", "layout", "v", listOf(input),
                { _, path -> Files.write(path, ByteArray(jpeg.size)); true }, { _, _ -> },
                validationFailure = { item, _ -> failed += item.id })
        }
        assertEquals(listOf(input.id), failed)
        failed.clear()
        assertThrows(IllegalArgumentException::class.java) {
            publisher.publish("group", "layout", "v", listOf(input), { _, _ -> false }, { _, _ -> },
                validationFailure = { item, _ -> failed += item.id })
        }
        assertTrue(failed.isEmpty())
    }

    @Test fun unownedExistingOutputDoesNotBypassValidation() {
        val input = detail(); Files.createDirectories(Path.of(input.filePath).parent); Files.write(Path.of(input.filePath), jpeg)
        assertThrows(IllegalArgumentException::class.java) { publish(listOf(input), download = { _, _ -> false }) }
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(input.filePath)))
    }

    @Test fun archiveInsideIndexedRootIncludingSymlinkIsRejected() {
        val input = detail()
        assertThrows(IllegalArgumentException::class.java) { MediaPublisher(directory.resolve("out/archive")).publish("group", "layout", "v", listOf(input), { _, _ -> false }, { _, _ -> }) }
        val spaced = CrontabHistoryDetail(input) {
            crontabHistory = CrontabHistory(input.crontabHistory) {
                crontab = Crontab(input.crontabHistory.crontab) {
                    config = input.crontabHistory.crontab.config.copy(targetPath = "  ${directory.resolve("out")}  ")
                }
            }
            filePath = directory.resolve("out/icloud-1/zone/photo.jpg").toString()
        }
        assertThrows(IllegalArgumentException::class.java) { MediaPublisher(directory.resolve("out/archive")).publish("group", "layout", "v", listOf(spaced), { _, _ -> false }, { _, _ -> }) }
        Files.createDirectories(directory.resolve("out")); Files.createSymbolicLink(directory.resolve("alias"), directory.resolve("out"))
        assertThrows(IllegalArgumentException::class.java) { MediaPublisher(directory.resolve("alias/archive")).publish("group", "layout", "v", listOf(input), { _, _ -> false }, { _, _ -> }) }
    }

    @Test fun orphanCompanionCannotBeReportedAsLiveGroup() {
        assertThrows(IllegalArgumentException::class.java) { publish(listOf(detail("resOriginalVidCompl"))) }
    }
}
