# Mirror and provider retention contract

## 1. Scope / Trigger

Cloud-to-local photo/video deletion supports Xiaomi FULL/TIMELINE and iCloud FULL/CURSOR. Xiaomi CURSOR is backup-only. Xiaomi mirror uses strict full enumeration plus recycle-bin/storage-presence confirmation; timeline fingerprints alone do not prove deletion. No cloud write/delete calls. Use existing media identities, receipts and ownership locks.

## 2. Signatures

- `CrontabConfig.syncCloudDeletes=false` for missing old fields; UI new tasks default mirror where supported. Creating/updating Xiaomi CURSOR mirror raises BadRequestException (HTTP400).
- Existing Xiaomi FULL/TIMELINE settings and history stay intact; no legacy cursor migration is needed.
- `ICloudChangePage(zone,cursor,assets,presence)` describes one provider page. `fetchIncrementalPages` supplies baseline, beforePage and consume callbacks and returns whether a baseline was established.
- `CloudDeletionService.enabled`, `reconcileBaseline`, `applyStates`, `confirmPending`, `deletedGroups` are provider-scoped. `AssetService.commitICloudPage` commits presence, assets, deletion report and syncCursors atomically.
- Mirror cursor keys append `:mirror-v1` to the account/zone/selected-album key. Backup cursor keys remain compatible. Histories expose executionStatus and deletionReport as before.

## 3. Contracts

iCloud first mirror run, changed selection and expired token capture a token BEFORE strict full enumeration, save observed live assets, probe known missing library records and replay changes since the captured token. Normal mirror CURSOR runs do not enumerate full albums. Explicit FULL mode still enumerates metadata; a newly established full baseline is reused rather than immediately scanning it again.

For each changed CPLAsset and untyped tombstone, batch lookup the current state outside DB transactions. Typed relation deletion never proves library deletion; restored untyped relation records are ignored. Validate response zone, token, complete records and exact unique lookup names. Only explicit deletion flags or per-record NOT_FOUND/UNKNOWN_ITEM confirm deletion. Current live assets clear tombstones even outside selected collections. Apply presence to all known roles, memberships and versions in the same account/zone, limited to selected or historical task scope. Preserve currentVersion independently.

Before page network IO capture the media observation revision; reject commit if another account observation occurred. Under the media monitor and a SQLite transaction, apply presence, assets, report counters and cursor; update the in-memory cursor only after commit. Provider IO and file/group locking must remain outside transactions/monitor. A failed page is retryable; previously committed pages remain valid. Read persisted reports after rollback before recording failure.

After metadata refresh succeeds, recheck recorded tombstones (including historical zones), then use existing cleanup with persisted presence rechecks under logical file locks. Cleanup failures/conflicts remain retryable independently of cursor advancement. Preserve path/hash/shared-owner protection, per-group deletion journals and partial results. New receipts have no archive; legacy archive cleanup remains readable for supported mirror modes. Runtime and cleanup both gate Xiaomi CURSOR mirror. Xiaomi FULL/TIMELINE mirror keeps strict enumeration and explicit recycle-bin evidence; a purged unobservable object never becomes a newly confirmed deletion. Previously persisted intent survives only explicit uncertain-storage responses, not auth/network failures.

Strict Apple full enumeration preserves opaque continuation markers, query/rank/filters/zone throughout each chain; reject malformed, repeated, empty nonterminal, duplicate or deleted live pages. Unknown failures never become empty successful lists.

## 4. Validation & Error Matrix

| Event | Result |
| --- | --- |
| Xiaomi CURSOR mirror input | HTTP400; settings unchanged |
| Xiaomi FULL/TIMELINE mirror input | Accepted; complete deletion check retained |
| Valid iCloud token, empty page | Commit checkpoint; no full enumeration |
| Deleted CPLAsset / untyped tombstone with current deletion proof | Save tombstone and cursor together |
| Album move, hide, favorite change, restored photo | Preserve files; clear old deletion state |
| Invalid/expired token | Strict baseline and replay |
| Lookup/auth/page/transaction failure | Abort run; no cleanup; failed page cursor not advanced |
| Cleanup failure after checkpoint | Retain journal/tombstone; retry later |
| Modified, unsafe or shared target | Preserve affected file, report conflict/deferred |

## 5. Good / Base / Bad Cases

Good: incremental deletion commits every component/version with the checkpoint, then safely unlinks outputs; restoration later clears tombstones. Base: backup ignores deletion and retains local files. Bad: treating relation removal or a failed lookup as deletion; reusing a backup cursor without a mirror baseline; committing cursor before presence.

## 6. Tests Required

Protocol HTTP tests cover steady-state no-scan, deletion/current restore, hidden/out-of-selection live state, relation tombstones, incomplete lookups, consumer failure, baseline token ordering and expiration. Real SQLite tests cover account/zone/history scope, legacy identities, rollback AFTER asset writes on checkpoint failure, concurrent observation guards and restored pending groups. Temp-file integration proves checkpoint then delete then restore without an original pool. API E2E covers Xiaomi FULL/TIMELINE mirror deletion, restoration, conflict, retry and enumeration failure; rejects CURSOR mirror create/update; preserves backup/timeline/cursor behavior. Run JDK25 clean compileKotlin test + apiE2eJvm and frontend yarn lint/format/build. Cloud testing remains read-only unless user explicitly authorizes media mutations; synthetic results do not prove real Apple deletion delivery.

## 7. Wrong vs Correct

Wrong: delete files because an album lost a member, or advance syncToken before persisting deleted state.
Correct: current library deletion proof → transactional presence/assets/report/checkpoint → successful refresh → current pending-state confirmation → ownership/hash/lock checks → journaled cleanup.
