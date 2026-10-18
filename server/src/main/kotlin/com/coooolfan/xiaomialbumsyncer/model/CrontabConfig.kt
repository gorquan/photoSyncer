package com.coooolfan.xiaomialbumsyncer.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.babyfish.jimmer.sql.Serialized

@Serialized
@JsonIgnoreProperties(ignoreUnknown = true)
data class CrontabConfig(

    // cron 表达式
    // eg: "0 0 * * * ?" 每天午夜
    val expression: String,

    val timeZone: String,

    val targetPath: String,

    val downloadImages: Boolean,

    val downloadVideos: Boolean,

    val rewriteExifTime: Boolean,

    val rewriteExifTimeZone: String?,

    val skipExistingFile: Boolean = true,

    val rewriteFileSystemTime: Boolean = false,

    val checkSha1: Boolean = false,

    val fetchFromDbSize: Int = 2,

    val downloaders: Int = 8,

    val verifiers: Int = 2,

    val exifProcessors: Int = 2,

    val fileTimeWorkers: Int = 2,

    val downloadAudios: Boolean = true,

    val notify: Boolean = true,

    val downloadLivePhotos: Boolean = true,

    /** Use targetPath as a literal root, with account/zone isolated, album-independent files. */
    val synologyPhotos: Boolean = false,

    /** Mirror confirmed library deletion: Xiaomi FULL/TIMELINE or iCloud FULL/CURSOR. */
    val syncCloudDeletes: Boolean = false,
) {
    init {
        if (synologyPhotos) {
            require(targetPath.isNotBlank() && !targetPath.contains("${'$'}{")) {
                "群晖 Photos 模式需要固定的目标根目录，不支持路径插值"
            }
        }
    }
}
