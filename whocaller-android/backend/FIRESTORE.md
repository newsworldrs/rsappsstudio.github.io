# WhoCaller on Firestore

The app reads and writes Firestore directly. There are two kinds of data:

| Data | Where it comes from | Collection |
|---|---|---|
| **Caller dataset** | A file you upload once (and again whenever you update it) with `npm run seed` | `callerNumbers` |
| **User reports** | The app's "Know this caller?" screen after an unknown call, and the Report screen | `reports` → summarised into `callerNumbers` |
| **WhoCaller IDs** | Created automatically when a user signs up (Google or email) and completes their profile | `whocallerUsers`, `registeredCallers` |

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
                 BANKING_SCAM, BUSINESS_SERVICE, DELIVERY, ROBOCALL, HARASSMENT, OTHER, NOT_SPAM
  callType       "INCOMING_UNKNOWN" (post-call screen) or "MANUAL" (Report screen)
  callAnswered   true / false
  reportedAt     server time
  appVersion     "1.0.0"

whocallerUsers/{uid}               the account's WhoCaller ID (only the owner can read it)
  name               "Rahul Sharma"
  phoneNumber        "+919876543210"    verified by SMS code, must equal the account's phone number
  email              account email (may be empty)
  showNameToCallers  true / false
  createdAt, updatedAt

registeredCallers/{E.164}          public caller ID, e.g. registeredCallers/+919876543210
  name               only when showNameToCallers is on
  carrier            network the owner's SIM is on now ("Airtel"), read from their phone and
                     refreshed by the daily sync, so it stays right after the number is ported
  uid, updatedAt
```

Firestore creates both collections by itself the first time a user saves their profile: there's
nothing to create in the console. The rules only accept a number that the user verified by SMS
code (Firebase Auth's `phone_number`), so nobody can put their name on someone else's number. When
a number is looked up, a business name from the dataset wins; otherwise the registered name is shown.

**Operator after number portability.** A number's prefix only tells which network first issued it.
The app therefore shows the owner-confirmed `carrier` when there is one, and otherwise the prefix
network marked "(original)" / "Originally Jio. The number may have moved to another network."
Live lookups for any number (HLR / MNP query) need a paid provider (e.g. Twilio Lookup in the US,
an HLR lookup service in India) called from a Cloud Function so the API key stays secret.

`BUSINESS_SERVICE` and `DELIVERY` describe a legitimate caller: they label the number but don't raise
its spam score. Because each user has exactly one report per number, re-reporting replaces the old
vote and one person can't push a number's score up.

## One-time setup (Firebase console)

1. **Firestore Database** → Create database → production mode → region `asia-south1` (Mumbai).
2. **Authentication → Sign-in method** → enable **Google**, **Email/Password**, **Phone** and
   **Anonymous**. New users must sign in with Google or email and verify their mobile number by SMS
   code (Phone). Anonymous is used for lookups in the background.
   - Google sign-in and SMS codes on a real phone need your signing key's **SHA-1 and SHA-256** under
     Project settings → Your apps → Android app (debug key for test APKs, Play App Signing key for release).
   - For testing without real SMS: Authentication → Sign-in method → Phone → *Phone numbers for testing*.
3. **Rules** — your project `calculator-6935be9a` is shared with your other apps, so **don't replace
   its rules**. Open Firestore → Rules and paste the two blocks marked `WHOCALLER` from
   [`firestore.rules`](firestore.rules) (the `callerNumbers` and `reports` matches plus the
   `validReport` function) inside your existing `match /databases/{database}/documents { … }`.
   Leave your other apps' rules as they are. Publish.
4. **Index** — the app downloads each country's top spam numbers for offline protection
   (`callerNumbers` where `region ==` and `spamScore >=`, newest score first). Firestore → Indexes →
   Composite → Add: collection `callerNumbers`, fields `region` Ascending, `spamScore` Descending,
   scope Collection (also in [`firestore.indexes.json`](firestore.indexes.json)).
5. **Service account key** (for the upload command): Project settings → Service accounts →
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

## Outside spam lists

Numbers from a third-party spam list are kept apart from WhoCaller's own data, because nobody can
check where such a list came from and Indian mobile numbers get reassigned to new people:

```
callerNumbers/{E.164}
  externalList        "community-spamlist-2026-09"   id of the list (used to remove it)
  externalListName    "Public spam list (GitHub)"     shown nowhere except the app's wording
  externalSpamScore   60                              never above 80
  externalCategories  ["SPAM"]
  externalActive      true until 2 users answer "Not spam" (or the number is verified)
```

- The app shows these as **"Possible spam · Flagged by an outside spam list, not yet reported by
  WhoCaller users"**, never as plain "Spam", and never blocks them automatically, even when the
  user turned on category blocking.
- **"Not spam"** on the "Know this caller?" screen hides the warning at once for that user; two
  different users answering "Not spam" switch it off for everyone (`NOT_SPAM_TO_CLEAR` in `tools/lib.mjs`).
- Numbers WhoCaller already knows (bank, business, helpline, named caller) are never flagged.
- One command takes a whole list back out:

```bash
node external-list.mjs import data/list.json --list community-spamlist-2026-09 --name "Public spam list (GitHub)" --score 60
node external-list.mjs remove --list community-spamlist-2026-09
```

**USA**: `import-us-fcc.mjs` builds a list from the FCC's public consumer-complaint data (unwanted
calls / robocalls, US government public data). Numbers need at least 3 complaints in the last year
(caller IDs in complaints are sometimes spoofed, so single complaints are ignored); the score grows
with the number of complaints (60 → 78, never above 80) and the category is ROBOCALL or SPAM. The
**WhoCaller US spam data** workflow runs it on GitHub (edit `tools/us-request.txt` or use the Actions
tab) and publishes `us-fcc-callers.csv` as the `whocaller-us-datasets` release; then:

```bash
node external-list.mjs import data/us-fcc-callers.csv --list us-fcc-2026-10 --region US --name "FCC complaint records"
```

On Android 11+ the app also reads the carrier's caller-ID check (STIR/SHAKEN, used by US carriers):
when it fails, the call shows "Caller ID not verified · number may be spoofed".

Imported so far: `community-spamlist-2026-09`, 1,034 numbers from
github.com/RajeshLakkam/call-blocker-spam-list (source of the numbers not stated by that project), and
`scam-calls-india`, 1 number from github.com/makash/scam-calls-india (public domain), and
`us-fcc-ftc-2026-09`, 1,137 US numbers with 3+ FCC/FTC complaints since October 2025 (US government
public data, via github.com/SysAdminDoc/CallShield, MIT).

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
