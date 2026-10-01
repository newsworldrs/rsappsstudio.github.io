package com.rskusum.whocaller.feature.callerid

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.CallerResult
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.model.NotificationCategory
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import com.rskusum.whocaller.core.ui.util.labelRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.rskusum.whocaller.core.ui.R as UiR

/** What a caller notification says. Separated from Android so the wording logic is testable. */
data class CallerAlert(
    val category: NotificationCategory,
    val title: String,
    val text: String,
    val numberKey: String?,
)

/**
 * Builds and posts caller-ID notifications. Notifications are the Play-compliant way to show caller
 * information during a call; WhoCaller does not draw over other apps.
 */
@Singleton
class CallerAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The network couldn't verify the caller ID (STIR/SHAKEN): the shown number may be fake. */
    fun spoofAlert(result: CallerResult): CallerAlert {
        val who = result.number?.display ?: context.getString(R.string.callerid_private)
        return CallerAlert(
            NotificationCategory.SPAM_ALERTS,
            context.getString(R.string.callerid_incoming_from, who),
            context.getString(R.string.callerid_spoofed),
            result.number?.key,
        )
    }

    /** Returns null when there's nothing worth interrupting the user for. */
    fun buildAlert(result: CallerResult): CallerAlert? {
        val key = result.number?.key
        val categoryName = context.getString(result.spamScore.category.labelRes())
        val reports = result.info?.reportCount ?: 0

        if (result.decision == CallDecision.BLOCK) {
            val text = when (result.reason) {
                DecisionReason.HIDDEN_NUMBER -> context.getString(R.string.callerid_blocked_hidden)
                DecisionReason.UNKNOWN_CALLER -> context.getString(R.string.callerid_blocked_unknown)
                DecisionReason.USER_BLOCK_LIST -> context.getString(R.string.callerid_blocked_list)
                else -> listOfNotNull(result.number?.display, categoryName).joinToString(" · ")
            }
            return CallerAlert(NotificationCategory.SPAM_ALERTS, context.getString(R.string.callerid_blocked_title), text, key)
        }

        // "Incoming call from …": contact name, else WhoCaller's name, else the number.
        val who = result.displayName ?: result.number?.display ?: context.getString(R.string.callerid_private)
        val title = context.getString(R.string.callerid_incoming_from, who)
        val numberLine = result.number?.display?.takeIf { it != who }
        val reportsText = if (reports > 0) context.resources.getQuantityString(R.plurals.callerid_reported_by, reports, reports) else null
        // Outside spam list only: a softer warning that says where it comes from.
        if (result.label == CallerLabel.SUSPECTED_SPAM && result.info?.flaggedOnlyByList == true) {
            return CallerAlert(
                NotificationCategory.SPAM_ALERTS,
                title,
                listOfNotNull(
                    context.getString(com.rskusum.whocaller.core.ui.R.string.label_possible_spam_list),
                    context.getString(com.rskusum.whocaller.core.ui.R.string.label_flagged_by_list, result.info?.listedBy.orEmpty()),
                    numberLine,
                ).joinToString(" · "),
                key,
            )
        }
        return when (result.label) {
            CallerLabel.POSSIBLE_SCAM -> CallerAlert(
                NotificationCategory.SPAM_ALERTS,
                title,
                listOfNotNull(context.getString(R.string.callerid_possible_scam), context.getString(R.string.callerid_scam_detail), numberLine)
                    .joinToString(" · "),
                key,
            )
            CallerLabel.SUSPECTED_SPAM, CallerLabel.TELEMARKETING -> CallerAlert(
                NotificationCategory.SPAM_ALERTS,
                title,
                listOfNotNull(
                    context.getString(R.string.callerid_suspected_spam),
                    categoryName.takeIf { result.spamScore.category != SpamCategory.UNKNOWN },
                    reportsText,
                    numberLine,
                ).joinToString(" · "),
                key,
            )
            CallerLabel.VERIFIED_BUSINESS -> CallerAlert(
                NotificationCategory.CALLER_ALERTS,
                title,
                listOfNotNull(
                    context.getString(R.string.callerid_verified_business),
                    result.info?.category?.takeIf { it != SpamCategory.BUSINESS && it != SpamCategory.UNKNOWN }
                        ?.let { context.getString(R.string.callerid_category, context.getString(it.labelRes())) },
                    numberLine,
                ).joinToString(" · "),
                key,
            )
            CallerLabel.BUSINESS, CallerLabel.PERSON -> CallerAlert(
                NotificationCategory.CALLER_ALERTS,
                title,
                listOfNotNull(context.getString(R.string.callerid_identified_by), numberLine).joinToString(" · "),
                key,
            )
            CallerLabel.CONTACT -> CallerAlert(NotificationCategory.CALLER_ALERTS, title, numberLine.orEmpty(), key)
            CallerLabel.UNKNOWN -> CallerAlert(
                NotificationCategory.CALLER_ALERTS,
                title,
                context.getString(R.string.callerid_not_in_contacts),
                key,
            )
            CallerLabel.HIDDEN -> CallerAlert(NotificationCategory.CALLER_ALERTS, title, context.getString(R.string.callerid_hidden_detail), key)
        }
    }

    fun post(alert: CallerAlert, disabled: Set<NotificationCategory>) {
        if (alert.category in disabled) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, NotificationChannels.idFor(alert.category))
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setSubText(context.getString(UiR.string.core_app_name))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            // Caller alerts are only relevant while the phone rings.
            .setTimeoutAfter(if (alert.category == NotificationCategory.CALLER_ALERTS) 60_000L else 6 * 60 * 60_000L)
            .setContentIntent(detailsIntent(alert.numberKey))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(alert.category.ordinal + NOTIFICATION_ID_BASE, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun detailsIntent(numberKey: String?): PendingIntent {
        val uri = if (numberKey != null && numberKey.startsWith("+")) {
            Uri.parse("whocaller://number/" + Uri.encode(numberKey))
        } else {
            Uri.parse("whocaller://blocked")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val NOTIFICATION_ID_BASE = 4100
    }
}
