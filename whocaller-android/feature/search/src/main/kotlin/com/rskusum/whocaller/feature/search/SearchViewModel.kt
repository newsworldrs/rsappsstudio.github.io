package com.rskusum.whocaller.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.SearchHistoryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.domain.usecase.NumberLookup
import com.rskusum.whocaller.core.domain.usecase.SearchNumberUseCase
import com.rskusum.whocaller.core.domain.usecase.SearchOutcome
import com.rskusum.whocaller.core.model.Country
import com.rskusum.whocaller.core.model.SearchHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchResultState {
    data object Idle : SearchResultState
    data object Loading : SearchResultState
    data class Found(val lookup: NumberLookup) : SearchResultState
    data object Hidden : SearchResultState
    data class Invalid(val reason: NormalizationResult.Reason) : SearchResultState
}

data class SearchUiState(
    val query: String = "",
    val region: String = "US",
    val result: SearchResultState = SearchResultState.Idle,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val searchNumber: SearchNumberUseCase,
    private val historyRepository: SearchHistoryRepository,
    private val blockRepository: BlockRepository,
    private val blockNumber: BlockNumberUseCase,
    private val countryRepository: CountryRepository,
    private val normalizer: PhoneNumberNormalizer,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val history: StateFlow<List<SearchHistoryItem>> = historyRepository.observe(HISTORY_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyEnabled: StateFlow<Boolean> = settingsRepository.settings.map { it.searchHistoryEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val countries: List<Country> get() = countryRepository.countries()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val region = countryRepository.defaultRegion()
            _state.update { it.copy(region = region) }
            val initial = savedStateHandle.get<String>(ARG_QUERY)?.takeIf { it.isNotBlank() }
            if (initial != null) {
                _state.update { it.copy(query = initial) }
                search(recordHistory = savedStateHandle.get<Boolean>(ARG_RECORD) ?: true)
            }
        }
    }

    fun onQueryChange(value: String) {
        // Keep digits and common phone punctuation only; cap length.
        val filtered = value.filter { it.isDigit() || it in "+-() ." }.take(MAX_QUERY)
        _state.update { it.copy(query = filtered, result = if (filtered.isEmpty()) SearchResultState.Idle else it.result) }
    }

    fun onRegionChange(region: String) {
        _state.update { it.copy(region = region) }
    }

    fun placeholder(region: String): String = normalizer.exampleNumber(region) ?: "+1 650 253 0000"

    fun search(recordHistory: Boolean = true) {
        val s = _state.value
        if (s.query.isBlank()) return
        searchJob?.cancel()
        _state.update { it.copy(result = SearchResultState.Loading) }
        searchJob = viewModelScope.launch {
            val outcome = searchNumber(s.query, regionOverride = s.region, recordHistory = recordHistory)
            _state.update {
                it.copy(
                    result = when (outcome) {
                        is SearchOutcome.Found -> SearchResultState.Found(outcome.lookup)
                        SearchOutcome.HiddenNumber -> SearchResultState.Hidden
                        is SearchOutcome.InvalidNumber -> SearchResultState.Invalid(outcome.reason)
                    },
                )
            }
        }
    }

    fun searchFromHistory(item: SearchHistoryItem) {
        _state.update { it.copy(query = item.displayNumber) }
        search(recordHistory = true)
    }

    fun clearResult() = _state.update { it.copy(result = SearchResultState.Idle, query = "") }

    fun deleteHistory(item: SearchHistoryItem) = viewModelScope.launch { historyRepository.delete(item.id) }

    fun clearHistory() = viewModelScope.launch { historyRepository.clear() }

    fun toggleBlock() {
        val lookup = (_state.value.result as? SearchResultState.Found)?.lookup ?: return
        viewModelScope.launch {
            if (lookup.isBlocked) {
                blockNumber.unblock(lookup.number.key)
            } else {
                blockNumber.block(lookup.number.e164 ?: lookup.number.raw, lookup.info?.displayName, lookup.score.category)
            }
            val nowBlocked = blockRepository.isBlocked(lookup.number.key)
            _state.update { st ->
                val r = st.result
                if (r is SearchResultState.Found) st.copy(result = r.copy(lookup = r.lookup.copy(isBlocked = nowBlocked))) else st
            }
        }
    }

    companion object {
        const val ARG_QUERY = "query"
        const val ARG_RECORD = "record"
        private const val HISTORY_LIMIT = 30
        private const val MAX_QUERY = 24
    }
}
