# Task-selected mirror deletion

## Scope / Trigger
Cloud-to-local photo/video retention. Xiaomi FULL/TIMELINE and iCloud FULL/CURSOR support mirror; Xiaomi CURSOR supports backup only. Backup keeps provider scan behavior. No cloud writes. Mirror scope is the union of this task's selected albums, including leaving that scope while remaining elsewhere in the library.

## Signatures
`CloudDeletionService.replaceSnapshot(task, albums, timeline?, zone?, records?)` commits a complete selected inventory inside the caller's transaction. `applyPage(task, ICloudChangePage)` updates affected record memberships. `deletedGroups(task)` returns selected/history groups absent from the committed union. `AssetService.refreshMirrorInventory` performs strict full/timeline enumeration; iCloud mirror cursor uses `refreshICloudAssetsByCursor` and `commitICloudPage`.

## Contracts

`PUT /api/crontab/{id}` compares loaded `syncMode` and `config.syncCloudDeletes` against persisted values inside an IMMEDIATE transaction before saving. Actual changes return HTTP400 with no partial update; omitted or identical fields are accepted. `config.synologyPhotos` may change false→true but never true→false. Missing legacy booleans default false. Other config fields and album selection remain editable. No migration of existing modes is implicit.

Private `mirror_inventory` stores task ID, per-album group keys and an exactly matched optional Xiaomi timeline. `mirror_members` is an indexed projection maintained by SQLite triggers in the same transaction, including rollback and deletion. Neither is exposed in API fetchers. Absence never mutates account-wide `Asset.cloudDeleted`; backups ignore legacy global flags. Publication SQL and under-lock checks use task+album membership. Missing inventory fails closed. Audio retains its existing backup path.

FULL mirror strictly enumerates all selected albums once, then compares the complete union without recycle-bin or whole-library confirmation. Xiaomi TIMELINE first establishes a complete inventory. Same indexHash reuses the album inventory; changed hash enumerates all current nonempty dates and replaces the complete album inventory. Count decreases, equal-count replacements and whole-day removal must work. Capture timeline before enumeration; commit only after all pages succeed. Failures preserve last complete inventory, skip cleanup and publication, and do not advance the baseline.

iCloud CURSOR mirror uses `:mirror-v2` keys isolated by account, zone and selected albums. New scope, missing inventory or expired token establishes a strict baseline with the token captured before enumeration. Subsequent pages consume changes without full scans. Selected relation removal, hidden/favorite state changes and asset deletion update current selected membership. Current-record lookup handles stale deletion events/restoration. Page inventory, assets, report and cursor commit atomically; no network inside the transaction. Concurrent account observations reject stale pages. Backup cursor routes remain compatible and do not consume mirror scope.

Cleanup retries committed absence, including old histories after selection changes, without provider re-probes. Recheck presence under logical file locks and before publication commit. Keep receipts, hashes, path/symlink validation, ownership checks, deletion journals and partial reports. Other tasks' referenced files survive. Task deletion and clearing download history reset inventory/projection atomically; the next run rebuilds its baseline. No duplicate original storage is introduced.

## Validation & Error Matrix
| Input / condition | Required result |
| --- | --- |
| Xiaomi CURSOR mirror create/update | HTTP400; no changed settings |
| Update scan/retention or disable saved Photos | HTTP400; entire update rejected |
| Same modes, omitted mode fields, or enable Photos | Save other editable settings; scan/retention remain unchanged |
| Successful empty full or timeline inventory | Remove owned absent groups |
| Present in another selected album | Retain, publish current membership |
| Present only outside selected scope | Remove this task's managed files |
| Failed/malformed scan or incomplete lookup | Preserve inventory/files; no cursor acknowledgment |
| Failed page transaction | Roll back inventory/projection/assets/report/cursor |
| Missing/changed scope or expired cursor | Rebuild complete baseline |
| Same Xiaomi timeline index | No media enumeration |
| Changed equal/decreasing day counts | Observe replacements and remove old members |
| Hash mismatch, symlink or shared file ownership | Preserve affected file; report conflict/deferred cleanup |

## Good / Base / Bad Cases
Good: a photo removed from all selected albums is absent only for this mirror task; another task's backup remains available. Base: backup imports observed additions/updates and retains removed photos. Bad: querying recycle bins to override complete selected absence, mutating global flags for task absence, or using an interrupted enumeration as a new baseline.

## Tests Required

Mode regressions cover Xiaomi FULL/TIMELINE/CURSOR backup and supported mirror combinations, iCloud updates, defaulted legacy booleans, identical/omitted mode fields, one-way Photos, editable scope/performance and rejected updates preserving all fields.

Use real SQLite/Flyway, synthetic provider HTTP and temporary files for task/account/zone scope, migration, indexed projection, rollback, current memberships, full and timeline absence, empty dates, unchanged hashes, cursor deletion/restore and relation changes. Keep ownership/path/hash conflict and retry tests. Run JDK25 clean compileKotlin test apiE2eJvm and frontend yarn lint/format/build. Synthetic tests do not prove real Apple destructive event delivery; never mutate real cloud media without user authorization.

## Wrong vs Correct
Wrong: "album removal is never deletion" or "full iCloud mirror must read changes too". Correct: mirror follows selected album membership, full scans directly compare complete inventories, and iCloud cursor changes update that same task-scoped model.
