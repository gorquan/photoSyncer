package com.coooolfan.xiaomialbumsyncer.xiaomicloud

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class XiaomiTimelineTest {
    private val mapper = jacksonObjectMapper()

    @Test fun validEmptyAndPopulatedTimelinesAreAccepted() {
        assertTrue(parseAlbumTimeline(mapper.readTree("""{"code":0,"data":{"indexHash":"empty","dayCount":{}}}""")).dayCount.isEmpty())
        val timeline = parseAlbumTimeline(mapper.readTree("""{"code":0,"data":{"indexHash":"hash","dayCount":{"20261010":2}}}"""))
        assertEquals("hash", timeline.indexHash)
        assertEquals(mapOf(LocalDate.of(2026, 10, 10) to 2L), timeline.dayCount)
    }

    @Test fun integralIndexHashesAreNormalizedWithoutLosingPrecisionOrSign() {
        for (hash in listOf("0", "123456789", "-123456789", "9223372036854775808")) {
            val numeric = parseAlbumTimeline(mapper.readTree("""{"code":0,"data":{"indexHash":$hash,"dayCount":{"20261010":2}}}"""))
            val textual = parseAlbumTimeline(mapper.readTree("""{"code":0,"data":{"indexHash":"$hash","dayCount":{"20261010":2}}}"""))
            assertEquals(hash, numeric.indexHash)
            assertEquals(textual, numeric)
        }
    }

    @Test fun malformedTimelinesCannotBePersistedAsSuccessfulEmptySnapshots() {
        for (data in listOf(
            """{"indexHash":null,"dayCount":{}}""", """{"indexHash":true,"dayCount":{}}""",
            """{"indexHash":1.5,"dayCount":{}}""", """{"indexHash":{},"dayCount":{}}""",
            """{"indexHash":[],"dayCount":{}}""", """{"indexHash":" ","dayCount":{}}""",
            """{}""", """{"indexHash":"" ,"dayCount":{}}""", """{"indexHash":"hash"}""",
            """{"indexHash":"hash","dayCount":[]}""", """{"indexHash":"hash","dayCount":{"20261010":"2"}}""",
            """{"indexHash":"hash","dayCount":{"20261010":-1}}""", """{"indexHash":"hash","dayCount":{"20261010":1.5}}""",
            """{"indexHash":"hash","dayCount":{"20261010":9223372036854775808}}""",
            """{"indexHash":"hash","dayCount":{"invalid":2}}""",
        )) assertThrows(Exception::class.java, { parseAlbumTimeline(mapper.readTree("""{"code":0,"data":$data}""")) }, data)
    }
}
