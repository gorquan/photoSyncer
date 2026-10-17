# Media compatibility contract

## Scope / Trigger
Xiaomi and iCloud originals exported to Synology Photos. Evidence: docs/plan/synology-photos-validation.md.

## Signatures
Asset.checksum is provider-specific: Xiaomi ordinary SHA-1; CloudKit opaque fileChecksum. CrontabHistoryDetail.sha1Verified is a legacy stage-completion flag, not proof of SHA-1.

## Contracts
Preserve original Apple ContentIdentifier and Xiaomi embedded video/XMP. Apple primary image and companion MOV share a stable basename from account/zone/record identity; alternate originals have distinct roles. Download ordinary videos independently from Live Photo companions. Persist local SHA-256 and verification basis. Never claim NAS indexing/preview from local completion.

## Validation & Error Matrix
Xiaomi checksum mismatch or source-size mismatch => failure, retryable. CloudKit opaque signature => size/structure plus local SHA-256, never raw SHA-1 interpretation. Motion metadata with invalid ranges/container => explicit failure; valid dynamic originals bypass metadata mutation. Publish only validated complete components. Existing paths require version/integrity evidence.

## Good / Base / Bad Cases
Good: JPEG+MOV retain bytes and shared stem; GContainer/MicroVideo originals retain trailers. Base: ordinary JPEG/video preserve bytes unless requested metadata fill. Bad: unknown signature deletes valid Apple original, skipped Xiaomi check reported verified, truncated tail published, only one Live component reported complete.

## Tests Required
Opaque signatures, real SHA1 mismatch, independent SHA256, stable pairing across checksums/albums, video switch, alternate role collision, malformed XMP/ranges/MP4 boxes, failed publication and restart/version changes.

## Wrong vs Correct
Wrong: strip first CloudKit signature byte and compare SHA1. Correct: store signature as version evidence and report size/structure verification separately. Wrong: blindly rewrite JPEG EXIF with an appended video. Correct: detect motion metadata and preserve validated original bytes.

## Recoverable publication

Use `MediaPublicationService`/`MediaPublisher`, not direct stage writes into final paths. `PHOTO_SYNCER_MEDIA_ROOT` defaults to the database parent/media and must stay outside indexed destinations. Archive and indexed export roots must be disjoint in both directions after resolving existing path aliases. Originals are immutable retained files; transforms use copies. Optional source SHA-1 never disables mandatory size/local SHA256/container/motion checks. Receipts include processor version, source metadata, target paths, selected roles and config; reuse verifies original/final hashes and requested mtime. Missing/corrupt output repairs from the retained original. Old completed history never bypasses receipt evaluation.

Publish required Apple resources together, MOV before primary. Persist pending state before renaming and complete only after all output operations. This is repairable multi-file publication, not a multi-file atomic transaction. Remove obsolete output paths only from a trusted same-group/layout receipt and only if bytes still match its hash; preserve originals and unowned/modified old exports. Under the ownership lock, validate other active receipts before removing obsolete exports, and preserve paths referenced by other tasks. Corrupt receipts or symlink paths/ancestors prevent obsolete cleanup. Both publication cleanup and cloud deletion use the same physical-path check. Authoritative Apple role sets retire removed companion/alternate resources; partial imports cannot claim role completeness.

`publicationStatus=PUBLISHED` is local export evidence. Execution `endTime/isCompleted` includes handled failures and must not be styled/reported as all media successfully exported. Store original and output digests separately. Ordinary/Apple static single-file inspection emits no missing-dynamic warning; Xiaomi hinted static degradation remains explicit.

Refresh loaded media metadata across every membership of the same logical resource/checksum, including date, extension and motion hints; canonical selection must not keep stale metadata merely because another album owns the lowest row ID. Partial drafts leave omitted scalars intact. Manual metadata actions use only current assets whose history row is the latest published owner of its output path; reactivating an old checksum does not establish ownership before it is exported again.

## Cloud deletion

`syncCloudDeletes` defaults false for old serialized tasks; new task forms opt in by default. For every supported sync mode, reconcile complete selected live scans plus provider confirmation before ordinary refresh/cursor advancement. Album absence, canceled favorite/hidden membership, auth/network failures, partial pages and Xiaomi storage 50050 never establish deletion. Xiaomi matched strict `deleted_v2` entries and iCloud explicit deleted/missing record lookups provide evidence. Previously unobserved Xiaomi permanent purges remain uncertain and must preserve files; persisted confirmed tombstones can retry after purge. New live observations restore presence, independently of current source version.

Persist cloudDeleted before cleanup; omit deleted assets from scheduling/manual metadata and recheck under the common logical-group publication/deletion lock. Never hold the media persistence monitor through provider IO or while waiting on that lock. Delete only owned exports with safe contained non-symlink paths and matching hashes, plus corresponding historical originals with deterministic source metadata, respecting retained other-task references. Journal intent before unlink, retain retry evidence, and report conflicts/deferred references. Multiple Apple component unlinks are recoverable, not atomic. Restore then delete again must discover new receipt/source generations. EndTime is never proof of cleanup success. No real cloud mutation is used in tests; synthetic provider fixtures exercise destructive cases.

Publication checks persisted cloudDeleted/currentVersion after acquiring the group lock and again through `MediaPublisher.publish(beforeCommit=...)` after preparation, before receipt/final-file commit. The reuse path also invokes this check. Superseded versions cannot overwrite newer exports, and deletion observed during download cannot publish from a retained archive.
