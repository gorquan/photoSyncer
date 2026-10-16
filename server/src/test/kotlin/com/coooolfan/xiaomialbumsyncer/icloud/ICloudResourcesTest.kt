package com.coooolfan.xiaomialbumsyncer.icloud

import com.coooolfan.xiaomialbumsyncer.model.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ICloudResourcesTest {
    private val mapper = jacksonObjectMapper()
    private fun album(id: Long) = Album {
        this.id = id
        accountId = 10
        remoteKey = ICloudAlbumKey("PrimarySync", "album-uuid").encode()
    }
    private val asset = mapper.readTree("""{"recordName":"asset-uuid","fields":{"masterRef":{"value":{"recordName":"master-uuid"}},"assetDate":{"value":1000}}}""")
    private val master = mapper.readTree("""{"recordName":"master-uuid","fields":{
        "filenameEnc":{"value":"SU1HXzAwMDEuSEVJQw=="},
        "resOriginalFileType":{"value":"public.heic"},"resOriginalRes":{"value":{"fileChecksum":"checksum-photo","size":123}},
        "resOriginalVidComplFileType":{"value":"com.apple.quicktime-movie"},"resOriginalVidComplRes":{"value":{"fileChecksum":"checksum-video","size":456}},
        "resOriginalAltFileType":{"value":"public.jpeg"},"resOriginalAltRes":{"value":{"fileChecksum":"checksum-jpeg","size":789}}
    }}""")
    @Test fun freshDownloadMetadataMustMatchEnumeratedOpaqueVersionEvenAtEqualSize() {
        val original = Asset { checksum = "opaque-A"; size = 123 }
        assertDoesNotThrow { requireResolvedResourceVersion(mapper.readTree("""{"fileChecksum":"opaque-A","size":123}"""), original) }
        assertThrows(IllegalArgumentException::class.java) { requireResolvedResourceVersion(mapper.readTree("""{"fileChecksum":"opaque-B","size":123}"""), original) }
        assertThrows(IllegalArgumentException::class.java) { requireResolvedResourceVersion(mapper.readTree("""{"fileChecksum":"opaque-A","size":124}"""), original) }
    }

    @Test fun keepsAllOriginalResourcesAndAlbumMembershipsSeparate() {
        val files = parseResources(album(1), asset, master)
        assertEquals(setOf("checksum-photo", "checksum-video", "checksum-jpeg"), files.map { it.checksum }.toSet())
        assertEquals(3, files.size)
        assertEquals(3, files.map { it.remoteKey }.toSet().size)
        assertEquals(3, files.map { it.fileName }.toSet().size)
        assertEquals(1, files.count { it.type == AssetType.VIDEO })
        assertTrue(files.first { it.type == AssetType.VIDEO }.fileName.endsWith(".mov"))
        assertEquals(files.first().fileName.substringBeforeLast('.'), files.first { it.type == AssetType.VIDEO }.fileName.substringBeforeLast('.'))
        assertTrue(files.first { it.remoteKey.contains("resOriginalAlt") }.fileName.contains("_alternate"))
        val changedMaster = master.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (changedMaster.at("/fields/resOriginalRes/value") as com.fasterxml.jackson.databind.node.ObjectNode).put("fileChecksum", "changed")
        assertEquals(files.first().fileName, parseResources(album(1), asset, changedMaster).first().fileName)
        val otherAccount = Album(album(1)) { accountId = 11 }
        assertNotEquals(files.first().fileName, parseResources(otherAccount, asset, master).first().fileName)
        val otherZone = Album(album(1)) { remoteKey = ICloudAlbumKey("SharedSync", "album-uuid").encode() }
        assertNotEquals(files.first().fileName, parseResources(otherZone, asset, master).first().fileName)
        val other = parseResources(album(2), asset, master)
        assertEquals(files.map { it.remoteKey }, other.map { it.remoteKey })
        assertNotEquals(files.first().album.id, other.first().album.id)
        assertEquals(files.map { it.fileName }, other.map { it.fileName })
    }
}
