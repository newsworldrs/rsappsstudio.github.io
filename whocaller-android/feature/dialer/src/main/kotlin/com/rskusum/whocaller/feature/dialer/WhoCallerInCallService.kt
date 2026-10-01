package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

/**
 * Bound by Telecom while WhoCaller is the default phone app. For every incoming call it shows
 * "Incoming call from <name>" — contact name, else WhoCaller's name, else the number with any spam
 * warning — while the phone rings, and hosts the in-call screen.
 */
@AndroidEntryPoint
class WhoCallerInCallService : InCallService() {

    @Inject lateinit var identification: CallerIdentificationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        CallManager.attach(this)
        observer = scope.launch {
            CallManager.calls.collect { CallNotifications.update(this@WhoCallerInCallService, it) }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        CallManager.add(call, CallerDisplayFormatter.initial(this, number))

        if (CallManager.stateOf(call) != Call.STATE_RINGING) {
            // Outgoing call: show the call screen straight away.
            startActivity(InCallActivity.intent(this))
        }
        scope.launch {
            val result = withTimeoutOrNull(IDENTIFY_BUDGET_MS) {
                identification.identify(number, networkBudgetMs = NETWORK_BUDGET_MS, record = false)
            }
            if (result != null) CallManager.setDisplay(call, CallerDisplayFormatter.from(this@WhoCallerInCallService, number, result))
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.remove(call)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.onAudioState(audioState)
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        CallNotifications.cancelAll(this)
        CallManager.detach()
        super.onDestroy()
    }

    private companion object {
        const val IDENTIFY_BUDGET_MS = 3_000L
        const val NETWORK_BUDGET_MS = 2_000L
    }
}

/** Incoming (full-screen, CallStyle) and ongoing call notifications. */
internal object CallNotifications {
    private const val INCOMING_ID = 4201
    private const val ONGOING_ID = 4202

    fun update(context: Context, calls: List<CallUi>) {
        val ringing = calls.firstOrNull { it.isRinging }
        val ongoing = calls.firstOrNull { !it.isRinging && it.state != Call.STATE_DISCONNECTED }
        val manager = NotificationManagerCompat.from(context)
        if (ringing == null) manager.cancel(INCOMING_ID) else notify(context, INCOMING_ID, incoming(context, ringing))
        if (ongoing == null) manager.cancel(ONGOING_ID) else notify(context, ONGOING_ID, ongoing(context, ongoing))
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_ID)
        NotificationManagerCompat.from(context).cancel(ONGOING_ID)
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission revoked mid-call.
        }
    }

    private fun incoming(context: Context, call: CallUi): android.app.Notification {
        val d = call.display
        val title = context.getString(
            if (call.incomingVideo) R.string.call_incoming_video_from else R.string.call_incoming_from,
            d.title,
        )
        val person = Person.Builder().setName(d.title).setImportant(true).build()
        val fullScreen = PendingIntent.getActivity(
            context, 10, InCallActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answer = PendingIntent.getActivity(
            context, 11, InCallActivity.intent(context).setAction(InCallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_DECLINE, 12)
        return NotificationCompat.Builder(context, NotificationChannels.INCOMING_CALLS)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(title)
            .setContentText(listOfNotNull(d.number, d.label).joinToString(" · "))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setColorized(d.warning)
            .setColor(if (d.warning) 0xFFB3261E.toInt() else 0xFF006A62.toInt())
            .build()
    }

    private fun ongoing(context: Context, call: CallUi): android.app.Notification {
        val open = PendingIntent.getActivity(
            context, 20, InCallActivity.intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_HANG_UP, 21)
        return NotificationCompat.Builder(context, NotificationChannels.ONGOING_CALLS)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(context.getString(R.string.call_ongoing, call.display.title))
            .setContentText(call.display.label)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(call.connectTimeMillis > 0)
            .setWhen(if (call.connectTimeMillis > 0) call.connectTimeMillis else System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.call_end), hangUp)
            .build()
    }
}

/** Decline / hang up from a notification without opening the app. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = CallManager.primary()?.call ?: return
        when (intent.action) {
            ACTION_DECLINE -> CallManager.decline(call)
            ACTION_HANG_UP -> CallManager.hangUp(call)
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.rskusum.whocaller.action.DECLINE"
        const val ACTION_HANG_UP = "com.rskusum.whocaller.action.HANG_UP"

        fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, CallActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
