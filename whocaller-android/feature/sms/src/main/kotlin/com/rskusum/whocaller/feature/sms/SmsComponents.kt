package com.rskusum.whocaller.feature.sms

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

/**
 * Receives SMS while WhoCaller is the default SMS app. The default app must store each message in
 * the Telephony provider itself; messages are never deleted or hidden, only labelled.
 */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {

    @Inject lateinit var classifier: SmsClassifier
    @Inject lateinit var contactsRepository: ContactsRepository
    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var normalizer: PhoneNumberNormalizer
    @Inject lateinit var countryRepository: CountryRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                // Multi-part messages arrive as several PDUs from the same sender.
                parts.groupBy { it.displayOriginatingAddress.orEmpty() }.forEach { (address, pdus) ->
                    val body = pdus.joinToString("") { it.displayMessageBody.orEmpty() }
                    val timestamp = pdus.first().timestampMillis
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, address)
                        put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, System.currentTimeMillis())
                        put(Telephony.Sms.DATE_SENT, timestamp)
                        put(Telephony.Sms.READ, 0)
                        put(Telephony.Sms.SEEN, 0)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                        put(Telephony.Sms.SUBSCRIPTION_ID, intent.getIntExtra("subscription", -1))
                    }
                    context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)

                    val key = normalizer.keyOf(address, countryRepository.defaultRegion())
                    val blocked = key != null && blockRepository.isBlocked(key)
                    if (blocked) return@forEach // Stored, but no alert for numbers the user blocked.
                    val classification = classifier.classify(address, body)
                    val name = runCatching { contactsRepository.lookupContactName(address) }.getOrNull() ?: address
                    val threadId = runCatching { Telephony.Threads.getOrCreateThreadId(context, address) }.getOrDefault(0L)
                    val suspicious = classification.category == SmsCategory.SCAM || classification.category == SmsCategory.SPAM
                    SmsNotifications.show(
                        context = context,
                        id = (threadId % Int.MAX_VALUE).toInt(),
                        title = context.getString(if (suspicious) R.string.sms_suspicious_from else R.string.sms_from, name),
                        text = if (suspicious) context.getString(R.string.sms_warning_short) + " · " + body else body,
                        threadId = threadId,
                        address = address,
                        warning = suspicious,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** MMS push while default SMS app: WhoCaller can't download picture messages yet, so it tells the user. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        SmsNotifications.show(
            context = context,
            id = MMS_NOTIFICATION_ID,
            title = context.getString(R.string.sms_mms_received, context.getString(UiR.string.unknown_number)),
            text = context.getString(R.string.sms_mms_unsupported),
            threadId = 0,
            address = null,
            warning = false,
        )
    }

    private companion object {
        const val MMS_NOTIFICATION_ID = 5100
    }
}

/** Handles "Reply with message" from the incoming-call screen. */
@AndroidEntryPoint
class HeadlessSmsSendService : Service() {

    @Inject lateinit var smsRepository: SmsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val recipients = intent.data?.schemeSpecificPart.orEmpty().split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            scope.launch {
                if (text.isNotBlank()) recipients.forEach { smsRepository.send(it, text) }
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

internal object SmsNotifications {
    fun show(
        context: Context,
        id: Int,
        title: String,
        text: String,
        threadId: Long,
        address: String?,
        warning: Boolean,
    ) {
        val uri = if (threadId > 0 && address != null) {
            Uri.parse("whocaller://sms/$threadId?address=" + Uri.encode(address))
        } else {
            Uri.parse("whocaller://messages")
        }
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setColor(if (warning) 0xFFB3261E.toInt() else 0xFF006A62.toInt())
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Notifications disabled.
        }
    }
}
