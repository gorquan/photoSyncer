# Frontend guidelines

## Pre-Development Checklist
Vue/TypeScript under web; use existing form composables and i18n catalogs. Keep generated API types aligned with backend. Describe verification basis accurately and explain HEIC preview dependency.

## Quality Check
Run `yarn lint`, `yarn format`, `yarn build` from web. Ensure defaults work for old JSON configurations; all language catalogs must contain new keys.

Overall running-task progress uses `publishedCount` over the fixed `assetCount` snapshot. Unknown totals show counts without percentages. Stage bars use the same total, but download completion does not imply task completion. Floor percentages to one decimal to avoid premature 100%; when all planned resources are published while the task is still running, show a finalizing label.

Running-task statistics age resets both local clock references to the same response-completion timestamp and clamps elapsed time to zero, including when the wall clock moves backward.

Deletion settings must explain permanent removal when a photo leaves every selected album, even if it remains elsewhere in the cloud. Mirror scope is the union of selected albums; backups keep removed files. Also explain managed target files and legacy unreferenced originals/history versions. New task forms default mirror, except unsupported Xiaomi CURSOR; editing old missing values defaults false. History uses executionStatus for success/partial/failure/cancellation and shows deletionReport totals plus conflicts; endTime alone only means ended. Use backend-generated report types rather than parallel UI payload definitions. See [backend deletion contract](../backend/cloud-deletion.md).

The create wizard exposes backup=false for both providers. Mirror=true is supported for Xiaomi FULL/TIMELINE and iCloud FULL/CURSOR. Xiaomi CURSOR shows backup only and resets mirror=false when selected, with an explanation to use FULL/TIMELINE for mirror. Wait for a confirmed Xiaomi provider before clearing this choice; unloaded account options must not reset an iCloud task. It exposes `syncCloudDeletes` as native radio choices and `synologyPhotos` as an independent switch on step “Storage & Sync”. Full/timeline/cursor are scan strategies and must not auto-advance past retention/Photos choices. Task headers show retention plus Photos on/off even when collapsed; old absent fields display backup/Photos off. Editing must not reset the other boolean.

Editing a Xiaomi account starts at the QR/manual method picker. QR renewal shows the existing Xiaomi user ID and asks users to authorize the same account; the existing user-ID upsert preserves its account and task links. Back from QR/manual returns to methods, without exposing provider selection during editing. Leaving QR or closing the dialog cancels frontend polling. Adding accounts still starts at provider selection; iCloud editing opens its account login form.

The EXIF fill switch is disabled while Synology compatibility is off; retain its saved preference when toggling compatibility. Explain that Photos-off preserves cloud-original bytes and both modes retain only target media.

Existing plans disable retention radios and scan strategy cards. Create-only provider normalization must not mutate edited plans. Explain creation-time immutability in both locales. Photos is reversible. Confirm editing a saved task in either direction, explaining next-sync migration: enable uses verified local originals; disable restores cloud-original bytes before retiring Photos exports. Modified EXIF bytes require cloud download, native Live Photo MOV remains, and unavailable originals preserve existing files. Unsaved choices may still be reverted without confirmation. Capture saved Photos state on dialog opening, including lazy initial mount. Reset ToggleSwitch internal state while confirmation is pending so dismissal leaves it visibly off. Scheduling, scope and performance remain editable; API rejects forbidden mode changes with HTTP400.
