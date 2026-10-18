# Database contracts

## Scope / Trigger
Jimmer entities, Kotlin DSL queries and SQLite Flyway migrations.

## Signatures
Entities live under `server/src/main/kotlin/com/coooolfan/xiaomialbumsyncer/model`; migrations under `server/src/main/resources/db/migration`. See `.agents/skills/jimmer-orm/SKILL.md` for syntax and association conventions.

## Contracts
Use forward versioned SQL migrations; never edit previously released migrations. Keep entity defaults and SQL defaults consistent. Existing history completion flags must not acquire stronger verification semantics during migration. Fetch association/scalar fields explicitly before accessing them; an unloaded property is different from null. Timeline baseline reuse requires both an ended history and fetchedAllAssets=true; a failed partial enumeration cannot advance its baseline. A fully fetched history with later publication failures is still a valid metadata baseline. Initial timeline snapshots are captured before enumeration and persisted only after enumeration succeeds; uploads during pagination must remain detectable on the next run. Serialized CrontabConfig additions need defaults for old JSON and corresponding TypeScript/form defaults.

Running-history `plannedAssetCount` is nullable for legacy or unplanned runs. After metadata enumeration and deletion reconciliation, count eligible resources using the same filters as publication paging and atomically store the snapshot with `fetchedAllAssets=true`, before inserting the first processing batch. Stats `assetCount` uses this fixed snapshot, never the growing detail-row count. `publishedCount` counts only final `PUBLISHED` rows; stage flags are separate evidence.

## Validation & Error Matrix
An old `sha1_verified=1` row migrates to `verification_status=LEGACY`, with null local digest/size evidence. Requested validation begins PENDING; disabled source validation is explicitly SKIPPED. Failure must leave a retryable state. Do not mark a missing source or half-published group successful merely to suppress retries.

## Good / Base / Bad Cases
Good: migration retains existing rows and records conservative evidence. Base: clean database applies the full migration chain. Bad: interpreting legacy booleans as source checks or reading an unloaded album account field from a minimal fetcher.

## Tests Required
Run real SQLite Flyway/Jimmer regressions, including upgrades with populated old rows and default config deserialization. Build with JDK25 `./gradlew clean compileKotlin test` in server.

## Wrong vs Correct
Wrong: change an old migration or require a new serialized field with no default. Correct: add a forward migration and backward-compatible Kotlin/TypeScript defaults. Wrong: assume a path exists because a history boolean is true. Correct: verify publication receipt/version and actual local output.
