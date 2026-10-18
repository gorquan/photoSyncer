package com.coooolfan.xiaomialbumsyncer.utils

import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

enum class MotionPhotoStatus { STATIC, NATIVE_MOTION, INVALID, UNSUPPORTED }

data class MotionPhotoInspection(
    val status: MotionPhotoStatus,
    val diagnostic: String,
    val videoOffset: Long? = null,
    val videoLength: Long? = null,
) {
    fun requireSafeOriginal(): MotionPhotoInspection {
        require(status != MotionPhotoStatus.INVALID && status != MotionPhotoStatus.UNSUPPORTED) { diagnostic }
        return this
    }
}

/** Read-only inspection. Offsets come exclusively from declared XMP ranges, never a byte-pattern search. */
fun inspectMotionPhoto(path: Path, cloudMotionHint: Boolean = false): MotionPhotoInspection {
    return try {
        val result = inspectJpegMotion(path)
        if (cloudMotionHint && result.status == MotionPhotoStatus.STATIC) result.copy(
            diagnostic = "云端标记为动态照片，但原件没有动态视频结构，仅能同步为静态照片",
        ) else result
    } catch (e: MotionFormatException) {
        MotionPhotoInspection(e.status, e.message ?: "动态照片结构无效")
    }
}

private class MotionFormatException(val status: MotionPhotoStatus, message: String) : RuntimeException(message)
private fun invalid(message: String): Nothing = throw MotionFormatException(MotionPhotoStatus.INVALID, "动态照片无效：$message")
private fun unsupported(message: String): Nothing = throw MotionFormatException(MotionPhotoStatus.UNSUPPORTED, "暂不支持此动态照片：$message")
private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
private val EXTENDED_XMP_HEADER = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.US_ASCII)
private const val MAX_XMP_BYTES = 1024 * 1024
private const val MAX_JPEG_SEGMENTS = 65536

private class JpegReader(val stream: InputStream) {
    var position = 0L
    fun byte(): Int {
        val b = stream.read()
        if (b >= 0) position++
        return b
    }
    fun bytes(length: Int): ByteArray {
        val result = stream.readNBytes(length)
        position += result.size
        if (result.size != length) invalid("JPEG 段被截断")
        return result
    }
    fun skip(length: Int) {
        var remaining = length.toLong()
        while (remaining > 0) {
            val n = stream.skip(remaining)
            if (n > 0) { position += n; remaining -= n }
            else if (byte() < 0) invalid("JPEG 段被截断") else remaining--
        }
    }
}

private fun inspectJpegMotion(path: Path): MotionPhotoInspection {
    val roots = mutableListOf<Element>()
    var extended = false
    var xmpBytes = 0
    var jpegEnd = 0L
    Files.newInputStream(path).use { source ->
        val reader = JpegReader(BufferedInputStream(source))
        if (reader.byte() != 0xff || reader.byte() != 0xd8) {
            return MotionPhotoInspection(MotionPhotoStatus.STATIC, "原件无 JPEG 动态照片声明")
        }
        var entropy = false
        var segments = 0
        while (true) {
            // Within a scan, FF00 is escaped data and FFD0..FFD7 are restart markers.
            var prefix = reader.byte()
            if (entropy) while (prefix >= 0 && prefix != 0xff) prefix = reader.byte()
            if (prefix < 0) {
                if (roots.isEmpty()) return MotionPhotoInspection(MotionPhotoStatus.STATIC, "原件无动态照片声明")
                invalid("JPEG 缺少结束标记")
            }
            if (prefix != 0xff) invalid("JPEG 标记边界无效")
            var marker = reader.byte()
            while (marker == 0xff) marker = reader.byte()
            if (entropy && (marker == 0 || marker in 0xd0..0xd7)) continue
            if (++segments > MAX_JPEG_SEGMENTS) unsupported("JPEG 段数量超出安全限制")
            if (marker == 0xd9) { jpegEnd = reader.position; break }
            if (marker < 0 || marker == 0 || marker == 0xd8) invalid("JPEG 标记无效")
            if (marker == 1 || marker in 0xd0..0xd7) continue
            entropy = marker == 0xda
            val hi = reader.byte()
            val lo = reader.byte()
            if (hi < 0 || lo < 0) invalid("JPEG 段头被截断")
            val length = (hi shl 8) + lo - 2
            if (length < 0) invalid("JPEG 段长度无效")
            if (marker == 0xe1) {
                val content = reader.bytes(length)
                when {
                    content.startsWith(XMP_HEADER) -> {
                        xmpBytes += content.size
                        if (xmpBytes > MAX_XMP_BYTES) unsupported("XMP 超出安全限制")
                        roots += parseXmp(content.copyOfRange(XMP_HEADER.size, content.size))
                    }
                    content.startsWith(EXTENDED_XMP_HEADER) -> extended = true
                }
            } else reader.skip(length)
        }
    }
    if (roots.isEmpty()) {
        if (extended) unsupported("只有扩展 XMP，无法安全确定动态视频范围")
        return MotionPhotoInspection(MotionPhotoStatus.STATIC, "原件无动态照片声明")
    }
    val modern = property(roots, CAMERA_NS, "MotionPhoto")
    val legacy = property(roots, CAMERA_NS, "MicroVideo")
    val legacyOffset = property(roots, CAMERA_NS, "MicroVideoOffset")
    val directories = roots.flatMap { it.descendants(CONTAINER_NS, "Directory") }
    val declared = modern == "1" || legacy == "1"
    if (!declared) {
        if ((modern != null && modern !in setOf("0", "1")) ||
            (legacy != null && legacy !in setOf("0", "1")) || legacyOffset != null || directories.isNotEmpty()) {
            unsupported("动态声明不完整或格式未知")
        }
        return MotionPhotoInspection(MotionPhotoStatus.STATIC, "原件无动态照片声明")
    }
    if (extended) unsupported("动态照片使用扩展 XMP")
    val fileSize = Files.size(path)
    var range: Pair<Long, Long>? = null
    if (modern == "1") {
        val version = property(roots, CAMERA_NS, "MotionPhotoVersion")
        if (version != "1") unsupported("MotionPhotoVersion 不是 1")
        if (directories.size != 1) invalid("MotionPhoto 必须包含唯一 GContainer Directory")
        range = modernRange(directories.single(), fileSize, jpegEnd)
    }
    if (legacy == "1") {
        if (property(roots, CAMERA_NS, "MicroVideoVersion") != "1") unsupported("MicroVideoVersion 不是 1")
        val length = positiveLong(legacyOffset, "MicroVideoOffset")
        if (length > fileSize) invalid("MicroVideoOffset 超出文件范围")
        val legacyRange = fileSize - length to length
        if (range != null && range != legacyRange) invalid("新旧动态声明的视频范围不一致")
        range = legacyRange
    }
    val (offset, length) = requireNotNull(range)
    if (offset < jpegEnd || length <= 0 || offset > fileSize - length) invalid("视频范围与 JPEG 重叠或超出文件")
    validateMp4(path, offset, length)
    return MotionPhotoInspection(MotionPhotoStatus.NATIVE_MOTION, "原生动态照片范围和容器结构检查通过，保留完整 JPEG 和内嵌 MP4，跳过 EXIF 改写", offset, length)
}

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
private fun parseXmp(bytes: ByteArray): Element = try {
    // Ignore only a terminal NUL run in the parser view. Embedded NULs (including
    // valid UTF-16/32 code units) and the original JPEG bytes remain untouched.
    val firstNul = bytes.indexOf(0)
    val xmlLength = if (firstNul >= 0 && (firstNul until bytes.size).all { bytes[it] == 0.toByte() }) firstNul else bytes.size
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    factory.newDocumentBuilder().apply {
        setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
        })
        setEntityResolver { _, _ -> InputSource("".reader()) }
    }.parse(bytes.inputStream(0, xmlLength)).documentElement
} catch (_: Exception) { invalid("XMP XML 无效或包含被禁止的外部实体") }

private fun Element.descendants(namespace: String, local: String): List<Element> {
    val nodes = getElementsByTagNameNS(namespace, local)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

private fun property(roots: List<Element>, namespace: String, local: String): String? {
    val values = mutableListOf<String>()
    for (root in roots) {
        for (element in listOf(root) + root.descendants("*", "*")) {
            if (element.hasAttributeNS(namespace, local)) values += element.getAttributeNS(namespace, local).trim()
            if (element.namespaceURI == namespace && element.localName == local) values += element.textContent.trim()
        }
    }
    if (values.distinct().size > 1) invalid("XMP $local 存在冲突声明")
    return values.firstOrNull()
}

private data class ContainerItem(val mime: String, val semantic: String, val length: Long, val padding: Long)
private fun modernRange(directory: Element, fileSize: Long, jpegEnd: Long): Pair<Long, Long> {
    val seq = directory.descendants(RDF_NS, "Seq")
    if (seq.size != 1) unsupported("GContainer Directory 不是唯一有序序列")
    val list = seq.single().childNodes
    val entries = (0 until list.length).mapNotNull { list.item(it) as? Element }
    if (entries.isEmpty() || entries.size > 64) invalid("GContainer 项数量无效")
    val items = entries.map { entry ->
        if (entry.namespaceURI != RDF_NS || entry.localName != "li") unsupported("GContainer 序列含未知元素")
        val contained = entry.descendants(CONTAINER_NS, "Item")
        if (contained.size != 1) unsupported("GContainer 项结构不明确")
        val item = contained.single()
        val mime = property(listOf(item), ITEM_NS, "Mime") ?: invalid("GContainer 项缺少 Mime")
        val semantic = property(listOf(item), ITEM_NS, "Semantic") ?: invalid("GContainer 项缺少 Semantic")
        val length = property(listOf(item), ITEM_NS, "Length")
        val padding = property(listOf(item), ITEM_NS, "Padding")
        ContainerItem(mime, semantic, nonnegativeLong(length ?: "0", "Length"), nonnegativeLong(padding ?: "0", "Padding"))
    }
    if (items.first().semantic != "Primary" || items.first().mime != "image/jpeg") unsupported("GContainer 首项不是 JPEG Primary")
    if (items.drop(1).any { it.semantic == "Primary" }) invalid("GContainer 包含多个 Primary")
    if (items.first().length != 0L && items.first().length != jpegEnd) invalid("Primary 长度与 JPEG 结束位置不符")
    if (items.count { it.semantic == "MotionPhoto" } != 1) invalid("GContainer 必须包含唯一 MotionPhoto 项")
    var cursor = fileSize
    var video: Pair<Long, Long>? = null
    for (item in items.drop(1).asReversed()) {
        if (item.length <= 0) unsupported("GContainer 非 Primary 项未声明正长度")
        if (item.padding != 0L) unsupported("GContainer 非 Primary 项声明非零 padding")
        if (item.padding > cursor || item.length > cursor - item.padding) invalid("GContainer 长度或 padding 超出文件范围")
        cursor -= item.padding + item.length
        if (item.semantic == "MotionPhoto") {
            if (item.mime != "video/mp4") unsupported("MotionPhoto 项不是 video/mp4")
            video = cursor to item.length
        }
    }
    if (items.first().padding > cursor || cursor - items.first().padding != jpegEnd) invalid("GContainer 各项与 JPEG 范围或 padding 不一致")
    return requireNotNull(video)
}

private fun nonnegativeLong(text: String?, field: String): Long {
    if (text == null || !text.matches(Regex("[0-9]+"))) invalid("$field 不是非负整数")
    return text.toLongOrNull() ?: invalid("$field 整数溢出")
}
private fun positiveLong(text: String?, field: String): Long = nonnegativeLong(text, field).also { if (it <= 0) invalid("$field 必须大于零") }

private val BMFF_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "dinf", "edts", "mvex")
private fun validateMp4(path: Path, offset: Long, length: Long) {
    RandomAccessFile(path.toFile(), "r").use { file ->
        var boxCount = 0
        var hasFtyp = false
        var hasMoov = false
        var hasMdat = false
        fun boxes(start: Long, end: Long, depth: Int) {
            if (depth > 16) unsupported("MP4 容器嵌套超出安全限制")
            var cursor = start
            while (cursor < end) {
                if (++boxCount > 100000) unsupported("MP4 box 数量超出安全限制")
                if (end - cursor < 8) invalid("MP4 box 头被截断")
                file.seek(cursor)
                var size = file.readInt().toLong() and 0xffffffffL
                val typeBytes = ByteArray(4).also(file::readFully)
                if (typeBytes.any { (it.toInt() and 255) !in 32..126 }) invalid("MP4 box 类型无效")
                val type = String(typeBytes, Charsets.US_ASCII)
                var header = 8L
                if (size == 1L) {
                    if (end - cursor < 16) invalid("MP4 扩展 box 头被截断")
                    size = file.readLong()
                    header = 16
                } else if (size == 0L) size = end - cursor
                if (size < header || size > end - cursor) invalid("MP4 box 长度无效或被截断")
                if (depth == 0) {
                    if (cursor == start && type != "ftyp") invalid("声明的视频范围未以 ftyp 开始")
                    when (type) {
                        "ftyp" -> {
                            if (hasFtyp || cursor != start || size < header + 8 || (size - header) % 4L != 0L) invalid("MP4 ftyp 无效")
                            hasFtyp = true
                        }
                        "moov" -> { if (hasMoov || size == header) invalid("MP4 moov 无效"); hasMoov = true }
                        "mdat" -> { if (size == header) invalid("MP4 mdat 为空"); hasMdat = true }
                    }
                }
                if (type in BMFF_CONTAINERS) boxes(cursor + header, cursor + size, depth + 1)
                cursor += size
            }
        }
        boxes(offset, offset + length, 0)
        if (!hasFtyp || !hasMoov || !hasMdat) invalid("MP4 缺少 ftyp、moov 或 mdat")
    }
}
