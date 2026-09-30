package com.rskusum.whocaller.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class NotificationCategory { CALLER_ALERTS, SPAM_ALERTS, SECURITY, GENERAL }

/** All user preferences. Defaults are privacy-preserving. */
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val permissionSetupCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Default region for numbers typed without a country code (ISO 3166-1 alpha-2). Null = from SIM/locale. */
    val defaultRegion: String? = null,

    // Protection
    val callerIdEnabled: Boolean = true,
    val spamProtectionEnabled: Boolean = true,
    /** Automatically reject calls whose score is VERY_HIGH. Off by default: users opt in. */
    val autoBlockHighRisk: Boolean = false,
    val blockUnknownCallers: Boolean = false,
    val blockHiddenNumbers: Boolean = false,
    val blockedCategories: Set<SpamCategory> = emptySet(),

    // Privacy
    val searchHistoryEnabled: Boolean = true,
    val personalizedRecommendations: Boolean = false,
    val analyticsEnabled: Boolean = false,
    val crashReportsEnabled: Boolean = false,
    val contactAccessEnabled: Boolean = true,

    // Notifications (in-app switches on top of the system channel settings)
    val disabledNotificationCategories: Set<NotificationCategory> = emptySet(),
) {
    fun isNotificationEnabled(category: NotificationCategory) = category !in disabledNotificationCategories
}
