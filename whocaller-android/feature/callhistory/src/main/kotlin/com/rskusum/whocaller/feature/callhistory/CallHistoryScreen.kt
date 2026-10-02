package com.rskusum.whocaller.feature.callhistory

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.model.CallFilter
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallType
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.ErrorState
import com.rskusum.whocaller.core.ui.component.LoadingState
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions
import com.rskusum.whocaller.core.ui.util.OffsetPagingSource
import com.rskusum.whocaller.core.ui.util.callTime
import com.rskusum.whocaller.core.ui.util.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.ui.R as UiR

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val repository: CallLogRepository,
    private val blockNumber: BlockNumberUseCase,
) : ViewModel() {

    private val _filter = MutableStateFlow(CallFilter.ALL)
    val filter: StateFlow<CallFilter> = _filter.asStateFlow()

    private val _hasPermission = MutableStateFlow(repository.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private var currentSource: PagingSource<Int, CallLogEntry>? = null

    val calls: Flow<PagingData<CallLogEntry>> = _filter.flatMapLatest { f ->
        Pager(PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, enablePlaceholders = false)) {
            OffsetPagingSource { offset, limit -> repository.loadPage(f, offset, limit) }.also { currentSource = it }
        }.flow
    }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            // Refresh when the system call log changes (new call, deletion).
            repository.changes().collect { currentSource?.invalidate() }
        }
    }

    fun setFilter(f: CallFilter) {
        _filter.value = f
    }

    fun onResume() {
        val granted = repository.hasPermission()
        if (granted != _hasPermission.value) {
            _hasPermission.value = granted
            currentSource?.invalidate()
        }
    }

    fun block(entry: CallLogEntry) = viewModelScope.launch {
        blockNumber.block(entry.rawNumber, entry.title, entry.category.takeIf { it != SpamCategory.UNKNOWN })
        currentSource?.invalidate()
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    onSearchNumber: (String) -> Unit,
    onReport: (String) -> Unit,
    viewModel: CallHistoryViewModel = hiltViewModel(),
) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onResume()
    }
    var selected by remember { mutableStateOf<CallLogEntry?>(null) }

    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.calls_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { openKeypad(context) }) {
                Icon(Icons.Filled.Dialpad, contentDescription = stringResource(R.string.calls_open_keypad))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = filter.ordinal, edgePadding = 8.dp) {
                CallFilter.entries.forEach { f ->
                    Tab(selected = f == filter, onClick = { viewModel.setFilter(f) }, text = { Text(stringResource(f.labelRes())) })
                }
            }
            if (!hasPermission) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.calls_permission_title),
                    message = stringResource(R.string.calls_permission_body),
                    actionLabel = stringResource(R.string.calls_permission_button),
                    onAction = { permissionLauncher.launch(Manifest.permission.READ_CALL_LOG) },
                )
            } else {
                CallList(viewModel, onSelect = { selected = it })
            }
        }
    }

    selected?.let { entry ->
        CallActionsSheet(
            entry = entry,
            onDismiss = { selected = null },
            onSearch = { onSearchNumber(entry.rawNumber) },
            onReport = { onReport(entry.rawNumber) },
            onBlock = { viewModel.block(entry) },
        )
    }
}

@Composable
private fun CallList(viewModel: CallHistoryViewModel, onSelect: (CallLogEntry) -> Unit) {
    val pagingItems = viewModel.calls.collectAsLazyPagingItems()
    val context = LocalContext.current
    when {
        pagingItems.loadState.refresh is LoadState.Loading && pagingItems.itemCount == 0 -> LoadingState()
        pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0 -> ErrorState(AppError.STORAGE) { pagingItems.retry() }
        pagingItems.itemCount == 0 && pagingItems.loadState.refresh is LoadState.NotLoading ->
            EmptyState(icon = Icons.Outlined.History, title = stringResource(R.string.calls_empty))
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.id }) { index ->
                val entry = pagingItems[index] ?: return@items
                CallRow(context, entry, onClick = { onSelect(entry) })
            }
        }
    }
}

@Composable
private fun CallRow(context: Context, entry: CallLogEntry, onClick: () -> Unit) {
    val label = when {
        entry.isHidden -> CallerLabel.HIDDEN
        entry.contactName != null -> CallerLabel.CONTACT
        entry.isSpam && entry.category == SpamCategory.TELEMARKETING -> CallerLabel.TELEMARKETING
        entry.isSpam -> CallerLabel.SUSPECTED_SPAM
        entry.cachedName != null -> CallerLabel.PERSON
        else -> CallerLabel.UNKNOWN
    }
    val title = when {
        entry.isHidden -> stringResource(UiR.string.private_number)
        entry.title != null -> entry.title.orEmpty()
        entry.isSpam && entry.category != SpamCategory.UNKNOWN -> stringResource(entry.category.labelRes())
        else -> stringResource(UiR.string.unknown_number)
    }
    val typeText = stringResource(entry.type.labelRes())
    val second = listOfNotNull(
        entry.displayNumber.takeIf { !entry.isHidden && title != entry.displayNumber },
        if (entry.isSpam) stringResource(R.string.calls_spam_label) else null,
    ).joinToString(" · ")
    ListItem(
        headlineContent = {
            Text(title, color = if (entry.isSpam) WhoCallerTheme.riskColors.high else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = {
            Column {
                if (second.isNotEmpty()) Text(second)
                Text("${callTime(context, entry.timestamp)}, $typeText", style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = { CallerAvatar(entry.title ?: entry.displayNumber, label, size = 52.dp) },
        trailingContent = {
            Icon(
                entry.type.icon(),
                contentDescription = typeText,
                tint = if (entry.type == CallType.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallActionsSheet(
    entry: CallLogEntry,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    val context = LocalContext.current
    val number = entry.rawNumber
    val title = entry.title ?: entry.displayNumber
    val hasWhatsApp = remember { TelecomActions.whatsAppPackage(context) != null }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().semantics { contentDescription = context.getString(R.string.calls_actions_for, title) }) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            if (entry.isHidden) {
                Text(stringResource(UiR.string.private_number), modifier = Modifier.padding(24.dp))
                return@Column
            }
            SheetAction(Icons.Outlined.Call, stringResource(UiR.string.action_call)) { ActionIntents.dial(context, number); onDismiss() }
            SheetAction(Icons.AutoMirrored.Outlined.Message, stringResource(UiR.string.action_message)) { ActionIntents.message(context, number); onDismiss() }
            if (hasWhatsApp) {
                SheetAction(Icons.Outlined.Call, stringResource(UiR.string.action_whatsapp_call)) {
                    TelecomActions.whatsAppCall(context, number, video = false)
                    onDismiss()
                }
                SheetAction(Icons.Outlined.Videocam, stringResource(UiR.string.action_whatsapp_video)) {
                    TelecomActions.whatsAppCall(context, number, video = true)
                    onDismiss()
                }
            }
            if (hasWhatsApp && entry.numberKey.startsWith("+")) {
                SheetAction(Icons.AutoMirrored.Outlined.Chat, stringResource(UiR.string.action_whatsapp)) {
                    TelecomActions.openWhatsApp(context, entry.numberKey)
                    onDismiss()
                }
            }
            if (entry.contactName == null) {
                SheetAction(Icons.Outlined.PersonAdd, stringResource(UiR.string.action_save_contact)) { ActionIntents.saveContact(context, number); onDismiss() }
            }
            SheetAction(Icons.Outlined.Search, stringResource(UiR.string.action_search)) { onDismiss(); onSearch() }
            SheetAction(Icons.Outlined.Flag, stringResource(UiR.string.action_report)) { onDismiss(); onReport() }
            SheetAction(Icons.Outlined.Block, stringResource(UiR.string.action_block)) {
                onBlock()
                Toast.makeText(context, context.getString(R.string.calls_blocked_toast, entry.displayNumber), Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            SheetAction(Icons.Outlined.Share, stringResource(UiR.string.action_share)) {
                ActionIntents.shareText(context, context.getString(UiR.string.share_number_text, entry.displayNumber))
                onDismiss()
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun CallFilter.labelRes(): Int = when (this) {
    CallFilter.ALL -> R.string.calls_tab_all
    CallFilter.MISSED -> R.string.calls_tab_missed
    CallFilter.INCOMING -> R.string.calls_tab_incoming
    CallFilter.OUTGOING -> R.string.calls_tab_outgoing
    CallFilter.SPAM -> R.string.calls_tab_spam
    CallFilter.UNKNOWN -> R.string.calls_tab_unknown
}

private fun CallType.labelRes(): Int = when (this) {
    CallType.INCOMING -> R.string.calls_type_incoming
    CallType.OUTGOING -> R.string.calls_type_outgoing
    CallType.MISSED -> R.string.calls_type_missed
    CallType.REJECTED -> R.string.calls_type_rejected
    CallType.BLOCKED -> R.string.calls_type_blocked
    CallType.VOICEMAIL -> R.string.calls_type_voicemail
    CallType.UNKNOWN -> R.string.calls_type_other
}

private fun CallType.icon(): ImageVector = when (this) {
    CallType.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
    CallType.MISSED -> Icons.AutoMirrored.Filled.CallMissed
    CallType.BLOCKED, CallType.REJECTED -> Icons.Filled.Block
    else -> Icons.AutoMirrored.Filled.CallReceived
}

/** Opens WhoCaller's own keypad (feature:dialer), whether or not it is the default phone app. */
private fun openKeypad(context: Context) {
    context.startActivity(Intent().setClassName(context, "com.rskusum.whocaller.feature.dialer.DialerActivity"))
}
