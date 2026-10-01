# Privacy

WhoCaller is designed so that the phone's personal data stays on the phone.

## Never uploaded
- **Contacts** — read on-device via the Contacts Provider only to recognise saved contacts. Never uploaded, never stored in Firestore.
- **Call history** — read on-device to show recent calls. Never uploaded.
- **SMS content** — messages are classified on-device (`SmsClassifier`). Nothing is uploaded.
- **Call audio** — WhoCaller cannot access or record calls.

## Sent to WhoCaller servers (only when a backend is configured)
| Data | When | Why |
|---|---|---|
| A phone number you **search** or type in the keypad (7+ digits, not a saved contact) | When you search or pause typing | To look it up |
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
| Permission / role | Used for | If denied |
|---|---|---|
| Call-screening role (Android 10+) | Identify/block incoming calls | Manual search still works |
| Default phone app role (optional) | WhoCaller's own call screen: "Incoming call from …" for every call, answer/decline, in-call controls, video calls | System phone app keeps handling calls |
| Default SMS app role (optional) | Receive, store and send texts with scam warnings | Messages stay in your current SMS app; "Check a message" still works |
| `READ_PHONE_STATE` | Caller ID on Android 8–9; checking whether the SIM supports video calling | Video call button hidden |
| `CALL_PHONE` | Placing calls/video calls when you tap Call | Calls open in the dialer instead |
| `CAMERA` | Self-view during video calls only | Video calls without self-view |
| `READ_CALL_LOG` | Recent calls; incoming number on Android 9 | Recent calls hidden |
| `READ_CONTACTS` | Recognise saved contacts | Contacts not recognised; "block unknown" disabled |
| `WRITE_CONTACTS` | Only when you star/delete a contact in WhoCaller | Those two actions unavailable |
| `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | Only used as the default SMS app (granted with that role) | — |
| `USE_FULL_SCREEN_INTENT` | Full-screen incoming-call screen as default phone app | Heads-up notification instead |
| `POST_NOTIFICATIONS` | Caller alerts, spam warnings, new messages | No alerts |

Not requested: accessibility, draw-over-other-apps.

### SMS
Messages are read, classified and stored **on the device only**; their text is never uploaded.
As the default SMS app WhoCaller stores every incoming message (it never deletes or hides any),
and records messages you send. Picture messages (MMS) are not downloaded yet: the user is told to
view them in another SMS app. Google Play only allows SMS permissions for default SMS apps.

### Profile
Name, profession, institute, email and the profile photo or avatar are stored on this device only.
The photo is picked with the system photo picker (no storage permission) and saved downscaled in
private app storage.

## Retention
Server: aggregate counts per number; per-user report records are anonymised on account deletion.
Device: caches expire (72 h positive, 24 h negative, 90-day retention); histories are capped.
