# Database

Room database `whocaller.db` (`core/database`). Version **1**. Schemas are exported to
`core/database/schemas/` and used as migration-test fixtures.

## Number normalization

Every number is stored under a **canonical key** from `PhoneNumberNormalizer`:

1. Trim; detect withheld numbers (`-1`, `-2`, `PRIVATE`, `Unknown`, `Restricted`, …) → hidden.
2. Strip formatting (spaces, dashes, dots, brackets; Unicode digits normalised; leading `+` kept).
3. Parse with libphonenumber relative to the default region (settings → SIM → network → locale).
4. Also try the digits as an international number (`919876543210` → `+919876543210`).
5. Prefer a *valid* parse, then a *possible* one → key = **E.164**.
6. Otherwise key = `raw:<digits>` (short codes etc.) — can never collide with E.164.

So `+919876543210`, `919876543210`, `09876543210`, `98765 43210` and `(+91) 98765-43210` all map to
`+919876543210` (unit-tested).

## Tables

| Entity | Table | Key | Notes |
|---|---|---|---|
| `CallerEntity` | `callers` | `phoneNumber` (key) | Cached lookups and the offline spam list (`fromSpamList`). `spamScore = -1` means "no server score". Votes stored as `CATEGORY:n,…`. |
| `SpamReportEntity` | `spam_reports` | auto id; unique `clientReportId` | Your reports; `syncState` PENDING/SYNCED/FAILED, `attempts`. |
| `CallHistoryEntity` | `call_history` | auto id | Calls WhoCaller screened (label, decision, score). Capped at 500. |
| `BlockedNumberEntity` | `blocked_numbers` | `numberKey` | Your block list. |
| `BlockedCallEntity` | `blocked_calls` | auto id | Calls WhoCaller rejected. Capped at 1,000. |
| `SearchHistoryEntity` | `search_history` | auto id; unique `numberKey` | One row per number; capped at 200. |
| `ContactCacheEntity` | `contact_cache` | `numberKey` | 30-min cache of contact names for fast screening. |
| `BusinessEntity` | `businesses` | `businessId` | Businesses viewed/searched (7-day TTL). |
| `UserSettingsEntity` | `user_settings` | `settingKey` | Local counters (`stat.*`) and sync bookkeeping. Preferences live in DataStore. |

## Cache policy
- Positive lookups: 72 h. "Not found": 24 h (so unknown numbers aren't re-queried every call). Spam list rows: 7 days.
- Rows older than 90 days are deleted on spam-list refresh.
- Replacing the spam list never overwrites explicitly looked-up rows.

## Migrations
1. Change entities and bump `WhoCallerDatabase.VERSION`.
2. Add a `Migration(n, n+1)` to `Migrations.ALL` (or `@AutoMigration` where possible).
3. Build to export the new schema JSON; commit it.
4. Add a `MigrationTestHelper` test from the previous schema to the new one.

There is intentionally **no** destructive fallback: losing a user's block list is not acceptable.
