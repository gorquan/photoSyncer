package com.coooolfan.xiaomialbumsyncer.model

import com.coooolfan.xiaomialbumsyncer.utils.MediaDeletionResult

/** Cleanup evidence, independent of whether execution has ended. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class CloudDeletionReport(
    val status: String,
    val checkedGroups: Int = 0,
    val newlyDeletedGroups: Int = 0,
    val restoredGroups: Int = 0,
    val results: List<MediaDeletionResult> = emptyList(),
    val message: String? = null,
) {
    val exportsDeleted: Int get() = results.sumOf { it.exportsDeleted }
    val originalsDeleted: Int get() = results.sumOf { it.originalsDeleted }
    val deferredFiles: Int get() = results.sumOf { it.deferredFiles }
    val conflictCount: Int get() = results.sumOf { it.conflicts.size }
}
