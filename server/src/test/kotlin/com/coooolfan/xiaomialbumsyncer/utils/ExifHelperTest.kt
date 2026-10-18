package com.coooolfan.xiaomialbumsyncer.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExifHelperTest {
    @TempDir lateinit var directory: Path

    private fun tool(script: String): Path {
        val path = Files.writeString(directory.resolve("exiftool"), "#!/bin/sh\n$script\n")
        assertTrue(path.toFile().setExecutable(true))
        return path
    }

    @Test
    fun `large file support is enabled for metadata reads and writes`() {
        val executable = tool("printf '%s\\n' \"\$@\"")
        for (args in listOf(
            listOf("-j", "-G", "large video.mp4"),
            listOf("-QuickTime:CreateDate=2026:01:01 00:00:00", "-overwrite_original", "large video.mp4"),
        )) {
            assertEquals(
                listOf("-api", "LargeFileSupport=1") + args,
                runExifTool(executable, args).lines(),
            )
        }
    }

    @Test
    fun `image condition only matches a missing or zero date`() {
        assertEquals(
            "not defined \$DateTimeOriginal or \$DateTimeOriginal =~ /^0000:00:00 00:00:00/",
            IMAGE_TIME_MISSING_CONDITION,
        )
    }

    @Test
    fun `failed condition can be treated as a successful no-op`() {
        runExifTool(
            tool("printf '1 files failed condition\\n'; exit 2"),
            emptyList(),
            failedConditionIsSuccess = true,
        )
    }

    @Test
    fun `other exiftool failures are not hidden`() {
        assertThrows(RuntimeException::class.java) {
            runExifTool(
                tool("printf 'write failed\\n'; exit 2"),
                emptyList(),
                failedConditionIsSuccess = true,
            )
        }
    }
}
