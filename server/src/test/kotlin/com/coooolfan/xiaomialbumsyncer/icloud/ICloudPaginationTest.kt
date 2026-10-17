package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.service.NotifyService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.babyfish.jimmer.sql.dialect.SQLiteDialect
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class ICloudPaginationTest {
    private val mapper = jacksonObjectMapper()
    private fun page(name: String?, marker: Any? = null): JsonNode = mapper.valueToTree(mapOf(
        "records" to if (name == null) emptyList<Any>() else listOf(
            mapOf("recordType" to "CPLAsset", "recordName" to name, "fields" to mapOf(
                "masterRef" to mapOf("value" to mapOf("recordName" to "master-$name")), "assetDate" to mapOf("value" to 1000))),
            mapOf("recordType" to "CPLMaster", "recordName" to "master-$name", "fields" to mapOf(
                "filenameEnc" to mapOf("value" to "cGhvdG8uanBn"),
                "resOriginalFileType" to mapOf("value" to "public.jpeg"),
                "resOriginalRes" to mapOf("value" to mapOf("size" to 10, "fileChecksum" to "checksum-$name"))))),
        "continuationMarker" to marker))

    private fun enumerate(pages: List<JsonNode>, albumName: String = "__all__", strict: Boolean = true,
                          inspect: (List<JsonNode>, List<Asset>, Throwable?) -> Unit) {
        val requests = mutableListOf<JsonNode>()
        val files = mutableListOf<Asset>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = mapper.readTree(exchange.requestBody.readAllBytes())
            val response = when {
                exchange.requestURI.path == "/setup/validate" -> mapper.valueToTree(mapOf(
                    "dsInfo" to mapOf("hsaVersion" to 2, "dsid" to "id"), "hsaTrustedBrowser" to true,
                    "webservices" to mapOf("ckdatabasews" to mapOf("url" to "http://127.0.0.1:${server.address.port}"))))
                body.at("/query/recordType").asText() == "CheckIndexingState" -> mapper.readTree(
                    """{"records":[{"fields":{"state":{"value":"FINISHED"}}}]}""")
                else -> {
                    requests.add(body)
                    pages.getOrNull(requests.lastIndex) ?: mapper.readTree("""{"serverErrorCode":"UNEXPECTED_REQUEST"}""")
                }
            }
            val bytes = mapper.writeValueAsBytes(response)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val sql = newKSqlClient { setDialect(SQLiteDialect()) }
            val root = "http://127.0.0.1:${server.address.port}"
            val client = ICloudClient(ICloudCredentials("apple", "password", "com",
                headers = mutableMapOf("X-Apple-Session-Token" to "session")), ICloudClient.Endpoints("$root/auth", "$root/setup", root))
            val album = Album { id = 1; accountId = 1; remoteKey = ICloudAlbumKey("SharedSync-example", albumName).encode(); name = albumName; shadow = false }
            val result = runCatching { ICloudPhotos(ICloudAccountService(sql, NotifyService(sql)))
                .fetchAssets(album, client, strict) { files += it } }
            inspect(requests, files, result.exceptionOrNull())
            if (result.isSuccess) assertEquals(files.size.toLong(), result.getOrThrow())
        } finally { server.stop(0) }
    }

    @Test fun continuationKeepsTheQueryAndEnumeratesEveryAsset() {
        for (strict in listOf(false, true)) for (album in listOf("__all__", "__favorites__", "custom-album")) {
            enumerate(listOf(page("a", "opaque+/= token"), page("b", "next"), page("c")), album, strict) { requests, files, error ->
                assertNull(error)
                assertEquals(3, requests.size)
                assertEquals(listOf(null, "opaque+/= token", "next"), requests.map { it.get("continuationMarker")?.asText() })
                assertTrue(requests.all { it.path("query") == requests.first().path("query") })
                assertTrue(requests.all { it.at("/zoneID/zoneName").asText() == "SharedSync-example" && it.path("resultsLimit").asInt() == 200 })
                assertEquals(listOf("a", "b", "c"), files.map { ICloudResourceKey.decode(it.remoteKey).assetId })
                val filters = requests.first().at("/query/filterBy").associate { it.path("fieldName").asText() to it.at("/fieldValue/value").asText() }
                assertEquals(if (album == "__favorites__") "FAVORITE" else null, filters["smartAlbum"])
                assertEquals(if (album == "custom-album") album else null, filters["parentId"])
            }
        }
    }

    @Test fun rankPaginationStillRequiresAnEmptyPageAndCanSwitchToContinuation() {
        for (pages in listOf(listOf(page("a"), page("b"), page(null)), listOf(page("a"), page("b", "next"), page("c")))) {
            enumerate(pages) { requests, files, error ->
                assertNull(error)
                assertEquals(listOf(0L, 1L, if (pages[1].path("continuationMarker").isNull) 2L else 1L), requests.map { it.at("/query/filterBy/0/fieldValue/value").asLong() })
                assertEquals(if (pages.last().path("records").isEmpty) 2 else 3, files.size)
            }
        }
        enumerate(listOf(page("a", "next"), page(null))) { requests, files, error ->
            assertNull(error); assertEquals(2, requests.size); assertEquals(1, files.size)
        }
    }

    @Test fun invalidOrIncompletePagesCannotCompleteEnumeration() {
        val cases = listOf(
            listOf(page("a", "same"), page("b", "same")),
            listOf(page("a", "one"), page("b", "two"), page("c", "one")),
            listOf(page("a", "next"), page("a")),
            listOf(page("a", "next"), page(null, "more")),
            listOf(page("a", "next"), mapper.readTree("""{"records":{}}""")),
            listOf(page("a", "next"), mapper.readTree("""{"serverErrorCode":"AUTHENTICATION_REQUIRED","records":[]}""")),
        ) + listOf("", " ", 1, true, emptyMap<String, Any>(), emptyList<Any>()).map { listOf(page("a", it)) }
        for (pages in cases) enumerate(pages) { requests, _, error ->
            if (pages.last().path("serverErrorCode").asText() == "AUTHENTICATION_REQUIRED") {
                assertInstanceOf(ICloudApiException::class.java, error)
            } else assertInstanceOf(IllegalArgumentException::class.java, error, pages.toString())
            assertEquals(pages.size, requests.size)
        }
    }
}
