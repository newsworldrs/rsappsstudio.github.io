package com.rskusum.whocaller.core.permissions

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes

/**
 * Features that need a permission or role. Each is requested on its own, only when the user taps
 * "Allow" – never all at once – and every feature degrades gracefully without it.
 */
enum class AppPermission(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    @StringRes val explanationRes: Int,
    @StringRes val deniedFallbackRes: Int,
    /** Stable, non-sensitive name used for analytics. */
    val analyticsName: String,
) {
    /** API 29+: call-screening role. API 26–28: phone state (+ call log on 28 to read the number). */
    CALLER_ID(
        R.string.perm_caller_id_title,
        R.string.perm_caller_id_summary,
        R.string.perm_caller_id_explain,
        R.string.perm_caller_id_denied,
        "caller_id",
    ),
    CALL_HISTORY(
        R.string.perm_call_history_title,
        R.string.perm_call_history_summary,
        R.string.perm_call_history_explain,
        R.string.perm_call_history_denied,
        "call_log",
    ),
    CONTACTS(
        R.string.perm_contacts_title,
        R.string.perm_contacts_summary,
        R.string.perm_contacts_explain,
        R.string.perm_contacts_denied,
        "contacts",
    ),
    /** Place calls/video calls directly from WhoCaller and check whether the SIM supports video calling. */
    PHONE_CALLS(
        R.string.perm_phone_title,
        R.string.perm_phone_summary,
        R.string.perm_phone_explain,
        R.string.perm_phone_denied,
        "phone_calls",
    ),
    NOTIFICATIONS(
        R.string.perm_notifications_title,
        R.string.perm_notifications_summary,
        R.string.perm_notifications_explain,
        R.string.perm_notifications_denied,
        "notifications",
    ),
    ;

    /** Runtime permissions to request for this feature on the current OS (empty = role or nothing needed). */
    val runtimePermissions: Array<String>
        get() = when (this) {
            CALLER_ID -> when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> emptyArray()
                Build.VERSION.SDK_INT == Build.VERSION_CODES.P ->
                    arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
                else -> arrayOf(Manifest.permission.READ_PHONE_STATE)
            }
            CALL_HISTORY -> arrayOf(Manifest.permission.READ_CALL_LOG)
            CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
            PHONE_CALLS -> arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        }

    /** True when this feature is granted through the call-screening role instead of runtime permissions. */
    val usesCallScreeningRole: Boolean
        get() = this == CALLER_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}

enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED, NOT_REQUESTED }
