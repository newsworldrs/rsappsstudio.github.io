/**
 * WHOCALLER VIDEO (experimental): rings the other phone for an app-to-app video call.
 * When the app creates videoCalls/{callId}, a high-priority data message goes to the callee's
 * device (token in pushTokens/{uid}); their phone shows the incoming video call screen.
 */
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated } from "firebase-functions/v2/firestore";

export const onVideoCallCreated = onDocumentCreated({ document: "videoCalls/{callId}", region: "asia-south1" }, async (event) => {
  const call = event.data?.data();
  if (!call || call.status !== "ringing" || typeof call.calleeUid !== "string") return;
  const tokenDoc = await getFirestore().collection("pushTokens").doc(call.calleeUid).get();
  const token = tokenDoc.get("token");
  if (typeof token !== "string" || !token) return;
  try {
    await getMessaging().send({
      token,
      data: {
        type: "video_call",
        callId: event.params.callId,
        callerName: String(call.callerName ?? "").slice(0, 60),
        callerNumber: String(call.callerNumber ?? "").slice(0, 20),
      },
      android: { priority: "high", ttl: 30_000 },
    });
  } catch (e) {
    console.warn("video call push failed", e);
  }
});
