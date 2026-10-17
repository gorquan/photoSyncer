# Backend guidelines

## Pre-Development Checklist
Read root AGENTS.md and .agents/skills/jimmer-orm/SKILL.md for entity/DSL edits. Kotlin/Solon, Jimmer, SQLite Flyway; use existing pipeline/service boundaries. Never log credentials, signed URLs or private media.

## Quality Check
Run JDK 25 `cd server && ./gradlew clean compileKotlin test`. Add regressions for provider checksums, grouping, malformed motion structures, publication failure/retry, timeline baselines and unchanged/decreasing-count replacements. Timeline index changes recheck every current nonempty date because daily counts cannot prove membership equality; malformed timeline payloads fail instead of becoming empty snapshots. No private media fixtures. See [media compatibility](media-compatibility.md) and [database contracts](database-guidelines.md).

See [native motion photo contracts](motion-photo.md) for XMP range validation and EXIF preservation.

See [cloud deletion contracts](cloud-deletion.md) for provider evidence, deletion journals, shared ownership and cleanup status.

See [deployment contracts](deployment.md) for fork builds, persistent mounts, migration and validation cleanup.

See [account authentication](account-auth.md) for persisted status evidence, manual checks and credential-version guards.
