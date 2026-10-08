package com.rskusum.whocaller.feature.dialer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.BusinessRepository
import com.rskusum.whocaller.core.domain.repository.CallLogRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.model.Business
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.model.SpamScore
import com.rskusum.whocaller.core.model.CallFilter
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallerResult
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The typed number and where the editing cursor is (index into [text]). */
data class DialInput(val text: String = "", val cursor: Int = 0)

/** What the keypad header shows about the number being typed. */
sealed interface DialLookup {
    data object Idle : DialLookup
    data object Searching : DialLookup
    data class Found(
        val result: CallerResult,
        val contact: ContactCard? = null,
        val facts: NumberFacts? = null,
        val business: Business? = null,
    ) : DialLookup
}

/** A saved contact matching the typed digits (by number or T9 name). */
data class DialSuggestion(val contactId: Long, val name: String, val number: String, val photoUri: String?)

/** What the contact details sheet is about: a saved contact, a number, or both. */
data class DetailsTarget(val number: String? = null, val contactId: Long? = null)

/** Everything the details sheet shows. */
data class DetailsData(
    val number: String?,
    val card: ContactCard?,
    val result: CallerResult?,
    val facts: NumberFacts?,
    val blocked: Boolean,
    /** Calls with this number (all of a contact's numbers), newest first. */
    val history: List<CallLogEntry> = emptyList(),
    /** Normalized keys the history was matched on — used to delete it. */
    val historyKeys: List<String> = emptyList(),
    /** Normalized key of [number] (for block/unblock before the WhoCaller lookup returns). */
    val numberKey: String? = null,
) {
    val totalTalkSeconds: Long get() = history.sumOf { it.durationSeconds }
}

/** Filter chips of the Recents tab. */
enum class RecentsFilter(val filter: CallFilter) { ALL(CallFilter.ALL), MISSED(CallFilter.MISSED), OUTGOING(CallFilter.OUTGOING), INCOMING(CallFilter.INCOMING) }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DialerViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val identification: CallerIdentificationManager,
    private val contacts: ContactsRepository,
    private val callLog: CallLogRepository,
    private val businesses: BusinessRepository,
    private val blockRepository: BlockRepository,
    private val blockNumber: BlockNumberUseCase,
    private val settingsRepository: SettingsRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
) : ViewModel() {

    val countryIso: String = NumberTools.countryIso(app)

    // ---------- Number editing ----------

    private val _input = MutableStateFlow(DialInput())
    val input: StateFlow<DialInput> = _input.asStateFlow()

    private val number: Flow<String> = _input.map { it.text }.distinctUntilChanged()

    fun setNumber(value: String) {
        val clean = clean(value)
        _input.value = DialInput(clean, clean.length)
    }

    fun insert(c: Char) {
        _input.value = Editing.insert(_input.value, c.toString())
    }

    fun insertText(text: String) {
        _input.value = Editing.insert(_input.value, clean(text))
    }

    fun backspace() {
        _input.value = Editing.backspace(_input.value)
    }

    fun clear() = setNumber("")

    fun setCursor(index: Int) {
        val current = _input.value
        _input.value = current.copy(cursor = index.coerceIn(0, current.text.length))
    }

    // ---------- Live caller ID ----------

    /** Contact (with photo, company, address), WhoCaller result, operator and location — local first. */
    val lookup: StateFlow<DialLookup> = number
        .map { it.filter(Char::isDigit) to it }
        .distinctUntilChanged { a, b -> a.first == b.first }
        .transformLatest { (digits, raw) ->
            if (digits.length < MIN_LOOKUP_DIGITS) {
                emit(DialLookup.Idle)
                return@transformLatest
            }
            emit(DialLookup.Searching)
            delay(LOOKUP_DEBOUNCE_MS)
            val result = try {
                identification.identify(raw, networkBudgetMs = NETWORK_BUDGET_MS, record = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val (contact, facts) = withContext(Dispatchers.IO) {
                ContactLookup.byNumber(app, raw) to NumberTools.facts(raw, countryIso)
            }
            if (result == null) {
                emit(if (facts != null || contact != null) DialLookup.Found(unknown(), contact, facts) else DialLookup.Idle)
                return@transformLatest
            }
            emit(DialLookup.Found(result, contact, facts))
            val businessId = result.info?.businessId
            if (businessId != null) {
                val business = withTimeoutOrNull(NETWORK_BUDGET_MS) { businesses.getBusiness(businessId).getOrNull() }
                if (business != null) emit(DialLookup.Found(result, contact, facts, business))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DialLookup.Idle)

    private fun unknown() = CallerResult(
        number = null,
        contactName = null,
        info = null,
        spamScore = SpamScore.NONE,
        decision = CallDecision.ALLOW,
        reason = DecisionReason.NONE,
        label = CallerLabel.UNKNOWN,
    )

    /** Whether the number being shown is on the user's block list. */
    val blocked: StateFlow<Boolean> = lookup
        .map { (it as? DialLookup.Found)?.result?.number?.key }
        .distinctUntilChanged()
        .flatMapLatest { key -> if (key == null) flowOf(false) else blockRepository.observeIsBlocked(key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleBlock(raw: String, label: String?) {
        viewModelScope.launch {
            val key = (lookup.value as? DialLookup.Found)?.result?.number?.key
            if (blocked.value && key != null) blockNumber.unblock(key) else blockNumber.block(raw, label)
        }
    }

    /**
     * Details sheet, step 1: everything on the phone (contact, number facts, block state, call
     * history). Fast, so the sheet fills in at once; the WhoCaller lookup follows in [identifyDetails].
     */
    suspend fun loadDetailsLocal(target: DetailsTarget): DetailsData = withContext(Dispatchers.IO) {
        val card = target.contactId?.let { ContactLookup.details(app, it) } ?: target.number?.let { ContactLookup.byNumber(app, it) }
        val number = target.number ?: card?.phones?.firstOrNull()?.value
        val facts = number?.let { runCatching { NumberTools.facts(it, countryIso) }.getOrNull() }
        val raws = (card?.phones?.map { it.value }.orEmpty() + listOfNotNull(number)).distinct()
        val keys = raws.mapNotNull { keyFor(it) }.distinct()
        val ownKey = number?.let { keyFor(it) }
        val blocked = ownKey?.let { runCatching { blockRepository.isBlocked(it) }.getOrDefault(false) } ?: false
        val history = if (callLog.hasPermission()) {
            keys.flatMap { runCatching { callLog.callsForNumber(it, HISTORY_LIMIT) }.getOrDefault(emptyList()) }
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp }
        } else {
            emptyList()
        }
        DetailsData(number, card, null, facts, blocked, history, keys, ownKey)
    }

    /** Details sheet, step 2: WhoCaller identification (cache first, then a short network lookup). */
    suspend fun identifyDetails(number: String?): CallerResult? = number?.let {
        withContext(Dispatchers.IO) {
            try {
                identification.identify(it, networkBudgetMs = NETWORK_BUDGET_MS, record = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Re-reads only the call history (after deleting calls). */
    suspend fun reloadHistory(d: DetailsData): DetailsData = withContext(Dispatchers.IO) {
        val history = if (callLog.hasPermission()) {
            d.historyKeys.flatMap { runCatching { callLog.callsForNumber(it, HISTORY_LIMIT) }.getOrDefault(emptyList()) }
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp }
        } else {
            emptyList()
        }
        d.copy(history = history)
    }

    private suspend fun keyFor(raw: String): String? =
        (normalizer.normalize(raw, countryRepository.defaultRegion()) as? NormalizationResult.Parsed)?.number?.key

    // ---------- Deleting from the call log ----------

    fun canDeleteCalls(): Boolean = callLog.canDelete()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    /** Recents rows picked for deletion (long-press to start). */
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun toggleSelected(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = recents.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Messages for the UI after a delete ("3 calls deleted"). */
    private val _events = MutableStateFlow<Int?>(null)
    val deletedCount: StateFlow<Int?> = _events.asStateFlow()

    fun consumeDeleted() {
        _events.value = null
    }

    private fun report(result: AppResult<Int>) {
        _events.value = (result as? AppResult.Success)?.data ?: -1
        refreshPermissions()
    }

    fun deleteSelected() {
        val ids = _selected.value
        _selected.value = emptySet()
        viewModelScope.launch { report(callLog.delete(ids)) }
    }

    fun deleteCalls(ids: Collection<Long>) {
        viewModelScope.launch { report(callLog.delete(ids)) }
    }

    fun deleteHistory(keys: Collection<String>) {
        viewModelScope.launch { report(callLog.deleteForNumbers(keys)) }
    }

    fun clearCallLog() {
        viewModelScope.launch { report(callLog.clearAll()) }
    }

    fun setBlocked(number: String, numberKey: String?, label: String?, block: Boolean) {
        viewModelScope.launch {
            if (block) blockNumber.block(number, label) else if (numberKey != null) blockNumber.unblock(numberKey)
        }
    }

    // ---------- Contacts, favorites, suggestions ----------

    /** Bumped when permissions may have changed (e.g. on resume). */
    private val permissionTick = MutableStateFlow(0)

    fun refreshPermissions() {
        searchPool = null
        permissionTick.value++
    }

    private val _contactsPermission = MutableStateFlow(contacts.hasPermission())
    val contactsPermission: StateFlow<Boolean> = _contactsPermission.asStateFlow()

    private val allContacts: StateFlow<List<Contact>> = permissionTick
        .map { contacts.hasPermission().also { _contactsPermission.value = it } }
        .distinctUntilChanged()
        .flatMapLatest { ok -> if (ok) contacts.observeContacts("") else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _contactQuery = MutableStateFlow("")
    val contactQuery: StateFlow<String> = _contactQuery.asStateFlow()

    fun setContactQuery(q: String) {
        _contactQuery.value = q.take(60)
    }

    val contactList: StateFlow<List<Contact>> = combine(allContacts, _contactQuery.debounce(120)) { list, q ->
        val query = q.trim()
        if (query.isEmpty()) {
            list
        } else {
            val digits = query.filter(Char::isDigit)
            list.filter { c ->
                c.displayName.contains(query, ignoreCase = true) ||
                    (digits.length >= 2 && c.phones.any { it.number.filter(Char::isDigit).contains(digits) })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recent calls searched along with contacts (loaded once per dialer visit, refreshed on changes). */
    @Volatile private var searchPool: List<CallLogEntry>? = null

    private suspend fun recentPool(): List<CallLogEntry> =
        searchPool ?: runCatching { callLog.loadPage(CallFilter.ALL, 0, SEARCH_POOL) }.getOrDefault(emptyList()).also { searchPool = it }

    /**
     * Numbers from recent calls that match the contact search but aren't saved as contacts
     * (by digits, or by the WhoCaller name), one row per number, newest first.
     */
    val recentMatches: StateFlow<List<CallLogEntry>> = combine(_contactQuery.debounce(150), allContacts) { q, contacts -> q.trim() to contacts }
        .mapLatest { (query, contacts) ->
            if (query.isEmpty() || !callLog.hasPermission()) return@mapLatest emptyList()
            val digits = query.filter(Char::isDigit)
            if (digits.isEmpty() && query.length < 2) return@mapLatest emptyList()
            val saved = contacts.flatMap { c -> c.phones.map { it.number.filter(Char::isDigit).takeLast(10) } }.toHashSet()
            recentPool()
                .asSequence()
                .filter { it.contactName == null && !it.isHidden }
                .filter { e ->
                    (digits.length >= 2 && e.rawNumber.filter(Char::isDigit).contains(digits)) ||
                        (digits.isEmpty() && e.cachedName?.contains(query, ignoreCase = true) == true)
                }
                .filter { e -> e.rawNumber.filter(Char::isDigit).takeLast(10) !in saved }
                .distinctBy { it.numberKey }
                .take(SEARCH_MATCHES)
                .toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<Contact>> = allContacts
        .map { list -> list.filter { it.starred } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saves the favourite flag. The UI has already shown the new state; returns false if it failed. */
    suspend fun setStarred(contactId: Long, starred: Boolean): Boolean {
        val ok = contacts.setStarred(contactId, starred) is AppResult.Success
        if (ok) refreshPermissions()
        return ok
    }

    /** Up to three contacts whose number contains the digits, or whose name matches them on a T9 keypad. */
    val suggestions: StateFlow<List<DialSuggestion>> = combine(number.debounce(SUGGEST_DEBOUNCE_MS), allContacts) { typed, list ->
        match(typed, list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------- Recents ----------

    private val _recentsFilter = MutableStateFlow(RecentsFilter.ALL)
    val recentsFilter: StateFlow<RecentsFilter> = _recentsFilter.asStateFlow()

    fun setRecentsFilter(filter: RecentsFilter) {
        _recentsFilter.value = filter
    }

    private val _callLogPermission = MutableStateFlow(callLog.hasPermission())
    val callLogPermission: StateFlow<Boolean> = _callLogPermission.asStateFlow()

    val recents: StateFlow<List<CallLogEntry>> = combine(permissionTick, _recentsFilter) { _, f -> f }
        .flatMapLatest { f ->
            val ok = callLog.hasPermission().also { _callLogPermission.value = it }
            if (!ok) {
                flowOf(emptyList())
            } else {
                // First screenful at once, the rest right after; later changes are batched.
                kotlinx.coroutines.flow.flow {
                    emit(runCatching { callLog.loadPage(f.filter, 0, RECENTS_FIRST) }.getOrDefault(emptyList()))
                    emit(runCatching { callLog.loadPage(f.filter, 0, RECENTS_LIMIT) }.getOrDefault(emptyList()))
                    emitAll(
                        callLog.changes().debounce(250).mapLatest {
                            runCatching { callLog.loadPage(f.filter, 0, RECENTS_LIMIT) }.getOrDefault(emptyList())
                        },
                    )
                }
            }
        }
        // Loaded as soon as the dialer opens, so the Recents tab is ready when it's tapped.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- Theme ----------

    val themeMode: StateFlow<ThemeMode> = settingsRepository.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val postCallPrompt: StateFlow<Boolean> = settingsRepository.settings
        .map { it.postCallPrompt }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setPostCallPrompt(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(postCallPrompt = enabled) } }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(themeMode = mode) } }
    }

    companion object {
        const val MAX_LENGTH = 32
        private const val MIN_LOOKUP_DIGITS = 7
        private const val LOOKUP_DEBOUNCE_MS = 350L
        private const val SUGGEST_DEBOUNCE_MS = 120L
        private const val NETWORK_BUDGET_MS = 2_500L
        private const val MAX_SUGGESTIONS = 3
        private const val RECENTS_LIMIT = 200
        private const val RECENTS_FIRST = 30
        private const val SEARCH_POOL = 1_000
        private const val SEARCH_MATCHES = 30
        private const val HISTORY_LIMIT = 200

        private const val T9 = "22233344455566677778889999"

        internal fun clean(value: String) = value.filter { it.isDigit() || it in "+*#," }.take(MAX_LENGTH)

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
                .map { (contact, phone) -> DialSuggestion(contact.id, contact.displayName, phone, contact.photoUri) }
                .toList()
        }
    }
}

/** Pure editing and cursor-mapping rules for the number field (unit tested). */
object Editing {

    fun insert(input: DialInput, text: String): DialInput {
        if (text.isEmpty()) return input
        val room = DialerViewModel.MAX_LENGTH - input.text.length
        if (room <= 0) return input
        val piece = text.take(room)
        val at = input.cursor.coerceIn(0, input.text.length)
        return DialInput(input.text.substring(0, at) + piece + input.text.substring(at), at + piece.length)
    }

    fun backspace(input: DialInput): DialInput {
        val at = input.cursor.coerceIn(0, input.text.length)
        if (at == 0) return input
        return DialInput(input.text.removeRange(at - 1, at), at - 1)
    }

    /** Characters of the raw number (formatting only adds spaces, dashes and brackets). */
    private fun isRaw(c: Char) = c.isDigit() || c in "+*#,"

    /** Position in [formatted] that corresponds to raw cursor [rawCursor]. */
    fun toFormatted(formatted: String, rawCursor: Int): Int {
        if (rawCursor <= 0) return 0
        var seen = 0
        formatted.forEachIndexed { i, c ->
            if (isRaw(c)) {
                seen++
                if (seen == rawCursor) return i + 1
            }
        }
        return formatted.length
    }

    /** Raw cursor for a position in [formatted]. */
    fun toRaw(formatted: String, offset: Int): Int = formatted.take(offset.coerceIn(0, formatted.length)).count(::isRaw)
}
