package com.rskusum.whocaller.feature.contacts

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.LoadingState
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions
import com.rskusum.whocaller.core.ui.util.callTime
import com.rskusum.whocaller.core.ui.util.formatDuration
import com.rskusum.whocaller.core.ui.util.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

data class ContactDetailState(
    val loading: Boolean = true,
    val contact: Contact? = null,
    val calls: List<CallLogEntry> = emptyList(),
)

sealed interface ContactEvent {
    data object Deleted : ContactEvent
    data class Error(val error: AppError) : ContactEvent
    data class Blocked(val number: String) : ContactEvent
}

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactsRepository: ContactsRepository,
    private val callLogRepository: CallLogRepository,
    private val blockNumber: BlockNumberUseCase,
) : ViewModel() {
    private val contactId: Long = savedStateHandle.get<Long>(ARG_ID) ?: -1L
    private val _state = MutableStateFlow(ContactDetailState())
    val state: StateFlow<ContactDetailState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ContactEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ContactEvent> = _events.asSharedFlow()

    fun load() = viewModelScope.launch {
        val contact = contactsRepository.getContact(contactId)
        val calls = contact?.phones?.flatMap { callLogRepository.callsForNumber(it.numberKey, 10) }
            ?.sortedByDescending { it.timestamp }?.take(20).orEmpty()
        _state.value = ContactDetailState(loading = false, contact = contact, calls = calls)
    }

    fun toggleFavorite() = viewModelScope.launch {
        val c = _state.value.contact ?: return@launch
        when (val r = contactsRepository.setStarred(c.id, !c.starred)) {
            is AppResult.Success -> _state.value = _state.value.copy(contact = c.copy(starred = !c.starred))
            is AppResult.Failure -> _events.emit(ContactEvent.Error(r.error))
        }
    }

    fun delete() = viewModelScope.launch {
        val c = _state.value.contact ?: return@launch
        when (val r = contactsRepository.deleteContact(c.id)) {
            is AppResult.Success -> _events.emit(ContactEvent.Deleted)
            is AppResult.Failure -> _events.emit(ContactEvent.Error(r.error))
        }
    }

    fun block(number: String) = viewModelScope.launch {
        blockNumber.block(number, _state.value.contact?.displayName)
        _events.emit(ContactEvent.Blocked(number))
    }

    companion object {
        const val ARG_ID = "contactId"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    onSearchNumber: (String) -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pendingWrite by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    // WRITE_CONTACTS is requested only when the user stars or deletes a contact.
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingWrite
        pendingWrite = null
        if (!granted) {
            Toast.makeText(context, R.string.contact_write_needed, Toast.LENGTH_SHORT).show()
        } else if (action == "star") {
            viewModel.toggleFavorite()
        } else if (action == "delete") {
            confirmDelete = true
        }
    }
    fun withWrite(action: String, block: () -> Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            block()
        } else {
            pendingWrite = action
            writeLauncher.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ContactEvent.Deleted -> onBack()
                is ContactEvent.Error -> Toast.makeText(context, event.error.messageRes(), Toast.LENGTH_SHORT).show()
                is ContactEvent.Blocked -> Toast.makeText(context, event.number, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val contact = state.contact
    val videoSupported = androidx.compose.runtime.remember { TelecomActions.supportsVideoCalling(context) }
    val hasWhatsApp = androidx.compose.runtime.remember { TelecomActions.whatsAppPackage(context) != null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contact_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
                actions = {
                    if (contact != null) {
                        IconButton(onClick = { withWrite("star") { viewModel.toggleFavorite() } }) {
                            Icon(
                                if (contact.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(
                                    if (contact.starred) R.string.contact_favorite_remove else R.string.contact_favorite_add,
                                ),
                            )
                        }
                        IconButton(onClick = { ActionIntents.editContact(context, contact.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.contact_edit))
                        }
                        IconButton(onClick = { withWrite("delete") { confirmDelete = true } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.contact_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            contact == null -> EmptyState(Icons.Outlined.Search, stringResource(R.string.contact_not_found), modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CallerAvatar(contact.displayName, CallerLabel.CONTACT, size = 88.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(contact.displayName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    }
                }
                items(contact.phones, key = { it.numberKey + it.number }) { phone ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(phone.number, style = MaterialTheme.typography.titleMedium)
                        phone.label?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DetailAction(Icons.Outlined.Call, stringResource(UiR.string.action_call)) { ActionIntents.dial(context, phone.number) }
                            DetailAction(Icons.AutoMirrored.Outlined.Message, stringResource(UiR.string.action_message)) { ActionIntents.message(context, phone.number) }
                            // Video calls over the carrier network only when the SIM's phone account supports them.
                            if (videoSupported) {
                                DetailAction(Icons.Outlined.Videocam, stringResource(UiR.string.action_video_call)) {
                                    TelecomActions.placeCall(context, phone.number, video = true)
                                }
                            }
                            if (hasWhatsApp && phone.numberKey.startsWith("+")) {
                                DetailAction(Icons.AutoMirrored.Outlined.Chat, stringResource(UiR.string.action_whatsapp)) {
                                    TelecomActions.openWhatsApp(context, phone.numberKey)
                                }
                            }
                            DetailAction(Icons.Outlined.Search, stringResource(UiR.string.action_search)) { onSearchNumber(phone.number) }
                            DetailAction(Icons.Outlined.Block, stringResource(UiR.string.action_block)) { viewModel.block(phone.number) }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.contact_recent_calls)) }
                if (state.calls.isEmpty()) {
                    item { Text(stringResource(R.string.contact_no_calls), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(state.calls, key = { it.id }) { call ->
                    ListItem(
                        headlineContent = { Text(callTime(context, call.timestamp)) },
                        supportingContent = { Text("${call.displayNumber} · ${formatDuration(call.durationSeconds)}") },
                        leadingContent = { Icon(Icons.Outlined.Call, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (confirmDelete && contact != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.contact_delete)) },
            text = { Text(stringResource(R.string.contact_delete_confirm, contact.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(UiR.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(UiR.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
