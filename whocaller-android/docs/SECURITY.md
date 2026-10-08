# Security

Report vulnerabilities to **rskusum@rsappsstudio.com**. Please don't open public issues for security bugs.

## Secrets
- No API secrets, private keys or service-account credentials are in the APK or the repository.
- Values in `BuildConfig` are *public identifiers* (API URL, OAuth web client ID, AdMob IDs).
- `google-services.json`, `keystore.properties`, `local.properties`, `.env`, `*.jks` are git-ignored;
  CI fails if any are committed or if a Google API key / private key pattern appears in sources.
- Server secrets (Play Developer API service account) live in Google Secret Manager.

## Transport
- HTTPS only: `network_security_config.xml` disables cleartext and trusts only system CAs.
- `NetworkConfig` refuses non-HTTPS base URLs. HTTP logging (debug only) logs no bodies and redacts auth headers.

## Client integrity and authentication
- Firebase **App Check** (Play Integrity in release, debug provider in debug) on every request.
- Firebase ID tokens (1 h lifetime, auto-refreshed) as Bearer tokens; the server verifies them with revocation checks.
- `TokenStore` treats tokens as expired 60 s early and clears them.

## Local data
- AES-256-GCM key in the Android Keystore (`KeystoreCipher`), non-exportable, random IV per value.
- Encrypted values and the database are excluded from cloud backup.
- Room queries are parameterised; content-provider selections use bound arguments.

## Input validation
- Phone numbers are parsed with libphonenumber; queries are filtered to phone characters and length-capped.
- Report comments: control characters stripped, 500-char cap (client and server).
- Deep links (`whocaller://`) and shared text are validated and length-capped before navigation.
- Only `http(s)`/`mailto` URLs are opened from business profiles.

## Abuse prevention (server)
- Per-user/IP fixed-window rate limits (lookups, reports, searches, verification, billing).
- One report per user per number, 24 h cool-down, idempotent `clientReportId`.
- Re-reports replace the previous vote. Blocks count once per user.
- SCAM/FRAUD labels require multiple agreeing reports.
- Firestore rules deny all direct client access.

## Android platform
- No accessibility service, no overlay, no hidden APIs; blocking uses `CallScreeningService` only.
- Exported components: launcher activity (validated input), screening service (system-bound permission),
  Android 8–9 receiver (sender must hold `READ_PHONE_STATE`, disabled on 10+).
- Release builds: R8 minification, resource shrinking, `Log.v/d/i` stripped.
