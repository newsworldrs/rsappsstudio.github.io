package com.rskusum.whocaller.videocall

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.rskusum.whocaller.firebase.isFirebaseAvailable
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * WHOCALLER VIDEO (experimental): call setup through Firestore.
 *
 *   videoCalls/{callId}             callerUid, calleeUid, names, numbers, status, offer, answer
 *   videoCalls/{callId}/candidates  network paths of both phones (from = uid)
 *   pushTokens/{uid}                this phone's push token, so the other side can ring it
 *
 * The other person is found by number in registeredCallers (verified WhoCaller IDs only).
 */
class VideoSignaling(private val db: FirebaseFirestore, val myUid: String) {

    private fun call(id: String) = db.collection(CALLS).document(id)

    /** uid + name of the WhoCaller user who verified [e164], or null if they aren't on WhoCaller. */
    suspend fun findUser(e164: String): Pair<String, String?>? {
        val doc = db.collection("registeredCallers").document(e164).get().await()
        val uid = doc.getString("uid") ?: return null
        return uid to doc.getString("name")
    }

    suspend fun create(calleeUid: String, calleeNumber: String, calleeName: String?, myName: String, myNumber: String): String {
        val ref = db.collection(CALLS).document()
        ref.set(
            mapOf(
                "callerUid" to myUid,
                "calleeUid" to calleeUid,
                "callerName" to myName.take(60),
                "callerNumber" to myNumber.take(20),
                "calleeName" to calleeName?.take(60),
                "calleeNumber" to calleeNumber.take(20),
                "status" to STATUS_RINGING,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        return ref.id
    }

    suspend fun get(id: String): DocumentSnapshot = call(id).get().await()

    suspend fun setOffer(id: String, sdp: SessionDescription) = call(id).update(
        mapOf("offer" to sdp.toMap(), "updatedAt" to FieldValue.serverTimestamp()),
    ).await()

    suspend fun accept(id: String, sdp: SessionDescription) = call(id).update(
        mapOf("answer" to sdp.toMap(), "status" to STATUS_ACCEPTED, "updatedAt" to FieldValue.serverTimestamp()),
    ).await()

    suspend fun setStatus(id: String, status: String) {
        runCatching { call(id).update(mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp())).await() }
    }

    /** Fire-and-forget status change (when the screen is closing). */
    fun setStatusAsync(id: String, status: String) {
        call(id).update(mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp()))
    }

    fun addCandidate(id: String, c: IceCandidate) {
        call(id).collection("candidates").add(
            mapOf(
                "from" to myUid,
                "candidate" to c.sdp,
                "sdpMid" to c.sdpMid,
                "sdpMLineIndex" to c.sdpMLineIndex,
                "at" to FieldValue.serverTimestamp(),
            ),
        )
    }

    fun listen(id: String, onChange: (DocumentSnapshot) -> Unit): ListenerRegistration =
        call(id).addSnapshotListener { snap, _ -> if (snap != null && snap.exists()) onChange(snap) }

    /** The other phone's network paths, as they arrive. */
    fun listenCandidates(id: String, onCandidate: (IceCandidate) -> Unit): ListenerRegistration =
        call(id).collection("candidates").addSnapshotListener { snap, _ ->
            snap?.documentChanges.orEmpty()
                .filter { it.type == DocumentChange.Type.ADDED }
                .map { it.document }
                .filter { it.getString("from") != myUid }
                .forEach { d ->
                    val sdp = d.getString("candidate") ?: return@forEach
                    onCandidate(IceCandidate(d.getString("sdpMid"), (d.getLong("sdpMLineIndex") ?: 0L).toInt(), sdp))
                }
        }

    /** Network relays: appConfig/webrtc { iceServers: [{urls:[…], username, credential}] }, else public STUN/TURN. */
    suspend fun iceServers(): List<PeerConnection.IceServer> {
        val configured = runCatching {
            @Suppress("UNCHECKED_CAST")
            (db.collection("appConfig").document("webrtc").get().await().get("iceServers") as? List<Map<String, Any?>>).orEmpty()
                .mapNotNull { m ->
                    val urls = when (val u = m["urls"]) {
                        is String -> listOf(u)
                        is List<*> -> u.filterIsInstance<String>()
                        else -> emptyList()
                    }
                    if (urls.isEmpty()) null else RtcSession.iceServer(urls, m["username"] as? String, m["credential"] as? String)
                }
        }.getOrDefault(emptyList())
        return configured.ifEmpty { DEFAULT_ICE }
    }

    companion object {
        const val CALLS = "videoCalls"
        const val STATUS_RINGING = "ringing"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_DECLINED = "declined"
        const val STATUS_ENDED = "ended"
        const val STATUS_MISSED = "missed"

        private val DEFAULT_ICE: List<PeerConnection.IceServer> by lazy {
            listOf(
                RtcSession.iceServer(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")),
                // Public test relay; put your own (e.g. a free metered.ca account) in appConfig/webrtc.
                RtcSession.iceServer(
                    listOf("turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443", "turn:openrelay.metered.ca:443?transport=tcp"),
                    "openrelayproject",
                    "openrelayproject",
                ),
            )
        }

        fun create(context: Context): VideoSignaling? {
            if (!context.isFirebaseAvailable()) return null
            val user = FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous } ?: return null
            return VideoSignaling(FirebaseFirestore.getInstance(), user.uid)
        }

        /** Saves this phone's push token so video calls can ring it. Safe to call often. */
        fun registerPushToken(context: Context) {
            if (!context.isFirebaseAvailable()) return
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> saveToken(token) }
        }

        fun saveToken(token: String) {
            val user = FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous } ?: return
            FirebaseFirestore.getInstance().collection("pushTokens").document(user.uid)
                .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp()))
        }

        private fun SessionDescription.toMap() = mapOf("type" to type.canonicalForm(), "sdp" to description)

        fun descriptionOf(map: Any?): SessionDescription? {
            val m = map as? Map<*, *> ?: return null
            val type = (m["type"] as? String)?.let { runCatching { SessionDescription.Type.fromCanonicalForm(it) }.getOrNull() } ?: return null
            val sdp = m["sdp"] as? String ?: return null
            return SessionDescription(type, sdp)
        }
    }
}
