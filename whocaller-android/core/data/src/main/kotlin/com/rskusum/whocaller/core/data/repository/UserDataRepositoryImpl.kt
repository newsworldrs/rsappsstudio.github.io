package com.rskusum.whocaller.core.data.repository

import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.database.WhoCallerDatabase
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.UserDataRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.security.SecureStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Export my data" and "Clear local data". The export contains only what WhoCaller itself stores;
 * the system call log and contacts belong to the phone and are not copied.
 */
@Singleton
class UserDataRepositoryImpl @Inject constructor(
    private val db: WhoCallerDatabase,
    private val settingsRepository: SettingsRepository,
    private val secureStorage: SecureStorage,
    @IoDispatcher private val io: CoroutineDispatcher,
) : UserDataRepository {

    override suspend fun exportAsJson(): String = withContext(io) {
        val s = settingsRepository.current()
        val root = JSONObject()
        root.put("format", "whocaller-export")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "settings",
            JSONObject()
                .put("themeMode", s.themeMode.name)
                .put("defaultRegion", s.defaultRegion ?: JSONObject.NULL)
                .put("callerIdEnabled", s.callerIdEnabled)
                .put("spamProtectionEnabled", s.spamProtectionEnabled)
                .put("autoBlockHighRisk", s.autoBlockHighRisk)
                .put("blockUnknownCallers", s.blockUnknownCallers)
                .put("blockHiddenNumbers", s.blockHiddenNumbers)
                .put("blockedCategories", JSONArray(s.blockedCategories.map { it.name }))
                .put("searchHistoryEnabled", s.searchHistoryEnabled)
                .put("analyticsEnabled", s.analyticsEnabled)
                .put("crashReportsEnabled", s.crashReportsEnabled),
        )
        root.put(
            "blockedNumbers",
            JSONArray(
                db.blockedNumberDao().getAll().map {
                    JSONObject().put("number", it.numberKey).put("label", it.label ?: JSONObject.NULL)
                        .put("category", it.category ?: JSONObject.NULL).put("blockedAt", it.blockedAt)
                },
            ),
        )
        root.put(
            "reports",
            JSONArray(
                db.spamReportDao().getAll().map {
                    JSONObject().put("number", it.numberKey).put("reason", it.reason)
                        .put("comment", it.comment ?: JSONObject.NULL).put("createdAt", it.createdAt)
                        .put("syncState", it.syncState)
                },
            ),
        )
        root.put(
            "searchHistory",
            JSONArray(
                db.searchHistoryDao().getAll().map {
                    JSONObject().put("number", it.numberKey).put("searchedAt", it.searchedAt)
                },
            ),
        )
        root.put(
            "screenedCalls",
            JSONArray(
                db.callHistoryDao().getAll().map {
                    JSONObject().put("number", it.numberKey).put("label", it.label)
                        .put("decision", it.decision).put("timestamp", it.timestamp)
                },
            ),
        )
        root.toString(2)
    }

    override suspend fun clearLocalData() = withContext(io) {
        db.clearAllTables()
        secureStorage.clear()
        // Keep onboarding state so the user isn't sent through onboarding again; reset everything else.
        settingsRepository.update { current ->
            AppSettings(
                onboardingCompleted = current.onboardingCompleted,
                permissionSetupCompleted = current.permissionSetupCompleted,
            )
        }
    }
}
