# WhoCaller reference backend

Firebase Cloud Functions (Node 22, TypeScript) + Firestore implementing [REST API v1](../docs/API.md).

```bash
cd backend/functions
npm install
npm run build          # type-check + compile
npm run serve          # local emulators (functions, firestore, auth)
firebase use <project-id>
npm run deploy         # functions + Firestore rules/indexes
```

Hosting rewrites `/api/**` to the `api` function, so the app's `WHOCALLER_API_BASE_URL` is your Hosting
domain (e.g. `https://<project>.web.app/`).

What it enforces:
- **App Check** token on every request, **Firebase Auth** for writes.
- Rate limits per user/IP (lookups 120/h, reports 30/day, verification 3/day …).
- One report per user per number (re-reporting replaces the earlier vote; 24 h cool-down).
- Report comments are stored for moderation only and never returned by the API.
- Firestore rules deny all client access; only the API (Admin SDK) reads/writes.
- `maintainNumbers` (daily) prunes report buckets, refreshes scores and cleans rate-limit docs.

Still to implement for production:
- `POST /api/v1/billing/verify` with the Play Developer API (`purchases.subscriptionsv2.get`),
  using a service account from Secret Manager.
- Staff tooling for business verification and comment moderation.
- Seeding business data from **authorized/public sources only**.
