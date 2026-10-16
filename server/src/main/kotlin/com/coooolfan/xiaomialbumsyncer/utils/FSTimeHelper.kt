package com.coooolfan.xiaomialbumsyncer.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.Instant

fun rewriteFSTime(
    path: Path,
    newTimeInstant: Instant
) {

    // 这种情况比较罕见
    if (!Files.exists(path)) {
        return
    }

    val newFileTime = FileTime.from(newTimeInstant)
    val attributeView = Files.getFileAttributeView(path, BasicFileAttributeView::class.java)

    // 如果某个参数为 null，则对应的时间不会被修改
    // A failed birthtime must not hide a failed modification-time write.
    attributeView.setTimes(newFileTime, null, null)
    try {
        attributeView.setTimes(null, null, newFileTime)
    } catch (_: UnsupportedOperationException) {
        org.slf4j.LoggerFactory.getLogger("FileTime").warn("文件系统不支持创建时间，已保留修改时间更新")
    } catch (_: java.io.IOException) {
        org.slf4j.LoggerFactory.getLogger("FileTime").warn("文件系统拒绝创建时间更新，已保留修改时间更新")
    }
}