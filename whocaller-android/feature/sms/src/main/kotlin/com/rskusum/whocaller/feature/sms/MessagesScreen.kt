package com.rskusum.whocaller.feature.sms

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.model.SmsClassification
import com.rskusum.whocaller.core.model.SmsConversation
import com.rskusum.whocaller.core.model.SmsSignal
import com.rskusum.whocaller.core.permissions.PermissionManager
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.PrimaryWideButton
import com.rskusum.whocaller.core.ui.component.WarningBanner
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

data class MessagesUiState(
    val sender: String = "",
    val text: String = "",
    val result: SmsClassification? = null,
    val canRead: Boolean = false,
    val isDefault: Boolean = false,
    val conversations: List<SmsConversation> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val classifier: SmsClassifier,
    private val smsRepository: SmsRepository,
    private val permissionManager: PermissionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()
    private var watcher: Job? = null

    /** True when the screen was opened with shared text, so the checker tab opens first. */
    val openedWithText: Boolean

    init {
        val shared = savedStateHandle.get<String>(ARG_TEXT)?.takeIf { it.isNotBlank() }
        openedWithText = shared != null
        if (shared != null) {
            _state.update { it.copy(text = shared.take(MAX_TEXT)) }
            check()
        }
        refresh()
    }

    fun refresh() {
        val canRead = smsRepository.isInboxAvailable()
        _state.update { it.copy(canRead = canRead, isDefault = smsRepository.isDefaultSmsApp()) }
        if (!canRead) {
            _state.update { it.copy(loaded = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(conversations = smsRepository.loadConversations(CONVERSATION_LIMIT), loaded = true) }
        }
        if (watcher == null) {
            watcher = viewModelScope.launch {
                smsRepository.changes().collect {
                    _state.update { it.copy(conversations = smsRepository.loadConversations(CONVERSATION_LIMIT)) }
                }
            }
        }
    }

    fun defaultSmsIntent() = permissionManager.defaultSmsIntent()

    fun onSender(v: String) = _state.update { it.copy(sender = v.take(40), result = null) }
    fun onText(v: String) = _state.update { it.copy(text = v.take(MAX_TEXT), result = null) }

    fun check() {
        val s = _state.value
        if (s.text.isBlank()) return
        _state.update { it.copy(result = classifier.classify(s.sender, s.text)) }
    }

    companion object {
        const val ARG_TEXT = "text"
        private const val MAX_TEXT = 2_000
        private const val CONVERSATION_LIMIT = 200
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBack: () -> Unit,
    onOpenConversation: (threadId: Long, address: String) -> Unit,
    onNewMessage: () -> Unit,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if (viewModel.openedWithText) 1 else 0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sms_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0 && state.isDefault) {
                ExtendedFloatingActionButton(
                    onClick = onNewMessage,
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.sms_new_message)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.sms_tab_conversations)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.sms_tab_check)) })
            }
            if (tab == 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (!state.isDefault) {
                        item {
                            Card(
                                Modifier.fillMaxWidth().padding(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(stringResource(R.string.sms_make_default), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.sms_make_default_desc), style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { viewModel.defaultSmsIntent()?.let { roleLauncher.launch(it) } }) {
                                        Text(stringResource(R.string.sms_make_default))
                                    }
                                    if (!state.canRead) {
                                        OutlinedButton(onClick = { readLauncher.launch(Manifest.permission.READ_SMS) }) {
                                            Text(stringResource(R.string.sms_allow_read))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!state.canRead) {
                        item { Text(stringResource(R.string.sms_inbox_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else if (state.loaded && state.conversations.isEmpty()) {
                        item { EmptyState(Icons.Filled.Edit, stringResource(R.string.sms_no_conversations)) }
                    }
                    items(state.conversations, key = { it.threadId }) { c -> ConversationRow(c) { onOpenConversation(c.threadId, c.address) } }
                }
            } else {
                CheckMessageTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun ConversationRow(c: SmsConversation, onClick: () -> Unit) {
    val suspicious = c.classification.category == SmsCategory.SCAM || c.classification.category == SmsCategory.SPAM
    val unread = c.unreadCount > 0
    ListItem(
        headlineContent = {
            Text(
                c.displayName ?: c.address,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                if (suspicious) {
                    Text(
                        "⚠ " + stringResource(R.string.sms_warning_short),
                        color = WhoCallerTheme.riskColors.high,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(c.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        overlineContent = { Text(relativeTime(c.timestamp)) },
        leadingContent = { CallerAvatar(c.displayName, if (suspicious) CallerLabel.SUSPECTED_SPAM else CallerLabel.PERSON) },
        trailingContent = if (unread) {
            { Badge { Text(c.unreadCount.toString()) } }
        } else if (suspicious) {
            { Icon(Icons.Filled.Warning, contentDescription = null, tint = WhoCallerTheme.riskColors.high) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun CheckMessageTab(state: MessagesUiState, viewModel: MessagesViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.sms_check_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sms_check_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.sender,
                onValueChange = viewModel::onSender,
                label = { Text(stringResource(R.string.sms_sender)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onText,
                label = { Text(stringResource(R.string.sms_text)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryWideButton(stringResource(R.string.sms_check_button), onClick = viewModel::check, enabled = state.text.isNotBlank())
        }
        state.result?.let { result ->
            item {
                Spacer(Modifier.height(16.dp))
                ClassificationCard(result, preview = null)
            }
        }
    }
}

@Composable
fun ClassificationCard(result: SmsClassification, preview: String?) {
    val suspicious = result.category == SmsCategory.SCAM || result.category == SmsCategory.SPAM
    Column {
        if (suspicious) {
            WarningBanner(
                title = stringResource(R.string.sms_suspicious),
                message = listOfNotNull(preview, if (result.category == SmsCategory.SCAM) stringResource(R.string.sms_possible_phishing) else null)
                    .joinToString("\n").ifBlank { null },
                severe = result.category == SmsCategory.SCAM,
            )
            Spacer(Modifier.height(8.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.sms_result_title) + ": " + stringResource(result.category.labelRes()), style = MaterialTheme.typography.titleMedium)
                if (result.signals.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sms_why), style = MaterialTheme.typography.labelLarge)
                    result.signals.sortedBy { it.ordinal }.forEach { Text("• " + stringResource(it.labelRes())) }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (suspicious) R.string.sms_advice else R.string.sms_looks_ok), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun SmsCategory.labelRes(): Int = when (this) {
    SmsCategory.PERSONAL -> R.string.sms_cat_personal
    SmsCategory.TRANSACTIONS -> R.string.sms_cat_transactions
    SmsCategory.PROMOTIONS -> R.string.sms_cat_promotions
    SmsCategory.SPAM -> R.string.sms_cat_spam
    SmsCategory.SCAM -> R.string.sms_cat_scam
    SmsCategory.UNKNOWN -> R.string.sms_cat_unknown
}

internal fun SmsSignal.labelRes(): Int = when (this) {
    SmsSignal.CONTAINS_LINK -> R.string.sms_sig_link
    SmsSignal.SHORTENED_LINK -> R.string.sms_sig_short_link
    SmsSignal.URGENCY -> R.string.sms_sig_urgency
    SmsSignal.PRIZE_OR_LOTTERY -> R.string.sms_sig_prize
    SmsSignal.ACCOUNT_THREAT -> R.string.sms_sig_account
    SmsSignal.CREDENTIAL_REQUEST -> R.string.sms_sig_credentials
    SmsSignal.PAYMENT_REQUEST -> R.string.sms_sig_payment
    SmsSignal.OTP -> R.string.sms_sig_otp
    SmsSignal.TRANSACTION -> R.string.sms_sig_transaction
    SmsSignal.PROMOTION -> R.string.sms_sig_promotion
    SmsSignal.SENDER_BLOCKED -> R.string.sms_sig_blocked
    SmsSignal.SENDER_REPORTED -> R.string.sms_sig_reported
}
