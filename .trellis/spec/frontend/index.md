# Frontend guidelines

## Pre-Development Checklist
Vue/TypeScript under web; use existing form composables and i18n catalogs. Keep generated API types aligned with backend. Describe verification basis accurately and explain HEIC preview dependency.

## Quality Check
Run `yarn lint`, `yarn format`, `yarn build` from web. Ensure defaults work for old JSON configurations; all language catalogs must contain new keys.

Overall running-task progress uses `publishedCount` over the fixed `assetCount` snapshot. Unknown totals show counts without percentages. Stage bars use the same total, but download completion does not imply task completion. Floor percentages to one decimal to avoid premature 100%; when all planned resources are published while the task is still running, show a finalizing label.

Running-task statistics age resets both local clock references to the same response-completion timestamp and clamps elapsed time to zero, including when the wall clock moves backward.

Deletion settings must explain permanent removal of managed exports and unreferenced originals/history versions. New task forms enable syncCloudDeletes; editing old missing values defaults false. History uses executionStatus for success/partial/failure/cancellation and shows deletionReport totals plus conflicts; endTime alone only means ended. Use backend-generated report types rather than parallel UI payload definitions. See [backend deletion contract](../backend/cloud-deletion.md).

The create/edit wizard exposes `syncCloudDeletes` as native backup=false / mirror=true radio choices and `synologyPhotos` as an independent switch on step “Storage & Sync”. Full/timeline/cursor are scan strategies and must not auto-advance past retention/Photos choices. Task headers show retention plus Photos on/off even when collapsed; old absent fields display backup/Photos off. Editing must not reset the other boolean.

Editing a Xiaomi account starts at the QR/manual method picker. QR renewal shows the existing Xiaomi user ID and asks users to authorize the same account; the existing user-ID upsert preserves its account and task links. Back from QR/manual returns to methods, without exposing provider selection during editing. Leaving QR or closing the dialog cancels frontend polling. Adding accounts still starts at provider selection; iCloud editing opens its account login form.
