package com.rskusum.whocaller.core.ui.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.rskusum.whocaller.core.ui.R
import java.util.Calendar
import java.util.Date

/** "2 hours ago", "Yesterday", … localized by the platform. */
fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        .toString()

/** "Today, 3:42 PM", "Yesterday, 10:12 AM" or a localized date for older calls. */
fun callTime(context: Context, timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val time = DateFormat.getTimeFormat(context).format(Date(timestamp))
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val dayDiff = today.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> context.getString(R.string.time_today, time)
        sameYear && dayDiff == 1 -> context.getString(R.string.time_yesterday, time)
        else -> DateUtils.formatDateTime(
            context,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
        )
    }
}

fun formatDuration(seconds: Long): String = DateUtils.formatElapsedTime(seconds)
