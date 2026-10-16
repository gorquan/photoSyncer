# Cloud deletion contract

## 1. Scope / Trigger

Cloud-to-local photo/video deletion for Xiaomi and iCloud tasks. No cloud delete calls. Follow [media compatibility](media-compatibility.md) for identities, grouping and publication ownership.

## 2. Signatures

- `CrontabConfig.syncCloudDeletes: Boolean = false`; new-task UI defaults true, editing old missing values defaults false.
- `Asset.cloudDeleted: Boolean`, migration V0.25.0; independent of `currentVersion`.
- `CloudDeletionService.reconcile(crontab): CloudDeletionResult` returns checked/newlyDeleted/restored group counts and all confirmed `deletedGroups`.
- `CloudDeletionService.deletedGroups(crontab): List<CloudDeletionGroup>` includes selected albums and prior task-history scope, isolated to the task account.
- `MediaDeletionService.cleanup(crontab, groups): List<MediaDeletionResult>` returns groupKey, status, exportsDeleted, originalsDeleted, deferredFiles and conflicts.
- `CrontabHistory.executionStatus: String?` is RUNNING/SUCCESS/PARTIAL/FAILED/CANCELLED for new executions; null is legacy evidence. `deletionReport: CloudDeletionReport?` is stored by V0.26.0 and projected through API/MCP/grouped histories.
- `CloudDeletionReport(status, checkedGroups, newlyDeletedGroups, restoredGroups, results, message)` retains every per-group result. Its exportsDeleted/originalsDeleted/deferredFiles/conflictCount are derived totals.

## 3. Contracts

Every enabled FULL/TIMELINE/CURSOR run reconciles deletion before ordinary refresh/cursor advancement. Reconciliation performs strict complete selected scans and library probes; no file cleanup follows a failed phase. Presence changes commit only after all provider IO succeeds and the account observation revision still matches. Never hold the media monitor through provider IO or while waiting for logical-group locks.

Cleanup and publication use the same bounded `MediaFileLocks.group(groupKey)` lock and recheck persisted presence after acquiring it. No provider IO under the ownership lock. Originals and journals use the shared `PHOTO_SYNCER_MEDIA_ROOT` resolution. Journal path is `deletions/SHA256(taskId/groupKey).json`; it takes over receipt evidence before active receipts are removed. Retained active receipts protect paths/originals; pending deletion journals do not create circular ownership. Every retry merges current receipts and original inventory, including after restore and another deletion.

Remove only safe contained non-symlink receipt-owned exports with matching hashes, and deterministic source-version originals with validated identity, size and hash. Roots and ancestors containing symlinks are conflicts; physical real paths are required. Preserve audit metadata after bytes are removed. Per-call counts report actual media unlinks; missing files count zero. Apple multiple-component deletion is recoverable, not atomic.

Persist cleanup results after each group. CHECKING/RUNNING deletion reports become COMPLETE or INCOMPLETE; failures and cancellation preserve accumulated results. A completed deletion report remains complete when a later publication phase fails; execution outcome is separate. Detection failure messages identify that no local cleanup occurred without exposing raw provider reasons. Folded history must not hide deletion evidence or failed/partial/cancelled executions. All public history projections and generated types must carry the same fields.

## 4. Validation & Error Matrix

| Evidence / event | Required result |
| --- | --- |
| Xiaomi strict deleted_v2 match (id, deleted status, positive deleteTime, complete advancing pagination) | Confirm deletion |
| iCloud complete requested lookup with explicit deletion flags or per-record NOT_FOUND/UNKNOWN_ITEM | Confirm deletion |
| Valid direct live record/URL after album move or favorite/hidden change | Preserve; restore presence if necessary |
| Xiaomi 50050, unknown error, auth/network failure or malformed/incomplete scan | Abort detection; preserve files |
| Previously confirmed tombstone absent from later scan after purge | Continue cleanup retry |
| New live observation | Restore presence; allow fresh publication |
| Modified file, corrupt evidence, unsafe path/symlink | CONFLICT; preserve affected file |
| Retained other-task receipt | DEFERRED; preserve referenced media |
| IO/journal/recheck failure | FAILED with actual partial unlink counts; retry later |
| Persisted presence restored before cleanup | RESTORED; preserve files |
| All owned cleanup complete / already absent | DELETED; no count for absent files |

## 5. Good / Base / Bad Cases

Good: confirmed Apple deletion retires every role/membership/version, deletes owned exports and unreferenced historical originals, then repeat reports zero actual unlinks. Base: disabled old task performs no deletion check or cleanup. Bad: canceled favorite, failed storage URL, count decrease or incomplete page is treated as deletion; a finished execution is labeled all-success despite a conflict.

## 6. Tests Required

Protocol tests assert strict pages and exact deletion/live/error classification. SQLite tests assert account/member/source-version scope, independent currentVersion, revision rejection, persisted retry and restoration. Temp-file tests assert paired cleanup, failed-prepare originals, old layouts, shared refs without circular wait, actual partial counts, journal retry, missing/modified/unowned/symlink/escaped files and restore-delete-new-version inventory. Lock tests must prove stale publication reads fresh DB tombstones after waiting. JVM API E2E uses synthetic cloud mutations and verifies exported bytes, original absence and public history. Private cloud testing stays read-only.

## 7. Wrong vs Correct

Wrong: `if (storageFailed || !selectedAlbumContains(id)) deleteFiles(id)`.

Correct: complete reconciliation with provider library confirmation → persist cloudDeleted → recheck under the publication/deletion group lock → journal ownership → validate and unlink → persist explicit complete/partial/failure evidence. A Xiaomi object purged before any deletion observation remains uncertain unless stronger library evidence becomes available.
