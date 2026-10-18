# Deployment contract

## Scope / Trigger
Build and deploy this fork to Docker/Container Manager; clean isolated validation data.

## Signatures
`(cd web && npm install && npm run build)`; copy web/dist into server/src/main/resources/static; JDK25 `./gradlew clean solonJar -PappVersion=<revision>`; Dockerfile.jvm accepts app.jar in build context. Compose uses locally built photosyncer:local.

For frontend-only releases repackaging a verified shaded JAR, replace all `static/` contents and version.properties, retain directory entries `static/` and `static/assets/`, and preserve every other entry in order and byte content. Duplicate names require reading each ZipInfo, not lookup by filename. Solon resource discovery requires the directory marker even when static/index.html exists.

## Contracts
Persist `/app/db` including `media` publication/deletion records, mount target media separately at `/photos`. New syncs keep no permanent originals; media/work contains only operation-scoped temporary files. Host state/export roots must be disjoint. `PHOTO_SYNCER_STATE_DIR`, `PHOTO_SYNCER_EXPORT_DIR`, `PHOTO_SYNCER_PORT` parameterize compose. No provider behavior or task defaults change during deployment. Retain account/system config; remove only explicitly identified validation tasks/media. NAS operations never edit Photos DB or @eaDir.

## Validation & Error Matrix
Missing frontend or static directory marker in JAR => stop deployment. Wrong architecture/JRE => rebuild. Invalid SQLite integrity or lost accounts => restore pre-start backup. API auth failure => verify migrated application password before changing bindings. Unknown media ownership => preserve.

## Good / Base / Bad Cases
Good: private consistent DB backup, configured ExifTool, separate original/export volumes and working authenticated UI. Base: no tasks after removing test schedules; user creates production tasks. Bad: deploy upstream image as this fork; point archive into Photos; delete home/Photos broadly.

## Tests Required
Check compose rendering, JAR static/index.html and directory markers, runtime Java25/ExifTool, WebUI+authenticated account/task API, SQLite integrity and preserved account count, mounted directory write and physical paths. Fetch root HTML and a newly hashed asset over HTTPS (HTTP 200, matching build); a cached browser page or working API cannot prove static resources are served. Record cleanup file counts and natural Photos index removal separately.

## Wrong vs Correct
Wrong: copy SQLite main file while application writes WAL. Correct: stop application or use SQLite backup API. Wrong: retain test target paths on NAS. Correct: remove identified test schedules in deployment copy while preserving account bindings.
