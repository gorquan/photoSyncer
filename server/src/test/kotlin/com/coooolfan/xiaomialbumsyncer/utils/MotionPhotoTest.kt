package com.coooolfan.xiaomialbumsyncer.utils

import com.coooolfan.xiaomialbumsyncer.model.Asset
import com.coooolfan.xiaomialbumsyncer.model.AssetType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.TimeZone

class MotionPhotoTest {
    @TempDir lateinit var directory: Path
    private val video = box("ftyp", "mp42".toByteArray() + ByteArray(4)) +
        box("moov", box("trak", box("mdia", box("free", ByteArray(4))))) + box("mdat", ByteArray(10))
    private val asset = Asset { type = AssetType.IMAGE; dateTaken = Instant.EPOCH }
    private val disabledTool = ExifRewriteConfig(Path.of("/does-not-exist"), TimeZone.getTimeZone("UTC"))
    private fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(8 + payload.size)
        .putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
    private fun xml(properties: String, directory: String = "") = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF
        xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/" $properties>$directory</rdf:Description></rdf:RDF></x:xmpmeta>"""
    private fun modern(length: String = video.size.toString(), padding: Int = 0, extra: String = "") = xml(
        """GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"""",
        """<Container:Directory><rdf:Seq><rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Padding="$padding"/></rdf:li>$extra
            <rdf:li><Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="$length"/></rdf:li></rdf:Seq></Container:Directory>""",
    )
    private fun legacy(offset: String = video.size.toString()) = xml("""GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$offset"""")
    private fun jpeg(xmp: String? = null, trailer: ByteArray = ByteArray(0)): ByteArray {
        val app = if (xmp == null) ByteArray(0) else {
            val content = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
            byteArrayOf(0xff.toByte(), 0xe1.toByte()) + ByteBuffer.allocate(2).putShort((content.size + 2).toShort()).array() + content
        }
        // A JPEG scan contains escaped FF00 and a restart marker before the actual EOI.
        return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + app +
            byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2, 1, 0xff.toByte(), 0, 2, 0xff.toByte(), 0xd0.toByte(), 3, 0xff.toByte(), 0xd9.toByte()) + trailer
    }
    private fun inspect(bytes: ByteArray) = inspectMotionPhoto(Files.write(directory.resolve("test.jpg"), bytes))

    @Test fun `modern and legacy motion preserve bytes through both EXIF entrypoints`() {
        for (metadata in listOf(modern(), legacy())) {
            val original = jpeg(metadata, video)
            val path = Files.write(directory.resolve("motion.jpg"), original)
            val inspection = inspectMotionPhoto(path)
            assertEquals(MotionPhotoStatus.NATIVE_MOTION, inspection.status, inspection.diagnostic)
            assertEquals(video.size.toLong(), inspection.videoLength)
            assertArrayEquals(video, original.copyOfRange(inspection.videoOffset!!.toInt(), original.size))
            rewriteExifTime(asset, path, disabledTool)
            rewriteImageExifTime(asset, path, disabledTool)
            rewriteVideoExifTime(asset, path, disabledTool)
            assertArrayEquals(original, Files.readAllBytes(path))
        }
    }

    @Test fun `explicit extra item and primary padding are accounted for`() {
        val extra = """<rdf:li><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="4"/></rdf:li>"""
        val result = inspect(jpeg(modern(padding = 3, extra = extra), ByteArray(3 + 4) + video))
        assertEquals(MotionPhotoStatus.NATIVE_MOTION, result.status, result.diagnostic)
    }

    @Test fun `declarations with absent truncated overlapping or overflowing video fail without mutation`() {
        val broken = listOf(
            jpeg(modern(), ByteArray(0)), jpeg(legacy("0"), video), jpeg(legacy("999999"), video),
            jpeg(modern("9223372036854775808"), video), jpeg(modern("1"), video),
            jpeg(modern(), video.copyOf(video.size - 1)), jpeg(modern(padding = 9), video),
        )
        for (bytes in broken) {
            val path = Files.write(directory.resolve("bad.jpg"), bytes)
            assertEquals(MotionPhotoStatus.INVALID, inspectMotionPhoto(path).status)
            assertThrows(IllegalArgumentException::class.java) { rewriteImageExifTime(asset, path, disabledTool) }
            assertArrayEquals(bytes, Files.readAllBytes(path))
        }
    }

    @Test fun `BMFF box sizes and mandatory boxes are validated at declared offset`() {
        val cases = listOf(
            video.clone().also { ByteBuffer.wrap(it).putInt(Int.MAX_VALUE) },
            video.clone().also { ByteBuffer.wrap(it, 24, 4).putInt(Int.MAX_VALUE) },
            box("ftyp", "mp42".toByteArray() + ByteArray(4)) + box("mdat", ByteArray(10)),
            box("ftyp", "mp42".toByteArray() + ByteArray(4)) + box("moov", ByteArray(0)) + box("mdat", ByteArray(10)),
            ByteBuffer.allocate(16).putInt(1).put("ftyp".toByteArray()).putLong(-1L).array(),
        )
        for (bytes in cases) assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg(legacy(bytes.size.toString()), bytes)).status)
    }

    @Test fun `multiple declarations must agree and malformed XML is rejected`() {
        assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg(xml("""GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1""""), video)).status)
        val conflicting = modern().replace("</rdf:Description>", "<GCamera:MotionPhoto>0</GCamera:MotionPhoto></rdf:Description>")
        assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg(conflicting, video)).status)
        val conflictingRange = modern().replace("GCamera:MotionPhotoVersion=\"1\"", "GCamera:MotionPhotoVersion=\"1\" GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"1\"")
        assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg(conflictingRange, video)).status)
        val unknownPadding = modern().replace("Item:Length=\"${video.size}\"", "Item:Length=\"${video.size}\" Item:Padding=\"2\"")
        assertEquals(MotionPhotoStatus.UNSUPPORTED, inspect(jpeg(unknownPadding, video + ByteArray(2))).status)
        assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg("<broken>", video)).status)
        assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg("<x>".repeat(150) + "</x>".repeat(150), video)).status)
        assertEquals(MotionPhotoStatus.INVALID, inspect(jpeg("""<!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><x>&a;</x>""", video)).status)
        assertEquals(MotionPhotoStatus.UNSUPPORTED, inspect(jpeg(legacy().replace("MicroVideoVersion=\"1\"", "MicroVideoVersion=\"2\""), video)).status)
        assertEquals(MotionPhotoStatus.UNSUPPORTED, inspect(jpeg(xml("""GCamera:MotionPhoto="3""""), video)).status)
    }

    @Test fun `ordinary images including temporary paths still invoke requested EXIF processing`() {
        val ordinary = jpeg()
        val temporaryDirectory = Files.createTempDirectory(Path.of("/tmp"), "photosyncer-static-")
        val path = Files.write(temporaryDirectory.resolve("static.jpg"), ordinary)
        assertEquals(MotionPhotoStatus.STATIC, inspectMotionPhoto(path).status)
        val hinted = inspectMotionPhoto(path, cloudMotionHint = true)
        assertEquals(MotionPhotoStatus.STATIC, hinted.status)
        assertTrue(hinted.diagnostic.contains("仅能同步为静态照片"))
        val marker = temporaryDirectory.resolve("called")
        val tool = temporaryDirectory.resolve("exiftool")
        Files.writeString(tool, "#!/bin/sh\ntouch '${marker}'\n")
        tool.toFile().setExecutable(true)
        rewriteExifTime(asset, path, ExifRewriteConfig(tool, TimeZone.getTimeZone("UTC")))
        assertTrue(Files.exists(marker))
        listOf(marker, tool, path, temporaryDirectory).forEach(Files::delete)
        assertEquals(MotionPhotoStatus.STATIC, inspect("not-jpeg".toByteArray()).status)
    }
}
