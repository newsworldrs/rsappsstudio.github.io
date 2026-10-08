# WhoCaller Premium on Google Play

The app reads every price from Google Play (in the user's own currency), so prices are changed in
Play Console only — never in the code. The **ids** below are in the code
(`feature/premium/BillingManager.kt`) and must be created exactly like this. Don't rename them after
launch: existing purchases are tied to them.

## Products

| What | Play Console type | Product id | Base plan / offer id | India | USA |
|---|---|---|---|---|---|
| Monthly | Subscription | `whocaller_premium` | base plan `monthly` (auto-renewing, 1 month) | ₹49 | $2.99 |
| Yearly | Subscription (same product) | `whocaller_premium` | base plan `yearly` (auto-renewing, 1 year) | ₹399 (save 32%) | $19.99 (save 44%) |
| Yearly trial (optional) | Offer on the yearly base plan | `whocaller_premium` | offer `yearly-trial`: 7-day free trial, eligibility "New customer acquisition" | — | — |
| Lifetime | One-time product (in-app, non-consumable) | `whocaller_premium_lifetime` | — | ₹999 | $49.99 |

For other countries use Play Console's "Set prices → Update exchange rates" from the US price, then
round to local price points.

## Play Console steps

1. **Monetize → Products → Subscriptions → Create subscription**: id `whocaller_premium`, name
   "WhoCaller Premium". Add benefits (Advanced spam protection, Enhanced lookup, Advanced blocking…).
2. Add base plan `monthly` → Auto-renewing → Billing period 1 month → set prices → Activate.
3. Add base plan `yearly` → Auto-renewing → Billing period 1 year → set prices → Activate.
4. (Optional) On `yearly` → Add offer `yearly-trial` → New customer acquisition → phase "Free trial,
   7 days" → Activate. The app shows "Start free trial" automatically when this offer exists.
5. **Monetize → Products → In-app products → Create product**: id `whocaller_premium_lifetime`,
   name "WhoCaller Premium Lifetime", set prices → Activate.
6. **Setup → License testing**: add your Google account so test purchases aren't charged.
7. Products only load in a build installed **from Play** (internal testing track is enough), signed
   with the release key. The test APK from GitHub shows "Subscriptions aren't available right now".

## How the app behaves

- Paywall: Monthly / Yearly (preselected, "Best value", saving % computed from Play's prices) /
  Lifetime (one-time).
- Switching monthly ↔ yearly replaces the current subscription (time-prorated), so nobody pays twice.
- "Restore purchases" re-reads both the subscription and the lifetime purchase from the Google account.
- Purchases are acknowledged after checking; with a server configured, `POST /api/v1/billing/verify`
  checks them with the Play Developer API first (recommended before launch).
- No ads at all for the first two years, for everyone (see "Ads" below), so Premium doesn't sell "no ads".

## Ads

Ads are switched off and the AdMob SDK isn't in the app (search the code for `ADS OFF` to bring it
back later). In Play Console → App content:
- **Ads**: "No, my app does not contain ads".
- **Data safety**: no advertising ID (the AD_ID permission is removed in the manifest).

## Other Google Play libraries in the app

| Library | What it does in WhoCaller |
|---|---|
| In-App Review | Shows Play's rating card after 5+ opens over 3+ days, at most every 60 days. |
| In-App Updates | High-priority releases (priority 4–5) update immediately; others download in the background and the app offers "Restart". Set the priority when releasing with the Play Developer API (`inAppUpdatePriority`). |
| Play Integrity (Standard API) | Firebase App Check already uses it for Firestore (turn on **enforcement** in Firebase → App Check once the release is live). `PlayIntegrity.token()` is ready for server checks. Link the Cloud project in Play Console → App integrity. |
| Install Referrer | On first launch, records which campaign link brought the install (utm_source/medium/campaign) in analytics. |
