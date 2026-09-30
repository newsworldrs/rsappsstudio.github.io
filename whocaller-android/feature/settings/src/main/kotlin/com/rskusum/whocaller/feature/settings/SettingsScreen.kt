package com.rskusum.whocaller.feature.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.Country
import com.rskusum.whocaller.core.model.NotificationCategory
import com.rskusum.whocaller.core.model.ThemeMode
import com.rskusum.whocaller.core.ui.component.NavigationRow
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.component.SettingSwitch
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

/** Languages WhoCaller ships UI strings for. Names are shown in their own language. */
object SupportedLanguages {
    val TAGS = listOf("en", "hi", "pa", "es", "fr", "de", "ar", "pt", "in")

    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(if (tag == "in") "id" else tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val countryRepository: CountryRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val countries: List<Country> get() = countryRepository.countries()

    val detectedRegion = MutableStateFlow("")

    init {
        viewModelScope.launch { detectedRegion.value = countryRepository.defaultRegion() }
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        settingsRepository.update(transform)
        detectedRegion.value = countryRepository.defaultRegion()
    }

    fun toggleNotification(category: NotificationCategory, enabled: Boolean) = update {
        it.copy(
            disabledNotificationCategories = if (enabled) {
                it.disabledNotificationCategories - category
            } else {
                it.disabledNotificationCategories + category
            },
        )
    }
}

data class SettingsLinks(
    val versionName: String,
    val privacyPolicyUrl: String,
    val termsUrl: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAccount: () -> Unit,
    onPremium: () -> Unit,
    onSpamProtection: () -> Unit,
    onBlocked: () -> Unit,
    onPermissions: () -> Unit,
    onPrivacy: () -> Unit,
    onContacts: () -> Unit,
    onMessages: () -> Unit,
    links: SettingsLinks,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val detected by viewModel.detectedRegion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { NavigationRow(stringResource(R.string.settings_account), Icons.Outlined.AccountCircle, onAccount, subtitle = stringResource(R.string.settings_account_desc)) }
            item { NavigationRow(stringResource(R.string.settings_premium), Icons.Outlined.WorkspacePremium, onPremium, subtitle = stringResource(R.string.settings_premium_desc)) }

            item { SectionHeader(stringResource(R.string.settings_protection)) }
            item { NavigationRow(stringResource(R.string.settings_spam), Icons.Outlined.Shield, onSpamProtection) }
            item { NavigationRow(stringResource(R.string.settings_blocked), Icons.Outlined.Block, onBlocked) }
            item { NavigationRow(stringResource(R.string.settings_permissions), Icons.Outlined.VerifiedUser, onPermissions, subtitle = stringResource(R.string.settings_permissions_desc)) }
            item { NavigationRow(stringResource(R.string.settings_contacts), Icons.Outlined.Contacts, onContacts) }
            item { NavigationRow(stringResource(R.string.settings_messages), Icons.AutoMirrored.Outlined.Message, onMessages) }

            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                NavigationRow(
                    stringResource(R.string.settings_theme),
                    Icons.Outlined.Palette,
                    onClick = { dialog = "theme" },
                    subtitle = stringResource(s.themeMode.labelRes()),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SettingSwitch(
                        stringResource(R.string.settings_dynamic_color),
                        stringResource(R.string.settings_dynamic_color_desc),
                        s.dynamicColor,
                        { v -> viewModel.update { it.copy(dynamicColor = v) } },
                    )
                }
            }
            item {
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
                NavigationRow(
                    stringResource(R.string.settings_language),
                    Icons.Outlined.Language,
                    onClick = { dialog = "language" },
                    subtitle = if (current.isBlank()) stringResource(R.string.settings_language_system) else SupportedLanguages.displayName(current),
                )
            }
            item {
                val region = s.defaultRegion
                NavigationRow(
                    stringResource(R.string.settings_country),
                    Icons.Outlined.Public,
                    onClick = { dialog = "country" },
                    subtitle = if (region == null) {
                        stringResource(R.string.settings_country_auto, Locale("", detected).displayCountry)
                    } else {
                        Locale("", region).displayCountry
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_notifications)) }
            items(NotificationCategory.entries) { category ->
                SettingSwitch(
                    title = stringResource(category.labelRes()),
                    description = null,
                    checked = s.isNotificationEnabled(category),
                    onChange = { viewModel.toggleNotification(category, it) },
                )
            }
            item {
                NavigationRow(
                    stringResource(R.string.settings_notif_system),
                    Icons.Outlined.NotificationsActive,
                    onClick = { context.startActivity(NotificationChannels.channelSettingsIntent(context, NotificationCategory.CALLER_ALERTS)) },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_privacy)) }
            item { NavigationRow(stringResource(R.string.settings_privacy), Icons.Outlined.Lock, onPrivacy, subtitle = stringResource(R.string.settings_privacy_desc)) }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(UiR.string.core_app_name)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.settings_version, links.versionName))
                            Text(stringResource(R.string.settings_developer, "RS APPS STUDIO"))
                        }
                    },
                    leadingContent = { androidx.compose.material3.Icon(Icons.Outlined.Info, contentDescription = null) },
                )
            }
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to stringResource(it.labelRes()) },
            selected = s.themeMode,
            onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) }; dialog = null },
            onDismiss = { dialog = null },
        )
        "language" -> {
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
            ChoiceDialog(
                title = stringResource(R.string.settings_language),
                options = listOf("" to stringResource(R.string.settings_language_system)) +
                    SupportedLanguages.TAGS.map { it to SupportedLanguages.displayName(it) },
                selected = current,
                onSelect = { tag ->
                    // Per-app language (Android 13+ system setting; AppCompat back-port below 13).
                    AppCompatDelegate.setApplicationLocales(
                        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                    )
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        "country" -> ChoiceDialog(
            title = stringResource(R.string.settings_country),
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.settings_country_auto, Locale("", detected).displayCountry)) +
                viewModel.countries.map { it.regionCode to "${Locale("", it.regionCode).displayCountry} (+${it.callingCode})" },
            selected = s.defaultRegion,
            onSelect = { region -> viewModel.update { it.copy(defaultRegion = region) }; dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_close)) } },
    )
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private fun NotificationCategory.labelRes(): Int = when (this) {
    NotificationCategory.CALLER_ALERTS -> R.string.settings_notif_caller
    NotificationCategory.SPAM_ALERTS -> R.string.settings_notif_spam
    NotificationCategory.SECURITY -> R.string.settings_notif_security
    NotificationCategory.GENERAL -> R.string.settings_notif_general
}
