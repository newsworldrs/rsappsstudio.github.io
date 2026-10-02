package com.rskusum.whocaller

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.rskusum.whocaller.core.model.ThemeMode
import com.rskusum.whocaller.core.permissions.PermissionManager
import com.rskusum.whocaller.core.permissions.PermissionSetupScreen
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.feature.premium.AdsManager
import com.rskusum.whocaller.navigation.Routes
import com.rskusum.whocaller.navigation.WhoCallerApp
import com.rskusum.whocaller.navigation.navigateTopLevel
import com.rskusum.whocaller.ui.OnboardingScreen
import com.rskusum.whocaller.ui.SplashContent
import com.rskusum.whocaller.widget.WhoCallerWidgetProvider
import com.rskusum.whocaller.feature.profile.CompleteProfileScreen
import com.rskusum.whocaller.feature.profile.SignInConfig
import com.rskusum.whocaller.feature.profile.SignInScreen
import com.rskusum.whocaller.navigation.defaultWebClientId
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.rskusum.whocaller.play.InAppReview
import com.rskusum.whocaller.play.InAppUpdates
import com.rskusum.whocaller.play.InstallReferrer
import com.rskusum.whocaller.play.PlayIntegrity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var permissionManager: PermissionManager
    @Inject lateinit var adsManager: AdsManager
    @Inject lateinit var inAppReview: InAppReview
    @Inject lateinit var playIntegrity: PlayIntegrity
    @Inject lateinit var installReferrer: InstallReferrer

    private val updates by lazy { InAppUpdates(this) }
    private val updateLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    private val viewModel: MainViewModel by viewModels()

    /** Pending navigation from a deep link, shortcut, widget, notification or share. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.settings.value == null }
        if (savedInstanceState == null) pendingRoute = routeFor(intent)

        // Google Play: updates, install source (first launch only), integrity warm-up.
        updates.register()
        if (savedInstanceState == null) updates.check(updateLauncher)
        installReferrer.captureOnce()
        lifecycleScope.launch { playIntegrity.prepare() }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val start by viewModel.startState.collectAsStateWithLifecycle()
            val s = settings
            WhoCallerTheme(
                themeMode = s?.themeMode ?: ThemeMode.SYSTEM,
                dynamicColor = s?.dynamicColor ?: true,
            ) {
                Surface {
                    val updateReady by updates.readyToInstall.collectAsStateWithLifecycle()
                    if (updateReady) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text(stringResource(R.string.update_ready_title)) },
                            text = { Text(stringResource(R.string.update_ready_text)) },
                            confirmButton = { TextButton(onClick = updates::install) { Text(stringResource(R.string.update_restart)) } },
                        )
                    }
                    when (start) {
                        StartState.Loading -> SplashContent()
                        StartState.Onboarding -> OnboardingScreen(onFinished = viewModel::completeOnboarding)
                        StartState.Permissions -> PermissionSetupScreen(
                            permissionManager = permissionManager,
                            onFinished = viewModel::completePermissionSetup,
                            onResult = viewModel::onPermissionResult,
                        )
                        StartState.Registration -> SignInScreen(
                            config = SignInConfig(BuildConfig.GOOGLE_WEB_CLIENT_ID.ifBlank { defaultWebClientId(this@MainActivity) }, logoRes = R.drawable.whocaller_logo),
                            onBack = {},
                            onDone = {},
                            mandatory = true,
                        )
                        StartState.CompleteProfile -> CompleteProfileScreen(
                            onDone = {},
                            onSkip = { viewModel.skipIdSetup() },
                            onSignOut = { viewModel.signOut() },
                        )
                        is StartState.Ready -> {
                            val navController = rememberNavController()
                            LaunchedEffect(pendingRoute) {
                                val route = pendingRoute ?: return@LaunchedEffect
                                pendingRoute = null
                                if (route in TOP_LEVEL_BASES) navController.navigateTopLevel(route) else navController.navigate(route)
                            }
                            // ADS OFF (first two years): no ads SDK, no consent form.
                            // LaunchedEffect(Unit) { adsManager.initialize(this@MainActivity) }
                            // Play rating card, only at a good moment (see InAppReview).
                            // WHOCALLER VIDEO (experimental): so other WhoCaller users can ring this phone.
                            LaunchedEffect(Unit) { com.rskusum.whocaller.videocall.VideoSignaling.registerPushToken(this@MainActivity) }
                            LaunchedEffect(Unit) {
                                inAppReview.recordOpen()
                                delay(REVIEW_DELAY_MS)
                                inAppReview.maybeAsk(this@MainActivity)
                            }
                            WhoCallerApp(
                                navController = navController,
                                permissionManager = permissionManager,
                                adsManager = adsManager,
                                onPermissionResult = viewModel::onPermissionResult,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updates.resume(updateLauncher)
    }

    override fun onDestroy() {
        updates.unregister()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pendingRoute = it }
    }

    override fun onStop() {
        super.onStop()
        WhoCallerWidgetProvider.requestUpdate(this)
    }

    /** Maps whocaller:// links and shared text to navigation routes. Input is validated, never trusted. */
    private fun routeFor(intent: Intent?): String? {
        intent ?: return null
        // sms:, smsto:, mms:, mmsto: links (default SMS app requirement) open a conversation.
        val scheme = intent.data?.scheme
        if ((intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) &&
            scheme in setOf("sms", "smsto", "mms", "mmsto")
        ) {
            val address = intent.data?.schemeSpecificPart?.substringBefore('?')?.take(40)
                ?.filter { it.isDigit() || it in "+;, " }?.trim()
            val body = (intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT))?.take(1_600)
            return Routes.conversation(0, address, body)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(2_000) ?: return null
            return Routes.messages(text)
        }
        val data = intent.data ?: return null
        if (data.scheme != "whocaller") return null
        return when (data.host) {
            "home" -> "home"
            "search" -> "search"
            "calls" -> "calls"
            "protection" -> "protection"
            "blocked" -> Routes.BLOCKED
            "settings" -> "settings"
            "privacy" -> Routes.PRIVACY
            "profile" -> if (data.lastPathSegment == "phone") Routes.PROFILE_PHONE else Routes.PROFILE
            "contacts" -> Routes.CONTACTS
            "premium" -> Routes.PREMIUM
            "report" -> data.lastPathSegment
                ?.filter { it.isDigit() || it == '+' }
                ?.takeIf { it.length in 3..20 }
                ?.let(Routes::report)
            "messages" -> Routes.messages(null)
            "sms" -> {
                val thread = data.lastPathSegment?.toLongOrNull() ?: return null
                Routes.conversation(thread, data.getQueryParameter("address")?.take(40))
            }
            "number" -> data.lastPathSegment
                ?.filter { it.isDigit() || it == '+' }
                ?.takeIf { it.length in 3..20 }
                ?.let { Routes.search(it, record = false) }
            else -> null
        }
    }

    private companion object {
        val TOP_LEVEL_BASES = setOf("home", "search", "calls", "protection", "settings")
        /** Let the main screen settle before Play's rating card may appear. */
        const val REVIEW_DELAY_MS = 4_000L
    }
}
