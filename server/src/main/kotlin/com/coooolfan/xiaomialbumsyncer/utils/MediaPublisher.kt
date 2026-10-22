package com.coooolfan.xiaomialbumsyncer.utils

import com.coooolfan.xiaomialbumsyncer.model.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.UUID

/** Source and output hashes are evidence; only target media is retained after publication. */
data class PublishedResource(
    val resource: String, val source: String, val path: String, val archive: String,
    val originalSha256: String, val outputSha256: String, val size: Long,
    val verification: VerificationStatus, val diagnostic: String, val motionStatus: MotionPhotoStatus,
)
data class PublicationReceipt(
    val group: String, val layout: String, val fingerprint: String, val complete: Boolean,
    val outputs: List<PublishedResource>, val ownedPaths: List<String>, val ownedHashes: Map<String, String> = emptyMap(), val version: Int = MediaPublisher.VERSION,
)

/** Filesystem transaction journal. Callers serialize publication for a task/group. */
class MediaPublisher(private val root: Path) {
    companion object { const val VERSION = 1 }
    private val mapper = jacksonObjectMapper()

    fun publish(
        group: String,
        layout: String,
        fingerprint: String,
        details: List<CrontabHistoryDetail>,
        download: (Asset, Path) -> Boolean,
        transform: (CrontabHistoryDetail, Path) -> Unit,
        evidence: (CrontabHistoryDetail, PublishedResource) -> Unit = { _, _ -> },
        validationFailure: (CrontabHistoryDetail, Exception) -> Unit = { _, _ -> },
        beforeCommit: () -> Unit = {},
        localReceipts: List<PublicationReceipt> = emptyList(),
    ): PublicationReceipt {
        require(details.isNotEmpty())
        return MediaFileLocks.group(MediaIdentity.of(details.first().asset).groupKey) {
            publishLocked(group, layout, fingerprint, details, download, transform, evidence, validationFailure, beforeCommit, localReceipts)
        }
    }

    private fun publishLocked(
        group: String, layout: String, fingerprint: String, details: List<CrontabHistoryDetail>,
        download: (Asset, Path) -> Boolean, transform: (CrontabHistoryDetail, Path) -> Unit,
        evidence: (CrontabHistoryDetail, PublishedResource) -> Unit,
        validationFailure: (CrontabHistoryDetail, Exception) -> Unit,
        beforeCommit: () -> Unit,
        localReceipts: List<PublicationReceipt>,
    ): PublicationReceipt {
        require(details.isNotEmpty())
        val absoluteRoot = canonical(root)
        val config = details.first().crontabHistory.crontab.config
        if (!config.targetPath.contains("${'$'}{")) require(disjoint(absoluteRoot, canonical(Path.of(config.targetPath.trim())))) {
            "发布状态和临时目录必须位于照片索引目标根目录之外"
        }
        if (details.any { MediaIdentity.of(it.asset).liveCompanion }) require(details.any {
            MediaIdentity.of(it.asset).role == "resOriginal" && it.asset.type == AssetType.IMAGE
        }) { "实况照片缺少主图片，资源组尚未发布" }
        details.forEach {
            val output = canonical(Path.of(it.filePath))
            require(disjoint(absoluteRoot, output.parent)) { "发布状态和临时目录必须位于照片索引目标目录之外" }
        }
        Files.createDirectories(absoluteRoot)
        val receiptPath = absoluteRoot.resolve("receipts/${hash(group + layout)}.json")
        val previous = read<PublicationReceipt>(receiptPath)?.takeIf { it.group == group && it.layout == layout && it.version == VERSION }
        // Existence alone is never sufficient. Check final bytes on every run, even for completed history.
        if (config.skipExistingFile && previous?.complete == true && previous.fingerprint == fingerprint &&
            previous.outputs.size == details.size && previous.outputs.all {
                matches(Path.of(it.path), it.outputSha256)
            }) {
            beforeCommit()
            val reused = previous.copy(outputs = previous.outputs.map { it.copy(archive = "") })
            if (reused != previous) write(receiptPath, reused)
            reused.outputs.forEach { output -> evidence(details.first { it.filePath == output.path }, output) }
            return reused
        }
        val work = absoluteRoot.resolve("work/${UUID.randomUUID()}")
        Files.createDirectories(work)
        try {
            val prepared = details.mapIndexed { index, detail ->
                val asset = detail.asset
                val identity = MediaIdentity.of(asset)
                val source = mapper.writeValueAsString(listOf(identity.resourceKey, asset.checksum, asset.size, asset.mimeType))
                val copy = work.resolve("export-$index.${Path.of(detail.filePath).fileName.toString().substringAfterLast('.', "bin")}")
                val local = prepareLocalOriginal(detail, source, copy, localReceipts)
                val (digest, status, motion) = local ?: run {
                    require(download(asset, copy)) { "云端原件暂不可用，资源组尚未发布" }
                    validateOriginal(detail, copy, validationFailure)
                }
                transform(detail, copy)
                validateOriginalContainer(copy, asset.mimeType)
                inspectMotionPhoto(copy, asset.motionPhotoHint).requireSafeOriginal()
                val output = PublishedResource(identity.resourceKey, source, detail.filePath, "", digest.sha256,
                    fileHash(copy), digest.size, status, motion.diagnostic, motion.status)
                evidence(detail, output)
                copy to output
            }
            return MediaFileLocks.ownership {
                beforeCommit()
                val outputs = prepared.map { it.second }
                val owned = (previous?.ownedPaths.orEmpty() + previous?.outputs.orEmpty().map { it.path } + outputs.map { it.path }).distinct()
                val ownedHashes = previous?.ownedHashes.orEmpty() + previous?.outputs.orEmpty().associate { it.path to it.outputSha256 } + outputs.associate { it.path to it.outputSha256 }
                val obsolete = owned.filter { old -> outputs.none { it.path == old } }
                val retained = if (obsolete.isEmpty()) emptySet() else retainedPaths(receiptPath)
                require(obsolete.all { canonical(Path.of(it)) in retained || noSymlinks(Path.of(it)) }) {
                    "旧导出路径包含符号链接，保留该文件，请检查后重试"
                }
                val pending = PublicationReceipt(group, layout, fingerprint, false, outputs, owned, ownedHashes)
                // Persist before the first rename: interruption/DB failure remains repairable next run.
                write(receiptPath, pending)
                prepared.sortedBy { (_, output) -> if (output.resource.endsWith("/resOriginalVidCompl")) 0 else 1 }.forEach { (copy, output) ->
                    val target = Path.of(output.path).toAbsolutePath().normalize()
                    Files.createDirectories(target.parent)
                    val sibling = target.resolveSibling(".${target.fileName}.photosyncer-${UUID.randomUUID()}.tmp")
                    try {
                        Files.copy(copy, sibling, REPLACE_EXISTING)
                        require(matches(sibling, output.outputSha256)) { "发布副本 SHA-256 不匹配" }
                        Files.setLastModifiedTime(sibling, Files.getLastModifiedTime(copy))
                        atomicMove(sibling, target)
                    } finally { Files.deleteIfExists(sibling) }
                }
                // Only receipt-owned obsolete exports in this exact layout are eligible for removal.
                obsolete.forEach { old ->
                    val path = Path.of(old)
                    if (canonical(path) in retained) return@forEach
                    require(noSymlinks(path)) { "旧导出路径包含符号链接，保留该文件，请检查后重试" }
                    if (Files.exists(path)) {
                        require(ownedHashes[old]?.let { matches(path, it) } == true) { "旧导出文件已被外部修改，保留该文件，请检查后重试" }
                        Files.delete(path)
                    }
                }
                val completed = pending.copy(complete = true, ownedPaths = outputs.map { it.path }, ownedHashes = outputs.associate { it.path to it.outputSha256 })
                write(receiptPath, completed)
                completed
            }
        } finally {
            Files.walk(work).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    /** A migration may reuse only unchanged cloud bytes, never an already transformed export. */
    private fun prepareLocalOriginal(
        detail: CrontabHistoryDetail, source: String, copy: Path, receipts: List<PublicationReceipt>,
    ): Triple<OriginalDigests, VerificationStatus, MotionPhotoInspection>? {
        val task = detail.crontabHistory.crontab
        val identity = MediaIdentity.of(detail.asset)
        val base = "${task.id}/${identity.groupKey}"
        for (receipt in receipts) {
            if (!receipt.complete || receipt.version != VERSION || receipt.group != base &&
                (!receipt.group.startsWith("$base/") || receipt.group.removePrefix("$base/").toLongOrNull() == null)) continue
            for (output in receipt.outputs) {
                if (output.resource != identity.resourceKey || output.source != source || output.size != detail.asset.size ||
                    !output.originalSha256.matches(Regex("[a-f0-9]{64}")) || output.originalSha256 != output.outputSha256) continue
                try {
                    val path = Path.of(output.path).toAbsolutePath().normalize()
                    if (!safeMediaExport(path, receipt.layout, root.toAbsolutePath().normalize()) ||
                        !noSymlinks(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) continue
                    Files.copy(path, copy, REPLACE_EXISTING)
                    Files.setLastModifiedTime(copy, Files.getLastModifiedTime(path))
                    // Validate the copy: a source can disappear or change since discovery.
                    val validated = validateOriginal(detail, copy) { _, _ -> }
                    require(validated.first.sha256 == output.originalSha256) { "本地原件 SHA-256 不匹配" }
                    return validated
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                } catch (_: Exception) {
                    // Try another album copy, then fetch only this resource if none is valid.
                }
                Files.deleteIfExists(copy)
            }
        }
        return null
    }

    private fun validateOriginal(
        detail: CrontabHistoryDetail, path: Path,
        failed: (CrontabHistoryDetail, Exception) -> Unit,
    ): Triple<OriginalDigests, VerificationStatus, MotionPhotoInspection> = try {
        val digest = digestOriginal(path, detail.asset.size)
        validateOriginalContainer(path, detail.asset.mimeType)
        val motion = inspectMotionPhoto(path, detail.asset.motionPhotoHint).requireSafeOriginal()
        Triple(digest, verifySource(detail.asset, detail.crontabHistory.crontab.config, digest), motion)
    } catch (failure: Exception) {
        try { failed(detail, failure) } catch (persistFailure: Exception) { failure.addSuppressed(persistFailure) }
        throw failure
    }

    private fun canonical(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        while (!Files.exists(existing)) existing = existing.parent ?: return absolute
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
    }

    private fun disjoint(first: Path, second: Path): Boolean = !first.startsWith(second) && !second.startsWith(first)

    private fun retainedPaths(receiptPath: Path): Set<Path> {
        require(noSymlinks(receiptPath.parent)) { "发布凭证目录包含符号链接，无法确认其他任务引用" }
        val retained = mutableSetOf<Path>()
        Files.newDirectoryStream(receiptPath.parent, "*.json").use { paths ->
            for (path in paths) {
                if (path == receiptPath) continue
                require(noSymlinks(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "发布凭证路径不安全，无法确认其他任务引用" }
                val receipt = mapper.readValue<PublicationReceipt>(Files.readString(path))
                require(receipt.version == VERSION && path.fileName.toString() == "${hash(receipt.group + receipt.layout)}.json") {
                    "发布凭证不完整，无法确认其他任务引用"
                }
                retained += (receipt.ownedPaths + receipt.outputs.map { it.path }).map { canonical(Path.of(it)) }
            }
        }
        return retained
    }

    private fun verifySource(asset: Asset, config: CrontabConfig, digest: OriginalDigests): VerificationStatus {
        if (!config.checkSha1) return VerificationStatus.SKIPPED
        if (asset.album.account.provider == CloudProvider.ICLOUD) return VerificationStatus.TRANSPORT_VERIFIED
        require(asset.checksum.matches(Regex("[a-fA-F0-9]{40}")) && digest.sha1.equals(asset.checksum, true)) { "小米云文件 SHA-1 不匹配" }
        return VerificationStatus.SOURCE_VERIFIED
    }
    private inline fun <reified T> read(path: Path): T? = if (Files.isRegularFile(path)) runCatching { mapper.readValue<T>(Files.readString(path)) }.getOrNull() else null
    private fun write(path: Path, value: Any) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try { Files.writeString(temp, mapper.writeValueAsString(value)); atomicMove(temp, path) }
        finally { Files.deleteIfExists(temp) }
    }
    private fun matches(path: Path, digest: String): Boolean = Files.isRegularFile(path) && runCatching { fileHash(path) == digest }.getOrDefault(false)
    private fun fileHash(path: Path): String = digestOriginal(path, Files.size(path)).sha256
    private fun hash(value: String): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun atomicMove(from: Path, to: Path) {
        try { Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from, to, REPLACE_EXISTING) }
    }
}
