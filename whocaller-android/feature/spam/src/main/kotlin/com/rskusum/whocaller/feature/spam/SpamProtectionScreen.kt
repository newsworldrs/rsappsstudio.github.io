package com.rskusum.whocaller.feature.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.SyncState
import com.rskusum.whocaller.core.permissions.PermissionManager
import com.rskusum.whocaller.core.ui.component.PulsingDot
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.component.SettingSwitch
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.labelRes
import com.rskusum.whocaller.core.ui.util.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpamProtectionUiState(
    val settings: AppSettings = AppSettings(),
    val protectionReady: Boolean = false,
    val canBlockCalls: Boolean = true,
    val reports: List<SpamReport> = emptyList(),
)

@HiltViewModel
class SpamProtectionViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    spamRepository: SpamRepository,
    private val permissionManager: PermissionManager,
) : ViewModel() {

    private val ready = MutableStateFlow(permissionManager.isCallerIdReady())

    val state: StateFlow<SpamProtectionUiState> = combine(
        settingsRepository.settings,
        spamRepository.observeMyReports(),
        ready,
    ) { settings, reports, ready ->
        SpamProtectionUiState(
            settings = settings,
            protectionReady = ready,
            canBlockCalls = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
            reports = reports.take(50),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpamProtectionUiState())

    fun refreshStatus() {
        ready.value = permissionManager.isCallerIdReady()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun toggleCategory(category: SpamCategory) = update {
        val set = it.blockedCategories.toMutableSet()
        if (!set.add(category)) set.remove(category)
        it.copy(blockedCategories = set)
    }

    companion object {
        /** Categories that can be auto-blocked. Neutral categories (business, charity…) are not offered. */
        val BLOCKABLE = listOf(
            SpamCategory.SPAM, SpamCategory.SCAM, SpamCategory.FRAUD, SpamCategory.TELEMARKETING,
            SpamCategory.ROBOCALL, SpamCategory.DEBT_COLLECTION, SpamCategory.POLITICAL,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpamProtectionScreen(
    onSetUpProtection: () -> Unit,
    onBlockedNumbers: () -> Unit,
    viewModel: SpamProtectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshStatus() }
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }
    val s = state.settings

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.spam_title)) }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { ProtectionStatusCard(state, onSetUpProtection) }
            item {
                SettingSwitch(
                    title = stringResource(R.string.spam_caller_id),
                    description = stringResource(R.string.spam_caller_id_desc),
                    checked = s.callerIdEnabled,
                    onChange = { v -> viewModel.update { it.copy(callerIdEnabled = v) } },
                )
            }
            item {
                SettingSwitch(
                    title = stringResource(R.string.spam_protection),
                    description = stringResource(R.string.spam_protection_desc),
                    checked = s.spamProtectionEnabled,
                    onChange = { v -> viewModel.update { it.copy(spamProtectionEnabled = v) } },
                )
            }
            if (state.canBlockCalls) {
                item {
                    SettingSwitch(
                        title = stringResource(R.string.spam_auto_block),
                        description = stringResource(R.string.spam_auto_block_desc),
                        checked = s.autoBlockHighRisk,
                        enabled = s.spamProtectionEnabled,
                        onChange = { v -> viewModel.update { it.copy(autoBlockHighRisk = v) } },
                    )
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.spam_block_categories), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.spam_block_categories_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpamProtectionViewModel.BLOCKABLE.forEach { category ->
                                FilterChip(
                                    selected = category in s.blockedCategories,
                                    onClick = { viewModel.toggleCategory(category) },
                                    enabled = s.spamProtectionEnabled,
                                    label = { Text(stringResource(category.labelRes())) },
                                )
                            }
                        }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.spam_blocked_numbers)) },
                    leadingContent = { Icon(Icons.Outlined.Block, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onBlockedNumbers),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.spam_how_it_works)) },
                    supportingContent = if (showHowItWorks) {
                        { Text(stringResource(R.string.spam_how_it_works_body)) }
                    } else {
                        null
                    },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    modifier = Modifier.clickable { showHowItWorks = !showHowItWorks },
                )
            }
            item { SectionHeader(stringResource(R.string.spam_my_reports)) }
            if (state.reports.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.spam_no_reports),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.reports, key = { it.id }) { report ->
                ListItem(
                    headlineContent = { Text(report.numberKey.removePrefix("raw:")) },
                    supportingContent = {
                        Text(
                            stringResource(report.reason.labelRes()) + " · " + relativeTime(report.createdAt) + " · " +
                                stringResource(
                                    when (report.syncState) {
                                        SyncState.SYNCED -> R.string.spam_report_synced
                                        SyncState.PENDING -> R.string.spam_report_pending
                                        SyncState.FAILED -> R.string.spam_report_failed
                                    },
                                ),
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun ProtectionStatusCard(state: SpamProtectionUiState, onSetUp: () -> Unit) {
    val risk = WhoCallerTheme.riskColors
    val active = state.protectionReady && state.settings.callerIdEnabled
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(if (active) risk.safe else risk.moderate, animate = active)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (active) R.string.spam_status_active else R.string.spam_status_setup),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when {
                        !state.canBlockCalls -> R.string.spam_status_legacy_desc
                        active -> R.string.spam_status_active_desc
                        else -> R.string.spam_status_setup_desc
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.protectionReady) {
                TextButton(onClick = onSetUp) { Text(stringResource(R.string.spam_setup_button)) }
            }
        }
    }
}
