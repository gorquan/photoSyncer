package com.coooolfan.xiaomialbumsyncer.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

class MediaIntegrityTest {
    @TempDir lateinit var directory: Path

    private fun box(kind: String, payload: ByteArray = byteArrayOf()) =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(kind.toByteArray()).put(payload).array()

    @Test fun validVideoContainerIsAcceptedAndNotChanged() {
        val bytes = box("ftyp", "qt  ".toByteArray()) + box("moov") + box("mdat", byteArrayOf(1, 2, 3))
        val path = Files.write(directory.resolve("original.mov"), bytes)
        validateOriginalContainer(path, "video/quicktime")
        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun appleQuickTimeWithoutFtypIsValid() {
        val bytes = box("wide") + box("mdat", byteArrayOf(1, 2, 3)) + box("moov")
        val path = Files.write(directory.resolve("iphone-hevc.mov"), bytes)
        validateOriginalContainer(path, "video/quicktime")
        assertThrows(IllegalArgumentException::class.java) { validateOriginalContainer(path, "video/mp4") }
    }

    @Test fun truncatedBmffBoxFails() {
        val bytes = box("ftyp", "heic".toByteArray()) + ByteBuffer.allocate(8).putInt(200).put("mdat".toByteArray()).array()
        val path = Files.write(directory.resolve("broken.heic"), bytes)
        assertThrows(IllegalArgumentException::class.java) { validateOriginalContainer(path, "image/heic") }
    }

    @Test fun jpegEmbeddedVideoTailIsRetained() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()) + box("ftyp", "isom".toByteArray())
        val path = Files.write(directory.resolve("motion.jpg"), bytes)
        validateOriginalContainer(path, "image/jpeg")
        assertArrayEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun digestIsIndependentOfProviderSignature() {
        val path = Files.writeString(directory.resolve("original"), "abc")
        val digest = digestOriginal(path, 3)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest.sha1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest.sha256)
    }
}
