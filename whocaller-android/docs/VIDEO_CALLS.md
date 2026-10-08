# WhoCaller video (experimental)

Free app-to-app video calls between two WhoCaller users, over the internet (Wi-Fi or mobile data).
Built to be tested and, if it doesn't work well enough, removed in one go.

## How it works
1. Caller taps **WhoCaller video**. The app looks the number up in `registeredCallers` (people who
   verified their WhoCaller ID). Not found: "Not on WhoCaller yet", with Invite / WhatsApp video.
2. The app creates `videoCalls/{id}` (status `ringing`) and its WebRTC offer.
3. Cloud Function `onVideoCallCreated` sends a push to the other phone (token in `pushTokens/{uid}`),
   which shows a full-screen incoming video call (Accept / Decline).
4. Accept: the answer and network paths (`videoCalls/{id}/candidates`) go through Firestore, then
   video and sound flow phone-to-phone (WebRTC), or through a TURN relay when a network blocks that.

## Setup (once)
1. **Firestore rules**: add the `WHOCALLER VIDEO` blocks from `backend/firestore.rules`.
2. **Deploy the push function** (Google Cloud Shell works; nothing to install on your PC):
   ```bash
   git clone https://github.com/newsworldrs/rsappsstudio.github.io.git
   cd rsappsstudio.github.io && git checkout claude/peaceful-meitner-760s14
   cd whocaller-android/backend/functions && npm install && npm run build
   npx firebase-tools deploy --only functions:onVideoCallCreated --project calculator-6935be9a
   ```
3. **Relay (TURN)** — needed on many mobile networks. A public test relay is built in; for real use
   create a free account at metered.ca (or Cloudflare Calls) and add, in Firestore,
   `appConfig/webrtc` → `iceServers` (array of maps): `{urls: ["turn:…"], username: "…", credential: "…"}`.

Both phones must be signed in and have verified their WhoCaller ID.

## Remove it
- Delete `app/src/main/kotlin/com/rskusum/whocaller/videocall/`.
- In `app/src/main/AndroidManifest.xml` remove the `VideoCallActivity` / `VideoCallMessagingService`
  entries and the CAMERA permission block marked `WHOCALLER VIDEO`.
- In `app/build.gradle.kts` remove `firebase.messaging` and `stream.webrtc`.
- Remove the lines marked `WHOCALLER VIDEO (experimental)` (MainActivity, Telecom.kt, the buttons in
  KeypadTab, DetailsSheet, NumberResultContent, ContactDetailScreen) and the `vc_*` strings.
- Backend: delete `functions/src/videoCalls.ts` and its export, run
  `firebase functions:delete onVideoCallCreated`, and remove the rules blocks.
