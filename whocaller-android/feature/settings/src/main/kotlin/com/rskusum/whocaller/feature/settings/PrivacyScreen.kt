package com.rskusum.whocaller.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.IoDispatcher
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.domain.repository.SearchHistoryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.domain.repository.UserDataRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.core.ui.component.NavigationRow
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.component.SettingSwitch
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val userDataRepository: UserDataRepository,
    private val statsRepository: StatsRepository,
    private val authRepository: AuthRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val user: StateFlow<UserProfile> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile.GUEST)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepository.update(transform) }

    fun setSearchHistory(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(searchHistoryEnabled = enabled) }
        if (!enabled) searchHistoryRepository.clear()
    }

    fun deleteSearchHistory() = viewModelScope.launch {
        searchHistoryRepository.clear()
        _messages.emit(R.string.privacy_done)
    }

    fun clearLocalData() = viewModelScope.launch {
        userDataRepository.clearLocalData()
        statsRepository.reset()
        _messages.emit(R.string.privacy_done)
    }

    fun export(write: suspend (String) -> Unit) = viewModelScope.launch {
        val ok = try {
            val json = userDataRepository.exportAsJson()
            withContext(io) { write(json) }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        _messages.emit(if (ok) R.string.privacy_exported else R.string.privacy_export_failed)
    }

    fun deleteAccount() = viewModelScope.launch {
        when (val r = authRepository.deleteAccount()) {
            is AppResult.Success -> {
                userDataRepository.clearLocalData()
                _messages.emit(R.string.privacy_done)
            }
            is AppResult.Failure -> _messages.emit(r.error.messageRes())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    onBack: () -> Unit,
    privacyPolicyUrl: String,
    termsUrl: String,
    viewModel: PrivacyViewModel = hiltViewModel(),
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.export { json ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("No output stream")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.privacy_statement),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item { SectionHeader(stringResource(R.string.privacy_features)) }
            item { SettingSwitch(stringResource(R.string.privacy_caller_id), null, s.callerIdEnabled, { v -> viewModel.update { it.copy(callerIdEnabled = v) } }) }
            item { SettingSwitch(stringResource(R.string.privacy_spam), null, s.spamProtectionEnabled, { v -> viewModel.update { it.copy(spamProtectionEnabled = v) } }) }
            item {
                SettingSwitch(
                    stringResource(R.string.privacy_post_call),
                    stringResource(R.string.privacy_post_call_desc),
                    s.postCallPrompt,
                    { v -> viewModel.update { it.copy(postCallPrompt = v) } },
                )
            }
            item { SettingSwitch(stringResource(R.string.privacy_search_history), stringResource(R.string.privacy_search_history_desc), s.searchHistoryEnabled, viewModel::setSearchHistory) }
            item {
                SettingSwitch(
                    stringResource(R.string.privacy_personalized),
                    stringResource(R.string.privacy_personalized_desc),
                    s.personalizedRecommendations,
                    { v -> viewModel.update { it.copy(personalizedRecommendations = v) } },
                )
            }
            item { SettingSwitch(stringResource(R.string.privacy_analytics), stringResource(R.string.privacy_analytics_desc), s.analyticsEnabled, { v -> viewModel.update { it.copy(analyticsEnabled = v) } }) }
            item { SettingSwitch(stringResource(R.string.privacy_crash), stringResource(R.string.privacy_crash_desc), s.crashReportsEnabled, { v -> viewModel.update { it.copy(crashReportsEnabled = v) } }) }
            item { SettingSwitch(stringResource(R.string.privacy_contacts), stringResource(R.string.privacy_contacts_desc), s.contactAccessEnabled, { v -> viewModel.update { it.copy(contactAccessEnabled = v) } }) }

            item { SectionHeader(stringResource(R.string.privacy_your_data)) }
            item { NavigationRow(stringResource(R.string.privacy_delete_history), Icons.Outlined.History, onClick = { confirm = "history" }) }
            item { NavigationRow(stringResource(R.string.privacy_clear_local), Icons.Outlined.DeleteSweep, onClick = { confirm = "clear" }, subtitle = stringResource(R.string.privacy_clear_local_desc)) }
            item { NavigationRow(stringResource(R.string.privacy_export), Icons.Outlined.FileDownload, onClick = { exportLauncher.launch("whocaller-data.json") }, subtitle = stringResource(R.string.privacy_export_desc)) }
            item {
                NavigationRow(
                    stringResource(R.string.privacy_delete_account),
                    Icons.Outlined.DeleteForever,
                    onClick = {
                        if (user.isGuest) {
                            Toast.makeText(context, R.string.privacy_guest_no_account, Toast.LENGTH_SHORT).show()
                        } else {
                            confirm = "account"
                        }
                    },
                    subtitle = stringResource(R.string.privacy_delete_account_desc),
                )
            }
            item { NavigationRow(stringResource(R.string.privacy_policy), Icons.Outlined.Policy, onClick = { ActionIntents.openUrl(context, privacyPolicyUrl) }) }
            item { NavigationRow(stringResource(R.string.privacy_terms), Icons.Outlined.Description, onClick = { ActionIntents.openUrl(context, termsUrl) }) }
            item {
                // Required credit for OpenStreetMap data (ODbL) used in caller identification.
                Text(
                    stringResource(R.string.privacy_data_sources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.privacy_confirm)) },
            text = {
                Text(
                    stringResource(
                        when (which) {
                            "history" -> R.string.privacy_confirm_history
                            "clear" -> R.string.privacy_confirm_clear
                            else -> R.string.privacy_confirm_delete_account
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (which) {
                        "history" -> viewModel.deleteSearchHistory()
                        "clear" -> viewModel.clearLocalData()
                        else -> viewModel.deleteAccount()
                    }
                    confirm = null
                }) { Text(stringResource(R.string.action_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(UiR.string.action_cancel)) } },
        )
    }
}
