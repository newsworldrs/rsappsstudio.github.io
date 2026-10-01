package com.rskusum.whocaller.play

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play In-App Review. Asks at a good moment only: the app has been used on several days,
 * at least [MIN_OPENS] times, and not asked in the last [ASK_EVERY_DAYS] days. Google also limits
 * how often the card really appears, and never says whether the user rated.
 */
@Singleton
class InAppReview @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("play_review", Context.MODE_PRIVATE)

    /** Call once per app open (main screen shown). */
    fun recordOpen() {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putInt(KEY_OPENS, prefs.getInt(KEY_OPENS, 0) + 1)
            .apply { if (!prefs.contains(KEY_FIRST)) putLong(KEY_FIRST, now) }
            .apply()
    }

    private fun due(): Boolean {
        val now = System.currentTimeMillis()
        val first = prefs.getLong(KEY_FIRST, now)
        val last = prefs.getLong(KEY_LAST_ASK, 0L)
        return prefs.getInt(KEY_OPENS, 0) >= MIN_OPENS &&
            now - first >= MIN_INSTALLED_DAYS * DAY_MS &&
            now - last >= ASK_EVERY_DAYS * DAY_MS
    }

    /** Shows Play's rating card if it's a good time. Never blocks or nags. */
    suspend fun maybeAsk(activity: Activity) {
        if (!due()) return
        prefs.edit().putLong(KEY_LAST_ASK, System.currentTimeMillis()).apply()
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReviewFlow().await()
            manager.launchReviewFlow(activity, info).await()
        }
    }

    private companion object {
        const val KEY_OPENS = "opens"
        const val KEY_FIRST = "first_open"
        const val KEY_LAST_ASK = "last_ask"
        const val MIN_OPENS = 5
        const val MIN_INSTALLED_DAYS = 3L
        const val ASK_EVERY_DAYS = 60L
        const val DAY_MS = 86_400_000L
    }
}

/**
 * Google Play In-App Updates. High-priority releases (priority 4–5 in the Play Developer API) are
 * installed right away (immediate); others download in the background (flexible) and the app
 * offers a restart once ready.
 */
class InAppUpdates(activity: Activity) {
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val _readyToInstall = MutableStateFlow(false)
    /** A flexible update finished downloading: show "Restart to update". */
    val readyToInstall: StateFlow<Boolean> = _readyToInstall.asStateFlow()

    private val listener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) _readyToInstall.value = true
    }

    fun register() = manager.registerListener(listener)
    fun unregister() = manager.unregisterListener(listener)

    /** Checks Play for an update. Call on start. */
    fun check(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@addOnSuccessListener
            val type = when {
                info.updatePriority() >= HIGH_PRIORITY && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
                else -> return@addOnSuccessListener
            }
            runCatching { manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build()) }
        }
    }

    /** Call on resume: finishes an interrupted immediate update, or re-shows "Restart to update". */
    fun resume(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) _readyToInstall.value = true
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                runCatching { manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()) }
            }
        }
    }

    /** Restarts the app into the downloaded version. */
    fun install() {
        _readyToInstall.value = false
        manager.completeUpdate()
    }

    private companion object {
        const val HIGH_PRIORITY = 4
    }
}

/**
 * Play Integrity (Standard API). Firebase App Check already uses Play Integrity to protect
 * Firestore; this gives a token for our own server checks, e.g. before trusting a purchase or a
 * burst of reports. The token must be decoded on a server (Google's decodeIntegrityToken API),
 * never on the phone.
 */
@Singleton
class PlayIntegrity @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    /** Google Cloud project number (from google-services.json), or 0 when Firebase isn't configured. */
    private val projectNumber: Long by lazy {
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier("gcm_defaultSenderId", "string", context.packageName)
        if (id == 0) 0L else context.getString(id).toLongOrNull() ?: 0L
    }

    /** Warms up the provider in the background (call once at app start). */
    suspend fun prepare() {
        if (provider != null || projectNumber == 0L) return
        provider = runCatching {
            IntegrityManagerFactory.createStandard(context)
                .prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(projectNumber).build(),
                )
                .await()
        }.getOrNull()
    }

    /** Integrity token bound to [requestHash] (a hash of the request being protected), or null. */
    suspend fun token(requestHash: String): String? {
        prepare()
        val p = provider ?: return null
        return runCatching {
            p.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        }.getOrNull()
    }
}

/**
 * Play Install Referrer: reads once, on first launch, which campaign or link brought the install
 * (utm_source / utm_medium / utm_campaign) and sends it to analytics. Nothing personal is read.
 */
@Singleton
class InstallReferrer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analytics: AnalyticsTracker,
) {
    private val prefs = context.getSharedPreferences("play_referrer", Context.MODE_PRIVATE)

    fun captureOnce() {
        if (prefs.getBoolean(KEY_DONE, false)) return
        val client = InstallReferrerClient.newBuilder(context).build()
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    when (code) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val raw = runCatching { client.installReferrer.installReferrer }.getOrNull().orEmpty()
                            val q = Uri.parse("https://x/?$raw")
                            val source = q.getQueryParameter("utm_source").orEmpty().take(MAX).ifEmpty { "unknown" }
                            prefs.edit().putBoolean(KEY_DONE, true).putString(KEY_SOURCE, source).apply()
                            analytics.track(
                                AnalyticsEvent.InstallSource(
                                    source,
                                    q.getQueryParameter("utm_medium").orEmpty().take(MAX),
                                    q.getQueryParameter("utm_campaign").orEmpty().take(MAX),
                                ),
                            )
                        }
                        // Not installed from Play (e.g. a test APK): nothing to read, don't retry.
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR,
                        -> prefs.edit().putBoolean(KEY_DONE, true).apply()
                        else -> Unit // try again next launch
                    }
                    runCatching { client.endConnection() }
                }

                override fun onInstallReferrerServiceDisconnected() = Unit
            })
        }
    }

    /** utm_source of this install, once known ("google-play" for organic Play installs). */
    val source: String? get() = prefs.getString(KEY_SOURCE, null)

    private companion object {
        const val KEY_DONE = "captured"
        const val KEY_SOURCE = "utm_source"
        const val MAX = 60
    }
}
