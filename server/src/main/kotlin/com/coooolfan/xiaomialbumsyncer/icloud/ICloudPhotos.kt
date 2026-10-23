package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.model.*
import com.fasterxml.jackson.databind.JsonNode
import org.noear.solon.annotation.Managed
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

@Managed
class ICloudPhotos(private val accounts: ICloudAccountService) {
    private val log = LoggerFactory.getLogger(ICloudPhotos::class.java)

    fun fetchAlbums(accountId: Long): List<Album> = accounts.withClient(accountId) { fetchAlbums(accountId, it) }

    internal fun fetchAlbums(accountId: Long, client: ICloudClient): List<Album> {
        val zones = client.cloud("zones/list", emptyMap<String, String>()).path("zones")
            .filterNot { it.path("deleted").asBoolean() }.map { it.at("/zoneID/zoneName").asText() }
            .filter { it == "PrimarySync" || it.startsWith("SharedSync-") }
        require(zones.isNotEmpty()) { "iCloud 照片图库不可用，请检查网页数据访问和高级数据保护设置" }
        return zones.flatMap { zone ->
            checkIndex(client, zone)
            val prefix = if (zone == "PrimarySync") "" else "共享图库 / "
            val result = mutableListOf(album(accountId, "${prefix}所有照片", ICloudAlbumKey(zone, "__all__")))
            result += album(accountId, "${prefix}隐藏", ICloudAlbumKey(zone, "__hidden__"))
            result += album(accountId, "${prefix}个人收藏", ICloudAlbumKey(zone, "__favorites__"))
            result += album(accountId, "${prefix}截图", ICloudAlbumKey(zone, "__screenshots__"))
            result += album(accountId, "${prefix}连拍", ICloudAlbumKey(zone, "__bursts__"))
            var marker: JsonNode? = null
            val seenMarkers = mutableSetOf<String>()
            do {
                val body = mutableMapOf<String, Any>("query" to mapOf("recordType" to "CPLAlbumByPositionLive"), "zoneID" to zoneId(zone))
                marker?.let { body["continuationMarker"] = it }
                val page = client.cloud("records/query", body)
                for (record in page.path("records")) {
                    val name = record.path("recordName").asText()
                    if (name in setOf("----Root-Folder----", "----Project-Root-Folder----") || field(record, "isDeleted").asBoolean()) continue
                    val title = decodeName(field(record, "albumNameEnc").asText()).ifBlank { name }
                    result += album(accountId, prefix + title, ICloudAlbumKey(zone, name))
                }
                marker = page.get("continuationMarker")?.takeUnless { it.isNull }
                if (marker != null) require(seenMarkers.add(marker.toString())) { "iCloud 相册分页没有推进" }
            } while (marker != null)
            fetchAlbumCounts(result, client)
        }
    }

    fun fetchAlbumCounts(albums: List<Album>): List<Album> {
        if (albums.isEmpty()) return emptyList()
        require(albums.all { it.accountId == albums.first().accountId })
        return accounts.withClient(albums.first().accountId) { fetchAlbumCounts(albums, it) }
    }

    internal fun fetchAlbumCounts(albums: List<Album>, client: ICloudClient): List<Album> =
        albums.groupBy { ICloudAlbumKey.decode(it.remoteKey).zone }.flatMap { (zone, selected) ->
            selected.chunked(200).flatMap { batch ->
                val queries = batch.map { entry ->
                    mapOf("resultsLimit" to 1, "zoneWide" to true, "zoneID" to zoneId(zone),
                        "query" to mapOf("recordType" to "HyperionIndexCountLookup", "filterBy" to mapOf(
                            "fieldName" to "indexCountID", "comparator" to "IN", "fieldValue" to mapOf(
                                "type" to "STRING_LIST", "value" to listOf(ICloudAlbumKey.decode(entry.remoteKey).countIndex())))))
                }
                val counts = client.cloud("internal/records/query/batch", mapOf("batch" to queries)).path("batch")
                require(counts.isArray && counts.size() == batch.size) { "iCloud 相册计数响应不完整" }
                batch.mapIndexed { index, entry ->
                    val response = counts[index]
                    val records = response.path("records")
                    val count = field(records.path(0), "itemCount")
                    Album(entry) {
                        assetCount = count.takeIf {
                            response.path("serverErrorCode").asText().isBlank() && records.isArray && records.size() == 1 &&
                                records[0].path("serverErrorCode").asText().isBlank() &&
                                it.isIntegralNumber && it.canConvertToLong() && it.asLong() >= 0
                        }?.asLong()
                    }
                }
            }
        }

    fun fetchAssets(album: Album, handler: (List<Asset>) -> Unit): Long = accounts.withClient(album.accountId) { fetchAssets(album, it, handler = handler) }

    fun fetchAssetsForMirror(album: Album, handler: (List<Asset>) -> Unit): Long =
        accounts.withClient(album.accountId) { fetchAssets(album, it, strict = true, handler = handler) }

    internal fun fetchAssets(album: Album, client: ICloudClient, strict: Boolean = false, handler: (List<Asset>) -> Unit): Long {
        val ref = ICloudAlbumKey.decode(album.remoteKey)
        checkIndex(client, ref.zone)
        var rank = 0L
        var total = 0L
        val seen = mutableSetOf<String>()
        var marker: JsonNode? = null
        val seenMarkers = mutableSetOf<String>()
        while (true) {
            val filters = mutableListOf(filter("startRank", "INT64", rank), filter("direction", "STRING", "ASCENDING"))
            val smartAlbum = ref.smartAlbum()
            if (smartAlbum != null) filters += filter("smartAlbum", "STRING", smartAlbum)
            else if (!ref.albumId.startsWith("__")) filters += filter("parentId", "STRING", ref.albumId)
            val body = mutableMapOf<String, Any>("query" to mapOf("recordType" to ref.queryType(), "filterBy" to filters),
                "resultsLimit" to 200, "zoneID" to zoneId(ref.zone))
            marker?.let { body["continuationMarker"] = it }
            val page = client.cloud("records/query", body)
            if (strict) validateICloudEnumerationPage(page)
            val nextMarker = iCloudAssetContinuationMarker(page)
            if (nextMarker != null) require(seenMarkers.add(nextMarker.toString())) { "iCloud 资产分页没有推进，请重试同步" }
            val records = page.path("records").toList()
            val assets = records.filter { it.path("recordType").asText() == "CPLAsset" }
            require(assets.isNotEmpty() || nextMarker == null) { "iCloud 资产续页缺少资产记录，无法确认完整性" }
            if (assets.isEmpty()) break
            val masters = records.filter { it.path("recordType").asText() == "CPLMaster" }.associateBy { it.path("recordName").asText() }
            val missing = assets.map { field(it, "masterRef").path("recordName").asText() }.filterNot(masters::containsKey).distinct()
            val allMasters = masters + if (missing.isEmpty()) emptyMap() else lookupRecords(client, ref.zone, missing).associateBy { it.path("recordName").asText() }
            val rows = assets.flatMap { asset ->
                val recordName = asset.path("recordName").asText()
                require(seen.add(recordName)) { "iCloud 资产分页重复，请重试同步" }
                val masterName = field(asset, "masterRef").path("recordName").asText()
                val master = allMasters[masterName] ?: error("iCloud 资产缺少原件记录")
                parseResources(album, asset, master).also {
                    if (strict) require(it.isNotEmpty()) { "iCloud 镜像资产缺少可用原件，本次保留文件" }
                }
            }
            handler(rows)
            total += rows.size
            // A continuation chain keeps its original query, including startRank.
            if (marker != null && nextMarker == null) break
            if (nextMarker == null) rank += assets.size
            marker = nextMarker
        }
        return total
    }

    fun fetchIncremental(albums: List<Album>, cursor: String?, handler: (List<Asset>) -> Unit, commitCursor: (String) -> Unit) {
        require(albums.isNotEmpty())
        accounts.withClient(albums.first().accountId) { client ->
            fetchIncremental(albums, client, cursor, handler, commitCursor)
        }
    }

    fun fetchIncrementalPages(
        albums: List<Album>, cursor: String?, mirror: Boolean,
        saveBaseline: (List<Asset>) -> Unit,
        baselineReady: (Set<String>) -> Unit,
        beforePage: () -> Unit = {},
        consume: (ICloudChangePage) -> Unit,
    ): Boolean {
        require(albums.isNotEmpty())
        return accounts.withClient(albums.first().accountId) { client ->
            fetchIncrementalPages(albums, client, cursor, mirror, saveBaseline, baselineReady, beforePage, consume)
        }
    }

    fun download(accountId: Long, zone: String, asset: Asset, path: Path): Boolean {
        val key = ICloudResourceKey.decode(asset.remoteKey)
        repeat(2) { attempt ->
            val (client, url) = accounts.withClient(accountId) { client ->
                val resource = resolveResource(client, zone, key)
                requireResolvedResourceVersion(resource, asset)
                val url = resource.path("downloadURL").asText()
                require(url.isNotBlank()) { "iCloud 文件没有下载地址" }
                client to url
            }
            // CDN 下载不占用账号认证锁，多个下载器可以并行传输。
            try {
                client.download(url, path)
                require(Files.size(path) == asset.size) { "iCloud 文件大小不一致，请重试下载" }
                return true
            } catch (e: ICloudApiException) {
                if (attempt != 0 || e.status !in setOf(401, 403, 410)) throw e
            }
        }
        error("iCloud 下载失败")
    }

    internal fun resolveResource(client: ICloudClient, zone: String, key: ICloudResourceKey): JsonNode {
        val asset = lookupRecords(client, zone, listOf(key.assetId)).singleOrNull()
            ?: error("iCloud 资产已不可用")
        val resource = field(asset, key.resource + "Res")
        if (!resource.isMissingNode && !resource.isNull) return resource
        val masterName = field(asset, "masterRef").path("recordName").asText()
        require(masterName.isNotBlank()) { "iCloud 资产缺少原件标识" }
        val master = lookupRecords(client, zone, listOf(masterName)).singleOrNull()
            ?: error("iCloud 原件已不可用")
        return field(master, key.resource + "Res").also {
            require(!it.isMissingNode && !it.isNull) { "iCloud 文件资源已不可用" }
        }
    }

    private fun checkIndex(client: ICloudClient, zone: String) {
        val response = client.cloud("records/query", mapOf("query" to mapOf("recordType" to "CheckIndexingState"), "zoneID" to zoneId(zone)))
        val records = response.path("records")
        val record = records.path(0)
        val state = record.at("/fields/state/value")
        val serverCode = record.path("serverErrorCode").asText()
        if (serverCode.isNotBlank()) {
            log.error("iCloud 索引状态查询失败: zone={}, serverErrorCode={}, reason={}", zone, serverCode, record.path("reason"))
            error("iCloud 索引状态查询失败（错误码：$serverCode）")
        }
        if (!records.isArray || records.size() != 1 || !state.isTextual || state.asText().isBlank()) {
            log.error("iCloud 索引状态响应异常: zone={}, recordsType={}, recordsCount={}, state={}, responseFields={}, recordFields={}",
                zone, records.nodeType, records.size(), state, response.fieldNames().asSequence().toList(), record.fieldNames().asSequence().toList())
            error("iCloud 索引状态响应异常：缺少有效的索引状态，请查看服务端日志")
        }
        if (state.asText() != "FINISHED") {
            log.warn("iCloud 照片索引尚未完成: zone={}, state={}", zone, state.asText())
            error("iCloud 照片索引尚未完成（状态：${state.asText()}），请稍后重试")
        }
    }

    private fun album(accountId: Long, title: String, ref: ICloudAlbumKey): Album = Album {
        remoteKey = ref.encode()
        name = title
        assetCount = null
        lastUpdateTime = null
        shadow = false
        this.accountId = accountId
    }

}

    internal fun parseResources(album: Album, asset: JsonNode, master: JsonNode): List<Asset> {
        val recordName = asset.path("recordName").asText()
        val originalName = decodeName(field(master, "filenameEnc").asText()).ifBlank { "$recordName.bin" }
        return listOf("resOriginal", "resOriginalAlt", "resOriginalVidCompl").mapNotNull { variant ->
            val resource = field(master, variant + "Res")
            if (resource.isMissingNode || resource.isNull) return@mapNotNull null
            val checksum = resource.path("fileChecksum").asText()
            val uti = field(master, variant + "FileType").asText().ifBlank { field(master, "itemType").asText() }
            val (mime, extension) = mediaType(uti)
            val safeName = originalName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\r\\n]"), "_")
            val stem = safeName.substringBeforeLast('.', safeName)
            val ext = if (variant == "resOriginal") safeName.substringAfterLast('.', extension) else extension
            val zone = ICloudAlbumKey.decode(album.remoteKey).zone
            val groupIdentity = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(listOf(album.accountId, "ICLOUD", zone, recordName))
            val suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(groupIdentity.toByteArray()), 0, 16)
            val roleSuffix = if (variant == "resOriginalAlt") "_alternate" else ""
            Asset {
                remoteKey = ICloudResourceKey(recordName, variant).encode()
                fileName = "asset_$suffix$roleSuffix.${ext.lowercase()}"
                type = if (mime.startsWith("video/")) AssetType.VIDEO else AssetType.IMAGE
                recordingType = null
                dateTaken = Instant.ofEpochMilli(field(asset, "assetDate").asLong())
                albumId = album.id
                this.checksum = checksum
                mimeType = mime
                title = stem
                size = resource.path("size").asLong()
            }
        }
    }
    private fun decodeName(encoded: String) = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrDefault("")
    internal fun field(record: JsonNode, key: String): JsonNode = record.path("fields").path(key).path("value")
    private fun zoneId(zone: String) = mapOf("zoneName" to zone)
    private fun filter(name: String, type: String, value: Any) = mapOf("fieldName" to name, "comparator" to "EQUALS", "fieldValue" to mapOf("type" to type, "value" to value))
    private fun mediaType(uti: String): Pair<String, String> = when (uti) {
        "public.heic", "public.heif" -> "image/heic" to "heic"
        "public.jpeg" -> "image/jpeg" to "jpg"
        "public.png" -> "image/png" to "png"
        "com.compuserve.gif" -> "image/gif" to "gif"
        "public.tiff" -> "image/tiff" to "tiff"
        "com.apple.quicktime-movie" -> "video/quicktime" to "mov"
        "public.mpeg-4" -> "video/mp4" to "mp4"
        "com.adobe.raw-image" -> "image/x-adobe-dng" to "dng"
        "com.canon.cr2-raw-image" -> "image/x-canon-cr2" to "cr2"
        "com.canon.cr3-raw-image" -> "image/x-canon-cr3" to "cr3"
        "com.sony.arw-raw-image" -> "image/x-sony-arw" to "arw"
        "com.nikon.raw-image", "com.nikon.nef-raw-image" -> "image/x-nikon-nef" to "nef"
        "com.fuji.raw-image" -> "image/x-fuji-raf" to "raf"
        else -> "application/octet-stream" to "bin"
    }

internal fun lookupRecords(client: ICloudClient, zone: String, names: List<String>): List<JsonNode> =
    names.distinct().chunked(200).flatMap { batch ->
        val records = client.cloud("records/lookup", mapOf("zoneID" to mapOf("zoneName" to zone),
            "records" to batch.map { mapOf("recordName" to it) })).path("records")
        require(records.isArray) { "iCloud 记录查询响应无效" }
        require(batch.toSet() == records.map { it.path("recordName").asText() }.toSet()) { "iCloud 记录查询响应不完整" }
        records.filter { record ->
            val error = record.path("serverErrorCode").asText()
            require(error.isBlank() || error == "NOT_FOUND" || error == "UNKNOWN_ITEM") { "iCloud 记录查询失败：$error" }
            error.isBlank() && !record.path("deleted").asBoolean()
        }
    }

/** Fresh CloudKit signature is opaque version evidence, not a raw content hash. */
internal fun requireResolvedResourceVersion(resource: JsonNode, asset: Asset) {
    require(resource.path("fileChecksum").asText() == asset.checksum && resource.path("size").asLong(-1) == asset.size) {
        "iCloud 来源版本已变化，请刷新相册后重试"
    }
}

/** Only a complete requested-record response may establish library presence/deletion. */
internal fun parseICloudPresence(response: JsonNode, recordName: String): CloudPresence {
    require(response.path("serverErrorCode").asText().isBlank()) { "iCloud 资产状态查询失败" }
    val records = response.path("records")
    require(records.isArray && records.size() == 1 && records[0].path("recordName").asText() == recordName) {
        "iCloud 资产状态查询响应不完整"
    }
    return parseICloudPresenceRecord(records[0])
}

internal fun parseICloudPresenceRecord(record: JsonNode): CloudPresence {
    val error = record.path("serverErrorCode").asText()
    if (error in setOf("NOT_FOUND", "UNKNOWN_ITEM")) return CloudPresence.DELETED
    require(error.isBlank()) { "iCloud 资产状态查询失败：$error" }
    if (iCloudDeletionFlag(record.path("deleted")) || iCloudDeletionFlag(field(record, "isDeleted")) ||
        iCloudDeletionFlag(field(record, "isExpunged"))) return CloudPresence.DELETED
    require(record.path("recordType").asText() == "CPLAsset" && record.path("fields").isObject &&
        field(record, "masterRef").path("recordName").asText().isNotBlank()) {
        "iCloud 资产状态查询没有明确的有效记录"
    }
    return CloudPresence.LIVE
}

internal fun iCloudDeletionFlag(flag: JsonNode): Boolean {
    if (flag.isMissingNode || flag.isNull) return false
    require(flag.isBoolean || flag.isIntegralNumber && flag.asLong() in 0L..1L) { "iCloud 删除标识无效" }
    return flag.asBoolean()
}

internal fun validateICloudEnumerationPage(page: JsonNode) {
    require(page.path("serverErrorCode").asText().isBlank() && page.path("records").isArray) {
        "iCloud 资产分页响应不完整"
    }
    val records = page.path("records")
    val marker = iCloudAssetContinuationMarker(page)
    require(!records.isEmpty || marker == null) { "iCloud 资产续页缺少资产记录，无法确认完整性" }
    require(records.all { record ->
        record.path("serverErrorCode").asText().isBlank() && record.path("recordName").asText().isNotBlank() &&
            record.path("recordType").asText() in setOf("CPLAsset", "CPLMaster", "CPLContainerRelation")
    }) { "iCloud 资产分页包含无效记录" }
    require(records.isEmpty || records.any { it.path("recordType").asText() == "CPLAsset" }) {
        "iCloud 资产分页缺少资产记录，无法确认扫描结束"
    }
    // A tombstone in a live query is not a live observation; a fresh direct lookup must decide it.
    require(records.none { iCloudDeletionFlag(it.path("deleted")) || iCloudDeletionFlag(field(it, "isDeleted")) ||
        iCloudDeletionFlag(field(it, "isExpunged")) }) { "iCloud 活跃资产分页包含删除记录，请重试同步" }
}

private fun iCloudAssetContinuationMarker(page: JsonNode): JsonNode? {
    val marker = page.get("continuationMarker") ?: return null
    if (marker.isNull) return null
    require(
        marker.isTextual && marker.asText().isNotBlank() ||
            marker.isObject && marker.size() > 0
    ) { "iCloud 资产续页标记无效，无法确认完整性" }
    return marker
}
