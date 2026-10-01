package com.rskusum.whocaller.navigation

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.navArgument
import com.rskusum.whocaller.BuildConfig
import com.rskusum.whocaller.R
import com.rskusum.whocaller.core.permissions.PermissionManager
import com.rskusum.whocaller.core.permissions.PermissionSetupScreen
import com.rskusum.whocaller.feature.blocking.BlockedNumbersScreen
import com.rskusum.whocaller.feature.callhistory.CallHistoryScreen
import com.rskusum.whocaller.feature.contacts.ContactDetailScreen
import com.rskusum.whocaller.feature.contacts.ContactDetailViewModel
import com.rskusum.whocaller.feature.contacts.ContactsScreen
import com.rskusum.whocaller.feature.home.HomeScreen
import com.rskusum.whocaller.feature.premium.AdsManager
import com.rskusum.whocaller.feature.premium.PremiumScreen
import com.rskusum.whocaller.feature.profile.EditProfileScreen
import com.rskusum.whocaller.feature.profile.ProfileScreen
import com.rskusum.whocaller.feature.profile.SignInConfig
import com.rskusum.whocaller.feature.profile.SignInScreen
import com.rskusum.whocaller.feature.search.BusinessDirectoryScreen
import com.rskusum.whocaller.feature.search.BusinessProfileScreen
import com.rskusum.whocaller.feature.search.BusinessProfileViewModel
import com.rskusum.whocaller.feature.search.BusinessVerificationScreen
import com.rskusum.whocaller.feature.search.SearchScreen
import com.rskusum.whocaller.feature.search.SearchViewModel
import com.rskusum.whocaller.feature.settings.PrivacyScreen
import com.rskusum.whocaller.feature.settings.SettingsLinks
import com.rskusum.whocaller.feature.settings.SettingsScreen
import com.rskusum.whocaller.feature.sms.ConversationScreen
import com.rskusum.whocaller.feature.sms.ConversationViewModel
import com.rskusum.whocaller.feature.sms.MessagesScreen
import com.rskusum.whocaller.feature.sms.MessagesViewModel
import com.rskusum.whocaller.feature.spam.ReportNumberRoute
import com.rskusum.whocaller.feature.spam.ReportViewModel
import com.rskusum.whocaller.feature.spam.SpamProtectionScreen

object Routes {
    const val HOME = "home"
    const val SEARCH = "search?${SearchViewModel.ARG_QUERY}={${SearchViewModel.ARG_QUERY}}&${SearchViewModel.ARG_RECORD}={${SearchViewModel.ARG_RECORD}}"
    const val CALLS = "calls"
    const val PROTECTION = "protection"
    const val SETTINGS = "settings"
    const val BLOCKED = "blocked"
    const val CONTACTS = "contacts"
    const val CONTACT = "contact/{${ContactDetailViewModel.ARG_ID}}"
    const val MESSAGES = "messages?${MessagesViewModel.ARG_TEXT}={${MessagesViewModel.ARG_TEXT}}"
    const val PRIVACY = "privacy"
    const val PROFILE = "profile"
    const val SIGN_IN = "signin"
    const val PREMIUM = "premium"
    const val PERMISSIONS = "permissions"
    const val BUSINESS = "business/{${BusinessProfileViewModel.ARG_ID}}"
    const val BUSINESSES = "businesses"
    const val BUSINESS_VERIFY = "business_verify"
    const val REPORT = "report/{${ReportViewModel.ARG_NUMBER}}"
    const val PROFILE_EDIT = "profile/edit"
    const val PROFILE_PHONE = "profile/phone"
    const val CONVERSATION = "sms/{${ConversationViewModel.ARG_THREAD}}?${ConversationViewModel.ARG_ADDRESS}={${ConversationViewModel.ARG_ADDRESS}}&${ConversationViewModel.ARG_BODY}={${ConversationViewModel.ARG_BODY}}"

    fun search(query: String? = null, record: Boolean = true) =
        if (query == null) "search" else "search?${SearchViewModel.ARG_QUERY}=${Uri.encode(query)}&${SearchViewModel.ARG_RECORD}=$record"
    fun contact(id: Long) = "contact/$id"
    fun messages(text: String?) = if (text == null) "messages" else "messages?${MessagesViewModel.ARG_TEXT}=${Uri.encode(text)}"
    fun business(id: String) = "business/${Uri.encode(id)}"
    fun report(number: String) = "report/${Uri.encode(number)}"
    fun conversation(threadId: Long, address: String?, body: String? = null): String =
        "sms/$threadId?${ConversationViewModel.ARG_ADDRESS}=${Uri.encode(address.orEmpty())}&${ConversationViewModel.ARG_BODY}=${Uri.encode(body.orEmpty())}"
}

private data class TopLevel(val route: String, val base: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val TOP_LEVEL = listOf(
    TopLevel(Routes.HOME, "home", R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    TopLevel(Routes.SEARCH, "search", R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search),
    TopLevel(Routes.CALLS, "calls", R.string.nav_calls, Icons.Outlined.History, Icons.Filled.History),
    TopLevel(Routes.PROTECTION, "protection", R.string.nav_protection, Icons.Outlined.Shield, Icons.Filled.Shield),
    TopLevel(Routes.SETTINGS, "settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

fun NavHostController.navigateTopLevel(base: String) {
    navigate(base) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun WhoCallerApp(
    navController: NavHostController,
    permissionManager: PermissionManager,
    adsManager: AdsManager,
    onPermissionResult: (com.rskusum.whocaller.core.permissions.AppPermission, Boolean) -> Unit,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = TOP_LEVEL.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(item.base) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onSearch = { navController.navigateTopLevel("search") },
                    onSearchNumber = { navController.navigate(Routes.search(it)) },
                    onRecentCalls = { navController.navigateTopLevel("calls") },
                    onSpamProtection = { navController.navigateTopLevel("protection") },
                    onBlocked = { navController.navigate(Routes.BLOCKED) },
                    onContacts = { navController.navigate(Routes.CONTACTS) },
                    onMessages = { navController.navigate(Routes.messages(null)) },
                    onProfile = { navController.navigate(Routes.PROFILE) },
                    onSetUpProtection = { navController.navigate(Routes.PERMISSIONS) },
                    adBanner = { adsManager.Banner(Modifier.padding(vertical = 8.dp)) },
                )
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(
                    navArgument(SearchViewModel.ARG_QUERY) { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument(SearchViewModel.ARG_RECORD) { type = NavType.BoolType; defaultValue = true },
                ),
            ) {
                SearchScreen(
                    onReport = { navController.navigate(Routes.report(it)) },
                    onOpenBusiness = { navController.navigate(Routes.business(it)) },
                    onBusinessDirectory = { navController.navigate(Routes.BUSINESSES) },
                    adBanner = { adsManager.Banner(Modifier) },
                )
            }
            composable(Routes.CALLS) {
                CallHistoryScreen(
                    onSearchNumber = { navController.navigate(Routes.search(it)) },
                    onReport = { navController.navigate(Routes.report(it)) },
                )
            }
            composable(Routes.PROTECTION) {
                SpamProtectionScreen(
                    onSetUpProtection = { navController.navigate(Routes.PERMISSIONS) },
                    onBlockedNumbers = { navController.navigate(Routes.BLOCKED) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onAccount = { navController.navigate(Routes.PROFILE) },
                    onPremium = { navController.navigate(Routes.PREMIUM) },
                    onSpamProtection = { navController.navigateTopLevel("protection") },
                    onBlocked = { navController.navigate(Routes.BLOCKED) },
                    onPermissions = { navController.navigate(Routes.PERMISSIONS) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onContacts = { navController.navigate(Routes.CONTACTS) },
                    onMessages = { navController.navigate(Routes.messages(null)) },
                    links = SettingsLinks(BuildConfig.VERSION_NAME, BuildConfig.PRIVACY_POLICY_URL, BuildConfig.TERMS_URL),
                )
            }
            composable(Routes.BLOCKED) {
                BlockedNumbersScreen(onBack = { navController.popBackStack() }, onOpenNumber = { navController.navigate(Routes.search(it)) })
            }
            composable(Routes.CONTACTS) {
                ContactsScreen(onBack = { navController.popBackStack() }, onOpenContact = { navController.navigate(Routes.contact(it)) })
            }
            composable(Routes.CONTACT, arguments = listOf(navArgument(ContactDetailViewModel.ARG_ID) { type = NavType.LongType })) {
                ContactDetailScreen(onBack = { navController.popBackStack() }, onSearchNumber = { navController.navigate(Routes.search(it)) })
            }
            composable(
                Routes.MESSAGES,
                arguments = listOf(navArgument(MessagesViewModel.ARG_TEXT) { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                MessagesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { thread, address -> navController.navigate(Routes.conversation(thread, address)) },
                    onNewMessage = { navController.navigate(Routes.conversation(0, null)) },
                )
            }
            composable(
                Routes.CONVERSATION,
                arguments = listOf(
                    navArgument(ConversationViewModel.ARG_THREAD) { type = NavType.LongType },
                    navArgument(ConversationViewModel.ARG_ADDRESS) { type = NavType.StringType; defaultValue = "" },
                    navArgument(ConversationViewModel.ARG_BODY) { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                ConversationScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PROFILE_EDIT) {
                EditProfileScreen(onBack = { navController.popBackStack() }, onChangeNumber = { navController.navigate(Routes.PROFILE_PHONE) })
            }
            composable(Routes.PROFILE_PHONE) {
                com.rskusum.whocaller.feature.profile.CompleteProfileScreen(
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PRIVACY) {
                PrivacyScreen(
                    onBack = { navController.popBackStack() },
                    privacyPolicyUrl = BuildConfig.PRIVACY_POLICY_URL,
                    termsUrl = BuildConfig.TERMS_URL,
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onSignIn = { navController.navigate(Routes.SIGN_IN) },
                    onEditProfile = { navController.navigate(Routes.PROFILE_EDIT) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                )
            }
            composable(Routes.SIGN_IN) {
                val context = androidx.compose.ui.platform.LocalContext.current
                SignInScreen(
                    config = SignInConfig(BuildConfig.GOOGLE_WEB_CLIENT_ID.ifBlank { defaultWebClientId(context) }),
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            composable(Routes.PREMIUM) { PremiumScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.PERMISSIONS) {
                PermissionSetupScreen(
                    permissionManager = permissionManager,
                    onFinished = { navController.popBackStack() },
                    onResult = onPermissionResult,
                )
            }
            composable(Routes.BUSINESS, arguments = listOf(navArgument(BusinessProfileViewModel.ARG_ID) { type = NavType.StringType })) {
                BusinessProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.BUSINESSES) {
                BusinessDirectoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBusiness = { navController.navigate(Routes.business(it)) },
                    onRequestVerification = { navController.navigate(Routes.BUSINESS_VERIFY) },
                )
            }
            composable(Routes.BUSINESS_VERIFY) { BusinessVerificationScreen(onBack = { navController.popBackStack() }) }
            dialog(Routes.REPORT, arguments = listOf(navArgument(ReportViewModel.ARG_NUMBER) { type = NavType.StringType })) {
                Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    ReportNumberRoute(onDone = { navController.popBackStack() })
                }
            }
        }
    }
}

/** OAuth web client id generated from google-services.json by the Google Services plugin, if present. */
@android.annotation.SuppressLint("DiscouragedApi")
internal fun defaultWebClientId(context: android.content.Context): String {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    return if (id != 0) context.getString(id) else ""
}
