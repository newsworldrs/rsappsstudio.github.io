package com.rskusum.whocaller.feature.blocking

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.BlockedCall
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.SettingSwitch
import com.rskusum.whocaller.core.ui.util.labelRes
import com.rskusum.whocaller.core.ui.util.messageRes
import com.rskusum.whocaller.core.ui.util.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

data class BlockingUiState(
    val blocked: List<BlockedNumber> = emptyList(),
    val history: List<BlockedCall> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val hasContactsAccess: Boolean = false,
)

@HiltViewModel
class BlockingViewModel @Inject constructor(
    private val blockRepository: BlockRepository,
    private val blockNumber: BlockNumberUseCase,
    private val settingsRepository: SettingsRepository,
    private val contactsRepository: ContactsRepository,
) : ViewModel() {

    val state: StateFlow<BlockingUiState> = combine(
        blockRepository.observeBlockedNumbers(),
        blockRepository.observeBlockedCalls(HISTORY_LIMIT),
        settingsRepository.settings,
    ) { blocked, history, settings ->
        BlockingUiState(blocked, history, settings, contactsRepository.hasPermission() && settings.contactAccessEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockingUiState())

    suspend fun block(number: String, label: String?): AppError? =
        (blockNumber.block(number, label) as? AppResult.Failure)?.error

    fun unblock(item: BlockedNumber) = viewModelScope.launch { blockNumber.unblock(item.numberKey) }

    fun restore(item: BlockedNumber) = viewModelScope.launch { blockRepository.block(item) }

    fun clearHistory() = viewModelScope.launch { blockRepository.clearBlockedCallHistory() }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepository.update(transform) }

    private companion object {
        const val HISTORY_LIMIT = 200
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(
    onBack: () -> Unit,
    onOpenNumber: (String) -> Unit,
    viewModel: BlockingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val undo = stringResource(R.string.blocking_undo)
    val canReject = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blocking_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
                actions = {
                    if (tab == 1 && state.history.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearHistory) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.blocking_clear_history))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.blocking_add)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.blocking_tab_numbers)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.blocking_tab_history)) })
            }
            if (tab == 0) {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (!canReject) {
                        item {
                            Text(
                                stringResource(R.string.blocking_legacy_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    item {
                        SettingSwitch(
                            title = stringResource(R.string.blocking_block_unknown),
                            description = stringResource(
                                if (state.hasContactsAccess) R.string.blocking_block_unknown_desc else R.string.blocking_block_unknown_needs_contacts,
                            ),
                            checked = state.settings.blockUnknownCallers && state.hasContactsAccess,
                            enabled = state.hasContactsAccess && canReject,
                            onChange = { v -> viewModel.update { it.copy(blockUnknownCallers = v) } },
                        )
                    }
                    item {
                        SettingSwitch(
                            title = stringResource(R.string.blocking_block_hidden),
                            description = stringResource(R.string.blocking_block_hidden_desc),
                            checked = state.settings.blockHiddenNumbers,
                            enabled = canReject,
                            onChange = { v -> viewModel.update { it.copy(blockHiddenNumbers = v) } },
                        )
                    }
                    if (state.blocked.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.Block,
                                title = stringResource(R.string.blocking_empty),
                                message = stringResource(R.string.blocking_empty_desc),
                            )
                        }
                    }
                    items(state.blocked, key = { it.numberKey }) { item ->
                        ListItem(
                            headlineContent = { Text(item.label ?: item.displayNumber) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        item.displayNumber.takeIf { item.label != null },
                                        item.category?.let { stringResource(it.labelRes()) },
                                        stringResource(R.string.blocking_blocked_at, relativeTime(item.blockedAt)),
                                    ).joinToString(" · "),
                                )
                            },
                            leadingContent = { CallerAvatar(item.label, com.rskusum.whocaller.core.model.CallerLabel.SUSPECTED_SPAM) },
                            trailingContent = {
                                TextButton(onClick = {
                                    viewModel.unblock(item)
                                    scope.launch {
                                        val r = snackbar.showSnackbar(
                                            context.getString(R.string.blocking_unblocked, item.displayNumber),
                                            actionLabel = undo,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (r == SnackbarResult.ActionPerformed) viewModel.restore(item)
                                    }
                                }) { Text(stringResource(UiR.string.action_unblock)) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                BlockedHistory(state.history, onOpenNumber)
            }
        }
    }

    if (showAdd) {
        AddBlockedNumberDialog(
            onDismiss = { showAdd = false },
            onBlock = { number, label -> viewModel.block(number, label) },
        )
    }
}

@Composable
private fun BlockedHistory(history: List<BlockedCall>, onOpenNumber: (String) -> Unit) {
    if (history.isEmpty()) {
        EmptyState(icon = Icons.Outlined.History, title = stringResource(R.string.blocking_history_empty))
        return
    }
    LazyColumn {
        items(history, key = { it.id }) { call ->
            val reason = stringResource(
                when (call.reason) {
                    DecisionReason.USER_BLOCK_LIST -> R.string.blocking_reason_list
                    DecisionReason.HIDDEN_NUMBER -> R.string.blocking_reason_hidden
                    DecisionReason.UNKNOWN_CALLER -> R.string.blocking_reason_unknown
                    DecisionReason.BLOCKED_CATEGORY -> R.string.blocking_reason_category
                    else -> R.string.blocking_reason_high_risk
                },
            )
            val canOpen = call.numberKey.startsWith("+")
            ListItem(
                headlineContent = { Text(call.displayNumber) },
                supportingContent = { Text("$reason · ${relativeTime(call.timestamp)}") },
                leadingContent = { Icon(Icons.Outlined.Block, contentDescription = null) },
                modifier = if (canOpen) Modifier.fillMaxWidth().clickable { onOpenNumber(call.numberKey) } else Modifier,
            )
        }
    }
}

@Composable
private fun AddBlockedNumberDialog(onDismiss: () -> Unit, onBlock: suspend (String, String?) -> AppError?) {
    var number by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<AppError?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blocking_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.take(24); error = null },
                    label = { Text(stringResource(R.string.blocking_number_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(stringResource(it.messageRes())) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(80) },
                    label = { Text(stringResource(R.string.blocking_label_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = number.any { it.isDigit() },
                onClick = {
                    scope.launch {
                        val e = onBlock(number, label.ifBlank { null })
                        if (e == null) onDismiss() else error = e
                    }
                },
            ) { Text(stringResource(R.string.blocking_block_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
