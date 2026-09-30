# WhoCaller

**Know who's calling. Stay protected.**

WhoCaller helps you identify unknown callers, detect suspicious calls, search phone numbers and protect yourself from unwanted communication.

- Package: `com.rskusum.whocaller`
- Developer: **RS APPS STUDIO**
- Platform: Android 8.0+ (minSdk 26), target/compile SDK 35
- Stack: Kotlin · Jetpack Compose · Material 3 · Clean Architecture + MVVM · Hilt · Room · DataStore · WorkManager · Retrofit + kotlinx.serialization · Paging 3 · libphonenumber · Firebase (optional) · Play Billing 7 · AdMob

> WhoCaller's branding, UI, icons and code are original. It is not affiliated with any other caller-ID service.

## Documentation

| Document | What it covers |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, layers, data flow, caller identification pipeline |
| [docs/API.md](docs/API.md) | REST API v1 contract, auth, rate limits |
| [docs/DATABASE.md](docs/DATABASE.md) | Room schema, normalization, migrations |
| [docs/PRIVACY.md](docs/PRIVACY.md) | What data is used, stored, uploaded — and what never is |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, secrets, App Check, abuse prevention |
| [docs/TESTING.md](docs/TESTING.md) | Test suites and how to run them |
| [docs/RELEASE.md](docs/RELEASE.md) | Signing, Play Console, billing, ads, release checklist |
| [backend/](backend/) | Reference backend (Firebase Cloud Functions + Firestore) |

## Quick start

Requirements: JDK 17, Android SDK (API 35), Android Studio Ladybug or newer.

```bash
cd whocaller-android
cp local.properties.example local.properties   # then set sdk.dir and optional keys
./gradlew assembleDebug                        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest :core:common:test :core:domain:test :core:network:test
./gradlew :app:lintDebug
```

The debug build runs **without any backend or Firebase project**:

- Caller ID, blocking, call history, contacts, spam scoring and SMS checks work on-device.
- Online lookups use a clearly labelled **development backend** that only knows a few
  fictional numbers from ranges reserved for fiction (e.g. `+1 202-555-0142`, `+44 20 7946 0321`).
  Every other number returns "No identity found". Release builds never include it.

## Configuration (all optional, never committed)

Set in `local.properties` or as environment variables (CI):

| Key | Purpose |
|---|---|
| `WHOCALLER_API_BASE_URL` | HTTPS base URL of the backend (e.g. your Hosting domain). Blank = dev backend (debug) / offline-only (release). |
| `WHOCALLER_GOOGLE_WEB_CLIENT_ID` | OAuth web client ID for "Continue with Google". |
| `WHOCALLER_ADMOB_APP_ID`, `WHOCALLER_ADMOB_BANNER_ID` | Real AdMob IDs. Defaults are Google's public **test** IDs. |
| `WHOCALLER_PRIVACY_URL`, `WHOCALLER_TERMS_URL` | Hosted policy pages. |

Firebase: put `app/google-services.json` in place (git-ignored). The Google Services and
Crashlytics Gradle plugins are applied automatically when that file exists. Register both
`com.rskusum.whocaller` and `com.rskusum.whocaller.debug` in the Firebase project.

### Configure Firebase
1. Create a Firebase project; add Android apps for both package names above; download `google-services.json` to `app/`.
2. Enable **Authentication** providers: Google, Email/Password, Phone.
3. Enable **App Check** with Play Integrity (release) and the debug provider (debug; register the debug token printed in Logcat).
4. Enable **Firestore**, **Crashlytics**, **Analytics**, **Remote Config**.
5. Deploy the backend: see [backend/README.md](backend/README.md).

### Configure CallScreeningService
Nothing to configure in code: `WhoCallerScreeningService` is declared in `feature/callerid`. On Android 10+
the user grants the **Caller ID & spam app** role from the permission setup screen
(`RoleManager.ROLE_CALL_SCREENING`). Android 8–9 falls back to phone-state identification (no blocking).

### Configure Play Billing and AdMob
See [docs/RELEASE.md](docs/RELEASE.md).

## CI

`.github/workflows/whocaller-android.yml` builds the debug and release APKs, runs all unit tests
(including Robolectric Room/repository/Compose tests) and lint, compiles instrumented tests, checks that no
secrets are committed, and uploads APKs and reports as artifacts.

## License

© RS APPS STUDIO. All rights reserved.
