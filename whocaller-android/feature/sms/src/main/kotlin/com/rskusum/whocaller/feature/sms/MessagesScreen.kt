package com.rskusum.whocaller.feature.sms

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.repository.SmsRepository
import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.model.SmsClassification
import com.rskusum.whocaller.core.model.SmsMessage
import com.rskusum.whocaller.core.model.SmsSignal
import com.rskusum.whocaller.core.ui.component.PrimaryWideButton
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.component.WarningBanner
import com.rskusum.whocaller.core.ui.util.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val inboxAvailable: Boolean = false,
    val inbox: List<SmsMessage> = emptyList(),
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val classifier: SmsClassifier,
    private val smsRepository: SmsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState(inboxAvailable = smsRepository.isInboxAvailable()))
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    init {
        // Text shared into WhoCaller ("Share → WhoCaller") is checked immediately.
        savedStateHandle.get<String>(ARG_TEXT)?.takeIf { it.isNotBlank() }?.let {
            _state.update { s -> s.copy(text = it.take(MAX_TEXT)) }
            check()
        }
        if (_state.value.inboxAvailable) {
            viewModelScope.launch { _state.update { it.copy(inbox = smsRepository.loadInbox(INBOX_LIMIT)) } }
        }
    }

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
        private const val INBOX_LIMIT = 100
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(onBack: () -> Unit, viewModel: MessagesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
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
            item { SectionHeader(stringResource(R.string.sms_inbox), Modifier.padding(top = 16.dp)) }
            if (!state.inboxAvailable) {
                item { Text(stringResource(R.string.sms_inbox_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (state.inbox.isEmpty()) {
                item { Text(stringResource(R.string.sms_inbox_empty)) }
            }
            items(state.inbox, key = { it.id }) { message ->
                ListItem(
                    overlineContent = { Text("${message.address} · ${relativeTime(message.timestamp)}") },
                    headlineContent = { Text(message.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(stringResource(message.classification.category.labelRes())) },
                )
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
                Text(
                    stringResource(if (suspicious) R.string.sms_advice else R.string.sms_looks_ok),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun SmsCategory.labelRes(): Int = when (this) {
    SmsCategory.PERSONAL -> R.string.sms_cat_personal
    SmsCategory.TRANSACTIONS -> R.string.sms_cat_transactions
    SmsCategory.PROMOTIONS -> R.string.sms_cat_promotions
    SmsCategory.SPAM -> R.string.sms_cat_spam
    SmsCategory.SCAM -> R.string.sms_cat_scam
    SmsCategory.UNKNOWN -> R.string.sms_cat_unknown
}

private fun SmsSignal.labelRes(): Int = when (this) {
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
