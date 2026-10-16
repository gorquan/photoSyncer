# Frontend guidelines

## Pre-Development Checklist
Vue/TypeScript under web; use existing form composables and i18n catalogs. Keep generated API types aligned with backend. Describe verification basis accurately and explain HEIC preview dependency.

## Quality Check
Run `yarn lint`, `yarn format`, `yarn build` from web. Ensure defaults work for old JSON configurations; all language catalogs must contain new keys.

Deletion settings must explain permanent removal of managed exports and unreferenced originals/history versions. New task forms enable syncCloudDeletes; editing old missing values defaults false. History uses executionStatus for success/partial/failure/cancellation and shows deletionReport totals plus conflicts; endTime alone only means ended. Use backend-generated report types rather than parallel UI payload definitions. See [backend deletion contract](../backend/cloud-deletion.md).
