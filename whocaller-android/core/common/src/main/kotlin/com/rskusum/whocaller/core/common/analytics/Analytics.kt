package com.rskusum.whocaller.core.common.analytics

/**
 * Analytics events. Parameters are deliberately coarse: never phone numbers, names, contacts,
 * message content or anything that identifies a person.
 */
sealed class AnalyticsEvent(val name: String, val params: Map<String, String> = emptyMap()) {
    data object AppOpened : AnalyticsEvent("app_opened")
    data class NumberSearched(val found: Boolean) :
        AnalyticsEvent("number_searched", mapOf("found" to found.toString()))
    data class CallerIdentified(val label: String) :
        AnalyticsEvent("caller_identified", mapOf("label" to label))
    data class SpamDetected(val riskLevel: String) :
        AnalyticsEvent("spam_detected", mapOf("risk" to riskLevel))
    data class NumberReported(val reason: String) :
        AnalyticsEvent("number_reported", mapOf("reason" to reason))
    data object NumberBlocked : AnalyticsEvent("number_blocked")
    data class PermissionGranted(val permission: String) :
        AnalyticsEvent("permission_granted", mapOf("permission" to permission))
    data class PermissionDenied(val permission: String) :
        AnalyticsEvent("permission_denied", mapOf("permission" to permission))
    data object PremiumViewed : AnalyticsEvent("premium_viewed")
    data class SubscriptionStarted(val productId: String) :
        AnalyticsEvent("subscription_started", mapOf("product" to productId))
}

/** Single entry point for analytics; implementations must honour the user's opt-in. */
interface AnalyticsTracker {
    fun track(event: AnalyticsEvent)
    fun setEnabled(enabled: Boolean)
}

/** Non-fatal error reporting. Keys and messages must never contain user data. */
interface CrashReporter {
    fun recordNonFatal(throwable: Throwable, context: String)
    fun log(message: String)
    fun setEnabled(enabled: Boolean)
}

object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
}

object NoOpCrashReporter : CrashReporter {
    override fun recordNonFatal(throwable: Throwable, context: String) = Unit
    override fun log(message: String) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
}
