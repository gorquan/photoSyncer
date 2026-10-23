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
    @TempDir lateinit var temporary: Path
    private val directory get() = temporary.toRealPath()
    private val jpeg by lazy { ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray() }
    private val mov = ByteBuffer.allocate(25).putInt(9).put("mdat".toByteArray()).put(1).putInt(8).put("moov".toByteArray()).putInt(8).put("wide".toByteArray()).array()
    private val mapper = jacksonObjectMapper()
    private fun detail(role: String = "resOriginal", version: String = "v1", extension: String = if (role == "resOriginalVidCompl") "mov" else "jpg"): CrontabHistoryDetail {
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
        val task = Crontab { id = 1; accountId = 1; name = "test"; config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve("out").toString(), downloadImages = true, downloadVideos = false, skipExistingFile = true, synologyPhotos = true) }
        val history = CrontabHistory { id = 1; crontab = task; startTime = Instant.EPOCH }
        return CrontabHistoryDetail(CrontabHistoryDetail.init(history, asset)) { id = asset.id; filePath = directory.resolve("out/photo.$extension").toString() }
    }
    private fun publish(details: List<CrontabHistoryDetail>, fingerprint: String = "v1", download: (Asset, Path) -> Boolean = { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform: (CrontabHistoryDetail, Path) -> Unit = { _, _ -> }) =
        MediaPublisher(directory.resolve("archive")).publish("group", directory.resolve("out").toString(), fingerprint, details, download, transform)

    private fun assertNoRetainedMedia() {
        assertFalse(Files.exists(directory.resolve("archive/originals")))
        val work = directory.resolve("archive/work")
        if (Files.exists(work)) assertEquals(0L, Files.list(work).use { it.count() })
    }

    private fun legacy(details: List<CrontabHistoryDetail>, folder: String = "old", transform: (CrontabHistoryDetail, Path) -> Unit = { _, _ -> }): PublicationReceipt {
        val inputs = details.map { input -> CrontabHistoryDetail(input) {
            crontabHistory = CrontabHistory(input.crontabHistory) {
                crontab = Crontab(input.crontabHistory.crontab) {
                    config = input.crontabHistory.crontab.config.copy(synologyPhotos = false, targetPath = directory.resolve(folder).toString())
                }
            }
            filePath = directory.resolve("$folder/${Path.of(input.filePath).fileName}").toString()
        } }
        return MediaPublisher(directory.resolve("archive")).publish("1/${MediaIdentity.of(inputs.first().asset).groupKey}/1",
            directory.resolve(folder).toString(), "legacy-$folder", inputs,
            { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform)
    }

    private fun migrate(details: List<CrontabHistoryDetail>, receipts: List<PublicationReceipt>,
                        download: (Asset, Path) -> Boolean = { _, _ -> fail("valid local original must not download") },
                        transform: (CrontabHistoryDetail, Path) -> Unit = { _, _ -> }): PublicationReceipt =
        MediaPublisher(directory.resolve("archive")).publish("1/${MediaIdentity.of(details.first().asset).groupKey}",
            directory.resolve("out").toString(), "photos-${details.map { it.asset.checksum }}", details, download, transform,
            validationFailure = { _, _ -> fail("rejected local candidates must not clear database evidence") }, localReceipts = receipts)

    @Test fun photosMigrationReusesApplePairEvenWithSkipExistingDisabled() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl")).map { input -> CrontabHistoryDetail(input) {
            crontabHistory = CrontabHistory(input.crontabHistory) {
                crontab = Crontab(input.crontabHistory.crontab) { config = input.crontabHistory.crontab.config.copy(skipExistingFile = false) }
            }
        } }
        val old = legacy(inputs)
        val transformed = mutableListOf<Long>()
        val current = migrate(inputs, listOf(old), transform = { item, _ -> transformed += item.id })
        assertEquals(inputs.map { it.id }, transformed)
        assertTrue(current.outputs.all { it.verification == VerificationStatus.TRANSPORT_VERIFIED })
        assertEquals(old.outputs.map { it.originalSha256 }, current.outputs.map { it.originalSha256 })
        assertTrue(old.outputs.all { Files.exists(Path.of(it.path)) }, "publisher must leave old media until retirement")
        assertEquals("DELETED", MediaDeletion(directory.resolve("archive")).retireAlbumLayouts(1, MediaIdentity.of(inputs.first().asset).groupKey).status)
        assertTrue(old.outputs.none { Files.exists(Path.of(it.path)) })
        assertNoRetainedMedia()
    }

    @Test fun photosMigrationReusesXiaomiOriginalWithSourceSha1Verification() {
        val base = detail()
        val input = CrontabHistoryDetail(base) {
            asset = Asset(base.asset) {
                album = Album(base.asset.album) { account = ProviderAccount(base.asset.album.account) { provider = CloudProvider.XIAOMI } }
                remoteKey = "xiaomi-record"
                checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(jpeg))
            }
        }
        val current = migrate(listOf(input), listOf(legacy(listOf(input))))
        assertEquals(VerificationStatus.SOURCE_VERIFIED, current.outputs.single().verification)
        assertNoRetainedMedia()
    }

    @Test fun photosMigrationDownloadsOnlyMissingOrCorruptComponent() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl"))
        val old = legacy(inputs)
        Files.delete(Path.of(old.outputs.last().path))
        val downloaded = mutableListOf<AssetType>()
        migrate(inputs, listOf(old), download = { asset, path -> downloaded += asset.type; Files.write(path, mov); true })
        assertEquals(listOf(AssetType.VIDEO), downloaded)
        // Keep the same size and valid container so rejection requires the recorded SHA-256.
        Files.write(Path.of(old.outputs.first().path), jpeg.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() })
        Files.write(Path.of(old.outputs.last().path), mov)
        Files.delete(Path.of(inputs.first().filePath))
        downloaded.clear()
        migrate(inputs, listOf(old), download = { asset, path -> downloaded += asset.type; Files.write(path, jpeg); true })
        assertEquals(listOf(AssetType.IMAGE), downloaded)
        assertNoRetainedMedia()
    }

    @Test fun photosMigrationTriesAnotherAlbumCopyBeforeDownloading() {
        val input = listOf(detail())
        val first = legacy(input, "old-one")
        val second = legacy(input, "old-two")
        Files.delete(Path.of(first.outputs.single().path))
        assertTrue(migrate(input, listOf(first, second)).complete)
        assertNoRetainedMedia()
    }

    @Test fun photosMigrationRejectsChangedVersionAndTransformedBytes() {
        val old = legacy(listOf(detail()))
        var downloads = 0
        val download: (Asset, Path) -> Boolean = { _, path -> downloads++; Files.write(path, jpeg); true }
        migrate(listOf(detail(version = "v2")), listOf(old), download)
        assertEquals(1, downloads)
        val transformed = legacy(listOf(detail()), "old-transformed", transform = { _, path ->
            Files.write(path, jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xef.toByte(), 0, 3, 7) + jpeg.drop(2))
        })
        migrate(listOf(detail()), listOf(transformed), download)
        assertEquals(2, downloads)
        assertNoRetainedMedia()
    }

    @Test fun photosMigrationRejectsUntrustedOwnershipAndPaths() {
        val inputs = listOf(detail())
        val old = legacy(inputs)
        var downloads = 0
        val download: (Asset, Path) -> Boolean = { _, path -> downloads++; Files.write(path, jpeg); true }
        val wrongOwner = old.copy(group = "2/${MediaIdentity.of(inputs.first().asset).groupKey}/1")
        val escaped = old.copy(layout = directory.resolve("elsewhere").toString())
        val link = directory.resolve("old/link.jpg")
        Files.createSymbolicLink(link, Path.of(old.outputs.single().path))
        val symlink = old.copy(outputs = old.outputs.map { it.copy(path = link.toString()) })
        for ((index, candidate) in listOf(wrongOwner, escaped, symlink).withIndex()) {
            MediaPublisher(directory.resolve("archive")).publish("1/${MediaIdentity.of(inputs.first().asset).groupKey}",
                directory.resolve("out").toString(), "paths-$index", inputs, download, { _, _ -> }, localReceipts = listOf(candidate))
        }
        assertEquals(3, downloads)
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(old.outputs.single().path)))
        assertNoRetainedMedia()
    }

    @Test fun failedLocalMigrationPreservesOldPairAndCleansTemporaryCopies() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl"))
        val old = legacy(inputs)
        assertThrows(IllegalStateException::class.java) {
            migrate(inputs, listOf(old), transform = { item, _ ->
                if (item.asset.type == AssetType.VIDEO) error("transform failed")
            })
        }
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(old.outputs.first().path)))
        assertArrayEquals(mov, Files.readAllBytes(Path.of(old.outputs.last().path)))
        assertTrue(inputs.none { Files.exists(Path.of(it.filePath)) })
        assertNoRetainedMedia()
        Files.delete(Path.of(old.outputs.last().path))
        assertThrows(IllegalArgumentException::class.java) { migrate(inputs, listOf(old), download = { _, _ -> false }) }
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(old.outputs.first().path)))
        assertTrue(inputs.none { Files.exists(Path.of(it.filePath)) })
        assertNoRetainedMedia()
    }

    private fun photos(inputs: List<CrontabHistoryDetail>, transform: (CrontabHistoryDetail, Path) -> Unit = { _, _ -> }) =
        MediaPublisher(directory.resolve("archive")).publish("1/${MediaIdentity.of(inputs.first().asset).groupKey}",
            directory.resolve("out").toString(), "photos", inputs,
            { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform)

    private fun restore(inputs: List<CrontabHistoryDetail>, receipt: PublicationReceipt,
                        download: (Asset, Path) -> Boolean = { _, _ -> fail("unchanged Photos bytes must not download") }): PublicationReceipt {
        val ordinary = inputs.map { input -> CrontabHistoryDetail(input) {
            crontabHistory = CrontabHistory(input.crontabHistory) {
                crontab = Crontab(input.crontabHistory.crontab) {
                    config = input.crontabHistory.crontab.config.copy(synologyPhotos = false, targetPath = directory.resolve("restored").toString())
                }
            }
            filePath = directory.resolve("restored/${Path.of(input.filePath).fileName}").toString()
        } }
        return MediaPublisher(directory.resolve("archive")).publish("1/${MediaIdentity.of(inputs.first().asset).groupKey}/1",
            directory.resolve("restored").toString(), "original", ordinary, download, { _, _ -> }, localReceipts = listOf(receipt))
    }

    @Test fun disablingPhotosReusesUnchangedOriginalsAndKeepsCloudLiveCompanion() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl"))
        val old = photos(inputs)
        val originals = restore(inputs, old)
        assertEquals(old.outputs.map { it.originalSha256 }, originals.outputs.map { it.outputSha256 })
        assertTrue(originals.outputs.any { it.resource.endsWith("/resOriginalVidCompl") })
        val cleanup = MediaDeletion(directory.resolve("archive"))
        assertEquals(setOf(MediaIdentity.of(inputs.first().asset).groupKey), cleanup.legacyLayouts(1, false).keys)
        assertEquals("DELETED", cleanup.retirePhotosLayouts(1, MediaIdentity.of(inputs.first().asset).groupKey, listOf(originals)).status)
        assertTrue(old.outputs.none { Files.exists(Path.of(it.path)) })
        assertTrue(originals.outputs.all { Files.isRegularFile(Path.of(it.path)) })
        assertTrue(cleanup.legacyLayouts(1, false).isEmpty())
        assertNoRetainedMedia()
    }

    @Test fun disablingPhotosRedownloadsOnlyTransformedContentAndRestoresExactCloudBytes() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl"))
        val old = photos(inputs, transform = { item, path -> if (item.asset.type == AssetType.IMAGE)
            Files.write(path, jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xef.toByte(), 0, 3, 7) + jpeg.drop(2)) })
        val downloads = mutableListOf<AssetType>()
        val originals = restore(inputs, old) { asset, path ->
            downloads += asset.type
            assertEquals(AssetType.IMAGE, asset.type)
            Files.write(path, jpeg); true
        }
        assertEquals(listOf(AssetType.IMAGE), downloads)
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(originals.outputs.first().path)))
        assertArrayEquals(mov, Files.readAllBytes(Path.of(originals.outputs.last().path)))
        assertTrue(originals.outputs.all { it.originalSha256 == it.outputSha256 })
        assertEquals("DELETED", MediaDeletion(directory.resolve("archive")).retirePhotosLayouts(1,
            MediaIdentity.of(inputs.first().asset).groupKey, listOf(originals)).status)
        assertNoRetainedMedia()
    }

    @Test fun photosCompatibilityCanBeToggledBackAndForthWithoutRedownloadingValidFiles() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl"))

        // Ordinary layout -> Photos layout.
        val ordinary = legacy(inputs)
        val photos = migrate(inputs, listOf(ordinary))
        assertEquals("DELETED", MediaDeletion(directory.resolve("archive")).retireAlbumLayouts(
            1, MediaIdentity.of(inputs.first().asset).groupKey
        ).status)

        // Photos layout -> ordinary layout. Valid unchanged bytes are reused locally.
        val restored = restore(inputs, photos)
        assertEquals("DELETED", MediaDeletion(directory.resolve("archive")).retirePhotosLayouts(
            1, MediaIdentity.of(inputs.first().asset).groupKey, listOf(restored)
        ).status)

        // Ordinary layout -> Photos layout again. The existing ordinary files are enough;
        // a download would fail the test and would indicate that the toggle reset lost reuse.
        val photosAgain = migrate(inputs, listOf(restored))
        assertTrue(photosAgain.complete)
        assertEquals("DELETED", MediaDeletion(directory.resolve("archive")).retireAlbumLayouts(
            1, MediaIdentity.of(inputs.first().asset).groupKey
        ).status)
        assertNoRetainedMedia()
    }

    @Test fun unavailableCloudOriginalWhileDisablingKeepsOnlyAvailablePhotosCopy() {
        val inputs = listOf(detail(), detail("resOriginalVidCompl"))
        val old = photos(inputs, transform = { item, path -> if (item.asset.type == AssetType.IMAGE)
            Files.write(path, jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xef.toByte(), 0, 3, 7) + jpeg.drop(2)) })
        val before = old.outputs.map { Files.readAllBytes(Path.of(it.path)) }
        assertThrows(IllegalArgumentException::class.java) { restore(inputs, old) { _, _ -> false } }
        old.outputs.forEachIndexed { index, output -> assertArrayEquals(before[index], Files.readAllBytes(Path.of(output.path))) }
        assertFalse(Files.exists(directory.resolve("restored")))
        assertNoRetainedMedia()
    }

    @Test fun completeGroupKeepsOnlyTargetsAndRedownloadsMissingOrCorruptOutput() {
        val pair = listOf(detail(), detail("resOriginalVidCompl"))
        var downloads = 0
        val download: (Asset, Path) -> Boolean = { asset, path -> downloads++; Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }
        val first = publish(pair, download = download)
        assertTrue(first.complete); assertEquals(2, downloads)
        assertTrue(first.outputs.all { it.archive.isEmpty() }); assertNoRetainedMedia()
        publish(pair, download = download); assertEquals(2, downloads)
        Files.delete(Path.of(pair[0].filePath)); Files.write(Path.of(pair[1].filePath), byteArrayOf(0))
        publish(pair, download = download)
        assertEquals(4, downloads)
        assertNoRetainedMedia()
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(pair[0].filePath)))
        assertArrayEquals(mov, Files.readAllBytes(Path.of(pair[1].filePath)))
    }

    @Test fun missingRequiredCompanionLeavesNoPrimaryOrRetainedDownloadAndRetryDownloadsBoth() {
        val pair = listOf(detail(), detail("resOriginalVidCompl"))
        assertThrows(IllegalArgumentException::class.java) { publish(pair, download = { asset, path -> if (asset.type == AssetType.VIDEO) false else { Files.write(path, jpeg); true } }) }
        assertFalse(Files.exists(Path.of(pair[0].filePath))); assertFalse(Files.exists(Path.of(pair[1].filePath)))
        var downloads = 0
        assertNoRetainedMedia()
        publish(pair, download = { asset, path -> downloads++; Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true })
        assertEquals(2, downloads)
        assertNoRetainedMedia()
    }

    @Test fun incompleteOrInvalidOriginalFailsAndKeepsPreviousExport() {
        val original = detail(); publish(listOf(original))
        val replacement = detail(version = "v2")
        assertThrows(IllegalArgumentException::class.java) { publish(listOf(replacement), "v2", download = { _, path -> Files.write(path, jpeg.dropLast(1).toByteArray()); true }) }
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(original.filePath)))
        assertThrows(IllegalArgumentException::class.java) { publish(listOf(replacement), "v2", download = { _, path -> Files.write(path, ByteArray(jpeg.size)); true }) }
    }

    @Test fun transformedExportRetainsSourceDigestButNoSourceFile() {
        val result = publish(listOf(detail()), transform = { _, copy ->
            // A well formed APP15 segment changes bytes without damaging the JPEG structure.
            val changed = jpeg.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xef.toByte(), 0, 3, 7) + jpeg.drop(2)
            Files.write(copy, changed)
        })
        val output = result.outputs.single()
        assertEquals("", output.archive)
        assertNoRetainedMedia()
        assertNotEquals(output.originalSha256, output.outputSha256)
    }

    @Test fun configurationVersionChangeRedownloadsIntoTemporaryStorage() {
        val input = listOf(detail()); publish(input)
        var transforms = 0
        publish(input, "new-time-settings", download = { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform = { _, _ -> transforms++ })
        assertEquals(1, transforms)
    }

    @Test fun legacyReceiptWithMissingArchiveReusesIntactTargetAndDropsArchiveReference() {
        val input = listOf(detail()); val first = publish(input)
        val receiptPath = Files.list(directory.resolve("archive/receipts")).use { it.findFirst().orElseThrow() }
        val legacy = first.copy(outputs = first.outputs.map { it.copy(archive = directory.resolve("removed-legacy.original").toString()) })
        Files.writeString(receiptPath, mapper.writeValueAsString(legacy))
        val reused = publish(input, download = { _, _ -> fail("intact legacy export must not redownload") },
            transform = { _, _ -> fail("intact legacy export must not transform") })
        assertTrue(reused.outputs.all { it.archive.isEmpty() })
        assertTrue(mapper.readTree(Files.readString(receiptPath)).path("outputs")[0].path("archive").asText().isEmpty())
        assertNoRetainedMedia()
    }

    @Test fun partialPublicationPersistsPendingReceiptAndRepairsAfterRestart() {
        val pair = listOf(detail(), detail("resOriginalVidCompl"))
        Files.createDirectories(Path.of(pair[0].filePath))
        assertThrows(Exception::class.java) { publish(pair) }
        val receipt = Files.list(directory.resolve("archive/receipts")).use { it.findFirst().orElseThrow() }
        assertFalse(mapper.readTree(Files.readString(receipt)).path("complete").asBoolean())
        assertTrue(Files.isRegularFile(Path.of(pair[1].filePath)))
        Files.delete(Path.of(pair[0].filePath))
        val repaired = publish(pair, download = { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true })
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
        assertNoRetainedMedia()
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

    @Test fun disablingSkipExistingForcesNewTemporaryDownloadAndProcessing() {
        val base = detail()
        val forced = CrontabHistoryDetail(base) {
            crontabHistory = CrontabHistory(base.crontabHistory) {
                crontab = Crontab(base.crontabHistory.crontab) { config = base.crontabHistory.crontab.config.copy(skipExistingFile = false) }
            }
        }
        publish(listOf(forced))
        var transforms = 0
        publish(listOf(forced), download = { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform = { _, _ -> transforms++ })
        assertEquals(1, transforms)
    }

    @Test fun originalValidationFailureReportsTheAffectedResourceWhileUnavailableDownloadDoesNot() {
        val input = detail()
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

    @Test fun indexedDestinationInsideArchiveRootIsRejectedBeforeDownload() {
        val input = detail()
        val archiveRoot = directory.resolve("archive")
        val nested = CrontabHistoryDetail(input) {
            crontabHistory = CrontabHistory(input.crontabHistory) {
                crontab = Crontab(input.crontabHistory.crontab) {
                    config = input.crontabHistory.crontab.config.copy(targetPath = archiveRoot.resolve("out").toString())
                }
            }
            filePath = archiveRoot.resolve("out/photo.jpg").toString()
        }
        var downloaded = false
        assertThrows(IllegalArgumentException::class.java) {
            MediaPublisher(archiveRoot).publish("group", nested.crontabHistory.crontab.config.targetPath, "v", listOf(nested),
                { _, path -> downloaded = true; Files.write(path, jpeg); true }, { _, _ -> })
        }
        assertFalse(downloaded)
        assertFalse(Files.exists(Path.of(nested.filePath)))
    }

    @Test fun presenceChangeDuringPreparationCannotCommitExportsOrReceipt() {
        val input = detail()
        var deleted = false
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MediaPublisher(directory.resolve("archive")).publish("group", directory.resolve("out").toString(), "v", listOf(input),
                { _, path -> Files.write(path, jpeg); true }, { _, _ -> deleted = true },
                beforeCommit = { require(!deleted) { "deleted during download" } })
        }
        assertEquals("deleted during download", failure.message)
        assertFalse(Files.exists(Path.of(input.filePath)))
        assertFalse(Files.exists(directory.resolve("archive/receipts")))
        assertNoRetainedMedia()
    }

    @Test fun receiptReuseAlsoRechecksPresence() {
        val input = detail()
        val published = publish(listOf(input))
        var checked = false
        assertThrows(IllegalArgumentException::class.java) {
            MediaPublisher(directory.resolve("archive")).publish("group", directory.resolve("out").toString(), "v1", listOf(input),
                { _, _ -> fail("receipt should be reusable") }, { _, _ -> fail("receipt should be reusable") },
                beforeCommit = { checked = true; throw IllegalArgumentException("restored source version changed") })
        }
        assertTrue(checked)
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(published.outputs.single().path)))
    }

    @Test fun obsoleteExportBehindSymlinkAncestorIsPreserved() {
        val input = detail()
        publish(listOf(input))
        val oldDirectory = Path.of(input.filePath).parent
        val elsewhere = directory.resolve("external")
        Files.move(oldDirectory, elsewhere)
        Files.createSymbolicLink(oldDirectory, elsewhere)
        assertThrows(IllegalArgumentException::class.java) {
            publish(listOf(detail(version = "v2", extension = "jpeg")), "v2")
        }
        assertArrayEquals(jpeg, Files.readAllBytes(elsewhere.resolve("photo.jpg")))
    }

    @Test fun invalidOtherReceiptCannotAuthorizeObsoleteCleanup() {
        val input = detail()
        publish(listOf(input))
        Files.writeString(directory.resolve("archive/receipts/broken.json"), "invalid")
        assertThrows(Exception::class.java) {
            publish(listOf(detail(version = "v2", extension = "jpeg")), "v2")
        }
        assertArrayEquals(jpeg, Files.readAllBytes(Path.of(input.filePath)))
    }
}
