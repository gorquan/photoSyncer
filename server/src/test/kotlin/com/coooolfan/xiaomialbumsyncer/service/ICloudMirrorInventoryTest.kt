package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.*
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.*
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.dialect.SQLiteDialect
import org.babyfish.jimmer.sql.kt.*
import org.babyfish.jimmer.sql.runtime.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ICloudMirrorInventoryTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private fun record(type: String, name: String, fields: Map<String, Any> = emptyMap()) = mapOf(
        "recordType" to type, "recordName" to name, "fields" to fields.mapValues { mapOf("value" to it.value) })
    private fun photo() = record("CPLAsset", "photo", mapOf("masterRef" to mapOf("recordName" to "master"), "assetDate" to 1000))
    private fun master() = record("CPLMaster", "master", mapOf("filenameEnc" to "cGhvdG8uanBn", "resOriginalFileType" to "public.jpeg",
        "resOriginalRes" to mapOf("size" to 10, "fileChecksum" to "version")))

    @Test fun fullMirrorUsesOnlyStrictInventoryAndPreservesItOnAnInterruptedScan() = withStore { sql, assets, deletion, task, requests, remote ->
        assets.refreshMirrorInventory(task, history(sql, task), deletion)
        assertEquals(1, assets.countAssetsForPublication(task))
        assertTrue(deletion.deletedGroups(task).isEmpty())
        assertTrue(requests.none { it == "zone" || it == "lookup" })
        val first = deletion.inventory(task)
        remote.mode = "broken"
        assertThrows(IllegalArgumentException::class.java) { assets.refreshMirrorInventory(task, history(sql, task), deletion) }
        assertEquals(first, deletion.inventory(task))
        assertTrue(deletion.deletedGroups(task).isEmpty())
        remote.mode = "empty"
        assets.refreshMirrorInventory(task, history(sql, task), deletion)
        assertEquals(0, assets.countAssetsForPublication(task))
        assertEquals(1, deletion.deletedGroups(task).size)
    }

    @Test fun cursorBaselineThenSteadyChangesUsesCommittedScopeWithoutFullRescan() = withStore { sql, assets, deletion, original, requests, remote ->
        val task = Crontab(original) { syncMode = CrontabSyncMode.CURSOR }
        val first = history(sql, task)
        assets.refreshICloudAssetsByCursor(task, first, emptyMap(), deletion)
        assertEquals(1, assets.countAssetsForPublication(task))
        val checkpoint = sql.findById(CrontabHistory::class, first.id)!!.syncCursors!!
        assertTrue(checkpoint.keys.single().endsWith(":mirror-v2"))
        requests.clear(); remote.mode = "deleted"
        val second = history(sql, task)
        assets.refreshICloudAssetsByCursor(task, second, checkpoint, deletion)
        assertEquals(0, assets.countAssetsForPublication(task))
        assertEquals(1, deletion.deletedGroups(task).size)
        assertFalse(requests.contains("query"), "steady mirror must not enumerate albums or capture a baseline")
        assertEquals(listOf("batch", "zone", "lookup"), requests)
        assertEquals("deleted", sql.findById(CrontabHistory::class, second.id)!!.syncCursors!!.values.single())
    }

    private fun history(sql: KSqlClient, task: Crontab) = sql.saveCommand(CrontabHistory {
        crontabId = task.id; startTime = Instant.EPOCH; endTime = null; fetchedAllAssets = false; syncCursors = null; timelineSnapshot = null
    }, SaveMode.INSERT_ONLY).execute().modifiedEntity
    private class Remote { var mode = "live" }
    private fun withStore(action: (KSqlClient, AssetService, CloudDeletionService, Crontab, MutableList<String>, Remote) -> Unit) {
        val remote = Remote()
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val body = mapper.readTree(exchange.requestBody.readAllBytes())
                val path = exchange.requestURI.path.substringAfterLast('/')
                val response: Any = if (path == "validate") mapOf("dsInfo" to mapOf("hsaVersion" to 2, "dsid" to "id"), "hsaTrustedBrowser" to true,
                    "webservices" to mapOf("ckdatabasews" to mapOf("url" to "http://127.0.0.1:${server.address.port}"))) else {
                    requests += path
                    when (path) {
                        "batch" -> mapOf("batch" to body.path("batch").map { mapOf("records" to listOf(record("Count", "count", mapOf("itemCount" to 1)))) })
                        "query" -> when {
                            body.path("resultsLimit").asInt() == 2 -> mapOf("syncToken" to "before", "records" to emptyList<Any>())
                            body.at("/query/recordType").asText() == "CheckIndexingState" -> mapOf("records" to listOf(record("Index", "index", mapOf("state" to "FINISHED"))))
                            remote.mode == "broken" && body.at("/query/filterBy/0/fieldValue/value").asInt() > 0 -> emptyMap<String, Any>()
                            remote.mode == "empty" || body.at("/query/filterBy/0/fieldValue/value").asInt() > 0 -> mapOf("records" to emptyList<Any>())
                            else -> mapOf("records" to listOf(photo(), master()))
                        }
                        "zone" -> mapOf("zones" to listOf(mapOf("zoneID" to mapOf("zoneName" to "PrimarySync"), "syncToken" to remote.mode,
                            "moreComing" to false, "records" to if (remote.mode == "deleted") listOf(mapOf("recordName" to "photo", "deleted" to true)) else emptyList())))
                        "lookup" -> mapOf("records" to body.path("records").map { mapOf("recordName" to it.path("recordName").asText(), "serverErrorCode" to "UNKNOWN_ITEM") })
                        else -> error("unexpected endpoint")
                    }
                }
                val bytes = mapper.writeValueAsBytes(response)
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            } catch (_: Throwable) { exchange.sendResponseHeaders(500, -1); exchange.close() }
        }
        server.start()
        try { HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${directory.resolve("db.sqlite")}"; maximumPoolSize = 1 }).use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val sql = newKSqlClient { setDialect(SQLiteDialect()); setConnectionManager(ConnectionManager.simpleConnectionManager(source)); setDatabaseNamingStrategy(DefaultDatabaseNamingStrategy.LOWER_CASE) }
            val notify = NotifyService(sql)
            val accounts = ICloudAccountService(sql, notify)
            val photos = ICloudPhotos(accounts)
            val xiaomi = XiaoMiApi(TokenManager(sql, notify))
            val media = CloudMediaService(sql, xiaomi, photos)
            val credentials = ICloudCredentials("test", "test", "com", headers = mutableMapOf("X-Apple-Session-Token" to "session"))
            val account = sql.saveCommand(ProviderAccount { nickname = "test"; provider = CloudProvider.ICLOUD; userId = "test"; this.credentials = mapper.writeValueAsString(credentials) }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val root = "http://127.0.0.1:${server.address.port}"
            val client = ICloudClient(credentials, ICloudClient.Endpoints("$root/auth", "$root/setup", root))
            @Suppress("UNCHECKED_CAST")
            val clients = ICloudAccountService::class.java.getDeclaredField("clients").apply { isAccessible = true }.get(accounts) as ConcurrentHashMap<Long, ICloudClient>
            clients[account.id] = client
            val album = sql.saveCommand(Album { accountId = account.id; remoteKey = ICloudAlbumKey("PrimarySync", "__all__").encode(); name = "all"; shadow = false; assetCount = null; lastUpdateTime = null }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val row = sql.saveCommand(Crontab { name = "test"; description = ""; enabled = false; accountId = account.id; syncMode = CrontabSyncMode.FULL
                config = CrontabConfig("0 0 * * * ?", "UTC", directory.resolve("out").toString(), true, true, false, null, synologyPhotos = true, syncCloudDeletes = true)
            }, SaveMode.INSERT_ONLY).execute().modifiedEntity
            val task = Crontab(row) { albumIds = listOf(album.id) }
            action(sql, AssetService(sql, xiaomi, media, photos), CloudDeletionService(sql, media), task, requests, remote)
        } } finally { server.stop(0) }
    }
}
