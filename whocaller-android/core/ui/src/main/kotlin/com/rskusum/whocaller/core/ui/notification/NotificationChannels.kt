package com.rskusum.whocaller.core.ui.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.rskusum.whocaller.core.model.NotificationCategory
import com.rskusum.whocaller.core.ui.R

/** The four notification channels. Users can switch each one off here or in system settings. */
object NotificationChannels {
    const val CALLER_ALERTS = "caller_alerts"
    const val SPAM_ALERTS = "spam_alerts"
    const val SECURITY = "security"
    const val GENERAL = "general"

    fun idFor(category: NotificationCategory): String = when (category) {
        NotificationCategory.CALLER_ALERTS -> CALLER_ALERTS
        NotificationCategory.SPAM_ALERTS -> SPAM_ALERTS
        NotificationCategory.SECURITY -> SECURITY
        NotificationCategory.GENERAL -> GENERAL
    }

    fun createAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            channel(context, CALLER_ALERTS, R.string.channel_caller_alerts, R.string.channel_caller_alerts_desc, NotificationManager.IMPORTANCE_HIGH),
            channel(context, SPAM_ALERTS, R.string.channel_spam_alerts, R.string.channel_spam_alerts_desc, NotificationManager.IMPORTANCE_HIGH),
            channel(context, SECURITY, R.string.channel_security, R.string.channel_security_desc, NotificationManager.IMPORTANCE_DEFAULT),
            channel(context, GENERAL, R.string.channel_general, R.string.channel_general_desc, NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannels(channels)
    }

    private fun channel(context: Context, id: String, name: Int, description: Int, importance: Int) =
        NotificationChannel(id, context.getString(name), importance).apply {
            this.description = context.getString(description)
            setShowBadge(id != GENERAL)
        }

    fun channelSettingsIntent(context: Context, category: NotificationCategory): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, idFor(category))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
