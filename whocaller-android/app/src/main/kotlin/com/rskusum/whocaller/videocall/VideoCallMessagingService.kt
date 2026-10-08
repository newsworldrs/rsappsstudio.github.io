package com.rskusum.whocaller.videocall

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rskusum.whocaller.R
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import com.rskusum.whocaller.core.ui.R as UiR

/**
 * WHOCALLER VIDEO (experimental): receives "someone is video calling you" pushes and shows the
 * incoming call (full-screen when the phone is locked, heads-up otherwise).
 */
class VideoCallMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) = VideoSignaling.saveToken(token)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "video_call") return
        val callId = data["callId"] ?: return
        val name = data["callerName"].orEmpty().ifBlank { data["callerNumber"].orEmpty() }
        showIncoming(callId, name)
    }

    private fun showIncoming(callId: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 51, VideoCallActivity.incoming(this, callId), flags)
        val accept = PendingIntent.getActivity(this, 52, VideoCallActivity.incoming(this, callId, accept = true), flags)
        val decline = PendingIntent.getActivity(this, 53, VideoCallActivity.incoming(this, callId, decline = true), flags)
        val person = Person.Builder().setName(name.ifBlank { getString(R.string.vc_someone) }).setImportant(true).build()
        val notification = NotificationCompat.Builder(this, NotificationChannels.INCOMING_CALLS)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(getString(R.string.vc_incoming_title, name))
            .setContentText(getString(R.string.vc_incoming))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, accept).setIsVideo(true))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_MS)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val NOTIFICATION_ID = 0x5E00_0001
        private const val RING_MS = 45_000L
    }
}
