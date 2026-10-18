package com.coooolfan.xiaomialbumsyncer.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Counts refer to files removed during this invocation, including interrupted-operation retries. */
data class MediaDeletionResult(
    val groupKey: String,
    val status: String,
    val exportsDeleted: Int = 0,
    val originalsDeleted: Int = 0,
    val deferredFiles: Int = 0,
    val conflicts: List<String> = emptyList(),
)
internal data class DeletionOutput(val path: String, val hash: String?, val layout: String)
/** Read-only compatibility with archives written by older releases. New publications never create these. */
internal data class ArchivedOriginal(val source: String, val sha256: String, val size: Long)
internal data class DeletionOriginal(val source: String, val sha256: String, val size: Long)
internal data class MediaDeletionJournal(
    val taskId: Long, val groupKey: String,
    val receipts: List<PublicationReceipt> = emptyList(),
    val originals: List<DeletionOriginal> = emptyList(),
    val complete: Boolean = false, val version: Int = 1,
)

/** A pending journal takes ownership before receipts are released. Missing files are retry-safe. */
class MediaDeletion(private val root: Path) {
    private val mapper = jacksonObjectMapper()
    private val absoluteRoot = root.toAbsolutePath().normalize()

    /** Discover migration work once per run, rather than scanning receipts for every published group. */
    fun legacyLayouts(taskId: Long, photosEnabled: Boolean = true): Map<String, List<PublicationReceipt>> = MediaFileLocks.ownership {
        val directory = absoluteRoot.resolve("receipts")
        val groups = mutableMapOf<String, MutableList<PublicationReceipt>>()
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            require(noSymlinks(directory)) { "发布凭证目录包含符号链接，无法迁移旧文件" }
            Files.newDirectoryStream(directory, "*.json").use { paths -> paths.forEach { path ->
                require(noSymlinks(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
                val receipt = mapper.readValue<PublicationReceipt>(Files.readString(path))
                require(receipt.version == MediaPublisher.VERSION && path.fileName.toString() == "${hash(receipt.group + receipt.layout)}.json")
                val prefix = "$taskId/"
                if (receipt.group.startsWith(prefix)) {
                    val albumLayout = receipt.group.substringAfterLast('/').toLongOrNull() != null
                    if (albumLayout == photosEnabled) {
                        val key = receipt.group.removePrefix(prefix).let { if (albumLayout) it.substringBeforeLast('/') else it }
                        groups.getOrPut(key) { mutableListOf() } += receipt
                    }
                }
            } }
        }
        val journals = absoluteRoot.resolve("deletions")
        if (Files.exists(journals, NOFOLLOW_LINKS)) {
            require(noSymlinks(journals))
            Files.newDirectoryStream(journals, "*.json").use { paths -> paths.forEach { path ->
                require(noSymlinks(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
                val journal = mapper.readValue<MediaDeletionJournal>(Files.readString(path))
                if (journal.taskId == taskId && !journal.complete &&
                    path.fileName.toString() in migrationPaths(taskId, journal.groupKey).map { it.fileName.toString() }) {
                    require(journal.version == 1)
                    require(journal.receipts.all { it.version == MediaPublisher.VERSION &&
                        belongs(it.group, taskId, journal.groupKey) })
                    groups.getOrPut(journal.groupKey) { mutableListOf() } += journal.receipts
                }
            } }
        }
        groups.mapValues { it.value.distinct() }
    }

    /** Retire album-layout receipts only after a complete, intact Photos replacement exists. */
    fun retireAlbumLayouts(taskId: Long, groupKey: String, replacements: List<PublicationReceipt>? = null): MediaDeletionResult = MediaFileLocks.group(groupKey) {
        MediaFileLocks.ownership { deleteLocked(taskId, groupKey, {}, photosLayout = true, replacements = replacements) }
    }

    fun retirePhotosLayouts(taskId: Long, groupKey: String, replacements: List<PublicationReceipt>): MediaDeletionResult = MediaFileLocks.group(groupKey) {
        MediaFileLocks.ownership { deleteLocked(taskId, groupKey, {}, photosLayout = false, replacements = replacements) }
    }

    fun delete(taskId: Long, groupKey: String, stillDeleted: () -> Boolean = { true },
               afterUnlink: (Path) -> Unit = {}): MediaDeletionResult = MediaFileLocks.group(groupKey) {
        val deleted = try { stillDeleted() }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) {
            return@group MediaDeletionResult(groupKey, "FAILED", conflicts = listOf("无法复核云端删除状态，已保留本地文件"))
        }
        if (!deleted) return@group MediaDeletionResult(groupKey, "RESTORED")
        MediaFileLocks.ownership { deleteLocked(taskId, groupKey, afterUnlink) }
    }

    private fun deleteLocked(taskId: Long, groupKey: String, afterUnlink: (Path) -> Unit,
                             photosLayout: Boolean? = null, replacements: List<PublicationReceipt>? = null): MediaDeletionResult {
        val migrating = photosLayout != null
        val conflicts = mutableListOf<String>()
        var exportsDeleted = 0
        var originalsDeleted = 0
        var deferred = 0
        fun result(status: String) = MediaDeletionResult(groupKey, status, exportsDeleted, originalsDeleted, deferred, conflicts.distinct())
        try {
            if (!noSymlinks(absoluteRoot)) {
                conflicts += "原件库路径包含符号链接，已保留文件"
                return result("CONFLICT")
            }
            val receiptDirectory = absoluteRoot.resolve("receipts")
            val journalKey = "$taskId/$groupKey" + when (photosLayout) {
                true -> "/layout-migration"
                false -> "/original-migration"
                null -> ""
            }
            val journalPath = absoluteRoot.resolve("deletions/${hash(journalKey)}.json")
            val active = mutableListOf<Pair<Path, PublicationReceipt>>()
            try {
                require(noSymlinks(journalPath))
                if (Files.exists(receiptDirectory, NOFOLLOW_LINKS)) {
                    require(noSymlinks(receiptDirectory) && Files.isDirectory(receiptDirectory, NOFOLLOW_LINKS))
                    Files.newDirectoryStream(receiptDirectory, "*.json").use { paths ->
                        for (path in paths) {
                            require(noSymlinks(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
                            val receipt = mapper.readValue<PublicationReceipt>(Files.readString(path))
                            require(receipt.version == MediaPublisher.VERSION && path.fileName.toString() == "${hash(receipt.group + receipt.layout)}.json")
                            active += path to receipt
                        }
                    }
                }
            } catch (_: Exception) {
                conflicts += "发布凭证不完整或路径不安全，无法确认其他任务引用，已保留文件"
                return result("CONFLICT")
            }
            val replacementKeys = replacements?.map { it.group to it.layout }?.toSet()
            fun owns(receipt: PublicationReceipt) = belongs(receipt.group, taskId, groupKey) &&
                (!migrating || if (replacementKeys != null) (receipt.group to receipt.layout) !in replacementKeys
                    else receipt.group != "$taskId/$groupKey")
            val own = active.filter { owns(it.second) }
            val retained = active.filterNot { owns(it.second) }.map { it.second }
            var journal = try {
                if (Files.exists(journalPath, NOFOLLOW_LINKS)) mapper.readValue<MediaDeletionJournal>(Files.readString(journalPath)).also {
                    require(it.version == 1 && it.taskId == taskId && it.groupKey == groupKey)
                    require(it.receipts.all { receipt -> belongs(receipt.group, taskId, groupKey) && receipt.version == MediaPublisher.VERSION })
                } else MediaDeletionJournal(taskId, groupKey)
            } catch (_: Exception) {
                conflicts += "删除日志损坏，已保留文件"
                return result("CONFLICT")
            }
            val originals = try { if (migrating) emptyList() else originalInventory(groupKey) } catch (_: Exception) {
                conflicts += "原件归属证据不完整或路径不安全，已保留文件"
                return result("CONFLICT")
            }
            val migrations = migrationPaths(taskId, groupKey).filter { it != journalPath && Files.exists(it, NOFOLLOW_LINKS) }.map { path ->
                require(noSymlinks(path))
                path to mapper.readValue<MediaDeletionJournal>(Files.readString(path)).also {
                    require(it.version == 1 && it.taskId == taskId && it.groupKey == groupKey &&
                        it.receipts.all { receipt -> belongs(receipt.group, taskId, groupKey) && receipt.version == MediaPublisher.VERSION })
                }
            }
            // Detached receipts may share a key with a newly published replacement but still own old paths.
            val pendingReceipts = (journal.receipts + migrations.flatMap { it.second.receipts }).filter {
                belongs(it.group, taskId, groupKey) && if (migrating && replacements != null) it !in replacements else owns(it)
            }
            val mergedReceipts = (own.map { it.second } + pendingReceipts).distinct()
            var migratedPaths = emptySet<Path>()
            if (migrating) {
                val current = replacements ?: retained.filter { it.group == "$taskId/$groupKey" && it.complete }
                val migrated = current.flatMap { it.outputs }.map { it.resource }.toSet()
                if (current.isEmpty() || current.any { !it.complete || it !in retained || !belongs(it.group, taskId, groupKey) ||
                        (it.group == "$taskId/$groupKey") != photosLayout } ||
                    mergedReceipts.any { old -> old.outputs.any { it.resource !in migrated } } ||
                    current.any { receipt -> receipt.outputs.any {
                        !resourceBelongs(it.resource, groupKey) || !sourceBelongs(it.source, groupKey) ||
                            mapper.readTree(it.source)[0].asText() != it.resource || !validHash(it.originalSha256) ||
                            photosLayout == false && it.originalSha256 != it.outputSha256 ||
                            !safeExport(normalized(it.path), receipt.layout) ||
                            !noSymlinks(Path.of(it.path)) || !matches(Path.of(it.path), it.outputSha256)
                    } }) {
                    conflicts += "新布局文件尚未完整发布或校验失败，已保留旧文件"
                    return result("CONFLICT")
                }
                migratedPaths = current.flatMap { it.outputs }.map { normalized(it.path) }.toSet()
            }
            if (mergedReceipts.any { receipt -> receipt.outputs.any {
                !resourceBelongs(it.resource, groupKey) || !sourceBelongs(it.source, groupKey) ||
                    mapper.readTree(it.source)[0].asText() != it.resource || !validHash(it.originalSha256) ||
                    it.archive.isNotBlank() && normalized(it.archive) != absoluteRoot.resolve("originals/${hash(it.source)}.original")
            } }) {
                conflicts += "发布凭证的资源归属不匹配，已保留文件"
                return result("CONFLICT")
            }
            journal = journal.copy(receipts = mergedReceipts,
                originals = (journal.originals + mergedReceipts.flatMap { receipt -> receipt.outputs.filter { it.archive.isNotBlank() }.map {
                    DeletionOriginal(it.source, it.originalSha256, it.size)
                } } + originals).associateBy { it.source }.values.toList(), complete = false)
            // New receipts/inventory are always merged, even after restore and another deletion.
            persist(journalPath, journal)
            own.forEach { (path, _) -> Files.deleteIfExists(path) }
            val retainedPaths = retained.flatMap { it.ownedPaths + it.outputs.map { output -> output.path } }
                .map { normalized(it) }.toSet()
            val retainedArchives = retained.flatMap { it.outputs.filter { output -> output.archive.isNotBlank() }.map { output -> normalized(output.archive) } }.toSet()
            val outputs = journal.receipts.flatMap { receipt ->
                (receipt.ownedPaths + receipt.outputs.map { it.path }).distinct().map { path ->
                    DeletionOutput(path, receipt.ownedHashes[path] ?: receipt.outputs.firstOrNull { it.path == path }?.outputSha256, receipt.layout)
                }
            }.groupBy { normalized(it.path) }
            for ((path, evidence) in outputs) {
                // A custom old template can already match the Photos path. Release only its old receipt.
                if (path in migratedPaths) continue
                if (path in retainedPaths && Files.exists(path, NOFOLLOW_LINKS)) { deferred++; continue }
                val safe = evidence.filter { safeExport(path, it.layout) }
                if (safe.isEmpty() || !noSymlinks(path)) {
                    conflicts += "导出路径不在可信目录内或包含符号链接：$path"
                    continue
                }
                if (!Files.exists(path, NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || safe.none { it.hash?.let { hash -> matches(path, hash) } == true }) {
                    conflicts += "导出文件已被外部修改或缺少哈希证据，已保留：$path"
                    continue
                }
                Files.delete(path)
                exportsDeleted++
                afterUnlink(path)
            }
            for (original in journal.originals) {
                if (!sourceBelongs(original.source, groupKey) || !validHash(original.sha256)) {
                    conflicts += "原件日志的资源归属或哈希无效，已保留文件"
                    continue
                }
                val archive = absoluteRoot.resolve("originals/${hash(original.source)}.original")
                val metadata = archive.resolveSibling("${archive.fileName}.json")
                if (archive in retainedArchives && (Files.exists(archive, NOFOLLOW_LINKS) || Files.exists(metadata, NOFOLLOW_LINKS))) {
                    deferred++
                    continue
                }
                if (!noSymlinks(archive) || !noSymlinks(metadata)) {
                    conflicts += "原件路径包含符号链接，已保留：$archive"
                    continue
                }
                val known = runCatching {
                    if (Files.exists(metadata, NOFOLLOW_LINKS)) mapper.readValue<ArchivedOriginal>(Files.readString(metadata)) else null
                }.getOrNull()
                if (known != null && (known.source != original.source || known.sha256 != original.sha256 || known.size != original.size)) {
                    conflicts += "原件归属证据已改变，已保留：$archive"
                    continue
                }
                if (Files.exists(archive, NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(archive, NOFOLLOW_LINKS) || Files.size(archive) != original.size || !matches(archive, original.sha256)) {
                        conflicts += "原件已被外部修改或证据缺失，已保留：$archive"
                        continue
                    }
                    Files.delete(archive)
                    originalsDeleted++
                    afterUnlink(archive)
                }
                // Journal preserves audit evidence after original metadata is removed.
                if (known != null) Files.deleteIfExists(metadata)
            }
            val complete = conflicts.isEmpty() && deferred == 0
            persist(journalPath, journal.copy(complete = complete))
            if (complete) migrations.forEach { (path, pending) -> persist(path, pending.copy(complete = true)) }
            return result(if (conflicts.isNotEmpty()) "CONFLICT" else if (deferred > 0) "DEFERRED" else "DELETED")
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            conflicts += "本地文件清理未完成，删除日志将在下次同步时重试"
            return result("FAILED")
        }
    }

    private fun migrationPaths(taskId: Long, groupKey: String) = listOf("layout-migration", "original-migration")
        .map { absoluteRoot.resolve("deletions/${hash("$taskId/$groupKey/$it")}.json") }

    private fun originalInventory(groupKey: String): List<DeletionOriginal> {
        val directory = absoluteRoot.resolve("originals")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
        require(noSymlinks(directory) && Files.isDirectory(directory, NOFOLLOW_LINKS))
        val result = mutableListOf<DeletionOriginal>()
        Files.newDirectoryStream(directory, "*.original.json").use { paths ->
            for (path in paths) {
                require(noSymlinks(path) && Files.isRegularFile(path, NOFOLLOW_LINKS))
                val known = mapper.readValue<ArchivedOriginal>(Files.readString(path))
                require(path.fileName.toString() == "${hash(known.source)}.original.json" && validHash(known.sha256) && known.size >= 0)
                if (sourceBelongs(known.source, groupKey)) result += DeletionOriginal(known.source, known.sha256, known.size)
            }
        }
        return result
    }

    private fun belongs(group: String, taskId: Long, groupKey: String): Boolean {
        val prefix = "$taskId/$groupKey"
        return group == prefix || group.startsWith("$prefix/") && group.removePrefix("$prefix/").toLongOrNull() != null
    }
    private fun resourceBelongs(resource: String, group: String): Boolean =
        resource.startsWith("$group/") && resource.removePrefix("$group/").let { it.isNotBlank() && '/' !in it }
    private fun sourceBelongs(source: String, group: String): Boolean = runCatching {
        val node = mapper.readTree(source)
        node.isArray && node.size() == 4 && node[0].isTextual && resourceBelongs(node[0].asText(), group) &&
            node[1].isTextual && node[2].isIntegralNumber && node[2].asLong() >= 0 && node[3].isTextual
    }.getOrDefault(false)
    private fun safeExport(path: Path, layout: String) = safeMediaExport(path, layout, absoluteRoot)
    private fun normalized(value: String): Path = Path.of(value).toAbsolutePath().normalize()
    private fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    private fun matches(path: Path, expected: String) = validHash(expected) && runCatching {
        digestOriginal(path, Files.size(path)).sha256 == expected
    }.getOrDefault(false)
    private fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun persist(path: Path, value: Any) {
        require(noSymlinks(path)) { "删除日志路径包含符号链接" }
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, mapper.writeValueAsString(value))
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            try { Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
