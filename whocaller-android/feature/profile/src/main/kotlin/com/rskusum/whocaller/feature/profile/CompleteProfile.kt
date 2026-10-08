package com.rskusum.whocaller.feature.profile

import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.rskusum.whocaller.core.ui.util.SimCards
import com.rskusum.whocaller.core.ui.util.SimCard
import androidx.core.content.ContextCompat
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.outlined.SimCard
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.AuthRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.LocalProfileRepository
import com.rskusum.whocaller.core.domain.repository.WhoCallerIdRepository
import com.rskusum.whocaller.core.permissions.findActivity
import com.rskusum.whocaller.core.ui.util.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

/** Countries offered in the mobile number field (the detected one is added if it's another). */
internal val PHONE_COUNTRIES = listOf("IN", "US")

data class CompleteProfileUiState(
    val name: String = "",
    /** National number, digits only (the country code is [region]'s). */
    val phone: String = "",
    /** Country of the number: "IN", "US"… Its calling code is shown in front of the field. */
    val region: String = "IN",
    val callingCode: Int = 91,
    val code: String = "",
    val codeSent: Boolean = false,
    /** E.164 number verified by SMS code and linked to the account. */
    val verifiedPhone: String? = null,
    val showName: Boolean = true,
    val busy: Boolean = false,
    val error: AppError? = null,
    val message: Int? = null,
    val saved: Boolean = false,
)

/**
 * WhoCaller ID: the account's name + an OTP-verified mobile number, saved on the phone and in
 * Firestore (whocallerUsers / registeredCallers), so other WhoCaller users see this name on calls.
 */
@HiltViewModel
class CompleteProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val localProfileRepository: LocalProfileRepository,
    private val whoCallerId: WhoCallerIdRepository,
    private val phoneAuth: PhoneAuthGateway,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CompleteProfileUiState())
    val state: StateFlow<CompleteProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val user = authRepository.currentUser.first()
            val local = localProfileRepository.profile.first()
            setRegion(countryRepository.defaultRegion())
            _state.update {
                it.copy(
                    name = local.name.ifBlank { user.name.orEmpty() },
                    verifiedPhone = user.phone,
                    showName = local.showNameToCallers,
                )
            }
            // Reinstall / new phone: restore the name saved with this account.
            (whoCallerId.load() as? AppResult.Success)?.data?.let { (name, _) ->
                _state.update { if (it.name.isBlank()) it.copy(name = name) else it }
            }
        }
    }

    fun edit(transform: (CompleteProfileUiState) -> CompleteProfileUiState) =
        _state.update { transform(it).copy(error = null, message = null) }

    fun changeNumber() = _state.update { it.copy(verifiedPhone = null, codeSent = false, code = "", phone = "") }

    fun setRegion(region: String) {
        val r = region.uppercase()
        val code = runCatching { normalizer.countryCodeFor(r) }.getOrDefault(0)
        if (code <= 0) return
        _state.update { it.copy(region = r, callingCode = code, phone = it.phone.take(maxDigits(r)), error = null, message = null) }
    }

    /** Typing or pasting: digits only; a pasted "+91 98765 43210" also sets the country. */
    fun setPhoneInput(raw: String) {
        val text = raw.trim()
        if (text.startsWith("+") || text.startsWith("00")) {
            val parsed = normalizer.normalize(text.replaceFirst(Regex("^00"), "+"), _state.value.region) as? NormalizationResult.Parsed
            val number = parsed?.number
            val region = number?.regionCode
            if (number != null && region != null && number.countryCallingCode != null) {
                setRegion(region)
                val national = number.e164.orEmpty().removePrefix("+" + number.countryCallingCode)
                return edit { it.copy(phone = national.take(maxDigits(region))) }
            }
        }
        var digits = text.filter(Char::isDigit)
        // A leading trunk "0" or the country code typed by habit (e.g. 0 98765… or 91 98765…).
        val max = maxDigits(_state.value.region)
        if (digits.length > max && digits.startsWith("0")) digits = digits.drop(1)
        val cc = _state.value.callingCode.toString()
        if (digits.length > max && digits.startsWith(cc)) digits = digits.drop(cc.length)
        edit { it.copy(phone = digits.take(max)) }
    }

    /** A SIM's own number (any format) → country + national digits. */
    fun useSimNumber(number: String, simCountryIso: String?) {
        val parsed = normalizer.normalize(number, simCountryIso?.uppercase() ?: _state.value.region) as? NormalizationResult.Parsed
        val n = parsed?.number
        val region = n?.regionCode ?: simCountryIso?.uppercase() ?: _state.value.region
        setRegion(region)
        val cc = n?.countryCallingCode
        val national = if (n?.e164 != null && cc != null) n.e164!!.removePrefix("+$cc") else number.filter(Char::isDigit)
        edit { it.copy(phone = national.take(maxDigits(region)), codeSent = false) }
    }

    /** Message for the number being typed, or null when it's fine (or still empty). */
    fun phoneProblem(s: CompleteProfileUiState): PhoneProblem? {
        val digits = s.phone
        if (digits.isEmpty()) return null
        val need = expectedDigits(s.region)
        if (s.region == "IN" && digits.first() !in '6'..'9') return PhoneProblem.IndiaStart
        if (need != null && digits.length < need) return PhoneProblem.TooShort(need - digits.length)
        if (need == null && digits.length < MIN_DIGITS) return PhoneProblem.TooShort(MIN_DIGITS - digits.length)
        return if (e164Of(s) == null) PhoneProblem.Invalid else null
    }

    /** Full number when valid, else null. */
    fun e164Of(s: CompleteProfileUiState): String? {
        val parsed = normalizer.normalize("+${s.callingCode}${s.phone}", s.region) as? NormalizationResult.Parsed
        return parsed?.number?.takeIf { it.isValid }?.e164
    }

    fun sendCode(activity: Activity) {
        viewModelScope.launch {
            val e164 = e164Of(_state.value)
                ?: return@launch _state.update { it.copy(error = AppError.INVALID_NUMBER) }
            _state.update { it.copy(busy = true, error = null) }
            when (val r = phoneAuth.sendCode(activity, e164)) {
                is AppResult.Success -> _state.update {
                    if (r.data) {
                        it.copy(busy = false, verifiedPhone = e164, message = R.string.complete_phone_verified)
                    } else {
                        it.copy(busy = false, codeSent = true, message = R.string.signin_code_sent)
                    }
                }
                is AppResult.Failure -> _state.update { it.copy(busy = false, error = r.error) }
            }
        }
    }

    fun verifyCode() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val r = phoneAuth.verifyCode(_state.value.code.trim())) {
                is AppResult.Success -> _state.update {
                    it.copy(busy = false, codeSent = false, code = "", verifiedPhone = r.data.phone, message = R.string.complete_phone_verified)
                }
                is AppResult.Failure -> _state.update { it.copy(busy = false, error = r.error) }
            }
        }
    }

    fun save() {
        val s = _state.value
        val name = s.name.trim().replace(Regex("\\s+"), " ")
        if (name.length !in MIN_NAME..MAX_NAME) return _state.update { it.copy(message = R.string.complete_name_required) }
        val phone = s.verifiedPhone ?: return _state.update { it.copy(message = R.string.complete_phone_required) }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            if (whoCallerId.isAvailable) {
                val r = whoCallerId.save(name, phone, s.showName)
                if (r is AppResult.Failure) return@launch _state.update { it.copy(busy = false, error = r.error) }
            }
            authRepository.updateDisplayName(name)
            localProfileRepository.update {
                it.copy(name = name, phoneNumber = phone, phoneVerified = true, showNameToCallers = s.showName)
            }
            _state.update { it.copy(busy = false, saved = true) }
        }
    }

    companion object {
        private const val MIN_NAME = 2
        private const val MAX_NAME = 60
        private const val MIN_DIGITS = 6
        private const val MAX_DIGITS = 15

        /** Mobile numbers have exactly this many digits in these countries. */
        fun expectedDigits(region: String): Int? = when (region) {
            "IN", "US", "CA" -> 10
            else -> null
        }

        fun maxDigits(region: String): Int = expectedDigits(region) ?: MAX_DIGITS
    }
}

sealed interface PhoneProblem {
    data class TooShort(val missing: Int) : PhoneProblem
    data object IndiaStart : PhoneProblem
    data object Invalid : PhoneProblem
}

/** Shows digits in groups: India 98765-43210, US/Canada 201-555-0123; the field keeps digits only. */
internal class GroupedDigits(groups: List<Int>) : VisualTransformation {
    /** Original indexes that get a "-" in front of them. */
    private val breaks: List<Int> = groups.runningReduce { a, b -> a + b }.dropLast(1)

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val active = breaks.filter { it < raw.length }
        val out = buildString {
            raw.forEachIndexed { i, c ->
                if (i in active) append('-')
                append(c)
            }
        }
        // Separator positions in the shown text.
        val separators = active.mapIndexed { n, b -> b + n }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset + active.count { it < offset }
            override fun transformedToOriginal(offset: Int): Int =
                (offset - separators.count { it < offset }).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), mapping)
    }

    companion object {
        fun forRegion(region: String): VisualTransformation = when (region) {
            "IN" -> GroupedDigits(listOf(5, 5))
            "US", "CA" -> GroupedDigits(listOf(3, 3, 4))
            else -> VisualTransformation.None
        }
    }
}

/**
 * Required after sign-in (and reachable from Edit profile): name + verified mobile number.
 * [onBack] null = mandatory first-run step, no way back. [onSkip] is only passed in test builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteProfileScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    viewModel: CompleteProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    // Live check of the number being typed (shown under the field; Send code waits for a valid one).
    val problem = remember(state.phone, state.region) { viewModel.phoneProblem(state) }
    val valid = remember(state.phone, state.region) { state.phone.isNotEmpty() && problem == null }

    // The phone's own SIMs (number + network, as SIM settings show them), to pick from.
    var sims by remember { mutableStateOf<List<SimCard>>(emptyList()) }
    var simsLoaded by remember { mutableStateOf(false) }
    suspend fun loadSims() {
        sims = withContext(Dispatchers.IO) { SimCards.list(context) }
        simsLoaded = true
        // One SIM with a stored number: fill it in.
        sims.singleOrNull()?.let { sim ->
            val n = sim.number ?: return@let
            if (viewModel.state.value.phone.isBlank() && viewModel.state.value.verifiedPhone == null) viewModel.useSimNumber(n, sim.countryIso)
        }
    }
    val scope = rememberCoroutineScope()
    val simPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { loadSims() }
    }
    LaunchedEffect(Unit) {
        if (SimCards.hasPermission(context) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED)
        ) {
            loadSims()
        } else {
            simPermission.launch(SimCards.PERMISSIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.complete_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.complete_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.edit { it.copy(name = v.take(60)) } },
                label = { Text(stringResource(R.string.complete_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            val verified = state.verifiedPhone
            if (verified != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.complete_mobile_label), style = MaterialTheme.typography.labelMedium)
                        Text(verified, style = MaterialTheme.typography.titleMedium)
                        val network = remember(verified, sims) { SimCards.forNumber(context, verified)?.network }
                        Text(
                            listOfNotNull(stringResource(R.string.complete_verified), network).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = viewModel::changeNumber) { Text(stringResource(R.string.complete_change)) }
                }
            } else {
                if (sims.isNotEmpty()) {
                    Text(stringResource(R.string.complete_your_sims), style = MaterialTheme.typography.labelLarge)
                    sims.forEach { sim ->
                        val label = listOfNotNull(
                            stringResource(R.string.complete_sim_n, sim.slot),
                            sim.network,
                            sim.number ?: stringResource(R.string.complete_sim_no_number),
                        ).joinToString(" · ")
                        FilterChip(
                            selected = sim.number?.filter(Char::isDigit)?.takeLast(10)?.let { it.isNotEmpty() && it == state.phone.takeLast(10) } == true,
                            onClick = { sim.number?.let { n -> viewModel.useSimNumber(n, sim.countryIso) } },
                            enabled = !state.codeSent && sim.number != null,
                            label = { Text(label) },
                            leadingIcon = { Icon(Icons.Outlined.SimCard, contentDescription = null) },
                        )
                    }
                    if (sims.all { it.number == null }) {
                        Text(stringResource(R.string.complete_sim_type_number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (simsLoaded && !SimCards.hasPermission(context)) {
                    TextButton(onClick = { simPermission.launch(SimCards.PERMISSIONS) }) { Text(stringResource(R.string.complete_detect_sims)) }
                }
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = viewModel::setPhoneInput,
                    label = { Text(stringResource(R.string.complete_mobile_label)) },
                    // Country code is added automatically; tap it to change the country.
                    leadingIcon = {
                        var open by remember { mutableStateOf(false) }
                        TextButton(onClick = { open = true }, enabled = !state.codeSent) {
                            Text("${flagOf(state.region)} +${state.callingCode}")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.complete_country))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            (PHONE_COUNTRIES + state.region).distinct().forEach { r ->
                                DropdownMenuItem(
                                    text = { Text("${flagOf(r)}  ${java.util.Locale("", r).displayCountry} (+${countryCodeLabel(r, state)})") },
                                    onClick = {
                                        open = false
                                        viewModel.setRegion(r)
                                    },
                                )
                            }
                        }
                    },
                    placeholder = { Text(if (state.region == "IN") "98765-43210" else if (state.region == "US" || state.region == "CA") "201-555-0123" else "") },
                    visualTransformation = GroupedDigits.forRegion(state.region),
                    isError = problem != null && (state.phone.length >= (CompleteProfileViewModel.expectedDigits(state.region) ?: 6) || problem == PhoneProblem.IndiaStart),
                    supportingText = {
                        val need = CompleteProfileViewModel.expectedDigits(state.region)
                        Text(
                            when (problem) {
                                null -> if (valid) stringResource(R.string.complete_number_ok) else if (need != null) stringResource(R.string.complete_mobile_digits, need) else stringResource(R.string.complete_mobile_hint)
                                PhoneProblem.IndiaStart -> stringResource(R.string.complete_number_india_start)
                                PhoneProblem.Invalid -> stringResource(R.string.complete_number_invalid)
                                is PhoneProblem.TooShort -> stringResource(R.string.complete_number_more, problem.missing)
                            },
                        )
                    },
                    singleLine = true,
                    enabled = !state.codeSent,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.codeSent) {
                    OutlinedButton(
                        onClick = { context.findActivity()?.let(viewModel::sendCode) },
                        enabled = !state.busy && valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.signin_send_code)) }
                } else {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = { v -> viewModel.edit { it.copy(code = v.filter(Char::isDigit).take(8)) } },
                        label = { Text(stringResource(R.string.signin_code_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::changeNumber, enabled = !state.busy) { Text(stringResource(R.string.complete_change)) }
                        Button(onClick = viewModel::verifyCode, enabled = !state.busy && state.code.length >= 6, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.signin_verify))
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.complete_show_name), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.complete_show_name_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.showName, onCheckedChange = { v -> viewModel.edit { it.copy(showName = v) } })
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.busy && state.verifiedPhone != null && state.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.complete_save)) }

            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.error?.let {
                val res = if (it == AppError.DUPLICATE) R.string.complete_number_taken else it.messageRes()
                Text(stringResource(res), color = MaterialTheme.colorScheme.error)
            }
            state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
            Text(stringResource(R.string.complete_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onSignOut != null) {
                TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.complete_other_account)) }
            }
            if (onSkip != null) {
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.complete_skip_test)) }
            }
        }
    }
}

/** 🇮🇳 for "IN", 🇺🇸 for "US" (regional indicator letters). */
private fun flagOf(region: String): String =
    if (region.length == 2 && region.all { it in 'A'..'Z' }) region.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") else region

private fun countryCodeLabel(region: String, state: CompleteProfileUiState): String = when (region) {
    state.region -> state.callingCode.toString()
    "IN" -> "91"
    "US", "CA" -> "1"
    else -> ""
}
