package com.coooolfan.xiaomialbumsyncer.utils

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Provider signatures and local digests have different meanings and must remain separate. */
data class OriginalDigests(val size: Long, val sha1: String, val sha256: String)

fun digestOriginal(path: Path, expectedSize: Long): OriginalDigests {
    require(expectedSize > 0) { "云端文件大小无效" }
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha256 = MessageDigest.getInstance("SHA-256")
    var size = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("文件校验已取消")
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            sha1.update(buffer, 0, count)
            sha256.update(buffer, 0, count)
        }
    }
    require(size == expectedSize) { "文件大小不匹配：预期 $expectedSize，实际 $size" }
    return OriginalDigests(size, HexFormat.of().formatHex(sha1.digest()), HexFormat.of().formatHex(sha256.digest()))
}

/** Bounded container checks, not a decoder or a cryptographic source-content guarantee. */
fun validateOriginalContainer(path: Path, mimeType: String) {
    RandomAccessFile(path.toFile(), "r").use { file ->
        when (mimeType.lowercase()) {
            "image/jpeg" -> {
                require(file.length() >= 4 && file.readUnsignedShort() == 0xffd8) { "JPEG 文件头无效" }
                // A motion JPEG may contain an MP4 trailer after EOI. Do not require EOI at EOF.
                var previous = -1
                var foundEnd = false
                val buffer = ByteArray(128 * 1024)
                while (!foundEnd) {
                    val count = file.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        val current = buffer[index].toInt() and 0xff
                        if (previous == 0xff && current == 0xd9) { foundEnd = true; break }
                        previous = current
                    }
                }
                require(foundEnd) { "JPEG 文件缺少结束标记" }
            }
            "image/heic", "image/heif", "image/avif", "video/quicktime", "video/mp4" -> {
                var offset = 0L
                var hasHeader = false
                var hasMetadata = false
                var hasData = false
                val metadataBox = if (mimeType.startsWith("image/", ignoreCase = true)) "meta" else "moov"
                while (offset < file.length()) {
                    require(file.length() - offset >= 8) { "媒体容器尾部不完整" }
                    file.seek(offset)
                    val shortSize = Integer.toUnsignedLong(file.readInt())
                    val kind = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
                    val headerSize = if (shortSize == 1L) 16L else 8L
                    val size = when (shortSize) {
                        0L -> file.length() - offset
                        1L -> { require(file.length() - offset >= 16) { "媒体容器头不完整" }; file.readLong() }
                        else -> shortSize
                    }
                    require(size >= headerSize && size <= file.length() - offset) { "媒体容器区块越界" }
                    hasHeader = hasHeader || kind == "ftyp"
                    hasMetadata = hasMetadata || kind == metadataBox
                    hasData = hasData || (kind == "mdat" && size > headerSize)
                    offset += size
                }
                // QuickTime predates the ISO ftyp box; genuine iPhone HEVC companions may use wide/mdat/moov.
                val headerValid = hasHeader || mimeType.equals("video/quicktime", ignoreCase = true)
                require(headerValid && hasMetadata && hasData) { "媒体容器缺少文件类型、元数据或媒体内容" }
            }
            // Other original formats retain size/digest evidence; no decoder support is implied.
        }
    }
}
