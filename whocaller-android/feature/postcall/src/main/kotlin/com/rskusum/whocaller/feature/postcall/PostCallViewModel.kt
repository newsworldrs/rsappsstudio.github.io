package com.rskusum.whocaller.feature.postcall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.model.CallerResult
import com.rskusum.whocaller.core.model.ReportCallType
import com.rskusum.whocaller.core.model.ReportCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

data class PostCallUiState(
    val number: String = "",
    val display: String = "",
    val answered: Boolean = false,
    /** Name WhoCaller knows for the number (dataset or community), if any. */
    val knownName: String? = null,
    val spamWarning: Boolean = false,
    val reportCount: Int = 0,
    val location: String? = null,
    val operator: String? = null,
    /** Set once the number is in the user's contacts (e.g. after "Add to Contacts"). */
    val savedContactId: Long? = null,
    val selected: List<ReportCategory> = emptyList(),
    val submitting: Boolean = false,
    val done: Boolean = false,
    val failed: Boolean = false,
) {
    val canSubmit: Boolean get() = selected.isNotEmpty() && !submitting && !done
}

@HiltViewModel
class PostCallViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    savedState: SavedStateHandle,
    private val identification: CallerIdentificationManager,
    private val spam: SpamRepository,
    private val coordinator: PostCallCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PostCallUiState(
            number = savedState.get<String>(PostCallActivity.EXTRA_NUMBER).orEmpty(),
            answered = savedState.get<Boolean>(PostCallActivity.EXTRA_ANSWERED) ?: false,
        ),
    )
    val state: StateFlow<PostCallUiState> = _state.asStateFlow()

    /** One-shot: the user tried to pick a third category. */
    private val _limitHit = MutableStateFlow(0)
    val limitHit: StateFlow<Int> = _limitHit.asStateFlow()

    init {
        val number = _state.value.number
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { numberFacts(number) }
            _state.update { it.copy(display = facts?.first ?: number, location = facts?.second, operator = facts?.third) }
        }
        viewModelScope.launch {
            val result = try {
                identification.identify(number, networkBudgetMs = 3_000, record = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) applyIdentity(result)
        }
        refreshContact()
    }

    private fun applyIdentity(result: CallerResult) {
        _state.update {
            it.copy(
                knownName = result.info?.displayName?.takeIf { n -> n.isNotBlank() },
                spamWarning = result.spamScore.score >= SPAM_WARNING_SCORE && result.contactName == null,
                reportCount = result.info?.reportCount ?: 0,
                savedContactId = it.savedContactId,
            )
        }
    }

    /** Re-checks the phonebook (call on resume, after the system contact editor closes). */
    fun refreshContact() {
        val number = _state.value.number
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { contactIdFor(number) }
            _state.update { it.copy(savedContactId = id) }
        }
    }

    fun toggle(category: ReportCategory) {
        val current = _state.value.selected
        when {
            category in current -> _state.update { it.copy(selected = current - category, failed = false) }
            current.size < ReportCategory.MAX_PER_REPORT -> _state.update { it.copy(selected = current + category, failed = false) }
            else -> _limitHit.value++
        }
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, failed = false) }
        viewModelScope.launch {
            val key = coordinator.keyFor(s.number)
            val result = if (key == null) {
                null
            } else {
                spam.submitCallReport(key, s.selected, ReportCallType.INCOMING_UNKNOWN, s.answered)
            }
            // Saved on the phone even when offline; it uploads later.
            val ok = result is AppResult.Success
            _state.update { it.copy(submitting = false, done = ok, failed = !ok) }
        }
    }

    /** "Not spam / Skip": close and don't ask about this number again for a while. */
    fun skip() {
        val number = _state.value.number
        viewModelScope.launch { coordinator.keyFor(number)?.let(coordinator::skip) }
    }

    private fun contactIdFor(number: String): Long? {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        if (number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /** International format, location (state/city where known) and operator, from offline data. */
    private fun numberFacts(number: String): Triple<String, String?, String?>? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val tm = app.getSystemService(TelephonyManager::class.java)
        val region = listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "IN"
        val parsed = util.parse(number, region)
        val display = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        val locale = Locale.getDefault()
        val country = util.getRegionCodeForNumber(parsed)?.let { Locale("", it).getDisplayCountry(locale) }?.takeIf { it.isNotBlank() }
        val place = PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(parsed, locale)?.takeIf { it.isNotBlank() }
        val location = when {
            place == null -> country
            country == null || place == country -> place
            else -> "$place, $country"
        }
        val operator = PhoneNumberToCarrierMapper.getInstance().getNameForNumber(parsed, Locale.ENGLISH)?.takeIf { it.isNotBlank() }
        Triple(display, location, operator)
    }.getOrNull()

    private companion object {
        const val SPAM_WARNING_SCORE = 50
    }
}
