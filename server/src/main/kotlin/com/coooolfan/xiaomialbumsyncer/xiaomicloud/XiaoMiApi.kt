package com.coooolfan.xiaomialbumsyncer.xiaomicloud

import com.coooolfan.xiaomialbumsyncer.config.XiaomiApiProperties
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.utils.*
import com.fasterxml.jackson.databind.JsonNode
import okhttp3.FormBody
import okhttp3.Request
import org.noear.solon.Solon
import org.noear.solon.annotation.Inject
import org.noear.solon.annotation.Managed
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE
import kotlin.io.path.Path

private val XIAOMI_ALBUM_NAME_BY_ID = mapOf(
    1L to "相机",
    2L to "屏幕截图",
    1000L to "隐私相册",
)

@Managed
class XiaoMiApi(private val tokenManager: TokenManager) {

    @Inject
    private lateinit var apiProperties: XiaomiApiProperties

    private val log = LoggerFactory.getLogger(XiaoMiApi::class.java)

    /** A single gallery page verifies authorization without changing local albums or media. */
    fun checkAuthentication(accountId: Long) {
        getJson(accountId, apiProperties.url("gallery/user/album/list?pageNum=0&pageSize=1&isShared=false&numOfThumbnails=0&ts=${System.currentTimeMillis()}"))
    }

    fun fetchAllAlbums(accountId: Long): List<Album> {
        val allAlbums = mutableListOf<Album>()
        var pageNum = 0
        var hasMorePages = true

        while (hasMorePages) {
            val responseTree = getJson(accountId, apiProperties.url("gallery/user/album/list?ts=${System.currentTimeMillis()}&pageNum=$pageNum&pageSize=10&isShared=false&numOfThumbnails=1"))
            val albumArrayJson = responseTree.at("/data/albums")

            log.info("解析第 ${pageNum + 1} 页相册数据，此页共 ${albumArrayJson.size()} 个相册")
            // 处理当前页数据
            for (albumJson in albumArrayJson) {
                val albumId = albumJson.get("albumId").asLong()
                allAlbums.add(Album {
                    remoteKey = albumId.toString()
                    name = XIAOMI_ALBUM_NAME_BY_ID[albumId]
                        ?: albumJson.get("name")?.asText()
                        ?: "Unknown Album"
                    assetCount = albumJson.get("mediaCount")?.asLong()
                    lastUpdateTime = albumJson.get("lastUpdateTime")?.let { Instant.ofEpochMilli(it.asLong()) }
                    this.accountId = accountId
                    shadow = false
                })
            }

            // 检查是否还有更多页面
            hasMorePages = !responseTree.at("/data/isLastPage").asBoolean()
            pageNum++
        }

        allAlbums.add(Album {
            remoteKey = "-1"
            name = "录音"
            assetCount = null
            lastUpdateTime = null
            shadow = false
            this.accountId = accountId
        })

        return allAlbums.toList() // 返回不可变列表
    }

    /**
     * 流式获取相册资源，每获取一页就调用 handler 处理，避免大相册内存溢出
     * @return 总资源数量
     */
    fun fetchAssetsByAlbumId(album: Album, day: LocalDate? = null, strict: Boolean = false, handler: (List<Asset>) -> Unit): Long {
        var totalCount = 0L
        var pageNum = 0
        var hasMorePages = true
        val pageSize = if (album.isAudioAlbum()) 500 else 200

        val urlDayParams =
            if (day == null) "" else "&startDate=${day.format(BASIC_ISO_DATE)}&endDate=${day.format(BASIC_ISO_DATE)}"

        val seen = mutableSetOf<String>()
        while (hasMorePages) {
            val url =
                if (album.isAudioAlbum())
                    apiProperties.url("sfs/ns/recorder/dir/0/list?ts=${System.currentTimeMillis()}&limit=$pageSize&offset=${pageNum * pageSize}")
                else
                    apiProperties.url("gallery/user/galleries?ts=${System.currentTimeMillis()}&pageNum=$pageNum&pageSize=$pageSize&albumId=${album.remoteKey}")


            val responseTree = getJson(album.accountId, url + urlDayParams)
            val assetArrayJson =
                if (album.isAudioAlbum())
                    responseTree.at("/data/list")
                else
                    responseTree.at("/data/galleries")

            if (strict) validateAssetEnumerationPage(responseTree, album.isAudioAlbum(), pageSize)

            log.info("解析用户 ${album.accountId} 的相册 ${album.name} ID=${album.remoteKey}${if (day != null) " day=$day" else ""} 第 ${pageNum + 1} 页数据，此页共 ${assetArrayJson.size()} 个资源")

            // 处理当前页数据
            val pageAssets = assetArrayJson.map { parseJsonNode(it, album) }
            if (strict) require(pageAssets.all { seen.add(it.remoteKey) }) { "小米资产分页重复，请重试同步" }

            // 立即处理这一页，避免累积
            handler(pageAssets)
            totalCount += pageAssets.size


            hasMorePages = shouldFetchNextAssetPage(
                audioAlbum = album.isAudioAlbum(),
                assetCount = assetArrayJson.size(),
                pageSize = pageSize,
                isLastPage = responseTree.at("/data/isLastPage").asBoolean(),
            )
            pageNum++
        }

        return totalCount
    }

    fun fetchAlbumTimeline(accountId: Long, albumId: Long): AlbumTimeline {
        val responseTree = getJson(accountId, apiProperties.url("gallery/user/timeline?ts=${System.currentTimeMillis()}&albumId=$albumId"))
        val timeline = parseAlbumTimeline(responseTree)
        log.info("从远程解析相册 ID=$albumId 时间线")
        return timeline
    }

    /**
     * 获取相册级水位快照（/gallery/album/full），用于位点同步模式的变更预检。
     * 每次返回全量相册快照，无分页。
     */
    fun fetchAlbumSyncSnapshot(accountId: Long): List<AlbumSyncInfo> {
        val responseTree = getJson(accountId, apiProperties.url("gallery/album/full?ts=${System.currentTimeMillis()}"))
        responseTree.throwIfBizError()

        return responseTree.at("/data/albums").mapNotNull {
            val albumId = it.get("albumId")?.asLong() ?: return@mapNotNull null
            AlbumSyncInfo(
                albumId = albumId,
                incrementalTag = it.path("incrementalTag").asText(),
                totalImageCount = it.path("totalImageCount").asLong(),
            )
        }
    }

    /**
     * 按位点拉取单个相册的资产记录页（/gallery/allitems）。
     * @param tag 续拉位点（服务端返回的 syncTag 原样回传），"0" 表示从相册头部全量回放
     */
    fun fetchAllItemsPage(accountId: Long, album: Album, tag: String): AllItemsPage {
        val responseTree = getJson(
            accountId,
            apiProperties.url(
                "gallery/allitems?ts=${System.currentTimeMillis()}" +
                    "&groupId=${album.remoteKey}&tag=$tag&limit=200&simpleResult=false"
            )
        )
        responseTree.throwIfBizError()

        val data = responseTree.at("/data")
        val content = data.get("content") ?: throw IllegalStateException("allitems 响应缺少 data.content")
        val assets = content
            // 归档语义：删除记录不进入下载队列，已归档的本地文件保留
            .filter { it.get("status")?.asText().let { s -> s == null || s == "custom" } }
            .map { parseJsonNode(it, album) }

        return AllItemsPage(
            syncTag = data.requiredText("syncTag"),
            lastPage = data.path("lastPage").asBoolean(true),
            assets = assets,
        )
    }

    fun downloadAsset(accountId: Long, asset: Asset, targetPath: Path): Boolean {
        val url =
            if (asset.type == AssetType.AUDIO)
                apiProperties.url("sfs/ns/recorder/file/${asset.remoteKey.toLong()}/cb/dl_sfs_cb_${System.currentTimeMillis()}_0/storage?ts=${System.currentTimeMillis()}")
            else
                apiProperties.url("gallery/storage?ts=${System.currentTimeMillis()}&id=${asset.remoteKey.toLong()}")

        // 1. 获取 OSS URL
        val fetchOssUrlJson = getJson(accountId, url)

        val ossUrl = fetchOssUrlJson.at("/data/url").asText()
        if (!ossUrl.startsWith("http")) {
            // code=0 却缺少合法下载地址属于反常响应，记录原始响应体后抛异常等待重试
            log.warn("资产 {} 的 storage 响应缺少合法下载地址", asset.id)
            throw IllegalStateException("storage 响应缺少合法的 data.url")
        }

        // 2. 请求签名直链
        val fetchSignedUrlReq = Request.Builder().url(ossUrl).ua().get().build()
        val fetchSignedUrlJson = client().executeWithRetry(fetchSignedUrlReq).use { resp ->
            throwIfNotSuccess(resp.code)
            Solon.context().objectMapper.readJsonpTree(resp.body)
        }

        // 3. 下载文件
        val downloadUrl = fetchSignedUrlJson.get("url").asText()
        val downloadMeta = fetchSignedUrlJson.get("meta").asText()
        val formBody = FormBody.Builder()
            .add("meta", downloadMeta)
            .build()
        val downloadReq = Request.Builder().url(downloadUrl).ua().post(formBody).build()
        client().executeWithRetry(downloadReq).use { downloadResp ->
            throwIfNotSuccess(downloadResp.code)
            // 4. 保存文件
            downloadResp.body.saveToFile(targetPath)
        }

        return true
    }

    // 读完即关闭响应，避免后续 saveToFile 等长耗时操作期间占用连接（okhttp3 会报 warning）
    private fun getJson(accountId: Long, url: String): JsonNode =
        tokenManager.authStatus.observeXiaomi(accountId) {
        val req = Request.Builder()
            .url(url)
            .ua()
            .authHeader(tokenManager.getAuthPair(accountId))
            .get()
            .build()
        client().executeWithRetry(req).use { res ->
            val tree = runCatching { Solon.context().objectMapper.readTree(res.body) }.getOrElse {
                if (res.code == 401 || res.code == 403) throw com.coooolfan.xiaomialbumsyncer.exception.XiaomiAuthenticationException()
                throwIfNotSuccess(res.code)
                error("小米接口返回无效 JSON")
            }
            requireNotNull(tree) { "小米接口返回空 JSON" }.validateXiaomiResponse(res.code)
        }
    }

    private fun parseJsonNode(jsonNode: JsonNode, album: Album): Asset {
        return when (album.isAudioAlbum()) {
            false -> {
                val fullFileName = jsonNode.get("fileName").asText()

                Asset {
                    remoteKey = jsonNode.get("id").asLong().toString()
                    recordingType = null
                    motionPhotoHint = hasXiaomiMotionHint(jsonNode.get("description"))
                    fileName = fullFileName
                    type = AssetType.valueOf(jsonNode.get("type").asText().uppercase())
                    dateTaken = Instant.ofEpochMilli(jsonNode.get("dateTaken").asLong())
                    albumId = album.id
                    checksum = jsonNode.get("sha1").asText()
                    mimeType = jsonNode.get("mimeType").asText()
                    title = jsonNode.get("title")?.asText() ?: fullFileName.substringBeforeLast('.')
                    size = jsonNode.get("size")?.asLong() ?: 0L
                }
            }

            true -> {
                val name = jsonNode.get("name").asText()
                val recordingName = parseXiaomiRecordingName(name)
                Asset {
                    remoteKey = jsonNode.get("id").asLong().toString()
                    this.fileName = recordingName.fileName
                    this.type = AssetType.AUDIO
                    this.recordingType = recordingName.recordingType
                    this.dateTaken = Instant.ofEpochMilli(jsonNode.get("create_time").asLong())
                    this.albumId = album.id
                    this.checksum = jsonNode.get("sha1").asText()
                    this.mimeType = Files.probeContentType(Path(recordingName.fileName)) ?: "application/octet-stream"
                    this.title = recordingName.fileName.substringBeforeLast(".")
                    this.size = jsonNode.get("size").asLong()
                }
            }
        }
    }
}

data class AlbumSyncInfo(
    val albumId: Long,
    val incrementalTag: String,
    val totalImageCount: Long,
)

data class AllItemsPage(
    val syncTag: String,
    val lastPage: Boolean,
    val assets: List<Asset>,
)

internal fun shouldFetchNextAssetPage(
    audioAlbum: Boolean,
    assetCount: Int,
    pageSize: Int,
    isLastPage: Boolean,
): Boolean =
    if (audioAlbum) assetCount == pageSize
    else assetCount > 0 && !isLastPage

private val XIAOMI_RECORDING_NAME_REGEX = Regex("""^(.+)\.([^._]+)_(\d+)_(\d+)_(\d+)_(\d+)$""")

internal data class XiaomiRecordingName(
    val fileName: String,
    val recordingType: RecordingType,
)

internal fun parseXiaomiRecordingName(name: String): XiaomiRecordingName {
    val match = XIAOMI_RECORDING_NAME_REGEX.matchEntire(name)
    if (match == null) {
        // 名称不符合标准格式时，从右侧剥离四段尾缀，类型记为 UNKNOWN。
        return XiaomiRecordingName(
            fileName = name.substringBeforeLast("_").substringBeforeLast("_").substringBeforeLast("_")
                .substringBeforeLast("_"),
            recordingType = RecordingType.UNKNOWN,
        )
    }

    val (base, ext, _, typeCode) = match.destructured
    return XiaomiRecordingName(
        fileName = "$base.$ext",
        recordingType = RecordingType.fromCode(typeCode.toIntOrNull() ?: -1),
    )
}

private val XIAOMI_DESCRIPTION_MAPPER = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

/** Flags are hints, never proof that the downloaded original contains a playable video. */
internal fun hasXiaomiMotionHint(description: JsonNode?): Boolean {
    val flags = when {
        description == null -> return false
        description.isObject -> description.path("specialTypeFlagsNew").asText("")
        description.isTextual -> try {
            XIAOMI_DESCRIPTION_MAPPER.readTree(description.asText())
                .path("specialTypeFlagsNew").asText("")
        } catch (_: Exception) { return false }
        else -> return false
    }.split(':').filter { it.isNotEmpty() }.toSet()
    return "1006" in flags || ("1073" in flags && "1065" in flags)
}

internal fun parseAlbumTimeline(response: JsonNode): AlbumTimeline {
    response.throwIfBizError()
    val indexHash = response.at("/data/indexHash")
    val counts = response.at("/data/dayCount")
    require((indexHash.isTextual && indexHash.asText().isNotBlank() || indexHash.isIntegralNumber) && counts.isObject) { "小米相册时间线响应不完整" }
    val dayCounts = counts.properties().associate { entry ->
        val count = entry.value
        require(count.isIntegralNumber && count.canConvertToLong() && count.asLong() >= 0) { "小米相册时间线数量无效" }
        LocalDate.parse(entry.key, BASIC_ISO_DATE) to count.asLong()
    }
    return AlbumTimeline(indexHash.asText(), dayCounts)
}

internal fun validateAssetEnumerationPage(response: JsonNode, audio: Boolean, pageSize: Int) {
    response.throwIfBizError()
    val content = response.at(if (audio) "/data/list" else "/data/galleries")
    require(content.isArray && content.size() <= pageSize) { "小米资产分页响应不完整" }
    if (!audio) {
        val last = response.at("/data/isLastPage")
        require(last.isBoolean && (last.asBoolean() || !content.isEmpty)) { "小米资产分页结束状态无效" }
    }
}
