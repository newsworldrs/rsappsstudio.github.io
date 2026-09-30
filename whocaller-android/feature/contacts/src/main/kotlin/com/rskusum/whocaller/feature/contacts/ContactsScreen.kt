package com.rskusum.whocaller.feature.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.util.ActionIntents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

data class ContactsUiState(
    val hasPermission: Boolean = false,
    val query: String = "",
    val favorites: List<Contact> = emptyList(),
    /** Contacts grouped by first letter, in display order. */
    val sections: List<Pair<Char, List<Contact>>> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val permission = MutableStateFlow(repository.hasPermission())

    val state: StateFlow<ContactsUiState> = combine(query, permission) { q, p -> q to p }
        .debounce(150)
        .flatMapLatest { (q, p) ->
            if (!p) {
                kotlinx.coroutines.flow.flowOf(ContactsUiState(hasPermission = false, query = q, loaded = true))
            } else {
                repository.observeContacts(q).map { contacts -> build(q, contacts) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState(hasPermission = repository.hasPermission()))

    private val _queryText = MutableStateFlow("")
    val queryText: StateFlow<String> = _queryText.asStateFlow()

    fun onQuery(value: String) {
        _queryText.value = value.take(60)
        query.value = value.take(60)
    }

    fun refreshPermission() {
        permission.value = repository.hasPermission()
    }

    private fun build(q: String, contacts: List<Contact>): ContactsUiState {
        val favorites = if (q.isBlank()) contacts.filter { it.starred } else emptyList()
        val sections = contacts.groupBy { it.initial }.toList()
        return ContactsUiState(hasPermission = true, query = q, favorites = favorites, sections = sections, loaded = true)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpenContact: (Long) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queryText by viewModel.queryText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermission() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermission() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { ActionIntents.saveContact(context, "") }) {
                Icon(Icons.Filled.PersonAdd, contentDescription = stringResource(R.string.contacts_add))
            }
        },
    ) { padding ->
        if (!state.hasPermission) {
            EmptyState(
                icon = Icons.Outlined.Contacts,
                title = stringResource(R.string.contacts_permission_title),
                message = stringResource(R.string.contacts_permission_body),
                actionLabel = stringResource(R.string.contacts_permission_button),
                onAction = { launcher.launch(Manifest.permission.READ_CONTACTS) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = queryText,
                onValueChange = viewModel::onQuery,
                label = { Text(stringResource(R.string.contacts_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.loaded && state.sections.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Contacts,
                    title = stringResource(if (state.query.isBlank()) R.string.contacts_empty else R.string.contacts_no_match),
                )
                return@Column
            }
            // Precompute list index of each section header for the alphabetical index.
            val headerIndex = remember(state) {
                val map = LinkedHashMap<Char, Int>()
                var i = if (state.favorites.isNotEmpty()) state.favorites.size + 1 else 0
                state.sections.forEach { (letter, list) ->
                    map[letter] = i
                    i += list.size + 1
                }
                map
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 28.dp)) {
                    if (state.favorites.isNotEmpty()) {
                        item(key = "fav_header") { SectionHeader(stringResource(R.string.contacts_favorites)) }
                        items(state.favorites, key = { "fav_${it.id}" }) { ContactRow(it, true) { onOpenContact(it.id) } }
                    }
                    state.sections.forEach { (letter, list) ->
                        item(key = "h_$letter") {
                            Text(
                                letter.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { heading() },
                            )
                        }
                        items(list, key = { "c_${it.id}" }) { ContactRow(it, false) { onOpenContact(it.id) } }
                    }
                }
                // Alphabetical index. Each letter is a 28dp-wide, ≥24dp-tall touch target.
                Column(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    headerIndex.keys.forEach { letter ->
                        val description = stringResource(R.string.contacts_index_letter, letter.toString())
                        Text(
                            letter.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .sizeIn(minWidth = 28.dp, minHeight = 20.dp)
                                .clickable(onClickLabel = description) {
                                    scope.launch { listState.scrollToItem(headerIndex[letter] ?: 0) }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, favorite: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(contact.displayName) },
        supportingContent = contact.phones.firstOrNull()?.let { p -> { Text(listOfNotNull(p.label, p.number).joinToString(" · ")) } },
        leadingContent = { CallerAvatar(contact.displayName, CallerLabel.CONTACT) },
        trailingContent = if (favorite) {
            { Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
