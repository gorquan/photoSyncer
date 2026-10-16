package com.coooolfan.xiaomialbumsyncer.xiaomicloud

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XiaomiMotionHintTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `only observed combinations in current description field provide hints`() {
        assertTrue(hasXiaomiMotionHint(mapper.readTree("""{"specialTypeFlagsNew":":1073:1065:"}""")))
        assertTrue(hasXiaomiMotionHint(mapper.readTree("""{"specialTypeFlagsNew":":1006:"}""")))
        assertTrue(hasXiaomiMotionHint(mapper.valueToTree("""{"specialTypeFlagsNew":":1006:"}""")))
        assertFalse(hasXiaomiMotionHint(mapper.readTree("""{"specialTypeFlagsNew":":1073:"}""")))
        assertFalse(hasXiaomiMotionHint(mapper.readTree("""{"specialTypeFlags":":1006:"}""")))
        assertFalse(hasXiaomiMotionHint(mapper.valueToTree("not json")))
        assertFalse(hasXiaomiMotionHint(null))
    }
}
