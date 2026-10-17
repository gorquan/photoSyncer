# Cloud account authentication status

## Scope
Provider requests, credential replacement and account settings.

## Contract
`ProviderAccount.authState` uses UNKNOWN, READY, MFA_REQUIRED, SESSION_EXPIRED or CHECK_FAILED. `authCheckedAt` is a nullable Instant serialized as an ISO timestamp. Old accounts start UNKNOWN; storing credentials alone is not authentication evidence. Public account projections expose these fields and never credentials.

`POST /api/account/{id}/check-auth` requires application login and performs a small provider read without persisting albums or downloading media. Xiaomi reads one gallery page. iCloud revalidates its saved session and photo service access without forcing password sign-in for a valid session.

Status records must match the original account provider, user and credential JSON. Older observations cannot replace newer results or credentials. Credential replacement resets Xiaomi status; rejected replacement logins must preserve the stored account. Provider authentication errors distinguish verification from expired credentials. Transport/service errors are CHECK_FAILED and must not imply expiry. Status is the last observation, not a guarantee that the session remains valid.

## Verification
Use real SQLite upgrade/conditional-update tests, typed provider-failure tests, saved iCloud session tests and authenticated API checks. Keep generated frontend projections aligned. Both providers must display status, latest check time and an explicit manual check action, with bilingual hints explaining recovery.
