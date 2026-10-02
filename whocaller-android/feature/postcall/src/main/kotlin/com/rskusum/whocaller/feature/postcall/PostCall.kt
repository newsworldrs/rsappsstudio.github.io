package com.rskusum.whocaller.feature.postcall

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.model.CallType
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.rskusum.whocaller.core.ui.R as UiR

/**
 * Decides whether to ask "Know this caller?" after an incoming call, and how:
 *  - only for numbers that aren't saved contacts and that the user hasn't reported yet,
 *  - only when the call was answered or declined (not missed or blocked),
 *  - at most once a week per number, and not again for 90 days after "Not spam / Skip".
 */
@Singleton
class PostCallCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val contacts: ContactsRepository,
    private val spam: SpamRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val countries: CountryRepository,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Outlives the in-call service, which Telecom unbinds as soon as the last call ends. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onIncomingCallEndedAsync(rawNumber: String?, answered: Boolean, declined: Boolean, canOpenScreen: Boolean) {
        scope.launch { onIncomingCallEnded(rawNumber, answered, declined, canOpenScreen) }
    }

    suspend fun keyFor(raw: String): String? =
        (normalizer.normalize(raw, countries.defaultRegion()) as? NormalizationResult.Parsed)?.number?.key

    /**
     * @param canOpenScreen true when called while WhoCaller's call screen is visible (it may then
     * open the post-call screen directly); otherwise only a notification is shown.
     */
    suspend fun onIncomingCallEnded(rawNumber: String?, answered: Boolean, declined: Boolean, canOpenScreen: Boolean) {
        if (!answered && !declined) return
        val raw = rawNumber?.takeIf { it.count(Char::isDigit) >= MIN_DIGITS } ?: return
        if (runCatching { settings.current() }.getOrNull()?.postCallPrompt != true) return
        val key = keyFor(raw) ?: return
        if (contacts.hasPermission() && runCatching { contacts.lookupContactName(raw) }.getOrNull() != null) return
        if (runCatching { spam.latestReportFor(key) }.getOrNull() != null) return
        val now = System.currentTimeMillis()
        if (!PostCallMemory.shouldAsk(prefs.getLong(key, 0L), now)) return
        remember(key, now + PostCallMemory.ASK_COOLDOWN_MS)

        PostCallNotifier.post(context, raw, answered)
        if (canOpenScreen) {
            runCatching { context.startActivity(PostCallActivity.intent(context, raw, answered).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** "Not spam / Skip": don't ask about this number for a long time. */
    fun skip(key: String) = remember(key, System.currentTimeMillis() + PostCallMemory.SKIP_COOLDOWN_MS)

    private fun remember(key: String, until: Long) {
        val editor = prefs.edit().putLong(key, until)
        // Keep the store small: drop entries whose quiet period is over.
        if (prefs.all.size > MAX_REMEMBERED) {
            val now = System.currentTimeMillis()
            prefs.all.forEach { (k, v) -> if ((v as? Long ?: 0L) < now) editor.remove(k) }
        }
        editor.apply()
    }

    private companion object {
        const val PREFS = "post_call_prompts"
        const val MIN_DIGITS = 7
        const val MAX_REMEMBERED = 500
    }
}

/** Pure timing rules (unit tested). Stored value = "don't ask before" time. */
object PostCallMemory {
    const val ASK_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
    const val SKIP_COOLDOWN_MS = 90L * 24 * 60 * 60 * 1000

    fun shouldAsk(quietUntil: Long, now: Long): Boolean = now >= quietUntil
}

object PostCallNotifier {
    fun id(number: String) = 0x5C00_0000 or (number.filter(Char::isDigit).hashCode() and 0xFFFF)

    fun post(context: Context, number: String, answered: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            id(number),
            PostCallActivity.intent(context, number, answered),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.POST_CALL)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(context.getString(R.string.postcall_title))
            .setContentText(context.getString(R.string.postcall_notification_text, number))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setTimeoutAfter(TimeUnit.HOURS.toMillis(12))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id(number), notification)
        } catch (_: SecurityException) {
            // Notifications not allowed.
        }
    }

    fun cancel(context: Context, number: String) = NotificationManagerCompat.from(context).cancel(id(number))
}

/**
 * When WhoCaller is not the phone app it can't see the call end. Call screening schedules this
 * worker; it waits until the call shows up in the call log (i.e. the call is over), then asks if it
 * was answered or declined.
 */
@HiltWorker
class PostCallWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val callLog: CallLogRepository,
    private val idReminder: WhoCallerIdReminder,
    private val coordinator: PostCallCoordinator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { idReminder.onCallEnded() }
        val number = inputData.getString(KEY_NUMBER) ?: return Result.success()
        val since = inputData.getLong(KEY_SINCE, 0L)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        if (!callLog.hasPermission()) return Result.success()
        val key = coordinator.keyFor(number) ?: return Result.success()
        val call = runCatching { callLog.callsForNumber(key, 3) }.getOrDefault(emptyList())
            .filter { it.timestamp >= since - SLACK_MS }
            .maxByOrNull { it.timestamp }
        if (call == null) {
            // Still ringing or talking: look again shortly.
            if (attempt < MAX_ATTEMPTS) enqueue(applicationContext, number, since, attempt + 1, RETRY_DELAY_S)
            return Result.success()
        }
        coordinator.onIncomingCallEnded(
            rawNumber = number,
            answered = call.type == CallType.INCOMING && call.durationSeconds > 0,
            declined = call.type == CallType.REJECTED,
            canOpenScreen = false,
        )
        return Result.success()
    }

    companion object {
        private const val KEY_NUMBER = "number"
        private const val KEY_SINCE = "since"
        private const val KEY_ATTEMPT = "attempt"
        private const val SLACK_MS = 15_000L
        private const val MAX_ATTEMPTS = 60
        private const val FIRST_DELAY_S = 20L
        private const val RETRY_DELAY_S = 30L

        /** Called by call screening for an incoming number that isn't a contact. */
        fun schedule(context: Context, number: String, screenedAt: Long = System.currentTimeMillis()) =
            enqueue(context, number, screenedAt, 0, FIRST_DELAY_S)

        private fun enqueue(context: Context, number: String, since: Long, attempt: Int, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<PostCallWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_NUMBER to number, KEY_SINCE to since, KEY_ATTEMPT to attempt))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("post_call_" + number.filter(Char::isDigit), ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * Users who skipped the WhoCaller ID screen are reminded after a call ends (a good moment: they just
 * used the phone), at most every [EVERY_DAYS] days, until they set it up.
 */
@Singleton
class WhoCallerIdReminder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: com.rskusum.whocaller.core.domain.repository.LocalProfileRepository,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("whocaller_id_reminder", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onCallEndedAsync() {
        scope.launch { runCatching { onCallEnded() } }
    }

    suspend fun onCallEnded() {
        val profile = profiles.profile.first()
        if (profile.isComplete || profile.idSetupSkippedAt == 0L) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < TimeUnit.DAYS.toMillis(EVERY_DAYS)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        prefs.edit().putLong(KEY_LAST, now).apply()
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("whocaller://profile/phone")).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.POST_CALL)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(context.getString(R.string.id_reminder_title))
            .setContentText(context.getString(R.string.id_reminder_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.id_reminder_text)))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed.
        }
    }

    private companion object {
        const val KEY_LAST = "last_reminder"
        const val EVERY_DAYS = 2L
        const val NOTIFICATION_ID = 0x5D00_0001
    }
}
