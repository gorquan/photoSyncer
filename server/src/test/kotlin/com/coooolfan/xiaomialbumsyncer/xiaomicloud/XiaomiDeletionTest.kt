package com.coooolfan.xiaomialbumsyncer.xiaomicloud

import com.coooolfan.xiaomialbumsyncer.model.CloudPresence
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XiaomiDeletionTest {
    private val mapper = jacksonObjectMapper()
    private fun json(value: String) = mapper.readTree(value)

    @Test fun recycleBinRequiresDeletionStatusTimestampAndPageContract() {
        val page = parseDeletedGalleryPage(json("""{"code":0,"data":{"content":[{"id":42,"status":"deleted","deleteTime":12345}],"lastPage":true,"syncTag":"400"}}"""))
        assertEquals(setOf("42"), page.ids)
        assertTrue(page.lastPage)
        assertEquals("400", page.syncTag)
        assertTrue(parseDeletedGalleryPage(json("""{"code":0,"data":{"content":[],"lastPage":true,"syncTag":0}}""")).ids.isEmpty())
        for (response in listOf(
            """{"code":0,"data":{"lastPage":true,"syncTag":"0"}}""",
            """{"code":0,"data":{"content":[],"syncTag":"0"}}""",
            """{"code":0,"data":{"content":[],"lastPage":false,"syncTag":"0"}}""",
            """{"code":0,"data":{"content":[],"lastPage":true,"syncTag":"bad&tag"}}""",
            """{"code":0,"data":{"content":[{"id":42,"status":"custom","deleteTime":123}],"lastPage":true,"syncTag":"1"}}""",
            """{"code":0,"data":{"content":[{"id":42,"status":"deleted"}],"lastPage":true,"syncTag":"1"}}""",
            """{"code":0,"data":{"content":[{"id":42,"status":"deleted","deleteTime":123},{"id":42,"status":"deleted","deleteTime":123}],"lastPage":true,"syncTag":"1"}}""",
        )) assertThrows(IllegalArgumentException::class.java, { parseDeletedGalleryPage(json(response)) }, response)
    }

    @Test fun storageUrlProvesLiveButNeither50050NorUnknown404ProvesDeletion() {
        assertEquals(CloudPresence.LIVE, parseGalleryPresence(json("""{"code":0,"data":{"url":"https://cdn.example.org/signed"}}""")))
        for (response in listOf(
            """{"code":50050,"description":"相册/图片/视频在非法的状态","reason":"status is not custom"}""",
            """{"code":50050,"result":"error","description":"文件已删除","reason":"media deleted","retriable":false}""",
            """{"code":40402,"description":"not found"}""",
        )) assertThrows(IllegalStateException::class.java, { parseGalleryPresence(json(response)) }, response)
        for (response in listOf("""{"code":0,"data":{}}""", """{"code":0,"data":{"url":"http-invalid"}}"""))
            assertThrows(IllegalArgumentException::class.java, { parseGalleryPresence(json(response)) }, response)
    }

    @Test fun malformedOrNonterminalEmptyGalleryPagesCannotEnableDeletion() {
        validateAssetEnumerationPage(json("""{"code":0,"data":{"galleries":[],"isLastPage":true}}"""), false, 200)
        for (response in listOf(
            """{"code":0,"data":{"isLastPage":true}}""",
            """{"code":0,"data":{"galleries":[]}}""",
            """{"code":0,"data":{"galleries":[],"isLastPage":false}}""",
            """{"code":0,"data":{"galleries":{},"isLastPage":true}}""",
        )) assertThrows(IllegalArgumentException::class.java, { validateAssetEnumerationPage(json(response), false, 200) }, response)
    }
}
