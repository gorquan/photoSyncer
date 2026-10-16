package com.coooolfan.xiaomialbumsyncer.service

import com.coooolfan.xiaomialbumsyncer.icloud.ICloudPhotos
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudAlbumKey
import com.coooolfan.xiaomialbumsyncer.model.*
import com.coooolfan.xiaomialbumsyncer.xiaomicloud.XiaoMiApi
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import com.coooolfan.xiaomialbumsyncer.utils.MediaIdentity
import org.noear.solon.annotation.Managed
import java.nio.file.Path

/** 按账号来源分派相册查询和下载；iCloud 资源按相册成员保留独立下载历史。 */
@Managed
class CloudMediaService(private val sql: KSqlClient, private val xiaomi: XiaoMiApi, private val icloud: ICloudPhotos) {
    fun isICloud(accountId: Long): Boolean = sql.findById(ProviderAccount::class, accountId)?.provider == CloudProvider.ICLOUD
    fun fetchAlbums(accountId: Long): List<Album> = if (isICloud(accountId)) icloud.fetchAlbums(accountId) else xiaomi.fetchAllAlbums(accountId)
    fun fetchAssets(album: Album, handler: (List<Asset>) -> Unit): Long =
        if (isICloud(album.accountId)) icloud.fetchAssets(album, handler) else xiaomi.fetchAssetsByAlbumId(album, handler = handler)

    @Synchronized fun saveAssets(assets: List<Asset>, authoritativeAppleRoles: Boolean = false): List<Asset> {
        if (assets.isEmpty()) return emptyList()
        return sql.transaction {
            val distinct = assets.distinctBy { Triple(it.album.id, it.remoteKey, it.checksum) }
            // Update all memberships in the same account/zone, including checksum reversions.
            val albums = sql.executeQuery(Album::class) {
                where(table.id valueIn distinct.map { it.album.id })
                select(table.fetchBy { allScalarFields(); account { provider() } })
            }.associateBy { it.id }
            val incoming = distinct.map { item ->
                val album = albums.getValue(item.album.id)
                val identity = MediaIdentity.of(Asset(item) { this.album = album })
                Asset(item) { currentVersion = true; logicalResourceKey = identity.resourceKey }
            }
            if (authoritativeAppleRoles) {
                incoming.filter { albums.getValue(it.album.id).account.provider == CloudProvider.ICLOUD }
                    .groupBy { it.logicalResourceKey.substringBeforeLast('/') }.forEach { (group, resources) ->
                        val allRoles = listOf("resOriginal", "resOriginalAlt", "resOriginalVidCompl").map { "$group/$it" }
                        val missing = allRoles - resources.map { it.logicalResourceKey }.toSet()
                        if (missing.isNotEmpty()) sql.executeUpdate(Asset::class) {
                            set(table.currentVersion, false)
                            where(table.logicalResourceKey valueIn missing)
                        }
                    }
            }
            for (asset in incoming) {
                sql.executeUpdate(Asset::class) {
                    set(table.currentVersion, true)
                    // Canonical export membership may be an older row in another album.
                    // Refresh shared media metadata without changing its album/id or inventing
                    // fields omitted by partial imports.
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "fileName")) set(table.fileName, asset.fileName)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "type")) set(table.type, asset.type)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "recordingType")) set(table.recordingType, asset.recordingType)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "dateTaken")) set(table.dateTaken, asset.dateTaken)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "mimeType")) set(table.mimeType, asset.mimeType)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "title")) set(table.title, asset.title)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "size")) set(table.size, asset.size)
                    if (org.babyfish.jimmer.ImmutableObjects.isLoaded(asset, "motionPhotoHint")) set(table.motionPhotoHint, asset.motionPhotoHint)
                    where(table.logicalResourceKey eq asset.logicalResourceKey)
                    where(table.checksum eq asset.checksum)
                }
                sql.executeUpdate(Asset::class) {
                    set(table.currentVersion, false)
                    where(table.logicalResourceKey eq asset.logicalResourceKey)
                    where(table.checksum ne asset.checksum)
                }
            }
            // In a malformed batch with several versions, the final observation is current.
            val lastChecksums = incoming.associate { it.logicalResourceKey to it.checksum }
            sql.saveEntitiesCommand(incoming.map { item -> Asset(item) {
                currentVersion = lastChecksums[item.logicalResourceKey] == item.checksum
            } }, SaveMode.UPSERT).execute().items.map { it.modifiedEntity }
        }
    }

    fun download(accountId: Long, asset: Asset, path: Path): Boolean {
        val album = requireNotNull(sql.findById(Album::class, asset.album.id)) { "资产所属相册不存在" }
        require(album.accountId == accountId) { "资产所属账号与下载账号不一致" }
        return if (isICloud(album.accountId)) {
            val zone = ICloudAlbumKey.decode(album.remoteKey).zone
            icloud.download(album.accountId, zone, asset, path)
        } else xiaomi.downloadAsset(album.accountId, asset, path)
    }
}
