package com.coooolfan.xiaomialbumsyncer.model

import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.Path

@Entity
interface CrontabHistoryDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long

    @OnDissociate(DissociateAction.DELETE)
    @ManyToOne
    val crontabHistory: CrontabHistory

    val downloadTime: Instant

    @OnDissociate(DissociateAction.DELETE)
    @ManyToOne
    val asset: Asset

    val filePath: String

    // 下载
    val downloadCompleted: Boolean

    // Legacy verification-stage completion flag; see verificationStatus for actual evidence.
    val sha1Verified: Boolean

    @Default("LEGACY")
    val verificationStatus: VerificationStatus

    val localSha256: String?

    val verifiedSize: Long?

    @Default("LEGACY")
    val publicationStatus: String

    val archivePath: String?

    val outputSha256: String?

    @Default("0")
    val exportVersion: Int

    // EXIF 填充
    val exifFilled: Boolean

    // 修改时间更新
    val fsTimeUpdated: Boolean

    val message: String?

    companion object {
        private val TOKEN_REGEX = Regex("""\$\{([^}]+)}""")

        // 保留旧导入实体（缺少账号关系）读取历史路径的兼容常量。
        const val DEFAULT_FILE_TEMPLATE = "\${album}/\${downloadFileName}"

        fun init(
            history: CrontabHistory,
            asset: Asset
        ): CrontabHistoryDetail {
            return CrontabHistoryDetail {
                this.crontabHistory = history
                this.downloadTime = Instant.now()
                this.asset = asset
                this.filePath = genFilePath(history, asset)
                this.downloadCompleted = false
                this.sha1Verified = !history.crontab.config.checkSha1
                this.verificationStatus = if (history.crontab.config.checkSha1) VerificationStatus.PENDING else VerificationStatus.SKIPPED
                this.localSha256 = null
                this.verifiedSize = null
                this.exifFilled = !history.crontab.config.synologyPhotos
                this.fsTimeUpdated = true
            }
        }
    }

    /**
     * 生成下载目标路径。
     *
     * targetPath 是系统级导出根目录，例如 /photos。无论是否开启群晖
     * Photos 适配，都必须先按厂商、账号（以及 iCloud 图库）隔离，不能
     * 通过路径模板绕过账号目录。旧版本保存过的路径表达式只取插值前的
     * 字面目录作为根目录，便于平滑迁移。
     */
    fun genFilePath(history: CrontabHistory, asset: Asset): String {
        val config = history.crontab.config
        val configuredPath = config.targetPath.trim()
        val root = configuredPath.substringBefore("${'$'}{").trimEnd('/')
        require(root.isNotEmpty()) { "目标根目录不能为空" }

        val identity = runCatching { com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity.of(asset) }.getOrNull()
        // 旧导入数据可能没有加载相册账号关系，只能沿用其历史路径；新任务和正常同步
        // 都会加载账号关系，因此始终走统一根目录和账号隔离布局。
        if (identity == null) return legacyFilePath(history, asset)
        val accountRoot = Path(root, identity.directory)
        if (config.synologyPhotos) {
            return accountRoot.resolve(identity.fileName(asset)).normalize().toString()
        }

        // 普通模式仍然使用同一个根目录和账号隔离，只恢复相册层级与云端文件名。
        // iCloud 使用稳定文件名，以保证 Live Photo 的图片和配套视频可以配对。
        val fileName = if (identity.provider == CloudProvider.ICLOUD) {
            identity.fileName(asset)
        } else {
            sanitizeSegment(asset.fileName)
        }
        return accountRoot.resolve(sanitizeSegment(asset.album.name)).resolve(fileName).normalize().toString()
    }

    private fun legacyFilePath(history: CrontabHistory, asset: Asset): String {
        val config = history.crontab.config
        val configuredPath = config.targetPath.trim()
        val expression = if (TOKEN_REGEX.containsMatchIn(configuredPath)) configuredPath
        else Path(configuredPath, DEFAULT_FILE_TEMPLATE).toString()
        val zoneId = resolveZoneId(config.timeZone)
        val downloadTime = history.startTime
        val takenTime = asset.dateTaken
        val fileNameSafe = sanitizeSegment(asset.fileName)
        val fileStem = fileNameSafe.substringBeforeLast('.', fileNameSafe)
        val fileExt = asset.fileName.substringAfterLast('.', "")
        val replacements = buildMap {
            put("crontabId", history.crontab.id.toString())
            put("crontabName", history.crontab.name)
            put("historyId", history.id.toString())
            put("album", sanitizeSegment(asset.album.name))
            put("albumName", sanitizeSegment(asset.album.name))
            put("fileName", fileNameSafe)
            put("downloadFileName", if (asset.type == AssetType.AUDIO) "${asset.id}_$fileNameSafe" else fileNameSafe)
            put("fileStem", fileStem)
            put("fileExt", fileExt)
            put("assetId", asset.id.toString())
            put("assetType", asset.type.name.lowercase(Locale.ROOT))
            put("checksum", asset.checksum)
            put("sha1", asset.checksum)
            put("title", sanitizeSegment(asset.title))
            put("size", asset.size.toString())
            put("downloadEpochMillis", downloadTime.toEpochMilli().toString())
            put("takenEpochMillis", takenTime.toEpochMilli().toString())
            put("downloadEpochSeconds", downloadTime.epochSecond.toString())
            put("takenEpochSeconds", takenTime.epochSecond.toString())
        }
        return Path(interpolateExpression(expression, replacements, downloadTime, takenTime, zoneId).trim()).normalize().toString()
    }

    private fun interpolateExpression(
        template: String, values: Map<String, String>, downloadTime: Instant, takenTime: Instant, zoneId: ZoneId,
    ): String = TOKEN_REGEX.replace(template) { match ->
        val key = match.groupValues[1]
        when {
            key.startsWith("download_") -> formatInstant(downloadTime, zoneId, key.removePrefix("download_")) ?: match.value
            key.startsWith("taken_") -> formatInstant(takenTime, zoneId, key.removePrefix("taken_")) ?: match.value
            else -> values[key] ?: match.value
        }
    }

    private fun formatInstant(instant: Instant, zoneId: ZoneId, pattern: String): String? {
        if (pattern.isBlank()) return null
        return runCatching {
            DateTimeFormatter.ofPattern(pattern).withLocale(Locale.ROOT).format(instant.atZone(zoneId))
        }.getOrNull()
    }

    private fun resolveZoneId(timeZone: String?): ZoneId = if (timeZone.isNullOrBlank()) {
        ZoneId.systemDefault()
    } else runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())

    // 用于生成安全路径片段，避免非法字符。
    private fun sanitizeSegment(value: String): String {
        if (value.isEmpty()) return value
        return value.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
    }

}
