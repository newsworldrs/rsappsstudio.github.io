package com.rskusum.whocaller.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.repository.NetworkMonitor
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.domain.repository.SyncController
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.UserProfile
import com.rskusum.whocaller.core.model.UserStats
import com.rskusum.whocaller.core.permissions.PermissionManager
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.PulsingDot
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.component.StatCard
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.callTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Calendar
import javax.inject.Inject

data class HomeUiState(
    val user: UserProfile = UserProfile.GUEST,
    val stats: UserStats = UserStats(),
    val protectionActive: Boolean = false,
    val syncing: Boolean = false,
    val online: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    statsRepository: StatsRepository,
    settingsRepository: SettingsRepository,
    syncController: SyncController,
    networkMonitor: NetworkMonitor,
    private val callLogRepository: CallLogRepository,
    private val permissionManager: PermissionManager,
) : ViewModel() {

    private val roleReady = MutableStateFlow(permissionManager.isCallerIdReady())

    val state: StateFlow<HomeUiState> = combine(
        authRepository.currentUser,
        statsRepository.stats,
        combine(settingsRepository.settings, roleReady) { s, ready -> s.callerIdEnabled && ready },
        syncController.isSyncing,
        networkMonitor.isOnline,
    ) { user, stats, active, syncing, online ->
        HomeUiState(user, stats, active, syncing, online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _unidentified = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val unidentified: StateFlow<List<CallLogEntry>> = _unidentified.asStateFlow()

    val hasCallLogAccess: Boolean get() = callLogRepository.hasPermission()

    init {
        viewModelScope.launch { callLogRepository.changes().collect { loadUnidentified() } }
    }

    fun refresh() {
        roleReady.value = permissionManager.isCallerIdReady()
        loadUnidentified()
    }

    private fun loadUnidentified() {
        viewModelScope.launch { _unidentified.value = callLogRepository.recentUnidentified(RECENT_LIMIT) }
    }

    private companion object {
        const val RECENT_LIMIT = 5
    }
}

@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onSearchNumber: (String) -> Unit,
    onRecentCalls: () -> Unit,
    onSpamProtection: () -> Unit,
    onBlocked: () -> Unit,
    onContacts: () -> Unit,
    onMessages: () -> Unit,
    onProfile: () -> Unit,
    onSetUpProtection: () -> Unit,
    adBanner: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unidentified by viewModel.unidentified.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val context = LocalContext.current
    val numberFormat = NumberFormat.getIntegerInstance()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(state.user), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(com.rskusum.whocaller.core.ui.R.string.core_app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                IconButton(onClick = onProfile) {
                    Icon(Icons.Outlined.AccountCircle, contentDescription = stringResource(R.string.home_profile))
                }
            }
        }
        item {
            // Search bar: a button that opens the search screen with the keyboard ready.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClick = onSearch),
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.home_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.syncing) {
            item { Banner(Icons.Outlined.Sync, stringResource(R.string.home_syncing), progress = true) }
        } else if (!state.online) {
            item { Banner(Icons.Outlined.CloudOff, stringResource(R.string.home_offline), progress = false) }
        }
        item { ProtectionCard(state.protectionActive, onClick = if (state.protectionActive) onSpamProtection else onSetUpProtection) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(Icons.Outlined.Search, stringResource(R.string.home_action_search), onSearch, Modifier.weight(1f))
                    ActionTile(Icons.Outlined.History, stringResource(R.string.home_action_calls), onRecentCalls, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(Icons.Outlined.Shield, stringResource(R.string.home_action_spam), onSpamProtection, Modifier.weight(1f))
                    ActionTile(Icons.Outlined.Block, stringResource(R.string.home_action_blocked), onBlocked, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(Icons.Outlined.Contacts, stringResource(R.string.home_action_contacts), onContacts, Modifier.weight(1f))
                    ActionTile(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.home_action_messages), onMessages, Modifier.weight(1f))
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(numberFormat.format(state.stats.callsIdentified), stringResource(R.string.home_stat_identified), Icons.Outlined.VerifiedUser, Modifier.weight(1f))
                StatCard(numberFormat.format(state.stats.spamBlocked), stringResource(R.string.home_stat_blocked), Icons.Outlined.Block, Modifier.weight(1f))
                StatCard(numberFormat.format(state.stats.numbersReported), stringResource(R.string.home_stat_reported), Icons.Outlined.Flag, Modifier.weight(1f))
            }
        }
        item { adBanner() }
        item { SectionHeader(stringResource(R.string.home_recent_unidentified)) }
        if (!viewModel.hasCallLogAccess) {
            item { Text(stringResource(R.string.home_calls_permission), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (unidentified.isEmpty()) {
            item { Text(stringResource(R.string.home_no_unidentified), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(unidentified, key = { it.id }) { call ->
            ListItem(
                headlineContent = { Text(call.displayNumber) },
                supportingContent = { Text(callTime(context, call.timestamp)) },
                leadingContent = { CallerAvatar(null, if (call.isSpam) CallerLabel.SUSPECTED_SPAM else CallerLabel.UNKNOWN) },
                trailingContent = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.clickable { onSearchNumber(call.rawNumber) },
            )
        }
    }
}

@Composable
private fun greeting(user: UserProfile): String {
    val name = user.name?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: stringResource(R.string.home_default_name)
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return stringResource(
        when (hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..16 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        },
        name,
    )
}

@Composable
private fun ProtectionCard(active: Boolean, onClick: () -> Unit) {
    val risk = WhoCallerTheme.riskColors
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(if (active) risk.safe else risk.moderate, animate = active)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (active) R.string.home_protection_active else R.string.home_protection_setup),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(if (active) R.string.home_protection_active_desc else R.string.home_protection_setup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 72.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, progress: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        if (progress) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}
