package com.rskusum.whocaller.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.rskusum.whocaller.MainActivity
import com.rskusum.whocaller.R
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.permissions.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home-screen widget: Search Number, most recent blocked spam, protection status. */
@AndroidEntryPoint
class WhoCallerWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var permissionManager: PermissionManager

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val lastBlocked = runCatching { blockRepository.observeBlockedCalls(1).firstOrNull()?.firstOrNull() }.getOrNull()
                val protectionActive = permissionManager.isCallerIdReady()
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, buildViews(context, protectionActive, lastBlocked?.displayNumber))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, protectionActive: Boolean, lastSpam: String?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_whocaller).apply {
            setTextViewText(
                R.id.widget_status,
                context.getString(if (protectionActive) R.string.widget_protection_active else R.string.widget_protection_setup),
            )
            setTextColor(
                R.id.widget_status,
                context.getColor(if (protectionActive) R.color.widget_active else R.color.widget_warning),
            )
            setTextViewText(
                R.id.widget_recent_spam,
                lastSpam?.let { "${context.getString(R.string.widget_recent_spam)}: $it" }
                    ?: context.getString(R.string.widget_no_recent_spam),
            )
            setOnClickPendingIntent(R.id.widget_search, deepLink(context, "search", 1))
            setOnClickPendingIntent(R.id.widget_recent_spam, deepLink(context, "blocked", 2))
            setOnClickPendingIntent(R.id.widget_status, deepLink(context, "protection", 3))
            setOnClickPendingIntent(R.id.widget_title, deepLink(context, "home", 4))
        }

    private fun deepLink(context: Context, host: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW, Uri.parse("whocaller://$host"), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Call after protection status or blocked calls change. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WhoCallerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, WhoCallerWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
