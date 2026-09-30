# Testing

```bash
# Everything CI runs
./gradlew assembleDebug
./gradlew testDebugUnitTest :core:model:test :core:common:test :core:domain:test :core:network:test
./gradlew :app:lintDebug
./gradlew assembleRelease
./gradlew assembleDebugAndroidTest

# One module
./gradlew :core:common:test --tests '*PhoneNumberNormalizerTest*'
```

The pure-Kotlin modules (`core:model`, `core:common`, `core:domain`, `core:network`) run as plain JVM
tests. Android modules use **Robolectric** (SDK 34) so Room, repositories and Compose UI tests run on the
JVM without an emulator.

## Suites

| Area | Where | Covers |
|---|---|---|
| Number normalization | `core/common/.../PhoneNumberNormalizerTest` | `+91…`, `91…`, `0…`, 10-digit, `+1…`, formatted input, Unicode digits, international, private/hidden markers, invalid, too short/long, short codes |
| Spam scoring | `core/common/.../SpamScoreEngineTest` | No evidence ⇒ unknown, contacts safe, single report stays low, recency, SCAM needs agreement, verified business cap, server weighting, own report/block, decay, bounds, risk bands |
| Caller identification | `core/domain/.../CallerIdentificationManagerTest` | Unknown numbers, contacts (no network), warnings vs blocks, blocked categories, block list, hidden numbers, block-unknown needs contacts access, backend failure → cache, **slow network** abandoned after budget, verified business, protection off, opt-in auto-block |
| Reporting | `core/domain/.../ReportNumberUseCaseTest` | Sanitising, **duplicate reports**, daily limit |
| SMS | `core/domain/.../SmsClassifierTest` | Phishing, OTP, promotions, personal, blocked sender |
| API | `core/network/.../RetrofitNetworkDataSourceTest` (MockWebServer) | Parsing with unknown fields, headers (auth, App Check), HTTP error mapping, **timeouts**, malformed bodies, report body, unconfigured backend, HTTPS enforcement |
| Room | `core/database/.../WhoCallerDatabaseTest` | **Empty database**, upsert de-dup, spam-list replacement, cleanup, block/unblock, search-history de-dup, duplicate `clientReportId`, pending sync, counters |
| Repositories | `core/data/.../RepositoryTest` | Cache-first lookups, **offline mode**, **backend failure**, negative caching, unconfigured backend, offline report + later sync with same id, server duplicate |
| Security | `core/security/.../TokenStoreTest` | Token expiry |
| ViewModels / Compose | `feature/*/src/test`, `app/src/test` | See each module |

## Manual test matrix (device)

| Scenario | Expected |
|---|---|
| Deny every permission during setup | App fully usable: search, block list, message check; each screen explains what's missing |
| Revoke contacts in system settings | Contacts screen asks again; "Block unknown callers" disables itself |
| Airplane mode, search a cached number | Result with "Showing saved information" |
| Airplane mode, search a new number | "No identity found" + offline notice, no crash |
| Call from `+1 202-555-0142` (debug dev backend) | Telemarketing warning notification |
| Hidden caller with "Block private numbers" on | Rejected, appears in Blocked › History |
| Android 9 device | Caller alert notification, no blocking, legacy notice shown |
| Dark mode, 200 % font, TalkBack | Readable, no clipped controls, all icons labelled |
| Arabic UI | Mirrored layout |
