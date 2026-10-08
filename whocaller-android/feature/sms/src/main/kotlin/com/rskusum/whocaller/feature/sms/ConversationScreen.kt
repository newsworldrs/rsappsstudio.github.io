package com.rskusum.whocaller.feature.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.model.SmsMessage
import com.rskusum.whocaller.core.ui.component.WarningBanner
import com.rskusum.whocaller.core.ui.util.callTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

data class ConversationUiState(
    val threadId: Long = 0,
    val address: String = "",
    val title: String = "",
    val messages: List<SmsMessage> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val error: Int? = null,
    val canSend: Boolean = false,
)

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val smsRepository: SmsRepository,
    private val contactsRepository: ContactsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ConversationUiState(
            threadId = savedStateHandle.get<Long>(ARG_THREAD) ?: 0L,
            address = savedStateHandle.get<String>(ARG_ADDRESS).orEmpty(),
            draft = savedStateHandle.get<String>(ARG_BODY).orEmpty(),
        ),
    )
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()
    private var watcher: Job? = null

    init {
        viewModelScope.launch {
            val s = _state.value
            val title = s.address.takeIf { it.isNotBlank() }?.let { contactsRepository.lookupContactName(it) } ?: s.address
            val thread = if (s.threadId == 0L && s.address.isNotBlank()) smsRepository.threadIdFor(s.address) else s.threadId
            _state.update { it.copy(title = title, threadId = thread) }
            load()
        }
        watcher = viewModelScope.launch { smsRepository.changes().collect { load() } }
    }

    fun refreshPermissions() = _state.update { it.copy(canSend = smsRepository.isDefaultSmsApp() && smsRepository.canSend()) }

    fun onAddress(value: String) = _state.update { it.copy(address = value.take(40), title = value.take(40)) }

    fun onDraft(value: String) = _state.update { it.copy(draft = value.take(MAX_LENGTH), error = null) }

    fun send() {
        val s = _state.value
        if (s.draft.isBlank() || s.address.isBlank() || s.sending) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (smsRepository.send(s.address, s.draft)) {
                is AppResult.Success -> {
                    val thread = if (s.threadId == 0L) smsRepository.threadIdFor(s.address) else s.threadId
                    _state.update { it.copy(draft = "", sending = false, threadId = thread) }
                    load()
                }
                is AppResult.Failure -> _state.update { it.copy(sending = false, error = R.string.sms_send_failed) }
            }
        }
    }

    private suspend fun load() {
        val thread = _state.value.threadId
        if (thread == 0L) return
        val messages = smsRepository.loadThread(thread, THREAD_LIMIT)
        _state.update { it.copy(messages = messages, address = it.address.ifBlank { messages.firstOrNull()?.address.orEmpty() }) }
        smsRepository.markThreadRead(thread)
    }

    companion object {
        const val ARG_THREAD = "threadId"
        const val ARG_ADDRESS = "address"
        const val ARG_BODY = "body"
        private const val THREAD_LIMIT = 300
        private const val MAX_LENGTH = 1_600
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(onBack: () -> Unit, viewModel: ConversationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    val newConversation = state.threadId == 0L && state.messages.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { stringResource(R.string.sms_new_message) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (newConversation) {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddress,
                    label = { Text(stringResource(R.string.sms_to)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { MessageBubble(it) }
            }
            if (!state.canSend) {
                Text(
                    stringResource(R.string.sms_cannot_send),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::onDraft,
                    placeholder = { Text(stringResource(R.string.sms_type_message)) },
                    enabled = state.canSend,
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = viewModel::send,
                    enabled = state.canSend && !state.sending && state.draft.isNotBlank() && state.address.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.sms_send))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: SmsMessage) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val suspicious = !message.outgoing &&
        (message.classification.category == SmsCategory.SCAM || message.classification.category == SmsCategory.SPAM)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp)) {
            if (suspicious) {
                WarningBanner(
                    title = stringResource(R.string.sms_suspicious),
                    message = if (message.classification.category == SmsCategory.SCAM) stringResource(R.string.sms_possible_phishing) else null,
                    severe = message.classification.category == SmsCategory.SCAM,
                )
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(message.body)
                    Text(
                        callTime(context, message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
