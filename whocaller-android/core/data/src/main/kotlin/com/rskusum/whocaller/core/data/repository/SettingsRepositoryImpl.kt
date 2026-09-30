package com.rskusum.whocaller.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rskusum.whocaller.core.data.mapper.enumOrNull
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.NotificationCategory
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(read(prefs))
            write(prefs, next)
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingCompleted = p[Keys.ONBOARDING] ?: d.onboardingCompleted,
            permissionSetupCompleted = p[Keys.PERMISSION_SETUP] ?: d.permissionSetupCompleted,
            themeMode = enumOrNull<ThemeMode>(p[Keys.THEME]) ?: d.themeMode,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: d.dynamicColor,
            defaultRegion = p[Keys.REGION]?.takeIf { it.length == 2 },
            callerIdEnabled = p[Keys.CALLER_ID] ?: d.callerIdEnabled,
            spamProtectionEnabled = p[Keys.SPAM_PROTECTION] ?: d.spamProtectionEnabled,
            autoBlockHighRisk = p[Keys.AUTO_BLOCK] ?: d.autoBlockHighRisk,
            blockUnknownCallers = p[Keys.BLOCK_UNKNOWN] ?: d.blockUnknownCallers,
            blockHiddenNumbers = p[Keys.BLOCK_HIDDEN] ?: d.blockHiddenNumbers,
            blockedCategories = p[Keys.BLOCKED_CATEGORIES]?.mapNotNull { enumOrNull<SpamCategory>(it) }?.toSet()
                ?: d.blockedCategories,
            searchHistoryEnabled = p[Keys.SEARCH_HISTORY] ?: d.searchHistoryEnabled,
            personalizedRecommendations = p[Keys.PERSONALIZED] ?: d.personalizedRecommendations,
            analyticsEnabled = p[Keys.ANALYTICS] ?: d.analyticsEnabled,
            crashReportsEnabled = p[Keys.CRASH_REPORTS] ?: d.crashReportsEnabled,
            contactAccessEnabled = p[Keys.CONTACT_ACCESS] ?: d.contactAccessEnabled,
            disabledNotificationCategories = p[Keys.DISABLED_NOTIFICATIONS]
                ?.mapNotNull { enumOrNull<NotificationCategory>(it) }?.toSet() ?: d.disabledNotificationCategories,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        p[Keys.ONBOARDING] = s.onboardingCompleted
        p[Keys.PERMISSION_SETUP] = s.permissionSetupCompleted
        p[Keys.THEME] = s.themeMode.name
        p[Keys.DYNAMIC_COLOR] = s.dynamicColor
        if (s.defaultRegion == null) p.remove(Keys.REGION) else p[Keys.REGION] = s.defaultRegion
        p[Keys.CALLER_ID] = s.callerIdEnabled
        p[Keys.SPAM_PROTECTION] = s.spamProtectionEnabled
        p[Keys.AUTO_BLOCK] = s.autoBlockHighRisk
        p[Keys.BLOCK_UNKNOWN] = s.blockUnknownCallers
        p[Keys.BLOCK_HIDDEN] = s.blockHiddenNumbers
        p[Keys.BLOCKED_CATEGORIES] = s.blockedCategories.map { it.name }.toSet()
        p[Keys.SEARCH_HISTORY] = s.searchHistoryEnabled
        p[Keys.PERSONALIZED] = s.personalizedRecommendations
        p[Keys.ANALYTICS] = s.analyticsEnabled
        p[Keys.CRASH_REPORTS] = s.crashReportsEnabled
        p[Keys.CONTACT_ACCESS] = s.contactAccessEnabled
        p[Keys.DISABLED_NOTIFICATIONS] = s.disabledNotificationCategories.map { it.name }.toSet()
    }

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val PERMISSION_SETUP = booleanPreferencesKey("permission_setup_completed")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val REGION = stringPreferencesKey("default_region")
        val CALLER_ID = booleanPreferencesKey("caller_id_enabled")
        val SPAM_PROTECTION = booleanPreferencesKey("spam_protection_enabled")
        val AUTO_BLOCK = booleanPreferencesKey("auto_block_high_risk")
        val BLOCK_UNKNOWN = booleanPreferencesKey("block_unknown")
        val BLOCK_HIDDEN = booleanPreferencesKey("block_hidden")
        val BLOCKED_CATEGORIES = stringSetPreferencesKey("blocked_categories")
        val SEARCH_HISTORY = booleanPreferencesKey("search_history_enabled")
        val PERSONALIZED = booleanPreferencesKey("personalized_recommendations")
        val ANALYTICS = booleanPreferencesKey("analytics_enabled")
        val CRASH_REPORTS = booleanPreferencesKey("crash_reports_enabled")
        val CONTACT_ACCESS = booleanPreferencesKey("contact_access_enabled")
        val DISABLED_NOTIFICATIONS = stringSetPreferencesKey("disabled_notification_categories")
    }
}
