package com.rskusum.whocaller.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.whocaller.core.model.Country
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.LoadingState
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.util.relativeTime
import java.util.Locale
import com.rskusum.whocaller.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onReport: (String) -> Unit,
    onOpenBusiness: (String) -> Unit,
    onBusinessDirectory: () -> Unit,
    adBanner: @Composable () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val historyEnabled by viewModel.historyEnabled.collectAsStateWithLifecycle()
    var showCountries by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val country = remember(state.region) { viewModel.countries.firstOrNull { it.regionCode == state.region } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                actions = {
                    IconButton(onClick = onBusinessDirectory) {
                        Icon(Icons.Outlined.Storefront, contentDescription = stringResource(R.string.search_businesses))
                    }
                },
            )
        },
        bottomBar = { adBanner() },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(stringResource(R.string.search_hint)) },
                placeholder = { Text(viewModel.placeholder(state.region)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearResult) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(UiR.string.action_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    viewModel.search()
                }),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AssistChip(
                    onClick = { showCountries = true },
                    label = {
                        Text(
                            stringResource(
                                R.string.search_country,
                                country?.let { displayName(it) } ?: state.region,
                                country?.callingCode ?: 0,
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                )
                TextButton(
                    onClick = {
                        focus.clearFocus()
                        viewModel.search()
                    },
                    enabled = state.query.any { it.isDigit() },
                ) { Text(stringResource(R.string.search_button)) }
            }

            when (val r = state.result) {
                SearchResultState.Loading -> LoadingState(Modifier.weight(1f))
                is SearchResultState.Found -> NumberResultContent(
                    lookup = r.lookup,
                    onReport = { onReport(r.lookup.number.e164 ?: r.lookup.number.raw) },
                    onToggleBlock = viewModel::toggleBlock,
                    onOpenBusiness = onOpenBusiness,
                    modifier = Modifier.weight(1f),
                )
                SearchResultState.Hidden -> EmptyState(Icons.Filled.Search, stringResource(R.string.search_hidden))
                is SearchResultState.Invalid -> EmptyState(Icons.Filled.Search, stringResource(R.string.search_invalid))
                SearchResultState.Idle -> SearchHistoryList(
                    history = history,
                    enabled = historyEnabled,
                    onSelect = viewModel::searchFromHistory,
                    onDelete = viewModel::deleteHistory,
                    onClear = viewModel::clearHistory,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showCountries) {
        CountryPickerDialog(
            countries = viewModel.countries,
            onPick = {
                viewModel.onRegionChange(it.regionCode)
                showCountries = false
            },
            onDismiss = { showCountries = false },
        )
    }
}

@Composable
private fun SearchHistoryList(
    history: List<com.rskusum.whocaller.core.model.SearchHistoryItem>,
    enabled: Boolean,
    onSelect: (com.rskusum.whocaller.core.model.SearchHistoryItem) -> Unit,
    onDelete: (com.rskusum.whocaller.core.model.SearchHistoryItem) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SectionHeader(stringResource(R.string.search_recent)) {
                if (history.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_all)) }
            }
        }
        if (!enabled) {
            item { Text(stringResource(R.string.search_history_off), Modifier.padding(horizontal = 16.dp)) }
        } else if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.search_empty_history),
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(history, key = { it.id }) { item ->
            ListItem(
                headlineContent = { Text(item.displayNumber) },
                supportingContent = { Text(relativeTime(item.searchedAt)) },
                leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.search_delete_item, item.displayNumber))
                    }
                },
                modifier = Modifier.clickable { onSelect(item) },
            )
        }
    }
}

fun displayName(country: Country): String =
    Locale("", country.regionCode).displayCountry.ifBlank { country.name }

@Composable
fun CountryPickerDialog(countries: List<Country>, onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    val filtered = remember(filter, countries) {
        val f = filter.trim().lowercase()
        if (f.isEmpty()) {
            countries
        } else {
            countries.filter {
                displayName(it).lowercase().contains(f) || it.regionCode.lowercase() == f || it.callingCode.toString().startsWith(f.removePrefix("+"))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_country_picker)) },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it.take(40) },
                    label = { Text(stringResource(R.string.search_country_filter)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(filtered, key = { it.regionCode }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(displayName(c), Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text("+${c.callingCode}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_close)) } },
    )
}
