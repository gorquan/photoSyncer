package com.coooolfan.xiaomialbumsyncer.utils

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XiaomiResponseTest {
    private val mapper = ObjectMapper()

    @Test fun unauthorizedSecondaryValidationHasActionableMessage() {
        val tree = mapper.readTree("""{"code":401,"data":{"isSecondValidation":true}}""")
        val failure = assertThrows(IllegalStateException::class.java) { tree.validateXiaomiResponse(401) }
        assertTrue(failure.message!!.contains("二次验证"))
    }

    @Test fun staleAuthorizationRecommendsRescanning() {
        val tree = mapper.readTree("""{"code":0}""")
        val failure = assertThrows(IllegalStateException::class.java) { tree.validateXiaomiResponse(401) }
        assertTrue(failure.message!!.contains("重新扫码"))
    }

    @Test fun businessFailureCannotBecomeEmptySuccessfulAlbum() {
        val tree = mapper.readTree("""{"code":1234,"description":"private-session-token"}""")
        val failure = assertThrows(IllegalStateException::class.java) { tree.validateXiaomiResponse(200) }
        assertTrue(failure.message!!.contains("1234"))
        assertFalse(failure.message!!.contains("private-session-token"))
    }

    @Test fun missingBusinessStatusIsNotSuccessful() {
        assertThrows(IllegalArgumentException::class.java) {
            mapper.readTree("""{"error":"unknown"}""").validateXiaomiResponse(200)
        }
    }

    @Test fun successfulResponseIsReturned() {
        val tree = mapper.readTree("""{"code":0,"data":{"albums":[]}}""")
        assertSame(tree, tree.validateXiaomiResponse(200))
    }

    @Test fun businessErrorsExposeExactCodesWithoutParsingMessagePrefixes() {
        for (code in listOf(50050, 500500)) {
            val failure = assertThrows(XiaomiBusinessException::class.java) {
                mapper.readTree("""{"code":$code,"description":"private-session-token"}""").validateXiaomiResponse(200)
            }
            assertEquals(code, failure.code)
            assertFalse(failure.message!!.contains("private-session-token"))
        }
    }
}
