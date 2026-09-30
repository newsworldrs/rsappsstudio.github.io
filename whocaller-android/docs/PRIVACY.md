# Privacy

WhoCaller is designed so that the phone's personal data stays on the phone.

## Never uploaded
- **Contacts** — read on-device via the Contacts Provider only to recognise saved contacts. Never uploaded, never stored in Firestore.
- **Call history** — read on-device to show recent calls. Never uploaded.
- **SMS content** — message checks run on-device (`SmsClassifier`). Nothing is uploaded.
- **Call audio** — WhoCaller cannot access or record calls.

## Sent to WhoCaller servers (only when a backend is configured)
| Data | When | Why |
|---|---|---|
| A phone number you **search** | When you search | To look it up |
| An **incoming** number not in your contacts | When it rings and caller ID is on | To identify it (cached; repeated calls use the cache) |
| A number you **report**, reason, optional comment | When you submit a report | Community spam protection. Comments are moderated and never shown publicly |
| Account email/phone/name | If you sign in | Account management (Firebase Authentication) |
| App Check token | Every request | Blocks fake clients and abuse |

Guests can use caller ID, blocking, call history, contacts, spam checks and search without an account.

## Analytics and crash reports
Both are **off by default** (opt-in under Settings › Privacy) and enforced in the manifest
(`firebase_*_collection_enabled=false`). Events are coarse (e.g. `number_searched{found=true}`) and never
contain phone numbers, names, contacts or message text. Crashlytics custom keys never contain user data.

## On-device storage
Room database (`whocaller.db`) holds: cached lookups, your block list and blocked-call log, your reports,
search history (can be turned off), screened calls, businesses you viewed, local counters.
Session tokens and premium entitlement are encrypted with a key in the Android Keystore.
Cloud backup excludes the database and encrypted values.

## Your controls (Settings › Privacy)
Caller identification · Spam protection · Search history · Personalized recommendations (on-device only) ·
Analytics · Crash reports · Contact access · Delete search history · Clear local data · Export my data (JSON) ·
Delete account · Privacy Policy · Terms of Service.

## Permissions
| Permission | Used for | If denied |
|---|---|---|
| Call-screening role (Android 10+) | Identify/block incoming calls | Manual search still works |
| `READ_PHONE_STATE` (Android 8–9 only) | Identify incoming calls | Manual search still works |
| `READ_CALL_LOG` | Recent calls; incoming number on Android 9 | Recent calls hidden |
| `READ_CONTACTS` | Recognise saved contacts | Contacts not recognised; "block unknown" disabled |
| `WRITE_CONTACTS` | Only when you star/delete a contact in WhoCaller | Those two actions unavailable |
| `POST_NOTIFICATIONS` | Caller alerts and spam warnings | No alerts |

Not requested: `CALL_PHONE` (calls go through your dialer), `READ_SMS`/`SEND_SMS`, accessibility, overlay.

### SMS inbox
Google Play restricts SMS permissions to default SMS apps and a few approved uses. The default build does
**not** declare `READ_SMS`; users check messages by sharing them to WhoCaller. The inbox reader
(`SmsRepositoryImpl`) activates automatically only in a build that declares the permission *and* has Play
approval (or is the default SMS handler).

## Retention
Server: aggregate counts per number; per-user report records are anonymised on account deletion.
Device: caches expire (72 h positive, 24 h negative, 90-day retention); histories are capped.
