# Architecture

WhoCaller follows Clean Architecture with MVVM in the presentation layer. Dependencies point inward:
**features → domain ← data**. The domain and the models are plain Kotlin (no Android), so the core
logic is fast to test and portable.

```
app ──────────────► feature:* ──────► core:domain ──► core:common ──► core:model
 │  (composition     (Compose UI +       (use cases,     (normalizer,
 │   root: DI,        ViewModels)         repository      spam engine,
 │   navigation,                          interfaces)     analytics API)
 │   Firebase impls)
 └──► core:data ──► core:database (Room)
         │      └─► core:network (Retrofit, pure JVM)
         └────────► core:security (Keystore)
core:ui (design system, shared components)   core:permissions (staged permissions, role)
```

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `core:model` | JVM | Immutable domain models (`CallerInfo`, `SpamScore`, `CallerResult`, `AppSettings`…). |
| `core:common` | JVM | `PhoneNumberNormalizer` (libphonenumber), `SpamScoreEngine`, `AppResult`/`AppError`, analytics & crash-reporting interfaces, dispatcher qualifiers. |
| `core:domain` | JVM | Repository interfaces, `CallerIdentificationManager`, use cases (search, report, block), `SmsClassifier`. |
| `core:network` | JVM | `WhoCallerApi` (Retrofit), DTOs, `NetworkDataSource` (REST / unconfigured / dev), HTTP client with auth + App Check headers. |
| `core:database` | Android | Room database, entities, DAOs, migrations. |
| `core:data` | Android | Repository implementations (Room, DataStore, Contacts/CallLog providers, connectivity), sync worker, DI bindings. |
| `core:security` | Android | AES-GCM with Android Keystore; encrypted key–value storage; token expiry. |
| `core:ui` | Android | Material 3 theme (light/dark/dynamic), risk colours, shared components, formatting, intents, notification channels. |
| `core:permissions` | Android | Permission catalogue with explanations, call-screening role, staged setup screen. |
| `feature:home` | Android | Dashboard: greeting, search bar, actions, statistics, protection status, unidentified callers. |
| `feature:callerid` | Android | `CallScreeningService`, `CallScreeningManager`, caller alerts, Android 8–9 fallback. |
| `feature:search` | Android | Reverse lookup, search history, business directory/profile/verification request. |
| `feature:callhistory` | Android | Paged call log with filters and actions. |
| `feature:contacts` | Android | Contacts list, alphabetical index, favourites, details. |
| `feature:spam` | Android | Spam protection settings, report dialog, your reports. |
| `feature:blocking` | Android | Block list, blocked history, unknown/hidden number rules. |
| `feature:sms` | Android | On-device message checker (shared text); optional inbox reader. |
| `feature:settings` | Android | Settings, language, country, notifications, privacy controls, export. |
| `feature:profile` | Android | Accounts (Google, email, phone, guest), profile and statistics. |
| `feature:premium` | Android | Play Billing subscriptions, `AdsManager` (AdMob + UMP consent). |
| `app` | Android | Composition root: Hilt modules, Firebase implementations, navigation, onboarding, splash, widget, shortcuts. |

Features never depend on each other; the app wires navigation through callbacks.

## Caller identification pipeline

```
Incoming call
  → WhoCallerScreeningService.onScreenCall()            (Android 10+, call-screening role)
  → CallScreeningManager.screen()                       (4 s total budget)
  → CallerIdentificationManager.identify()
       1. PhoneNumberNormalizer → canonical key (E.164)   or HIDDEN
       2. User block list (Room, indexed)                 → BLOCK
       3. Contacts (PhoneLookup, 30-min cache)            → ALLOW as contact
       4. Local cache (Room)  ─┐
       5. Backend lookup      ─┴ time-boxed (2.5 s), cache-first, negative results cached
       6. SpamScoreEngine (community reports, recency, blocks, server verdict, verified business,
                           your own reports/blocks)
       7. Decision: block list / block unknown (only with contacts access) / blocked categories /
                    auto-block very high risk (opt-in) / warn (high risk) / allow
  → CallResponse (reject when BLOCK) + CallerAlertNotifier (notification)
  → IdentifiedCall + BlockedCall + statistics recorded locally
```

Failures anywhere (database, network, timeout) never break the call: the call is allowed.

## Spam scoring

`SpamScoreEngine` is an interface; `RuleBasedSpamScoreEngine` is the on-device implementation and
`backend/functions/src/scoring.ts` mirrors it on the server. Replace either with an ML model without
touching callers. Bands: 0–19 low, 20–49 moderate, 50–74 high, 75–100 very high.

Guarantees (unit-tested): no evidence ⇒ 0/UNKNOWN; contacts ⇒ SAFE; SCAM/FRAUD require a high score
**and** ≥3 agreeing reports (or a confident server verdict, or your own report); verified businesses are
capped low; old reports decay.

## Offline

Room is the source of truth. Offline, the app uses cached lookups, the downloaded regional spam list,
the local block list, contacts and call log. `WorkManager` (`SyncWorker`) uploads pending reports and
refreshes the spam list when connectivity returns; Home shows "Syncing protection data…".

## Backend abstraction

`NetworkDataSource` has three implementations: `RetrofitNetworkDataSource` (any server speaking
[API v1](API.md)), `DevNetworkDataSource` (debug only, fictional numbers) and
`UnconfiguredNetworkDataSource` (fails honestly). Firebase specifics (Auth, App Check, Analytics,
Crashlytics) live only in the `app` module behind domain interfaces, so moving off Firebase means
replacing those classes and the base URL.

## Localization

All user-visible text is in `strings.xml` per module. Supported UI languages: English, Hindi, Punjabi,
Spanish, French, German, Arabic (RTL), Portuguese, Indonesian — `generateLocaleConfig` publishes them to
Android 13+ per-app language settings; AppCompat back-ports the picker. Phone-number data never depends
on UI language. Missing translations fall back to English (lint reports them as warnings).

## Performance

- Call log is paged (`OffsetPagingSource`, 50 rows) and scanned in bounded chunks for filters.
- Heavy work runs on `Dispatchers.IO`; ViewModels expose `StateFlow` collected with lifecycle awareness.
- Room indices on all lookup columns; caches are trimmed (`trim`, TTLs, 90-day retention).
- R8 with resource shrinking for release.
