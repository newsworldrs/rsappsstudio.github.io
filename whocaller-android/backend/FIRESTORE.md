# WhoCaller on Firestore

The app reads and writes Firestore directly. There are two kinds of data:

| Data | Where it comes from | Collection |
|---|---|---|
| **Caller dataset** | A file you upload once (and again whenever you update it) with `npm run seed` | `callerNumbers` |
| **User reports** | The app's "Know this caller?" screen after an unknown call, and the Report screen | `reports` → summarised into `callerNumbers` |

Nothing from the user's phonebook, call log or SMS is ever uploaded. Saving a caller to contacts
uses Android's own contact editor and stays on the phone.

## Layout

```
callerNumbers/{E.164}              e.g. callerNumbers/+919876543210
  phoneNumber        "+91 98765 43210"   display format
  normalizedNumber   "+919876543210"     E.164 (same as the document id)
  countryCode        "+91"
  region             "IN"
  displayName        person name (optional)
  businessName       business name (optional)
  isVerified         true for verified businesses
  spamScore          0-100 (max of the dataset score and the community score)
  totalReports       number of users who reported it
  categories         top categories, e.g. ["TELEMARKETING", "BUSINESS_SERVICE"]
  categoryCounts     { "TELEMARKETING": 12, "SPAM": 3, … }
  reportsLast24h, reportsLast7d, lastReportedAt
  seedSpamScore, seedCategories       what your file said
  source             "seed" | "reports" | "seed+reports"
  createdAt, updatedAt

reports/{E.164 digits}_{uid}       e.g. reports/919876543210_Xy12…   (one per user per number)
  phoneNumber    "+919876543210"
  userId         Firebase Auth uid (anonymous sign-in is fine)
  categories     1–2 of: SPAM, TELEMARKETING, FINANCIAL_SCAM, FRAUD_FAKE_OFFER, IMPERSONATION,
                 BANKING_SCAM, BUSINESS_SERVICE, DELIVERY, ROBOCALL, HARASSMENT, OTHER
  callType       "INCOMING_UNKNOWN" (post-call screen) or "MANUAL" (Report screen)
  callAnswered   true / false
  reportedAt     server time
  appVersion     "1.0.0"
```

`BUSINESS_SERVICE` and `DELIVERY` describe a legitimate caller: they label the number but don't raise
its spam score. Because each user has exactly one report per number, re-reporting replaces the old
vote and one person can't push a number's score up.

## One-time setup (Firebase console)

1. **Firestore Database** → Create database → production mode → region `asia-south1` (Mumbai).
2. **Authentication → Sign-in method** → enable **Anonymous**. The app signs users in anonymously
   so they can read caller data and report without creating an account.
3. **Rules** — your project `calculator-6935be9a` is shared with your other apps, so **don't replace
   its rules**. Open Firestore → Rules and paste the two blocks marked `WHOCALLER` from
   [`firestore.rules`](firestore.rules) (the `callerNumbers` and `reports` matches plus the
   `validReport` function) inside your existing `match /databases/{database}/documents { … }`.
   Leave your other apps' rules as they are. Publish.
4. **Service account key** (for the upload command): Project settings → Service accounts →
   *Generate new private key*. Keep this file private — never put it in the repository.

## Upload your caller dataset

Put your file in `backend/tools/data/` (everything there except the sample is git-ignored, because
this repository is public). CSV header:

```csv
phoneNumber,displayName,businessName,categories,spamScore,isVerified
+919876543210,,Acme Broadband,BUSINESS_SERVICE,,true
9876500000,,,TELEMARKETING|SPAM,75,false
```

- `phoneNumber` can be local (`9876543210`) or international (`+91…`); `--region` decides the country for local numbers.
- `categories`: up to 3, separated by `|`. Friendly names also work ("Business / Service").
- `spamScore` is optional: blank → 70 if any spam category, else 0.
- JSON works too: an array of objects with the same keys.

Then, from `backend/tools`:

```bash
npm install
npm run seed -- data/my-callers.csv --region IN --dry-run              # checks the file, uploads nothing
npm run seed -- data/my-callers.csv --region IN --key ~/whocaller-key.json   # uploads
```

Running it again updates the same numbers (report counts are kept).

## Ready-made public datasets (one click)

The **WhoCaller Firestore** workflow (GitHub → Actions → *WhoCaller Firestore* → *Run workflow*) builds
caller data from legal, public sources:

| Task | Source | What it adds | License |
|---|---|---|---|
| `import-ifsc` | RBI bank-branch list (via razorpay/ifsc) | ~30,000 bank branch landlines and toll-free numbers, marked verified | Public domain |
| `import-wikidata` | Wikidata | Organisations in India with an official number | CC0 |
| `import-osm` | OpenStreetMap India | Shops, hospitals, offices… with a phone number | ODbL (credit shown in the app's Privacy screen) |
| `import-all` | All three | | |

Quality filters: mobile numbers in the bank list (often a staff member's own phone), placeholders like
`1234567890`, numbers listed for several different banks, and landlines whose area code is in a
different state than the branch are dropped.

- **mode = preview** builds the CSVs only. Download them from the run page (*Artifacts → whocaller-datasets*) and check them.
- **mode = upload** also writes them to Firestore (needs the `FIREBASE_SERVICE_ACCOUNT` secret).
- **Blaze plan (default):** `max_writes = 0` uploads everything in one run.
- **Free Spark plan:** Firestore allows 20,000 writes a day, so set `max_writes` to 18,000. When a
  run stops at the limit, its log says `Continue another day with: --start N` — run it again the next
  day with `start = N`.

Big brands' **customer-care numbers** are the most useful entries for spotting fake "bank" calls.
`tools/data/customer-care-template.csv` lists 30 companies with their official websites: copy each
number from the company's own website into the `phoneNumber` column, then
`npm run seed -- data/customer-care-template.csv --region IN`. Rows without a number are skipped.

The app also recognises TRAI's reserved series without any data: **140xxxxxxx** is labelled
Telemarketing, **1600xxxxxx** is labelled as a bank/financial service call.

## Turning reports into spam scores

Pick one:

- **Blaze plan** — deploy the trigger once: `cd backend/functions && npm install && firebase deploy --only functions:onReportWritten`.
  Every new report updates its number within seconds.
- **Free Spark plan** — add the service-account JSON as the GitHub secret `FIREBASE_SERVICE_ACCOUNT`
  (Settings → Secrets and variables → Actions). The **WhoCaller Firestore** workflow then runs every
  night, and you can run it any time from the Actions tab. Or run `npm run aggregate -- --key ~/whocaller-key.json` yourself.

## SMS spam model

`appConfig/smsSpamModel` holds the on-device SMS spam model (`version`, `model`). Phones download it
once in the background (weekly check) and use it for every incoming message; the APK also contains a
copy, so it works offline from the first launch. To ship an improved model without an app update:

```bash
python ../tools/sms-model/train.py spam.csv new_model.txt      # retrain (add your own Indian examples)
node publish-sms-model.mjs new_model.txt --version 2 --key ~/whocaller-key.json
```

## Data the app shows

When a call comes in (or you search a number) the app reads `callerNumbers/{number}` and shows the
name, business, categories, report count and spam score, combined with its on-device checks. Results
are cached on the phone (72 h for known numbers, 24 h for unknown ones).
