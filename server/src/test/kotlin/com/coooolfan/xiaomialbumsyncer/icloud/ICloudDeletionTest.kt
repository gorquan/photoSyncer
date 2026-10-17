package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.model.CloudPresence
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ICloudDeletionTest {
    private val mapper = jacksonObjectMapper()
    private fun json(value: String) = mapper.readTree(value)

    @Test fun explicitMissingAndDeletedFlagsRetireWholeAsset() {
        for (record in listOf(
            """{"recordName":"asset","serverErrorCode":"NOT_FOUND"}""",
            """{"recordName":"asset","serverErrorCode":"UNKNOWN_ITEM"}""",
            """{"recordName":"asset","deleted":true}""",
            """{"recordName":"asset","fields":{"isDeleted":{"value":1}}}""",
            """{"recordName":"asset","fields":{"isExpunged":{"value":true}}}""",
        )) assertEquals(CloudPresence.DELETED, parseICloudPresence(json("""{"records":[$record]}"""), "asset"))
    }

    @Test fun activeHiddenAndFavoriteChangesRemainLive() {
        for (fields in listOf("", """, "isHidden":{"value":1}""", """, "isFavorite":{"value":0}""")) {
            val response = json("""{"records":[{"recordName":"asset","recordType":"CPLAsset","fields":{"masterRef":{"value":{"recordName":"master"}}$fields}}]}""")
            assertEquals(CloudPresence.LIVE, parseICloudPresence(response, "asset"))
        }
    }

    @Test fun malformedIncompleteAndAuthorizationErrorsCannotEstablishDeletion() {
        for (response in listOf(
            "{}", """{"records":[]}""", """{"records":{}}""",
            """{"records":[{"recordName":"other","deleted":true}]}""",
            """{"records":[{"recordName":"asset","deleted":true},{"recordName":"asset","deleted":true}]}""",
            """{"records":[{"recordName":"asset"}]}""",
            """{"records":[{"recordName":"asset","serverErrorCode":"AUTHENTICATION_REQUIRED"}]}""",
            """{"records":[{"recordName":"asset","serverErrorCode":"ZONE_NOT_FOUND"}]}""",
            """{"records":[{"recordName":"asset","fields":{"isDeleted":{"value":"true"}}}]}""",
            """{"records":[{"recordName":"asset","fields":{"isDeleted":{"value":2}}}]}""",
        )) assertThrows(IllegalArgumentException::class.java, { parseICloudPresence(json(response), "asset") }, response)
    }

    @Test fun completePagesMustContainRecognizedLiveRecordsAndValidContinuation() {
        validateICloudEnumerationPage(json("""{"records":[]}"""))
        validateICloudEnumerationPage(json("""{"records":[{"recordName":"asset","recordType":"CPLAsset"},{"recordName":"master","recordType":"CPLMaster"}]}"""))
        validateICloudEnumerationPage(json("""{"records":[{"recordName":"asset","recordType":"CPLAsset"}],"continuationMarker":"opaque"}"""))
        for (response in listOf(
            "{}", """{"records":{}}""", """{"records":[],"continuationMarker":"more"}""",
            """{"records":[{"recordName":"master","recordType":"CPLMaster"}]}""",
            """{"records":[{"recordName":"asset","recordType":"CPLAsset","serverErrorCode":"UNKNOWN_ITEM"}]}""",
            """{"records":[{"recordName":"asset","recordType":"CPLAsset","fields":{"isDeleted":{"value":1}}}]}""",
        )) assertThrows(IllegalArgumentException::class.java, { validateICloudEnumerationPage(json(response)) }, response)
    }
}
