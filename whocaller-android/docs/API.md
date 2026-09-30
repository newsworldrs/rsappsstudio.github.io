# REST API v1

Base URL: `WHOCALLER_API_BASE_URL` (HTTPS only). All bodies are JSON. Numbers are E.164 (`+919876543210`).
Unknown response fields must be ignored by clients (they are).

## Headers

| Header | Required | Notes |
|---|---|---|
| `X-Firebase-AppCheck` | always | App Check token (Play Integrity in release). |
| `Authorization: Bearer <Firebase ID token>` | writes | Short-lived (1 h), refreshed by the Firebase SDK. |
| `User-Agent` | yes | `WhoCaller/<version> (Android)` |

## Errors

| Status | Client error | Meaning |
|---|---|---|
| 400 / 422 | `INVALID_NUMBER` | Invalid number or payload |
| 401 / 403 | `UNAUTHORIZED` | Missing/invalid auth or App Check |
| 404 | `NOT_FOUND` | No data for this number — shown as "No identity found" |
| 409 | `DUPLICATE` | Already reported (idempotent retry is also 409) |
| 429 | `RATE_LIMITED` | Rate limit exceeded |
| 5xx | `SERVER` | Server problem; client falls back to cache |

## Endpoints

### `POST /api/v1/auth/login`
`{ "idToken": "…" }` → `{ "accessToken", "expiresAt", "userId" }`. For non-Firebase clients; the app sends the Firebase ID token directly.

### `GET /api/v1/number/{number}`
```json
{
  "number": "+919876543210",
  "name": "ABC Internet Services",      // null when unknown — never guessed
  "identityType": "BUSINESS",           // PERSON | BUSINESS | UNKNOWN
  "category": "TELEMARKETING",          // SpamCategory
  "spamScore": 68, "confidence": 0.8,
  "reportCount": 127, "reportsLast24h": 3, "reportsLast7d": 11, "blockCount": 17,
  "lastReportedAt": 1750000000000,
  "categoryVotes": { "TELEMARKETING": 100, "SPAM": 27 },
  "verified": false, "businessId": null,
  "carrier": null, "lineType": "MOBILE", "region": "IN", "updatedAt": 1750000000000
}
```
Rate limit: 120/hour per user or IP.

### `POST /api/v1/number/{number}/report` (auth)
`{ "reason": "SPAM|SCAM|TELEMARKETING|FRAUD|ROBOCALL|HARASSMENT|FAKE_BANK_CALL|FAKE_DELIVERY_CALL|OTHER", "comment": "≤500 chars", "clientReportId": "uuid", "reportedAt": 1750000000000 }`
→ `{ "reportId", "accepted" }`. One report per user per number; 24 h cool-down; re-report replaces the previous vote; `clientReportId` makes retries idempotent. 30 reports/day per user. Comments are moderated and never returned.

### `GET /api/v1/number/{number}/reports`
Aggregates only: `{ "number", "total", "byReason": {…}, "lastReportedAt" }`.

### `POST /api/v1/number/{number}/block` (auth)
`{ "blocked": true }` → 204. Counts once per user; feeds `blockCount`.

### `GET /api/v1/search?q=&region=`
Business directory search → `{ "results": [Business] }`.

### `GET /api/v1/business/{id}`
```json
{ "businessId", "name", "phoneNumbers": [], "category", "address", "website", "logo", "email",
  "verified", "verificationDate", "country", "language", "hours", "rating", "ratingCount" }
```
`verified` is true only with a completed verification record (`verificationDate`).

### `POST /api/v1/business/verify` (auth)
`{ "businessName", "phoneNumber", "category", "website", "email", "country" }` → `{ "requestId", "status": "PENDING" }`.
Starts a manual review (ownership of the number and the business). 3/day per user.

### `GET /api/v1/config`
`{ "minSupportedVersion", "lookupCacheTtlHours", "spamListMaxEntries", "freeSearchesPerDay" }`

### `GET /api/v1/countries`, `GET /api/v1/categories`
Supported countries (`region`, `callingCode`, `name`, `supported`) and categories (`id`, `severity`).

### `GET /api/v1/spam/top?region=IN&since=`
Frequently reported numbers (score ≥ 50) for the offline spam list: `{ "region", "generatedAt", "entries": [NumberInfo] }`.

### `POST /api/v1/billing/verify` (auth)
`{ "productId", "purchaseToken" }` → `{ "valid", "expiresAt" }`. Verified server-side with the Play Developer API.

### `DELETE /api/v1/account` (auth)
Deletes the account; the user's reports are anonymised (aggregate counts remain, comments removed).
