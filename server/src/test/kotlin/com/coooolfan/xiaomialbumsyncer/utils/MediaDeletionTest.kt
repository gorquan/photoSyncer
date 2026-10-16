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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class MediaDeletionTest {
    @TempDir lateinit var temporary: Path
    private val directory get() = temporary.toRealPath()
    private val root get() = directory.resolve("media")
    private val jpeg by lazy { ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray() }
    private val mov = ByteBuffer.allocate(25).putInt(9).put("mdat".toByteArray()).put(1).putInt(8).put("moov".toByteArray()).putInt(8).put("wide".toByteArray()).array()
    private val mapper = jacksonObjectMapper()
    private fun detail(role: String = "resOriginal", version: String = "v1", task: Long = 1, out: String = "out", provider: CloudProvider = CloudProvider.ICLOUD): CrontabHistoryDetail {
        val account = ProviderAccount { id = 1; this.provider = provider }
        val album = Album { id = 1; accountId = 1; this.account = account; remoteKey = if (provider == CloudProvider.ICLOUD) ICloudAlbumKey("PrimarySync", "album").encode() else "album"; name = "album" }
        val asset = Asset {
            id = if (role == "resOriginalVidCompl") 2 else 1; this.album = album
            remoteKey = if (provider == CloudProvider.ICLOUD) ICloudResourceKey("record", role).encode() else "record"
            checksum = version; fileName = if (role == "resOriginalVidCompl") "photo.mov" else "photo.jpg"
            mimeType = if (role == "resOriginalVidCompl") "video/quicktime" else "image/jpeg"
            type = if (role == "resOriginalVidCompl") AssetType.VIDEO else AssetType.IMAGE
            size = if (role == "resOriginalVidCompl") mov.size.toLong() else jpeg.size.toLong()
            dateTaken = Instant.EPOCH; motionPhotoHint = false; cloudDeleted = false
        }
        val job = Crontab { id = task; accountId = 1; name = "test"; config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve(out).toString(), true, true, false, null, synologyPhotos = true, syncCloudDeletes = true) }
        val history = CrontabHistory { id = 1; crontab = job; startTime = Instant.EPOCH }
        return CrontabHistoryDetail(CrontabHistoryDetail.init(history, asset)) { id = asset.id }
    }
    private fun key(input: CrontabHistoryDetail = detail()) = MediaIdentity.of(input.asset).groupKey
    private fun publish(inputs: List<CrontabHistoryDetail>, transform: (CrontabHistoryDetail, Path) -> Unit = { _, _ -> }): PublicationReceipt {
        val task = inputs.first().crontabHistory.crontab
        return MediaPublisher(root).publish("${task.id}/${key(inputs.first())}", task.config.targetPath, inputs.map { it.asset.checksum }.toString(), inputs,
            { asset, path -> Files.write(path, if (asset.type == AssetType.VIDEO) mov else jpeg); true }, transform)
    }
    private fun delete(task: Long = 1) = MediaDeletion(root).delete(task, key())
    private fun countOriginals() = Files.list(root.resolve("originals")).use { it.filter { path -> path.fileName.toString().endsWith(".original") }.count() }

    @Test fun applePairAlternateDeletionAndRepeatAreIdempotent() {
        val receipt = publish(listOf(detail(), detail("resOriginalVidCompl"), detail("resOriginalAlt")))
        val result = delete()
        assertEquals("DELETED", result.status); assertEquals(3, result.exportsDeleted); assertEquals(3, result.originalsDeleted)
        assertTrue(receipt.outputs.all { !Files.exists(Path.of(it.path)) && !Files.exists(Path.of(it.archive)) })
        assertEquals(0L, Files.list(root.resolve("receipts")).use { it.count() })
        val repeated = delete(); assertEquals("DELETED", repeated.status); assertEquals(0, repeated.exportsDeleted); assertEquals(0, repeated.originalsDeleted)
    }
    @Test fun xiaomiSingleFileIsDeleted() {
        val input = detail(provider = CloudProvider.XIAOMI)
        val receipt = publish(listOf(input))
        assertEquals("DELETED", MediaDeletion(root).delete(1, key(input)).status)
        assertFalse(Files.exists(Path.of(receipt.outputs.single().path))); assertFalse(Files.exists(Path.of(receipt.outputs.single().archive)))
    }
    @Test fun oldVersionsAndFailedPrepareOriginalsAreDeleted() {
        publish(listOf(detail())); publish(listOf(detail(version = "v2")))
        assertThrows(IllegalStateException::class.java) { publish(listOf(detail(version = "v3")), transform = { _, _ -> error("interrupted prepare") }) }
        assertEquals(3L, countOriginals()); assertEquals(3, delete().originalsDeleted); assertEquals(0L, countOriginals())
    }
    @Test fun interruptedJournalRetriesRemainingPairAndReportsActualPartialCounts() {
        val receipt = publish(listOf(detail(), detail("resOriginalVidCompl")))
        val failed = MediaDeletion(root).delete(1, key(), afterUnlink = { error("simulated interruption") })
        assertEquals("FAILED", failed.status); assertEquals(1, failed.exportsDeleted)
        assertEquals(0L, Files.list(root.resolve("receipts")).use { it.count() })
        assertEquals(1, receipt.outputs.count { Files.exists(Path.of(it.path)) })
        val retried = delete(); assertEquals("DELETED", retried.status); assertEquals(1, retried.exportsDeleted); assertEquals(2, retried.originalsDeleted)
    }
    @Test fun modifiedAndUnownedExportsSurviveAndConflictIsRetryable() {
        val receipt = publish(listOf(detail())); val output = Path.of(receipt.outputs.single().path)
        Files.write(output, jpeg + byteArrayOf(9))
        val foreign = directory.resolve("out/user.jpg"); Files.write(foreign, jpeg)
        val result = delete(); assertEquals("CONFLICT", result.status); assertEquals(0, result.exportsDeleted)
        assertTrue(Files.exists(output)); assertTrue(Files.exists(foreign))
        Files.write(output, jpeg); assertEquals("DELETED", delete().status)
        assertFalse(Files.exists(output)); assertTrue(Files.exists(foreign))
    }
    @Test fun changedOriginalIsPreservedWhileOwnedExportIsRemoved() {
        val receipt = publish(listOf(detail())); val archive = Path.of(receipt.outputs.single().archive)
        Files.write(archive, jpeg + byteArrayOf(3))
        val result = delete(); assertEquals("CONFLICT", result.status); assertEquals(1, result.exportsDeleted); assertEquals(0, result.originalsDeleted)
        assertTrue(Files.exists(archive)); assertTrue(Files.exists(archive.resolveSibling("${archive.fileName}.json")))
    }
    @Test fun sharedReceiptReferencesDeferAndTwoDeletingTasksCanFinishWithoutCircularWait() {
        val receipt = publish(listOf(detail())); publish(listOf(detail(task = 2)))
        val first = delete(); assertEquals("DEFERRED", first.status); assertEquals(2, first.deferredFiles)
        assertTrue(Files.exists(Path.of(receipt.outputs.single().archive)))
        val second = delete(2); assertEquals("DELETED", second.status); assertEquals(1, second.exportsDeleted); assertEquals(1, second.originalsDeleted)
        assertEquals("DELETED", delete().status)
    }
    @Test fun retainedOtherLayoutKeepsItsExportAndSharedArchive() {
        val first = publish(listOf(detail())); val retained = publish(listOf(detail(task = 2, out = "out-two")))
        val result = delete(); assertEquals("DEFERRED", result.status); assertEquals(1, result.exportsDeleted); assertEquals(1, result.deferredFiles)
        assertFalse(Files.exists(Path.of(first.outputs.single().path))); assertTrue(Files.exists(Path.of(retained.outputs.single().path)))
        assertTrue(Files.exists(Path.of(retained.outputs.single().archive)))
    }
    @Test fun symlinkAndEscapedPathsKeepTargets() {
        val receipt = publish(listOf(detail())); val output = Path.of(receipt.outputs.single().path)
        val foreign = directory.resolve("foreign.jpg"); Files.write(foreign, jpeg)
        Files.delete(output); Files.createSymbolicLink(output, foreign)
        assertEquals("CONFLICT", delete().status); assertTrue(Files.isSymbolicLink(output)); assertTrue(Files.exists(foreign))
        Files.delete(output)
        val fresh = publish(listOf(detail()))
        val receiptPath = Files.list(root.resolve("receipts")).use { it.findFirst().orElseThrow() }
        Files.writeString(receiptPath, mapper.writeValueAsString(fresh.copy(ownedPaths = listOf(foreign.toString()), ownedHashes = mapOf(foreign.toString() to fresh.outputs.single().outputSha256))))
        assertEquals("CONFLICT", delete().status); assertTrue(Files.exists(foreign))
    }
    @Test fun symlinkAncestorAndMalformedReceiptFailClosed() {
        val receipt = publish(listOf(detail())); val parent = Path.of(receipt.outputs.single().path).parent
        val elsewhere = directory.resolve("elsewhere"); Files.move(parent, elsewhere); Files.createSymbolicLink(parent, elsewhere)
        assertEquals("CONFLICT", delete().status)
        assertTrue(Files.exists(elsewhere.resolve(Path.of(receipt.outputs.single().path).fileName)))
        Files.writeString(root.resolve("receipts/broken.json"), "invalid")
        assertEquals("CONFLICT", delete().status)
    }
    @Test fun restoreThenDeleteAgainMergesFreshReceiptsAndVersions() {
        publish(listOf(detail())); assertEquals("DELETED", delete().status)
        publish(listOf(detail(version = "v2"), detail("resOriginalVidCompl", version = "movie-v2")))
        assertEquals("RESTORED", MediaDeletion(root).delete(1, key(), stillDeleted = { false }).status); assertEquals(2L, countOriginals())
        val second = delete(); assertEquals("DELETED", second.status); assertEquals(2, second.exportsDeleted); assertEquals(2, second.originalsDeleted)
    }
    @Test fun groupLockSerializesPublishingAndDeleting() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val input = detail()
            val publishing = pool.submit<PublicationReceipt> {
                MediaPublisher(root).publish("1/${key()}", input.crontabHistory.crontab.config.targetPath, "v", listOf(input),
                    { _, path -> started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); Files.write(path, jpeg); true }, { _, _ -> })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val deleting = pool.submit<MediaDeletionResult> { delete() }; assertFalse(deleting.isDone)
            release.countDown(); val receipt = publishing.get(5, TimeUnit.SECONDS)
            assertEquals("DELETED", deleting.get(5, TimeUnit.SECONDS).status)
            assertFalse(Files.exists(Path.of(receipt.outputs.single().path))); assertFalse(Files.exists(Path.of(receipt.outputs.single().archive)))
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun priorLayoutsAndLegacyFixedPrefixAreCleanedTogether() {
        val first = publish(listOf(detail()))
        val second = publish(listOf(detail(out = "new-layout")))
        val input = detail(out = "legacy")
        val legacy = MediaPublisher(root).publish("1/${key()}/1", directory.resolve("legacy").toString() + "/${'$'}{album}/file.jpg", "legacy", listOf(input),
            { _, path -> Files.write(path, jpeg); true }, { _, _ -> })
        val result = delete()
        assertEquals("DELETED", result.status); assertEquals(3, result.exportsDeleted); assertEquals(1, result.originalsDeleted)
        assertTrue((first.outputs + second.outputs + legacy.outputs).all { !Files.exists(Path.of(it.path)) })
    }
    @Test fun unrelatedGroupAndUnownedOriginalBytesSurvive() {
        val receipt = publish(listOf(detail()))
        val unowned = root.resolve("originals/unowned.original"); Files.write(unowned, jpeg)
        assertEquals("DELETED", MediaDeletion(root).delete(1, "other-group").status)
        assertTrue(Files.exists(Path.of(receipt.outputs.single().path)))
        assertTrue(Files.exists(Path.of(receipt.outputs.single().archive)))
        assertEquals("DELETED", delete().status)
        assertTrue(Files.exists(unowned))
    }
    @Test fun interruptionAfterOriginalUnlinkRetainsCountsAndRetriesMetadataRemoval() {
        val receipt = publish(listOf(detail()))
        val failed = MediaDeletion(root).delete(1, key(), afterUnlink = { path ->
            if (path.fileName.toString().endsWith(".original")) error("interrupted after original unlink")
        })
        assertEquals("FAILED", failed.status); assertEquals(1, failed.exportsDeleted); assertEquals(1, failed.originalsDeleted)
        val metadata = Path.of(receipt.outputs.single().archive + ".json")
        assertTrue(Files.exists(metadata)); assertEquals("DELETED", delete().status); assertFalse(Files.exists(metadata))
    }
    @Test fun providerDownloadRemainsOutsideGlobalOwnershipLock() {
        val downloaded = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        try {
            lateinit var future: java.util.concurrent.Future<PublicationReceipt>
            MediaFileLocks.ownership {
                val input = detail()
                future = pool.submit<PublicationReceipt> {
                    MediaPublisher(root).publish("1/${key()}", input.crontabHistory.crontab.config.targetPath, "v", listOf(input),
                        { _, path -> downloaded.countDown(); Files.write(path, jpeg); true }, { _, _ -> })
                }
                assertTrue(downloaded.await(5, TimeUnit.SECONDS))
            }
            assertTrue(future.get(5, TimeUnit.SECONDS).complete)
        } finally { pool.shutdownNow() }
    }

    @Test fun missingSharedFilesAreIdempotentAndReceiptCanProveAnOriginalWithoutSidecar() {
        val receipt = publish(listOf(detail())); publish(listOf(detail(task = 2)))
        val output = receipt.outputs.single()
        Files.delete(Path.of(output.path)); Files.delete(Path.of(output.archive)); Files.delete(Path.of(output.archive + ".json"))
        assertEquals("DELETED", delete().status)
        assertEquals("DELETED", delete(2).status)
        val restored = publish(listOf(detail(version = "new")))
        Files.delete(Path.of(restored.outputs.single().archive + ".json"))
        assertEquals("DELETED", delete().status)
        assertFalse(Files.exists(Path.of(restored.outputs.single().archive)))
    }
    @Test fun failedPresenceRecheckPreservesFilesAndReportsFailure() {
        val receipt = publish(listOf(detail()))
        val result = MediaDeletion(root).delete(1, key(), stillDeleted = { error("database unavailable") })
        assertEquals("FAILED", result.status); assertEquals(0, result.exportsDeleted); assertEquals(0, result.originalsDeleted)
        assertTrue(Files.exists(Path.of(receipt.outputs.single().path))); assertTrue(Files.exists(Path.of(receipt.outputs.single().archive)))
    }

}
