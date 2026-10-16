package com.coooolfan.xiaomialbumsyncer.utils

import com.coooolfan.xiaomialbumsyncer.icloud.ICloudAlbumKey
import com.coooolfan.xiaomialbumsyncer.icloud.ICloudResourceKey
import com.coooolfan.xiaomialbumsyncer.model.*
import java.security.MessageDigest
import java.util.HexFormat

/** Identity follows the remote record, never its checksum, album membership, or database row. */
data class MediaIdentity(val accountId: Long, val provider: CloudProvider, val zone: String, val record: String, val role: String) {
    val groupKey: String get() = mapper.writeValueAsString(listOf(accountId, provider.name, zone, record))
    val resourceKey: String get() = "$groupKey/$role"
    val liveCompanion: Boolean get() = role == "resOriginalVidCompl"
    val directory: String get() = "${provider.name.lowercase()}-$accountId/${digest(zone)}"
    val stem: String get() = "asset_${digest(groupKey)}" + if (role == "resOriginalAlt") "_alternate" else ""
    fun fileName(asset: Asset): String {
        val extension = asset.fileName.substringAfterLast('.', "bin").lowercase()
            .replace(Regex("[^a-z0-9]"), "").ifBlank { "bin" }
        return "$stem.$extension"
    }
    companion object {
        private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        fun of(asset: Asset): MediaIdentity {
            val album = asset.album
            val provider = album.account.provider
            val apple = if (provider == CloudProvider.ICLOUD) ICloudResourceKey.decode(asset.remoteKey) else null
            return MediaIdentity(album.accountId, provider,
                if (apple != null) ICloudAlbumKey.decode(album.remoteKey).zone else "library",
                apple?.assetId ?: asset.remoteKey, apple?.resource ?: "original")
        }
        private fun digest(value: String): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()), 0, 16)
    }
}
