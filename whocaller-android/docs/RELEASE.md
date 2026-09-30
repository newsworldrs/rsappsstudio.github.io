# Release

## Build

```bash
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk (id com.rskusum.whocaller.debug)
./gradlew assembleRelease               # R8 + resource shrinking
./gradlew bundleRelease                 # AAB for Play
```

## Signing
1. Create an upload key: `keytool -genkeypair -v -keystore whocaller-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias whocaller`
2. Copy `keystore.properties.example` → `keystore.properties` and fill it in (git-ignored).
3. Enrol in **Play App Signing**. Without `keystore.properties`, release builds are produced unsigned.
4. In CI, write the keystore from an encrypted secret at build time; never commit it.

## Play Billing
1. Play Console → Monetize → Subscriptions: create `whocaller_premium_monthly` and `whocaller_premium_yearly`
   (ids in `BillingManager.PRODUCT_IDS`), each with one base plan. Prices are set in Play Console and shown
   exactly as Google Play formats them; nothing is hard-coded in the app.
2. Add license testers.
3. Implement `POST /api/v1/billing/verify` (Play Developer API `purchases.subscriptionsv2.get`) with a
   service account in Secret Manager, and enable Real-time Developer Notifications for renewals/cancellations.
   Until then, builds without a backend fall back to the Play purchase state.

## AdMob
1. Create the app and a banner ad unit; set `WHOCALLER_ADMOB_APP_ID` / `WHOCALLER_ADMOB_BANNER_ID`.
   Defaults are Google's public test IDs.
2. Configure the GDPR/US-states message in AdMob › Privacy & messaging (shown via UMP before ads load).
3. Ads appear only on Home and Search, never on caller alerts, dialogs, permission screens or for Premium users.

## Crashlytics
Applied automatically when `app/google-services.json` exists. Mapping files upload during `assembleRelease`.
Collection is off until the user opts in.

## Play Console declarations
- **Permissions declaration** for `READ_CALL_LOG`: core functionality "Caller ID, spam detection and spam blocking".
- **Data safety**: phone numbers searched/reported and account info are collected; contacts, call logs and SMS are not.
- Privacy policy URL (`WHOCALLER_PRIVACY_URL`).
- No SMS permissions in the default build.

## Release checklist
- [ ] CI green: build, unit tests, lint, release build, instrumented-test compilation
- [ ] `versionCode`/`versionName` bumped in `app/build.gradle.kts`
- [ ] `WHOCALLER_API_BASE_URL` points to production (HTTPS); backend deployed; App Check enforced
- [ ] Real AdMob IDs and UMP message configured; test IDs removed
- [ ] Subscription products active; `/billing/verify` implemented
- [ ] Firebase: Auth providers, App Check (Play Integrity), Crashlytics, Analytics, Remote Config
- [ ] Privacy policy and terms published at the configured URLs
- [ ] Data safety form and permissions declaration submitted
- [ ] No secrets in repo (CI check), `google-services.json` not committed
- [ ] Room schema exported and migration test added if the schema changed
- [ ] Manual matrix in [TESTING.md](TESTING.md) passed on Android 9, 12 and 15 devices
- [ ] Translations reviewed by native speakers
- [ ] Store listing uses no unverified claims (database size, user counts, accuracy)
