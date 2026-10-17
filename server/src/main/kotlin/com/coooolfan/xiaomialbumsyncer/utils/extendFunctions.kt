package com.coooolfan.xiaomialbumsyncer.utils

import com.coooolfan.xiaomialbumsyncer.model.Album
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.noear.solon.core.AppContext
import java.text.DecimalFormat
import java.time.ZoneId
import java.util.*

fun Int.percentOf(total: Int): String {
    if (total == 0) return "0%"
    val formatter = DecimalFormat("#.##")  // 最多保留2位小数
    return "${formatter.format(this * 100.0 / total)}%"
}

fun String.toTimeZone(): TimeZone {
    return TimeZone.getTimeZone(ZoneId.of(this))
}

fun Album.isAudioAlbum(): Boolean {
    return this.remoteKey == "-1"
}

val AppContext.objectMapper: ObjectMapper
    get() = this.getBean(ObjectMapper::class.java)

fun JsonNode.requiredText(field: String): String =
    at("/$field").asText("").ifBlank { error("JSON 响应缺少字段 $field") }

fun JsonNode.textOrNull(field: String): String? =
    at("/$field").asText(null)?.takeIf { it.isNotBlank() }

// 小米接口约定：code != 0 即业务错误。响应体不写入日志或异常，避免泄露认证信息。
internal class XiaomiBusinessException(val code: Int) :
    IllegalStateException("小米返回业务错误码 $code，请检查账号授权后重试")

fun JsonNode.throwIfBizError() {
    if (findValue("isSecondValidation")?.asBoolean() == true) {
        throw com.coooolfan.xiaomialbumsyncer.exception.XiaomiAuthenticationException(requiresVerification = true)
    }
    require(hasNonNull("code")) { "小米接口响应缺少业务状态码" }
    val code = at("/code").asText().toIntOrNull()
        ?: error("小米接口业务状态码格式无效")
    if (code != 0) throw XiaomiBusinessException(code)
}

fun JsonNode.validateXiaomiResponse(httpCode: Int): JsonNode {
    // HTTP 401 的响应体仍可能包含可操作的二次验证状态。
    if (findValue("isSecondValidation")?.asBoolean() == true) throwIfBizError()
    if (httpCode == 401 || httpCode == 403) {
        throw com.coooolfan.xiaomialbumsyncer.exception.XiaomiAuthenticationException()
    }
    throwIfNotSuccess(httpCode)
    throwIfBizError()
    return this
}
