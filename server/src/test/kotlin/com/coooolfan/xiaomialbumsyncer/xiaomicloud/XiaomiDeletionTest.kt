package com.coooolfan.xiaomialbumsyncer.xiaomicloud

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XiaomiDeletionTest {
    private val mapper = jacksonObjectMapper()
    private fun json(value: String) = mapper.readTree(value)

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
