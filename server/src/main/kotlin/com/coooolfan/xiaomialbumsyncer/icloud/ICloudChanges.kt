package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.model.Album
import com.coooolfan.xiaomialbumsyncer.model.Asset
import com.coooolfan.xiaomialbumsyncer.model.CloudPresence
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory

/** A page is acknowledged only after assets, presence and its cursor have committed together. */
data class ICloudChangePage(
    val zone: String,
    val cursor: String,
    val assets: List<Asset>,
    val presence: Map<String, CloudPresence>,
)

/** CloudKit 图库变更流；协议参考 kei 的 syncToken 文档（见 THIRD_PARTY_NOTICES）。 */
internal fun ICloudPhotos.fetchIncremental(
    albums: List<Album>, client: ICloudClient, cursor: String?,
    handler: (List<Asset>) -> Unit, commitCursor: (String) -> Unit,
) {
    fetchIncrementalPages(albums, client, cursor, false, handler, {}) { page ->
        handler(page.assets)
        commitCursor(page.cursor)
    }
}

internal fun ICloudPhotos.fetchIncrementalPages(
    albums: List<Album>, client: ICloudClient, cursor: String?, mirror: Boolean,
    saveBaseline: (List<Asset>) -> Unit,
    baselineReady: (Set<String>) -> Unit,
    beforePage: () -> Unit = {},
    consume: (ICloudChangePage) -> Unit,
): Boolean {
    var established = false
    val albumKeys = albums.associate { it.id to ICloudAlbumKey.decode(it.remoteKey) }
    val zone = albumKeys.getValue(albums.first().id).zone
    require(albums.all { it.accountId == albums.first().accountId && albumKeys.getValue(it.id).zone == zone })
    fun establishBaseline(): String {
        // 在全量枚举之前取水位，再回放枚举期间的变更，防止漏掉同时上传的资产。
        val token = client.cloud("records/query", mapOf(
            "zoneID" to mapOf("zoneName" to zone), "resultsLimit" to 2,
            "query" to mapOf("recordType" to "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted",
                "filterBy" to listOf(
                    mapOf("fieldName" to "startRank", "comparator" to "EQUALS", "fieldValue" to mapOf("type" to "INT64", "value" to 0)),
                    mapOf("fieldName" to "direction", "comparator" to "EQUALS", "fieldValue" to mapOf("type" to "STRING", "value" to "ASCENDING"))))))
            .path("syncToken").asText()
        require(token.isNotBlank()) { "iCloud 未返回同步位点，无法建立增量基线" }
        val observed = mutableSetOf<String>()
        albums.forEach { album -> fetchAssets(album, client, strict = mirror) { rows ->
            observed += rows.map { ICloudResourceKey.decode(it.remoteKey).assetId }
            saveBaseline(rows)
        } }
        baselineReady(observed)
        established = true
        return token
    }
    try {
        readChanges(albums, albumKeys, client, cursor?.takeIf { it.isNotBlank() } ?: establishBaseline(), mirror, beforePage, consume)
    } catch (e: InvalidICloudCursor) {
        if (cursor.isNullOrBlank()) throw e
        LoggerFactory.getLogger(ICloudPhotos::class.java).warn("iCloud 图库 {} 位点失效，重新建立全量基线", zone)
        readChanges(albums, albumKeys, client, establishBaseline(), mirror, beforePage, consume)
    }
    return established
}

private class InvalidICloudCursor : RuntimeException("iCloud 同步位点失效")
private val expiredCursorCodes = setOf("CHANGE_TOKEN_EXPIRED", "SYNC_TOKEN_EXPIRED", "INVALID_SYNC_TOKEN", "ZONE_NOT_FOUND")

private fun ICloudPhotos.readChanges(
    albums: List<Album>, albumKeys: Map<Long, ICloudAlbumKey>, client: ICloudClient, initial: String,
    mirror: Boolean, beforePage: () -> Unit, consume: (ICloudChangePage) -> Unit,
) {
    val zone = albumKeys.getValue(albums.first().id).zone
    val userAlbums = albums.filter { !albumKeys.getValue(it.id).albumId.startsWith("__") }
    val selectedContainers = userAlbums.map { albumKeys.getValue(it.id).albumId }.toSet()
    var token = initial
    val seen = mutableSetOf(initial)
    while (true) {
        beforePage()
        val response = try {
            client.cloud("changes/zone", mapOf("zones" to listOf(mapOf(
                "zoneID" to mapOf("zoneName" to zone), "syncToken" to token, "resultsLimit" to 200))))
        } catch (e: ICloudApiException) {
            if (e.serverCode in expiredCursorCodes) throw InvalidICloudCursor()
            throw e
        }
        val page = response.path("zones").singleOrNull() ?: error("iCloud 图库变更响应无效")
        val error = page.path("serverErrorCode").asText()
        if (error.isNotBlank()) {
            val reason = page.path("reason").asText()
            if (error in expiredCursorCodes ||
                error == "BAD_REQUEST" && listOf("token", "continuation").any { reason.contains(it, ignoreCase = true) })
                throw InvalidICloudCursor()
            throw ICloudApiException(503, error)
        }
        require(page.at("/zoneID/zoneName").asText() == zone) { "iCloud 变化响应属于其他图库" }
        val nextToken = page.path("syncToken")
        require(nextToken.isTextual && nextToken.asText().isNotBlank()) { "iCloud 变更响应缺少同步位点" }
        val next = nextToken.asText()
        val more = page.path("moreComing")
        require(more.isBoolean && page.path("records").isArray) { "iCloud 图库变更响应不完整" }
        if (more.asBoolean()) require(seen.add(next)) { "iCloud 变更分页位点没有推进" }
        val records = page.path("records").toList()
        require(records.all { it.path("recordName").asText().isNotBlank() && it.path("serverErrorCode").asText().isBlank() }) {
            "iCloud 变化记录不完整"
        }
        // Tombstones can omit recordType. Relation tombstones do not imply library deletion.
        val candidates = if (mirror) records.filter {
            it.path("recordType").asText() == "CPLAsset" ||
                it.path("recordType").asText().isBlank() && !live(it)
        }.map { it.path("recordName").asText() }.distinct() else emptyList()
        val current = lookupPresenceRecords(client, zone, candidates)
        val presence = current.filterNot {
            // An untyped relation tombstone may have been restored since its event was emitted.
            it.path("recordType").asText() in setOf("CPLContainerRelation", "CPLMaster", "CPLAlbum") &&
                it.path("serverErrorCode").asText().isBlank() && live(it)
        }.associate { record ->
            val name = record.path("recordName").asText()
            name to parseICloudPresenceRecord(record)
        }
        val source = if (mirror) records.filterNot { it.path("recordName").asText() in presence } +
            current.filter { presence[it.path("recordName").asText()] == CloudPresence.LIVE } else records
        val assets = source.filter { it.path("recordType").asText() == "CPLAsset" }.associateBy { it.path("recordName").asText() }.toMutableMap()
        val addedMembers = records.filter { it.path("recordType").asText() == "CPLContainerRelation" && live(it) }
            .filter { field(it, "containerId").asText() in selectedContainers }
            .map { field(it, "itemId").asText().also { id -> require(id.isNotBlank()) { "iCloud 相册成员缺少资产标识" } } }
        lookupRecords(client, zone, addedMembers.filterNot(assets::containsKey)).forEach { assets[it.path("recordName").asText()] = it }
        val liveAssets = assets.values.filter(::live)
        val masters = records.filter { it.path("recordType").asText() == "CPLMaster" }.associateBy { it.path("recordName").asText() }.toMutableMap()
        val masterNames = liveAssets.map { field(it, "masterRef").path("recordName").asText().also { name ->
            require(name.isNotBlank()) { "iCloud 变化资产缺少原件标识" }
        } }
        lookupRecords(client, zone, masterNames.filterNot(masters::containsKey)).forEach { masters[it.path("recordName").asText()] = it }
        // 成员关系的 recordName 可直接 lookup；查询的是当前成员状态，跨页关系也不会漏掉。
        val relationNames = liveAssets.flatMap { asset -> userAlbums.map { "${asset.path("recordName").asText()}-IN-${albumKeys.getValue(it.id).albumId}" } }
        val memberships = lookupRecords(client, zone, relationNames).filter(::live).map { it.path("recordName").asText() }.toSet()
        val rows = liveAssets.flatMap { changed ->
            val name = changed.path("recordName").asText()
            var asset = changed
            var master = masters[field(asset, "masterRef").path("recordName").asText()]
            if (master == null) {
                // 变更流中的资产可能已被永久删除，确认当前状态后再决定是否推进。
                asset = lookupRecords(client, zone, listOf(name)).singleOrNull()?.takeIf(::live)
                    ?: return@flatMap emptyList()
                master = lookupRecords(client, zone, listOf(field(asset, "masterRef").path("recordName").asText())).singleOrNull()
                    ?: error("iCloud 变化资产缺少原件记录，请稍后重试")
            }
            if (!live(master)) return@flatMap emptyList()
            albums.filter { album ->
                val ref = albumKeys.getValue(album.id)
                when (ref.albumId) {
                    "__all__" -> !field(asset, "isHidden").asBoolean()
                    "__hidden__" -> field(asset, "isHidden").asBoolean()
                    "__favorites__" -> field(asset, "isFavorite").asBoolean() && !field(asset, "isHidden").asBoolean()
                    // remapEnums=true：CloudKit 截图枚举为 3，不是 PhotoKit 的位掩码。
                    "__screenshots__" -> field(asset, "assetSubtypeV2").asInt() == 3 && !field(asset, "isHidden").asBoolean()
                    "__bursts__" -> field(asset, "burstId").asText("").isNotBlank() && !field(asset, "isHidden").asBoolean()
                    else -> "$name-IN-${ref.albumId}" in memberships
                }
            }.flatMap { parseResources(it, asset, master) }
        }
        // The consumer atomically saves the page with its checkpoint. Empty pages still advance.
        consume(ICloudChangePage(zone, next, rows, presence))
        token = next
        if (!more.asBoolean()) break
    }
}

private fun live(record: JsonNode): Boolean = !iCloudDeletionFlag(record.path("deleted")) &&
    !iCloudDeletionFlag(field(record, "isDeleted")) && !iCloudDeletionFlag(field(record, "isExpunged"))

private fun lookupPresenceRecords(client: ICloudClient, zone: String, names: List<String>): List<JsonNode> =
    names.chunked(200).flatMap { batch ->
        val response = client.cloud("records/lookup", mapOf("zoneID" to mapOf("zoneName" to zone),
            "records" to batch.map { mapOf("recordName" to it) }))
        val records = response.path("records")
        require(records.isArray && records.size() == batch.size &&
            records.map { it.path("recordName").asText() }.toSet() == batch.toSet()) {
            "iCloud 变化状态查询响应不完整"
        }
        records.toList()
    }
