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
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    @Inject lateinit var smsRepository: SmsRepository
    @Inject lateinit var identification: CallerIdentificationManager

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
                    // Automatic check, in the background, the moment the message arrives:
                    // sender (contact / "Not spam" / WhoCaller community data) + wording.
                    val contactName = runCatching { contactsRepository.lookupContactName(address) }.getOrNull()
                    val trusted = runCatching { smsRepository.isMarkedNotSpam(address) }.getOrDefault(false)
                    val community = if (contactName == null && !trusted && address.count { it.isDigit() } >= 7) {
                        val result = withTimeoutOrNull(LOOKUP_BUDGET_MS) {
                            runCatching { identification.identify(address, networkBudgetMs = LOOKUP_BUDGET_MS, record = false) }.getOrNull()
                        }
                        result != null && result.spamScore.score >= COMMUNITY_SPAM_SCORE && result.spamScore.category.isUnwanted
                    } else {
                        false
                    }
                    val classification = classifier.classify(
                        address,
                        body,
                        senderReported = community,
                        senderIsContact = contactName != null,
                        senderTrusted = trusted,
                    )
                    val name = contactName ?: address
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
        const val LOOKUP_BUDGET_MS = 3_000L
        const val COMMUNITY_SPAM_SCORE = 50
    }
}

/** "Not spam" / "Block" buttons on a suspected-spam notification. */
@AndroidEntryPoint
class SmsSpamActionReceiver : BroadcastReceiver() {

    @Inject lateinit var smsRepository: SmsRepository
    @Inject lateinit var blockNumber: BlockNumberUseCase

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        NotificationManagerCompat.from(context).cancel(id)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_NOT_SPAM -> smsRepository.markNotSpam(address)
                    ACTION_BLOCK -> blockNumber.block(address)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NOT_SPAM = "com.rskusum.whocaller.sms.NOT_SPAM"
        const val ACTION_BLOCK = "com.rskusum.whocaller.sms.BLOCK"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun intent(context: Context, action: String, address: String, notificationId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId * 2 + if (action == ACTION_BLOCK) 1 else 0,
                Intent(context, SmsSpamActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
        val builder = NotificationCompat.Builder(context, if (warning) NotificationChannels.SPAM_MESSAGES else NotificationChannels.MESSAGES)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (warning) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setColor(if (warning) 0xFFB3261E.toInt() else 0xFF006A62.toInt())
            .setContentIntent(open)
        if (warning && address != null) {
            builder.addAction(0, context.getString(R.string.sms_not_spam), SmsSpamActionReceiver.intent(context, SmsSpamActionReceiver.ACTION_NOT_SPAM, address, id))
            builder.addAction(0, context.getString(R.string.sms_block), SmsSpamActionReceiver.intent(context, SmsSpamActionReceiver.ACTION_BLOCK, address, id))
        }
        val notification = builder.build()
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
