package com.rskusum.whocaller.feature.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.model.CallerResult
import com.rskusum.whocaller.core.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

/** What the keypad header shows about the number being typed. */
sealed interface DialLookup {
    data object Idle : DialLookup
    data object Searching : DialLookup
    data class Found(val result: CallerResult) : DialLookup
}

/** A saved contact matching the typed digits (by number or T9 name). */
data class DialSuggestion(val name: String, val number: String, val photoUri: String?)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DialerViewModel @Inject constructor(
    private val identification: CallerIdentificationManager,
    private val contacts: ContactsRepository,
) : ViewModel() {

    private val _number = MutableStateFlow("")
    val number: StateFlow<String> = _number.asStateFlow()

    fun setNumber(value: String) {
        _number.value = value.filter { it.isDigit() || it in "+*#," }.take(MAX_LENGTH)
    }

    fun append(c: Char) = setNumber(_number.value + c)

    fun backspace() = setNumber(_number.value.dropLast(1))

    fun clear() = setNumber("")

    /** Live caller ID while typing: local contacts and cache first, backend within a short budget. */
    val lookup: StateFlow<DialLookup> = _number
        .map { it.filter(Char::isDigit) }
        .distinctUntilChanged()
        .transformLatest { digits ->
            if (digits.length < MIN_LOOKUP_DIGITS) {
                emit(DialLookup.Idle)
                return@transformLatest
            }
            emit(DialLookup.Searching)
            delay(LOOKUP_DEBOUNCE_MS)
            val result = try {
                identification.identify(_number.value, networkBudgetMs = NETWORK_BUDGET_MS, record = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            emit(if (result != null) DialLookup.Found(result) else DialLookup.Idle)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DialLookup.Idle)

    private val allContacts = if (contacts.hasPermission()) contacts.observeContacts("") else flowOf(emptyList())

    /** Up to three contacts whose number contains the digits, or whose name matches them on a T9 keypad. */
    val suggestions: StateFlow<List<DialSuggestion>> = combine(
        _number.debounce(SUGGEST_DEBOUNCE_MS),
        allContacts,
    ) { typed, list -> match(typed, list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        const val MAX_LENGTH = 32
        private const val MIN_LOOKUP_DIGITS = 7
        private const val LOOKUP_DEBOUNCE_MS = 350L
        private const val SUGGEST_DEBOUNCE_MS = 120L
        private const val NETWORK_BUDGET_MS = 2_500L
        private const val MAX_SUGGESTIONS = 3

        private const val T9 = "22233344455566677778889999"

        internal fun t9(name: String): String = buildString {
            name.lowercase().forEach { c -> if (c in 'a'..'z') append(T9[c - 'a']) else if (c.isDigit()) append(c) }
        }

        internal fun match(typed: String, list: List<Contact>): List<DialSuggestion> {
            val digits = typed.filter(Char::isDigit)
            if (digits.length < 2) return emptyList()
            return list.asSequence()
                .flatMap { contact -> contact.phones.asSequence().map { contact to it.number } }
                .filter { (contact, phone) ->
                    phone.filter(Char::isDigit).contains(digits) ||
                        contact.displayName.split(' ').any { word -> t9(word).startsWith(digits) }
                }
                .distinctBy { (contact, _) -> contact.id }
                .take(MAX_SUGGESTIONS)
                .map { (contact, phone) -> DialSuggestion(contact.displayName, phone, contact.photoUri) }
                .toList()
        }
    }
}
