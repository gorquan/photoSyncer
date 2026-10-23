package com.coooolfan.xiaomialbumsyncer.model

import org.babyfish.jimmer.sql.Serialized

@Serialized
data class CrontabConfig(

    // cron 表达式
    // eg: "0 0 * * * ?" 每天午夜
    val expression: String,

    val timeZone: String,

    val targetPath: String,

    val downloadImages: Boolean,

    val downloadVideos: Boolean,

    val skipExistingFile: Boolean = true,

    val fetchFromDbSize: Int = 2,

    val downloaders: Int = 8,

    val verifiers: Int = 2,

    val exifProcessors: Int = 2,

    val notify: Boolean = true,

    val downloadLivePhotos: Boolean = true,

    /** Use targetPath as a literal root, with account/zone isolated, album-independent files. */
    val synologyPhotos: Boolean = false,

    /** Mirror confirmed library deletion: Xiaomi FULL/TIMELINE or iCloud FULL/CURSOR. */
    val syncCloudDeletes: Boolean = false,
) {
}
