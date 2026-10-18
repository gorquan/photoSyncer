package com.coooolfan.xiaomialbumsyncer.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator

class ApiE2eSuite {

    private val mcpJson = ObjectMapper()

    @Test
    fun fullApiWorkflow() {
        val workDir = Files.createTempDirectory("xiaomi-api-e2e-").toRealPath()
        try {
            MockXiaomiApiServer.start().use { mock ->
                ApplicationProcess.start(mock.baseUrl, workDir).use { application ->
                    try {
                        executeWorkflow(application.api, mock, workDir)
                    } catch (e: Throwable) {
                        throw AssertionError("API E2E 执行失败: ${e.message}\n\n应用日志:\n${application.logs()}", e)
                    }
                }
                mock.assertNoUnexpectedRequests()
            }
        } finally {
            deleteRecursively(workDir)
        }
    }

    private fun executeWorkflow(api: ApiClient, mock: MockXiaomiApiServer, workDir: Path) {
        assertFalse(api.json(api.get("/api/system-config").expect(200)).path("init").asBoolean())
        api.post("/api/system-config", mapOf("password" to "e2e-password")).expect(200)
        api.post("/api/account/1/check-auth").expect(401)
        api.get("/api/token?password=${api.encode("wrong-password")}").expect(401)
        api.get("/api/token?password=${api.encode("e2e-password")}").expect(200)

        assertTrue(api.json(api.get("/api/system-config").expect(200)).path("init").asBoolean())
        api.get("/api/system-config/normal").expect(200)
        api.post("/api/system-config/normal", mapOf("exifToolPath" to "exiftool")).expect(200)
        api.get("/api/system-config/info").expect(200)
        api.get("/api/system-config/info/debug").expect(200)
        api.post("/api/system-config/mount-path", mapOf("path" to workDir.toString())).expect(200)
        api.get("/api/system-config/notify-config").expect(200)
        api.post(
            "/api/system-config/notify-config",
            mapOf(
                "notifyConfig" to linkedMapOf<String, Any?>(
                    "url" to "${mock.baseUrl}/_control/v1/notify",
                    "headers" to mapOf("Content-Type" to "application/json"),
                    "body" to "{\"text\":\"${'$'}{crontab.name}\",\"success\":\"${'$'}{success}/${'$'}{total}\"}",
                    "dailySummaryBody" to null,
                    "dailySummaryCron" to null,
                    "dailySummaryTimeZone" to null,
                )
            )
        ).expect(200)

        api.get("/api/openapi.yml").expect(200)
        api.get("/api/openapi.zip").expect(200)
        api.get("/api/openapi.html").expect(200)

        api.get("/api/passkey/available").expect(200)
        api.get("/api/passkey").expect(200)
        val registerStart = api.post(
            "/api/passkey/register/start",
            mapOf("password" to "e2e-password", "credentialName" to "e2e-passkey")
        ).expect(200)
        assertTrue(api.json(registerStart).path("challenge").asText().isNotBlank())
        api.post("/api/passkey/authenticate/start").expect(500)

        val account = api.json(
            api.post(
                "/api/account",
                mapOf(
                    "nickname" to "Mock Xiaomi",
                    "passToken" to "mock-pass-token",
                    "userId" to "mock-user",
                )
            ).expect(200)
        )
        assertEquals("UNKNOWN", account.path("authState").asText())
        assertTrue(account.path("authCheckedAt").isNull || account.path("authCheckedAt").isMissingNode)
        assertFalse(account.has("credentials"))
        assertFalse(account.has("passToken"))
        val accountId = account.path("id").asLong()
        val accountList = api.json(api.get("/api/account").expect(200))
        assertFalse(accountList.toString().contains("mock-pass-token"))
        assertFalse(accountList.toString().contains("credentials"))
        api.put(
            "/api/account/$accountId",
            mapOf(
                "nickname" to "Mock Xiaomi Updated",
                "passToken" to "mock-pass-token",
                "userId" to "mock-user",
            )
        ).expect(200)

        val checkedAccount = api.json(api.post("/api/account/$accountId/check-auth").expect(200))
        assertEquals("READY", checkedAccount.path("authState").asText())
        assertTrue(java.time.Instant.parse(checkedAccount.path("authCheckedAt").asText()).isAfter(java.time.Instant.EPOCH))
        assertFalse(checkedAccount.toString().contains("mock-pass-token"))
        assertFalse(checkedAccount.has("credentials"))
        api.post("/api/account/999999/check-auth").expect(400)
        assertEquals(0, api.json(api.get("/api/album").expect(200)).size(), "Authentication checks must not create local albums")
        val albums = api.json(api.get("/api/album/latest/$accountId").expect(200))
        assertEquals(3, albums.size())
        val cameraAlbumId = findAlbumId(albums, "1")
        val screenshotsAlbumId = findAlbumId(albums, "2")
        val audioAlbumId = findAlbumId(albums, "-1")
        api.get("/api/album").expect(200)

        val cameraAssets = api.json(api.get("/api/asset/$cameraAlbumId/latest").expect(200))
        assertTrue(cameraAssets.first().path("checksum").asText().matches(Regex("[a-f0-9]{40}")))
        api.get("/api/asset/$cameraAlbumId").expect(200)
        val audioAssets = api.json(api.get("/api/asset/$audioAlbumId/latest").expect(200))
        assertEquals("sample-audio.m4a", audioAssets.first().path("fileName").asText())
        api.get("/api/album/date-map?albumIds=$cameraAlbumId").expect(200)

        val downloadDir = workDir.resolve("downloads")
        val config = linkedMapOf<String, Any?>(
            "expression" to "0 0 0 1 1 ? 2099",
            "timeZone" to "UTC",
            "targetPath" to downloadDir.toString(),
            "downloadImages" to true,
            "downloadVideos" to false,
            "rewriteExifTime" to false,
            "rewriteExifTimeZone" to "UTC",
            "skipExistingFile" to false,
            "rewriteFileSystemTime" to false,
            "checkSha1" to true,
            "fetchFromDbSize" to 2,
            "downloaders" to 1,
            "verifiers" to 1,
            "exifProcessors" to 1,
            "fileTimeWorkers" to 1,
            "downloadAudios" to false,
            "notify" to true,
        )
        val crontabBody = linkedMapOf<String, Any?>(
            "name" to "API E2E",
            "description" to "Native metadata coverage",
            "enabled" to false,
            "syncMode" to "FULL",
            "config" to config,
            "accountId" to accountId,
            "albumIds" to listOf(cameraAlbumId),
        )
        val crontab = api.json(api.post("/api/crontab", crontabBody).expect(200))
        val crontabId = crontab.path("id").asLong()
        api.put("/api/crontab/$crontabId", mapOf("description" to "Native metadata E2E")).expect(200)
        api.get("/api/crontab").expect(200)
        api.post("/api/crontab/$crontabId/current").expect(200)
        api.post("/api/crontab/$crontabId/executions").expect(200)

        val historyId = awaitCompletedHistory(api, crontabId)
        val detailPage = api.json(
            api.get("/api/crontab/history/$historyId/details?pageIndex=0&pageSize=10").expect(200)
        )
        assertEquals(1, detailPage.path("totalRowCount").asInt())
        val detail = detailPage.path("rows").first()
        assertTrue(detail.path("downloadCompleted").asBoolean())
        assertTrue(detail.path("sha1Verified").asBoolean())
        assertTrue(detail.path("exifFilled").asBoolean())
        assertTrue(detail.path("fsTimeUpdated").asBoolean())
        assertTrue(detail.path("message").isMissingNode || detail.path("message").isNull)

        val downloadedFile = Path.of(detail.path("filePath").asText())
        assertTrue(Files.isRegularFile(downloadedFile), "下载文件不存在: $downloadedFile")
        assertEquals(cameraAssets.first().path("checksum").asText(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(downloadedFile))))
        assertEquals("PUBLISHED", detail.path("publicationStatus").asText())
        assertTrue(detail.path("archivePath").isMissingNode || detail.path("archivePath").isNull)
        assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
        assertEquals(1, javax.imageio.ImageIO.read(downloadedFile.toFile()).width)
        mock.awaitRequest("/_control/v1/notify", Duration.ofSeconds(10))

        assertEquals(2, mock.routeCount("/gallery/user/album/list"))
        assertTrue(mock.routeCount("/gallery/user/galleries") >= 2)
        assertEquals(0, mock.routeCount("/gallery/user/timeline"))
        assertTrue(mock.routeCount("/gallery/storage") >= 1)
        assertTrue(mock.routeCount("/mock/oss/101") >= 1)
        assertTrue(mock.routeCount("/mock/download/101") >= 1)

        executeMcpWorkflow(api, crontabId, historyId)

        val beforeMigration = api.json(api.get("/api/crontab").expect(200)).first { it.path("id").asLong() == crontabId }
            .path("histories").maxOf { it.path("id").asLong() }
        api.put("/api/crontab/$crontabId", mapOf("config" to (config + mapOf("synologyPhotos" to true)))).expect(200)
        assertTrue(Files.exists(downloadedFile), "enabling Photos must keep old files until replacement succeeds")
        api.post("/api/crontab/$crontabId/executions").expect(200)
        val migratedHistory = awaitCompletedHistory(api, crontabId, afterHistoryId = beforeMigration)
        val migratedDetails = api.json(api.get("/api/crontab/history/$migratedHistory/details?pageIndex=0&pageSize=10").expect(200)).path("rows")
        val migratedFile = Path.of(migratedDetails.first().path("filePath").asText())
        assertEquals("PUBLISHED", migratedDetails.first().path("publicationStatus").asText())
        assertTrue(Files.isRegularFile(migratedFile))
        assertFalse(Files.exists(downloadedFile), "verified replacement must retire the old album-layout file")

        api.delete("/api/crontab/$crontabId/histories").expect(200)
        val historiesAfterClear = api.json(api.get("/api/crontab").expect(200))
            .first { it.path("id").asLong() == crontabId }
            .path("histories")
        assertTrue(
            historiesAfterClear.isArray && historiesAfterClear.size() == 0,
            "清理历史后 histories 应为空: $historiesAfterClear"
        )

        api.delete("/api/crontab/$crontabId").expect(200)

        executeCursorSyncWorkflow(
            api = api,
            mock = mock,
            workDir = workDir,
            accountId = accountId,
            screenshotsAlbumId = screenshotsAlbumId,
            baseConfig = config,
        )

        executeRecordingWorkflows(
            api = api,
            mock = mock,
            workDir = workDir,
            accountId = accountId,
            cameraAlbumId = cameraAlbumId,
            audioAlbumId = audioAlbumId,
            baseConfig = config,
        )

        executeDeletedMediaWorkflows(
            api = api,
            mock = mock,
            workDir = workDir,
            accountId = accountId,
            cameraAlbumId = cameraAlbumId,
            audioAlbumId = audioAlbumId,
            baseConfig = config,
        )

        executeTransientStorageErrorWorkflow(
            api = api,
            mock = mock,
            workDir = workDir,
            accountId = accountId,
            cameraAlbumId = cameraAlbumId,
            baseConfig = config,
        )

        executeCloudDeletionWorkflow(api, mock, workDir, accountId, config)
        executeTimelineReplacementWorkflow(api, mock, workDir, accountId, config)
        executeImmutableModesWorkflow(api, accountId, cameraAlbumId, screenshotsAlbumId, config)
        api.delete("/api/account/$accountId").expect(200)
        api.post(
            "/api/system-config/password",
            mapOf("oldPassword" to "e2e-password", "password" to "e2e-password-updated")
        ).expect(200)
        api.delete("/api/token").expect(200)
        api.get("/api/account").expect(401)
    }

    private fun executeImmutableModesWorkflow(api: ApiClient, accountId: Long, albumId: Long, otherAlbumId: Long,
                                               baseConfig: LinkedHashMap<String, Any?>) {
        for (mode in listOf("FULL", "TIMELINE", "CURSOR")) for (mirror in listOf(false, true)) {
            if (mode == "CURSOR" && mirror) continue
            // Missing serialized flags represent legacy backup/Photos-off configurations.
            val config = LinkedHashMap(baseConfig).apply { if (mirror) this["syncCloudDeletes"] = true }
            val task = api.json(api.post("/api/crontab", mapOf("name" to "Fixed modes", "description" to "original",
                "enabled" to false, "syncMode" to mode, "accountId" to accountId, "albumIds" to listOf(albumId),
                "config" to config)).expect(200)).path("id").asLong()
            fun saved() = api.json(api.get("/api/crontab").expect(200)).first { it.path("id").asLong() == task }
            for (other in listOf("FULL", "TIMELINE", "CURSOR").filter { it != mode }) {
                val rejected = api.put("/api/crontab/$task", mapOf("syncMode" to other, "description" to "must not save"))
                assertEquals(400, rejected.status, rejected.body)
                assertTrue(rejected.body.contains("扫描方式"), rejected.body)
            }
            val rejected = api.put("/api/crontab/$task", mapOf("name" to "must not save", "config" to
                (config + mapOf("syncCloudDeletes" to !mirror, "downloaders" to 99))))
            assertEquals(400, rejected.status, rejected.body)
            assertTrue(rejected.body.contains("备份／镜像"), rejected.body)
            val unchanged = saved()
            assertEquals(mode, unchanged.path("syncMode").asText())
            assertEquals(mirror, unchanged.path("config").path("syncCloudDeletes").asBoolean())
            assertEquals("Fixed modes", unchanged.path("name").asText())
            assertEquals("original", unchanged.path("description").asText())
            assertEquals(1, unchanged.path("config").path("downloaders").asInt())
            val enabled = config + mapOf("synologyPhotos" to true, "downloaders" to 2)
            val updated = api.json(api.put("/api/crontab/$task", mapOf("syncMode" to mode, "config" to enabled)).expect(200))
            assertTrue(updated.path("config").path("synologyPhotos").asBoolean())
            assertEquals(2, updated.path("config").path("downloaders").asInt())
            for (disabled in listOf(config, enabled + mapOf("synologyPhotos" to false))) {
                val result = api.put("/api/crontab/$task", mapOf("description" to "must not save", "config" to disabled))
                assertEquals(400, result.status, result.body)
                assertTrue(result.body.contains("不能关闭"), result.body)
            }
            api.put("/api/crontab/$task", mapOf("description" to "edited", "albumIds" to listOf(otherAlbumId))).expect(200)
            val final = saved()
            assertEquals("edited", final.path("description").asText())
            assertEquals(listOf(otherAlbumId), final.path("albumIds").map { it.asLong() })
            assertTrue(final.path("config").path("synologyPhotos").asBoolean())
            assertEquals(2, final.path("config").path("downloaders").asInt())
            api.delete("/api/crontab/$task").expect(200)
        }
    }

    /**
     * 覆盖 /mcp 端点：Bearer 鉴权、initialize/tools/list/tools/call 全 domain 调用，
     * 让 Native Image tracing agent 采集到 MCP 链路的反射与资源元数据。
     */
    private fun executeMcpWorkflow(api: ApiClient, crontabId: Long, historyId: Long) {
        val readOnlyCredential = api.json(
            api.post(
                "/api/mcp-token",
                mapOf("name" to "E2E 只读", "permission" to "READ_ONLY"),
            ).expect(200)
        )
        val triggerCredential = api.json(
            api.post(
                "/api/mcp-token",
                mapOf("name" to "E2E 允许触发", "permission" to "ALLOW_TRIGGER"),
            ).expect(200)
        )
        val readOnlyToken = readOnlyCredential.path("token").asText()
        val triggerToken = triggerCredential.path("token").asText()
        assertTrue(readOnlyToken.startsWith("xas_mcp_"))
        assertTrue(triggerToken.startsWith("xas_mcp_"))

        val credentials = api.json(api.get("/api/mcp-token").expect(200))
        assertEquals(2, credentials.size())
        assertTrue(credentials.all { !it.has("token") }, "Token 列表不应返回原始 Token: $credentials")
        assertEquals(
            setOf("READ_ONLY", "ALLOW_TRIGGER"),
            credentials.map { it.path("permission").asText() }.toSet(),
        )

        // 未携带或携带错误 token 的请求一律 401
        mcpRequest(api, null, null, 0, "initialize").expect(401)
        mcpRequest(api, "wrong-token", null, 0, "initialize").expect(401)

        fun initialize(token: String, id: Int): String? {
            val init = mcpRequest(
                api, token, null, id, "initialize",
                mapOf(
                    "protocolVersion" to "2025-06-18",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "e2e", "version" to "0"),
                )
            ).expect(200)
            return init.headers.firstValue("mcp-session-id").orElse("").ifEmpty { null }
        }

        val readOnlySessionId = initialize(readOnlyToken, 0)

        mcpRequest(api, readOnlyToken, readOnlySessionId, null, "notifications/initialized").let {
            check(it.status == 200 || it.status == 202) { "notifications/initialized 返回 ${it.status}: ${it.body}" }
        }

        val tools = mcpResult(mcpRequest(api, readOnlyToken, readOnlySessionId, 1, "tools/list"))
        assertTrue(
            tools.path("result").path("tools").any { it.path("name").asText() == "xas_query" },
            "tools/list 应包含 xas_query: $tools"
        )

        var callId = 10
        fun callTool(
            token: String,
            sessionId: String?,
            arguments: Map<String, Any?>,
            expectError: Boolean = false,
        ): JsonNode {
            val protocolResponse = mcpResult(
                mcpRequest(
                    api, token, sessionId, callId++, "tools/call",
                    mapOf("name" to "xas_query", "arguments" to arguments)
                )
            )
            assertEquals(
                expectError,
                protocolResponse.path("result").path("isError").asBoolean(),
                "tools/call $arguments 错误状态不符合预期: $protocolResponse",
            )
            return protocolResponse
        }

        callTool(readOnlyToken, readOnlySessionId, mapOf("domain" to "album", "action" to "list"))
        callTool(readOnlyToken, readOnlySessionId, mapOf("domain" to "crontab", "action" to "list"))
        callTool(
            readOnlyToken,
            readOnlySessionId,
            mapOf(
                "domain" to "crontab", "action" to "get", "id" to crontabId.toString()
            )
        )
        callTool(
            readOnlyToken,
            readOnlySessionId,
            mapOf("domain" to "crontab_history", "action" to "list"),
        )
        callTool(
            readOnlyToken,
            readOnlySessionId,
            mapOf(
                "domain" to "crontab_history_detail", "action" to "list", "id" to historyId.toString()
            )
        )
        callTool(readOnlyToken, readOnlySessionId, mapOf("domain" to "system", "action" to "list"))

        callTool(
            readOnlyToken,
            readOnlySessionId,
            mapOf("domain" to "not_exist", "action" to "list"),
            expectError = true,
        )
        callTool(
            readOnlyToken,
            readOnlySessionId,
            mapOf("domain" to "album", "action" to "trigger"),
            expectError = true,
        )
        callTool(
            readOnlyToken,
            readOnlySessionId,
            mapOf(
                "domain" to "crontab", "action" to "trigger", "id" to crontabId.toString()
            ),
            expectError = true,
        )

        val triggerSessionId = initialize(triggerToken, 2)
        mcpRequest(api, triggerToken, triggerSessionId, null, "notifications/initialized").let {
            check(it.status == 200 || it.status == 202) { "notifications/initialized 返回 ${it.status}: ${it.body}" }
        }
        callTool(
            triggerToken,
            triggerSessionId,
            mapOf(
                "domain" to "crontab", "action" to "trigger", "id" to crontabId.toString()
            ),
        )
        awaitCompletedHistory(api, crontabId, afterHistoryId = historyId)

        closeMcpSession(api, readOnlyToken, readOnlySessionId)
        closeMcpSession(api, triggerToken, triggerSessionId)
        api.delete("/api/mcp-token/${readOnlyCredential.path("id").asLong()}").expect(200)
        api.delete("/api/mcp-token/${triggerCredential.path("id").asLong()}").expect(200)
        assertEquals(0, api.json(api.get("/api/mcp-token").expect(200)).size())
    }

    private fun closeMcpSession(api: ApiClient, token: String, sessionId: String?) {
        api.delete("/mcp", buildMap {
            put("Authorization", "Bearer $token")
            sessionId?.let { put("mcp-session-id", it) }
        })
    }

    private fun mcpRequest(
        api: ApiClient,
        token: String?,
        sessionId: String?,
        id: Int?,
        method: String,
        params: Any? = null,
    ): ApiClient.Response {
        val body = linkedMapOf<String, Any?>("jsonrpc" to "2.0", "method" to method)
        id?.let { body["id"] = it }
        params?.let { body["params"] = it }
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            token?.let { put("Authorization", "Bearer $it") }
            sessionId?.let { put("mcp-session-id", it) }
        }
        return api.postOnNewConnection("/mcp", body, headers)
    }

    private fun mcpResult(response: ApiClient.Response): JsonNode {
        val contentType = response.headers.firstValue("Content-Type").orElse("")
        val payload = if (contentType.contains("text/event-stream")) {
            response.body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull() ?: throw AssertionError("SSE 响应无 data 行: ${response.body}")
        } else {
            response.body
        }
        return mcpJson.readTree(payload)
    }

    /**
     * 位点同步模式（CURSOR）：album/full 预检 + allitems 按 syncTag 翻页拉流。
     * 覆盖：首轮 tag=0 回放、水位头未变跳过、增量翻页位点推进、清理历史后重置回放。
     * 使用空相册 Screenshots(remoteKey=2)，与相册 1 的既有断言完全隔离。
     */
    private fun executeCursorSyncWorkflow(
        api: ApiClient,
        mock: MockXiaomiApiServer,
        workDir: Path,
        accountId: Long,
        screenshotsAlbumId: Long,
        baseConfig: LinkedHashMap<String, Any?>,
    ) {
        val cursorConfig = LinkedHashMap(baseConfig).apply {
            this["targetPath"] = workDir.resolve("cursor-downloads").toString()
            this["downloadImages"] = true
            this["downloadVideos"] = false
            this["downloadAudios"] = false
            this["notify"] = false
            this["skipExistingFile"] = false
        }
        val cursorCrontab = api.json(
            api.post(
                "/api/crontab",
                linkedMapOf<String, Any?>(
                    "name" to "Cursor Sync E2E",
                    "description" to "album/full precheck + allitems cursor stream",
                    "enabled" to false,
                    "syncMode" to "CURSOR",
                    "config" to cursorConfig,
                    "accountId" to accountId,
                    "albumIds" to listOf(screenshotsAlbumId),
                )
            ).expect(200)
        )
        val cursorCrontabId = cursorCrontab.path("id").asLong()

        // 第一轮：无位点基线 → tag=0 回放，空相册单页即追平
        api.post("/api/crontab/$cursorCrontabId/executions").expect(200)
        val firstHistoryId = awaitCompletedHistory(api, cursorCrontabId)
        assertCompletedDetailCount(api, firstHistoryId, 0)
        assertEquals(listOf("0"), mock.allItemsTags(2), "首轮应从 tag=0 回放")

        // 第二轮：syncTag 已到达 album/full 的水位 → 跳过 allitems
        api.post("/api/crontab/$cursorCrontabId/executions").expect(200)
        val secondHistoryId = awaitCompletedHistory(api, cursorCrontabId, afterHistoryId = firstHistoryId)
        assertEquals(listOf("0"), mock.allItemsTags(2), "水位头未变的相册应跳过拉流")

        // 云端新增 3 个资产 → 按位点增量拉取；allItemsPageSize=2 → 两页 0→2→3
        mock.mutate(
            mapOf(
                "operations" to listOf(
                    mapOf(
                        "op" to "addAssets",
                        "userId" to "mock-user",
                        "albumId" to 2,
                        "count" to 3,
                        "template" to mapOf(
                            "type" to "image",
                            "fileName" to "cursor.jpg",
                            "dateTaken" to 1714651200000L,
                            "size" to 4096,
                            "sha1Mode" to "exact",
                            "contentMode" to "jpeg",
                        ),
                    ),
                )
            )
        )
        api.post("/api/crontab/$cursorCrontabId/executions").expect(200)
        val thirdHistoryId = awaitCompletedHistory(api, cursorCrontabId, afterHistoryId = secondHistoryId)
        assertCompletedDetailCount(api, thirdHistoryId, 3)
        assertEquals(
            listOf("0", "0", "2"),
            mock.allItemsTags(2),
            "增量拉流应从上次位点翻页推进",
        )

        // 清理下载历史 → 位点随之清零 → 下一轮重新 tag=0 回放
        api.delete("/api/crontab/$cursorCrontabId/histories").expect(200)
        api.post("/api/crontab/$cursorCrontabId/executions").expect(200)
        val fourthHistoryId = awaitCompletedHistory(api, cursorCrontabId, afterHistoryId = thirdHistoryId)
        assertCompletedDetailCount(api, fourthHistoryId, 3)
        assertEquals(
            listOf("0", "0", "2", "0", "2"),
            mock.allItemsTags(2),
            "清理历史后应重新从 tag=0 回放",
        )

        assertEquals(0, mock.timelineCount(2), "位点模式不应请求相册 timeline")
        api.delete("/api/crontab/$cursorCrontabId").expect(200)
    }

    private fun executeRecordingWorkflows(
        api: ApiClient,
        mock: MockXiaomiApiServer,
        workDir: Path,
        accountId: Long,
        cameraAlbumId: Long,
        audioAlbumId: Long,
        baseConfig: LinkedHashMap<String, Any?>,
    ) {
        val recordingConfig = LinkedHashMap(baseConfig).apply {
            this["targetPath"] = workDir.resolve("recording-downloads").toString()
            this["downloadImages"] = false
            this["downloadVideos"] = false
            this["downloadAudios"] = true
            this["notify"] = false
        }
        val recordingCrontab = api.json(
            api.post(
                "/api/crontab",
                linkedMapOf<String, Any?>(
                    "name" to "Recording API E2E",
                    "description" to "Recorder is independent from gallery",
                    "enabled" to false,
                    "syncMode" to "TIMELINE",
                    "config" to recordingConfig,
                    "accountId" to accountId,
                    "albumIds" to listOf(audioAlbumId),
                )
            ).expect(200)
        )
        val recordingCrontabId = recordingCrontab.path("id").asLong()
        api.post("/api/crontab/$recordingCrontabId/executions").expect(200)
        val recordingHistoryId = awaitCompletedHistory(api, recordingCrontabId)
        assertCompletedDetailCount(api, recordingHistoryId, 1)
        val originalRecordingTransfers = mock.routePrefixCount("/sfs/ns/recorder/file/201/cb/")
        assertTrue(originalRecordingTransfers >= 1)
        api.delete("/api/crontab/$recordingCrontabId").expect(200)

        val mixedConfig = LinkedHashMap(baseConfig).apply {
            this["targetPath"] = workDir.resolve("mixed-downloads").toString()
            this["downloadImages"] = true
            this["downloadVideos"] = false
            this["downloadAudios"] = true
            this["notify"] = false
        }
        val mixedCrontab = api.json(
            api.post(
                "/api/crontab",
                linkedMapOf<String, Any?>(
                    "name" to "Mixed API E2E",
                    "description" to "Gallery and recorder full refresh",
                    "enabled" to false,
                    "syncMode" to "TIMELINE",
                    "config" to mixedConfig,
                    "accountId" to accountId,
                    "albumIds" to listOf(cameraAlbumId, audioAlbumId),
                )
            ).expect(200)
        )
        val mixedCrontabId = mixedCrontab.path("id").asLong()
        api.post("/api/crontab/$mixedCrontabId/executions").expect(200)
        val mixedHistoryId = awaitCompletedHistory(api, mixedCrontabId)
        assertCompletedDetailCount(api, mixedHistoryId, 2)
        api.delete("/api/crontab/$mixedCrontabId").expect(200)

        assertTrue(mock.routeCount("/sfs/ns/recorder/dir/0/list") >= 3)
        assertEquals(originalRecordingTransfers + 1, mock.routePrefixCount("/sfs/ns/recorder/file/201/cb/"), "新目标目录应从云端下载，不复用长期原件副本")
        assertEquals(0, mock.timelineCount(-1), "录音是独立远端资源，不应请求 gallery timeline 的 -1")
        assertTrue(mock.timelineCount(1) >= 1, "混合任务应只为真实相册请求时间线")
    }

    /**
     * 云端已删除资产（列表仍可见但 storage 不可用）的下载应被优雅跳过，
     * 而不是抛异常导致流水线在每个周期反复失败。
     * 相册删除 → code=50050；录音删除 → code=50202；两者均 retriable=false。
     */
    private fun executeDeletedMediaWorkflows(
        api: ApiClient,
        mock: MockXiaomiApiServer,
        workDir: Path,
        accountId: Long,
        cameraAlbumId: Long,
        audioAlbumId: Long,
        baseConfig: LinkedHashMap<String, Any?>,
    ) {
        // ============ 相册：storage 返回 code=50050 ============
        val galleryConfig = LinkedHashMap(baseConfig).apply {
            this["targetPath"] = workDir.resolve("deleted-gallery-downloads").toString()
            this["downloadImages"] = true
            this["downloadVideos"] = false
            this["downloadAudios"] = false
            this["notify"] = false
            this["skipExistingFile"] = false
        }
        val galleryCrontab = api.json(
            api.post(
                "/api/crontab",
                linkedMapOf<String, Any?>(
                    "name" to "Deleted Gallery E2E",
                    "description" to "云端不可用原件保持失败并等待恢复",
                    "enabled" to false,
                    "syncMode" to "FULL",
                    "config" to galleryConfig,
                    "accountId" to accountId,
                    "albumIds" to listOf(cameraAlbumId),
                )
            ).expect(200)
        )
        val galleryCrontabId = galleryCrontab.path("id").asLong()

        // 第一轮：正常下载资产 101
        api.post("/api/crontab/$galleryCrontabId/executions").expect(200)
        awaitCompletedHistory(api, galleryCrontabId)

        // 云端新增资产 103 后被删除：仍出现在列表中，但 storage 返回 code=50050
        mock.mutate(
            mapOf(
                "operations" to listOf(
                    mapOf(
                        "op" to "addAssets",
                        "userId" to "mock-user",
                        "albumId" to 1,
                        "assets" to listOf(
                            mapOf(
                                "id" to 103,
                                "type" to "image",
                                "fileName" to "deleted-photo.jpg",
                                "dateTaken" to 1714651200000L,
                                "size" to 4096,
                                "sha1Mode" to "exact",
                                "contentMode" to "jpeg",
                            )
                        ),
                    ),
                    mapOf(
                        "op" to "markDeleted",
                        "userId" to "mock-user",
                        "albumId" to 1,
                        "ids" to listOf(103),
                    ),
                )
            )
        )

        // 清空下载历史，让 101 与 103 同时进入第二轮流水线
        api.delete("/api/crontab/$galleryCrontabId/histories").expect(200)
        api.post("/api/crontab/$galleryCrontabId/executions").expect(200)
        val galleryHistoryId = awaitCompletedHistory(api, galleryCrontabId)
        val galleryDetails = api.json(
            api.get("/api/crontab/history/$galleryHistoryId/details?pageIndex=0&pageSize=10").expect(200)
        )
        assertEquals(2, galleryDetails.path("totalRowCount").asInt())

        val deletedGalleryId = findAssetId(api, cameraAlbumId, "103")
        val deletedGallery = galleryDetails.path("rows").first { it.path("asset").path("id").asText() == deletedGalleryId }
        assertFalse(deletedGallery.path("downloadCompleted").asBoolean())
        assertEquals("FAILED", deletedGallery.path("publicationStatus").asText())
        assertTrue(deletedGallery.path("message").asText().isNotBlank())
        assertFalse(Files.exists(Path.of(deletedGallery.path("filePath").asText())), "已删除资产不应产出文件")
        assertEquals(0, mock.routePrefixCount("/mock/oss/103"), "已删除资产不应请求 OSS 签名直链")
        api.delete("/api/crontab/$galleryCrontabId").expect(200)

        // ============ 录音：storage 返回 code=50202 ============
        val recordingConfig = LinkedHashMap(baseConfig).apply {
            this["targetPath"] = workDir.resolve("deleted-recording-downloads").toString()
            this["downloadImages"] = false
            this["downloadVideos"] = false
            this["downloadAudios"] = true
            this["notify"] = false
            this["skipExistingFile"] = false
        }
        val recordingCrontab = api.json(
            api.post(
                "/api/crontab",
                linkedMapOf<String, Any?>(
                    "name" to "Deleted Recording E2E",
                    "description" to "云端不可用录音保持失败并等待恢复",
                    "enabled" to false,
                    "syncMode" to "FULL",
                    "config" to recordingConfig,
                    "accountId" to accountId,
                    "albumIds" to listOf(audioAlbumId),
                )
            ).expect(200)
        )
        val recordingCrontabId = recordingCrontab.path("id").asLong()

        api.post("/api/crontab/$recordingCrontabId/executions").expect(200)
        awaitCompletedHistory(api, recordingCrontabId)

        // 云端新增录音 301 后被删除：列表可见，storage 返回 code=50202
        mock.mutate(
            mapOf(
                "operations" to listOf(
                    mapOf(
                        "op" to "addRecordings",
                        "userId" to "mock-user",
                        "recordings" to listOf(
                            mapOf(
                                "id" to 301,
                                "fileName" to "deleted-recording.m4a",
                                "recordingType" to 0,
                                "createTime" to 1714651200000L,
                                "size" to 28,
                                "sha1Mode" to "exact",
                                "contentPattern" to "xiaomi-album-syncer-api-e2e\n",
                            )
                        ),
                    ),
                    mapOf(
                        "op" to "markDeleted",
                        "userId" to "mock-user",
                        "ids" to listOf(301),
                    ),
                )
            )
        )

        api.delete("/api/crontab/$recordingCrontabId/histories").expect(200)
        api.post("/api/crontab/$recordingCrontabId/executions").expect(200)
        val recordingHistoryId = awaitCompletedHistory(api, recordingCrontabId)
        val recordingDetails = api.json(
            api.get("/api/crontab/history/$recordingHistoryId/details?pageIndex=0&pageSize=10").expect(200)
        )
        assertEquals(2, recordingDetails.path("totalRowCount").asInt())

        val deletedRecordingId = findAssetId(api, audioAlbumId, "301")
        val deletedRecording = recordingDetails.path("rows").first { it.path("asset").path("id").asText() == deletedRecordingId }
        assertFalse(deletedRecording.path("downloadCompleted").asBoolean())
        assertEquals("FAILED", deletedRecording.path("publicationStatus").asText())
        assertTrue(deletedRecording.path("message").asText().isNotBlank())
        assertFalse(Files.exists(Path.of(deletedRecording.path("filePath").asText())), "已删除录音不应产出文件")
        assertEquals(0, mock.routePrefixCount("/mock/oss/301"), "已删除录音不应请求 OSS 签名直链")
        api.delete("/api/crontab/$recordingCrontabId").expect(200)
    }

    /**
     * storage 返回 retriable=true 的瞬时错误时，资产应被记录为失败并在下一个周期重试，
     * 错误恢复后下载成功——不可用的来源均不能标为已发布。
     */
    private fun executeTransientStorageErrorWorkflow(
        api: ApiClient,
        mock: MockXiaomiApiServer,
        workDir: Path,
        accountId: Long,
        cameraAlbumId: Long,
        baseConfig: LinkedHashMap<String, Any?>,
    ) {
        val config = LinkedHashMap(baseConfig).apply {
            this["targetPath"] = workDir.resolve("transient-downloads").toString()
            this["downloadImages"] = true
            this["downloadVideos"] = false
            this["downloadAudios"] = false
            this["notify"] = false
            this["skipExistingFile"] = false
        }
        val crontab = api.json(
            api.post(
                "/api/crontab",
                linkedMapOf<String, Any?>(
                    "name" to "Transient Storage Error E2E",
                    "description" to "retriable=true 的瞬时错误应在下个周期重试并最终成功",
                    "enabled" to false,
                    "syncMode" to "FULL",
                    "config" to config,
                    "accountId" to accountId,
                    "albumIds" to listOf(cameraAlbumId),
                )
            ).expect(200)
        )
        val crontabId = crontab.path("id").asLong()

        // 第一轮：基线运行（相册中此时还有上一场景遗留的已删除资产，应被跳过而非失败）
        api.post("/api/crontab/$crontabId/executions").expect(200)
        val baselineHistoryId = awaitCompletedHistory(api, crontabId)

        // 注入瞬时错误：资产 104 的 storage 返回 code=50051, retriable=true
        mock.mutate(
            mapOf(
                "operations" to listOf(
                    mapOf(
                        "op" to "addAssets",
                        "userId" to "mock-user",
                        "albumId" to 1,
                        "assets" to listOf(
                            mapOf(
                                "id" to 104,
                                "type" to "image",
                                "fileName" to "transient-photo.jpg",
                                "dateTaken" to 1714651200000L,
                                "size" to 4096,
                                "sha1Mode" to "exact",
                                "contentMode" to "jpeg",
                            )
                        ),
                    ),
                    mapOf(
                        "op" to "setStorageError",
                        "userId" to "mock-user",
                        "albumId" to 1,
                        "ids" to listOf(104),
                        "code" to 50051,
                        "retriable" to true,
                        "description" to "系统繁忙，请稍后重试",
                        "reason" to "service busy",
                    ),
                )
            )
        )

        // 第二轮：104 下载失败，应记录错误消息且不标记完成，但流水线整体正常结束
        api.post("/api/crontab/$crontabId/executions").expect(200)
        val failedHistoryId = awaitCompletedHistory(api, crontabId, afterHistoryId = baselineHistoryId)
        val failedDetails = api.json(
            api.get("/api/crontab/history/$failedHistoryId/details?pageIndex=0&pageSize=10").expect(200)
        )
        val retryAssetId = findAssetId(api, cameraAlbumId, "104")
        val failedDetail = failedDetails.path("rows").first { it.path("asset").path("id").asText() == retryAssetId }
        assertEquals(retryAssetId, failedDetail.path("asset").path("id").asText())
        assertFalse(failedDetail.path("downloadCompleted").asBoolean(), "瞬时错误不应标记下载完成")
        assertTrue(failedDetail.path("message").asText().contains("50051"), "失败消息应包含错误码")

        // 云端恢复后，下一周期应重试并下载成功
        mock.mutate(
            mapOf(
                "operations" to listOf(
                    mapOf(
                        "op" to "setStorageError",
                        "userId" to "mock-user",
                        "albumId" to 1,
                        "ids" to listOf(104),
                        "code" to 0,
                    )
                )
            )
        )
        api.post("/api/crontab/$crontabId/executions").expect(200)
        val recoveredHistoryId = awaitCompletedHistory(api, crontabId, afterHistoryId = failedHistoryId)
        val recoveredDetails = api.json(
            api.get("/api/crontab/history/$recoveredHistoryId/details?pageIndex=0&pageSize=10").expect(200)
        )
        val recoveredDetail = recoveredDetails.path("rows").first { it.path("asset").path("id").asText() == retryAssetId }
        assertEquals(retryAssetId, recoveredDetail.path("asset").path("id").asText())
        assertTrue(recoveredDetail.path("downloadCompleted").asBoolean(), "恢复后应下载成功")
        assertTrue(recoveredDetail.path("message").isMissingNode || recoveredDetail.path("message").isNull)
        assertTrue(Files.exists(Path.of(recoveredDetail.path("filePath").asText())), "恢复后应产出文件")
        assertTrue(mock.routePrefixCount("/mock/oss/104") >= 1, "恢复后应请求 OSS 签名直链")
        api.delete("/api/crontab/$crontabId").expect(200)
    }

    /** Synthetic cloud only: single-copy exports mirror confirmed deletion in full and timeline modes. */
    private fun executeCloudDeletionWorkflow(api: ApiClient, mock: MockXiaomiApiServer, workDir: Path,
                                            accountId: Long, baseConfig: LinkedHashMap<String, Any?>) {
        val recycleRequests = mock.routeCount("/gallery/deleted_v2")
        for ((index, mode) in listOf("FULL", "TIMELINE").withIndex()) {
            val remoteAlbum = 70 + index
            val remoteAsset = 700 + index
            fun mutate(operation: Map<String, Any?>) = mock.mutate(mapOf("operations" to listOf(mapOf("userId" to "mock-user") + operation)))
            mutate(mapOf("op" to "createAlbum", "album" to mapOf("albumId" to remoteAlbum, "name" to "Mirror $mode")))
            val spec = mapOf("id" to remoteAsset, "type" to "image", "fileName" to "mirror-$mode.jpg",
                "dateTaken" to 1714651200000L, "size" to 4096, "sha1Mode" to "exact", "contentMode" to "jpeg")
            fun add(op: String) = mutate(mapOf("op" to op, "albumId" to remoteAlbum, "assets" to listOf(spec)))
            add("addAssets")
            val albumId = findAlbumId(api.json(api.get("/api/album/latest/$accountId").expect(200)), remoteAlbum.toString())
            val config = LinkedHashMap(baseConfig).apply {
                this["syncCloudDeletes"] = true; this["synologyPhotos"] = true; this["notify"] = false
                this["targetPath"] = workDir.resolve("mirror-$mode").toString()
            }
            val task = api.json(api.post("/api/crontab", mapOf("name" to "Mirror $mode", "description" to "Managed deletion",
                "enabled" to false, "syncMode" to mode, "config" to config, "accountId" to accountId,
                "albumIds" to listOf(albumId))).expect(200)).path("id").asLong()
            api.put("/api/crontab/$task", mapOf("syncMode" to "CURSOR")).expect(400)
            var previous: Long? = null
            fun run(): JsonNode {
                api.post("/api/crontab/$task/executions").expect(200)
                val id = awaitCompletedHistory(api, task, previous); previous = id
                return api.json(api.get("/api/crontab").expect(200)).first { it.path("id").asLong() == task }
                    .path("histories").first { it.path("id").asLong() == id }
            }
            fun exportedPath(history: JsonNode): Path {
                val details = api.json(api.get("/api/crontab/history/${history.path("id").asLong()}/details?pageIndex=0&pageSize=10").expect(200)).path("rows")
                val detail = details.single()
                assertEquals("PUBLISHED", detail.path("publicationStatus").asText())
                assertTrue(detail.path("archivePath").isMissingNode || detail.path("archivePath").isNull)
                assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
                return Path.of(detail.path("filePath").asText())
            }
            val first = run(); assertEquals("SUCCESS", first.path("executionStatus").asText())
            val export = exportedPath(first)
            val bytes = Files.readAllBytes(export)
            assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
            mutate(mapOf("op" to "deleteAssets", "albumId" to remoteAlbum, "ids" to listOf(remoteAsset)))
            val deleted = run()
            assertEquals("SUCCESS", deleted.path("executionStatus").asText(), deleted.toString())
            val report = deleted.path("deletionReport")
            assertEquals("COMPLETE", report.path("status").asText())
            assertEquals(1, report.path("exportsDeleted").asInt()); assertEquals(0, report.path("originalsDeleted").asInt())
            assertFalse(Files.exists(export)); assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
            assertEquals(0, run().path("deletionReport").path("exportsDeleted").asInt())
            add("restoreAssets")
            val restored = run(); exportedPath(restored)
            assertTrue(Files.exists(export)); assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
            // Changed user bytes survive deletion and the public run is visibly incomplete.
            Files.writeString(export, "externally edited")
            mutate(mapOf("op" to "deleteAssets", "albumId" to remoteAlbum, "ids" to listOf(remoteAsset)))
            val conflicted = run()
            assertEquals("PARTIAL", conflicted.path("executionStatus").asText())
            assertEquals("INCOMPLETE", conflicted.path("deletionReport").path("status").asText())
            assertTrue(conflicted.path("deletionReport").path("conflictCount").asInt() > 0)
            assertEquals("externally edited", Files.readString(export))
            Files.write(export, bytes)
            val retried = run()
            assertEquals("SUCCESS", retried.path("executionStatus").asText())
            assertFalse(Files.exists(export)); assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
            add("restoreAssets"); exportedPath(run())
            // Incomplete authoritative enumeration stops before ordinary refresh/cursor movement or cleanup.
            mutate(mapOf("op" to "deleteAlbum", "albumId" to remoteAlbum))
            val beforeFull = mock.routeCount("/gallery/album/full")
            val beforeTimeline = mock.routeCount("/gallery/user/timeline")
            val beforeCursor = mock.routeCount("/gallery/allitems")
            val beforeGallery = mock.routeCount("/gallery/user/galleries")
            val beforeStorage = mock.routeCount("/gallery/storage")
            val failed = run()
            assertEquals("FAILED", failed.path("executionStatus").asText())
            assertEquals("FAILED", failed.path("deletionReport").path("status").asText())
            assertFalse(failed.path("fetchedAllAssets").asBoolean())
            assertTrue(failed.path("syncCursors").isNull || failed.path("syncCursors").isMissingNode)
            assertEquals(beforeFull, mock.routeCount("/gallery/album/full"))
            assertEquals(beforeTimeline + if (mode == "TIMELINE") 1 else 0, mock.routeCount("/gallery/user/timeline"))
            assertEquals(beforeCursor, mock.routeCount("/gallery/allitems"))
            assertEquals(beforeGallery + if (mode == "FULL") 1 else 0, mock.routeCount("/gallery/user/galleries"))
            assertEquals(beforeStorage, mock.routeCount("/gallery/storage"))
            assertTrue(Files.exists(export)); assertFalse(Files.exists(workDir.resolve("fallback/media/originals")))
            // Failure/deletion-only rows must not disappear into collapsed zero-asset histories.
            val publicGroup = api.json(api.get("/api/crontab/$task/histories?pageIndex=0&pageSize=10").expect(200)).path("rows").first()
            assertEquals(1, publicGroup.path("runCount").asInt())
            assertEquals("FAILED", publicGroup.path("executionStatus").asText())
            api.delete("/api/crontab/$task").expect(200)
        }
        val albumId = findAlbumId(api.json(api.get("/api/album/latest/$accountId").expect(200)), "1")
        val beforeDeleted = mock.routeCount("/gallery/deleted_v2")
        for (mode in listOf("CURSOR")) {
            val config = LinkedHashMap(baseConfig).apply {
                this["syncCloudDeletes"] = true; this["synologyPhotos"] = true
                this["targetPath"] = workDir.resolve("retention-$mode").toString()
            }
            val request = mapOf("name" to "Retention $mode", "description" to "", "enabled" to false,
                "syncMode" to mode, "config" to config, "accountId" to accountId, "albumIds" to listOf(albumId))
            val rejected = api.post("/api/crontab", request)
            assertEquals(400, rejected.status, rejected.body)
            config["syncCloudDeletes"] = false
            val task = api.json(api.post("/api/crontab", request).expect(200)).path("id").asLong()
            config["syncCloudDeletes"] = true
            val update = api.put("/api/crontab/$task", mapOf("config" to config))
            assertEquals(400, update.status, update.body)
            api.delete("/api/crontab/$task").expect(200)
        }
        assertEquals(beforeDeleted, mock.routeCount("/gallery/deleted_v2"))
        assertEquals(recycleRequests, mock.routeCount("/gallery/deleted_v2"), "mirrors compare selected scope without recycle confirmation")
    }

    /** Count decreases and same-count replacements must still import newly observed records. */
    private fun executeTimelineReplacementWorkflow(api: ApiClient, mock: MockXiaomiApiServer, workDir: Path,
                                                   accountId: Long, baseConfig: LinkedHashMap<String, Any?>) {
        for (mirror in listOf(false, true)) {
            val remoteAlbum = if (mirror) 76 else 75
            val ids = (0..4).map { remoteAlbum * 10 + it }
            fun mutate(operation: Map<String, Any?>) = mock.mutate(mapOf("operations" to listOf(mapOf("userId" to "mock-user") + operation)))
            fun add(ids: List<Int>) = mutate(mapOf("op" to "addAssets", "albumId" to remoteAlbum, "assets" to ids.map {
                mapOf("id" to it, "type" to "image", "fileName" to "timeline-$it.jpg", "dateTaken" to 1714651200000L,
                    "size" to 4096, "sha1Mode" to "exact", "contentMode" to "jpeg")
            }))
            mutate(mapOf("op" to "createAlbum", "album" to mapOf("albumId" to remoteAlbum, "name" to "Timeline replacements")))
            add(listOf(ids[0], ids[1], ids[2]))
            val albumId = findAlbumId(api.json(api.get("/api/album/latest/$accountId").expect(200)), remoteAlbum.toString())
            val config = LinkedHashMap(baseConfig).apply {
                this["syncCloudDeletes"] = mirror; this["synologyPhotos"] = true; this["notify"] = false
                this["skipExistingFile"] = true
                this["targetPath"] = workDir.resolve("timeline-replacements-$mirror").toString()
            }
            val task = api.json(api.post("/api/crontab", mapOf("name" to "Timeline replacements", "description" to "Timeline replacements",
                "enabled" to false, "syncMode" to "TIMELINE", "config" to config, "accountId" to accountId,
                "albumIds" to listOf(albumId))).expect(200)).path("id").asLong()
            var previous: Long? = null
            fun run(expected: Int): JsonNode {
                api.post("/api/crontab/$task/executions").expect(200)
                val historyId = awaitCompletedHistory(api, task, previous); previous = historyId
                assertCompletedDetailCount(api, historyId, expected)
                return api.json(api.get("/api/crontab/history/$historyId/details?pageIndex=0&pageSize=10").expect(200)).path("rows")
            }
            val initial = run(3)
            val initialPaths = initial.map { Path.of(it.path("filePath").asText()) }
            mutate(mapOf("op" to "deleteAssets", "albumId" to remoteAlbum, "ids" to listOf(ids[0], ids[1])))
            add(listOf(ids[3]))
            val decreased = run(if (mirror) 2 else 4)
            val addedId = findAssetId(api, albumId, ids[3].toString())
            assertTrue(decreased.any { it.path("asset").path("id").asText() == addedId && Files.exists(Path.of(it.path("filePath").asText())) })
            mutate(mapOf("op" to "deleteAssets", "albumId" to remoteAlbum, "ids" to listOf(ids[2])))
            add(listOf(ids[4]))
            val replaced = run(if (mirror) 2 else 5)
            val replacementId = findAssetId(api, albumId, ids[4].toString())
            assertTrue(replaced.any { it.path("asset").path("id").asText() == replacementId && Files.exists(Path.of(it.path("filePath").asText())) })
            val before = mock.routeCount("/gallery/user/galleries")
            run(if (mirror) 2 else 5)
            assertEquals(before, mock.routeCount("/gallery/user/galleries"), "unchanged index should skip enumeration")
            if (mirror) {
                assertTrue(initialPaths.none(Files::exists), "replaced and removed members must be unlinked")
                mutate(mapOf("op" to "deleteAssets", "albumId" to remoteAlbum, "ids" to listOf(ids[3], ids[4])))
                run(0)
                assertTrue(replaced.none { Files.exists(Path.of(it.path("filePath").asText())) }, "empty days must remove their remaining exports")
                val emptyBefore = mock.routeCount("/gallery/user/galleries")
                run(0)
                assertEquals(emptyBefore, mock.routeCount("/gallery/user/galleries"))
            } else assertTrue(initialPaths.all(Files::exists), "backup keeps removed cloud members")
            api.delete("/api/crontab/$task").expect(200)
        }
    }

    private fun assertCompletedDetailCount(api: ApiClient, historyId: Long, expected: Int) {
        val detailPage = api.json(
            api.get("/api/crontab/history/$historyId/details?pageIndex=0&pageSize=10").expect(200)
        )
        assertEquals(expected, detailPage.path("totalRowCount").asInt())
        detailPage.path("rows").forEach { detail ->
            assertTrue(detail.path("downloadCompleted").asBoolean())
            assertTrue(detail.path("sha1Verified").asBoolean())
            assertTrue(detail.path("message").isMissingNode || detail.path("message").isNull)
        }
    }

    private fun findAssetId(api: ApiClient, albumId: Long, remoteKey: String): String {
        val assets = api.json(api.get("/api/asset/$albumId").expect(200))
        return assets.first { it.path("remoteKey").asText() == remoteKey }.path("id").asText()
    }

    private fun findAlbumId(albums: JsonNode, remoteKey: String): Long {
        return albums.firstOrNull { it.path("remoteKey").asText() == remoteKey }
            ?.path("id")
            ?.asLong()
            ?: error("未找到 remoteKey=$remoteKey 的相册，响应: $albums")
    }

    private fun awaitCompletedHistory(api: ApiClient, crontabId: Long, afterHistoryId: Long? = null): Long {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            val crontabs = api.json(api.get("/api/crontab").expect(200))
            val crontab = crontabs.firstOrNull { it.path("id").asLong() == crontabId }
            val history = crontab?.path("histories")?.firstOrNull {
                (afterHistoryId == null || it.path("id").asLong() > afterHistoryId) &&
                        it.path("endTime").asText().isNotBlank()
            }
            if (history != null) {
                return history.path("id").asLong()
            }
            Thread.sleep(100)
        }
        error("等待定时任务完成超时")
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
