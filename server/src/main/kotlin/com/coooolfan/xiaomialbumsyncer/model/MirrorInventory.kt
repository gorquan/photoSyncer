package com.coooolfan.xiaomialbumsyncer.model

import org.babyfish.jimmer.sql.Entity
import org.babyfish.jimmer.sql.Id
import org.babyfish.jimmer.sql.Serialized

/** Private, task-scoped cloud membership; never part of account-wide source versions or API projections. */
@Entity
interface MirrorInventory {
    /** The owning crontab ID. */
    @Id
    val id: Long

    /** Album database ID -> complete set of MediaIdentity.groupKey values. */
    @Serialized
    val albums: Map<Long, Set<String>>

    /** Xiaomi timeline hashes captured for exactly this inventory. */
    @Serialized
    val timeline: Map<Long, AlbumTimeline>?
}
