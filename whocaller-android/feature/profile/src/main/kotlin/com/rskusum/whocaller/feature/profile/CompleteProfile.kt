package com.rskusum.whocaller.feature.profile

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

data class CompleteProfileUiState(
    val name: String = "",
    val phone: String = "",
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
            _state.update {
                it.copy(
                    name = local.name.ifBlank { user.name.orEmpty() },
                    phone = user.phone ?: local.phoneNumber,
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

    fun sendCode(activity: Activity) {
        viewModelScope.launch {
            val e164 = normalize(_state.value.phone)
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

    private suspend fun normalize(input: String): String? {
        val parsed = normalizer.normalize(input, countryRepository.defaultRegion()) as? NormalizationResult.Parsed
        return parsed?.number?.takeIf { it.isValid }?.e164
    }

    private companion object {
        const val MIN_NAME = 2
        const val MAX_NAME = 60
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

    // The phone's own SIMs (number + network, as SIM settings show them), to pick from.
    var sims by remember { mutableStateOf<List<SimCard>>(emptyList()) }
    var simsLoaded by remember { mutableStateOf(false) }
    suspend fun loadSims() {
        sims = withContext(Dispatchers.IO) { SimCards.list(context) }
        simsLoaded = true
        // One SIM with a stored number: fill it in.
        sims.singleOrNull()?.number?.let { n ->
            if (viewModel.state.value.phone.isBlank() && viewModel.state.value.verifiedPhone == null) viewModel.edit { it.copy(phone = n) }
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
                            selected = sim.number?.filter(Char::isDigit)?.takeLast(10)?.let { it.isNotEmpty() && it == state.phone.filter(Char::isDigit).takeLast(10) } == true,
                            onClick = { sim.number?.let { n -> viewModel.edit { it.copy(phone = n, codeSent = false) } } },
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
                    onValueChange = { v -> viewModel.edit { it.copy(phone = v.filter { c -> c.isDigit() || c in "+ -()" }.take(24)) } },
                    label = { Text(stringResource(R.string.complete_mobile_label)) },
                    supportingText = { Text(stringResource(R.string.complete_mobile_hint)) },
                    singleLine = true,
                    enabled = !state.codeSent,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.codeSent) {
                    OutlinedButton(
                        onClick = { context.findActivity()?.let(viewModel::sendCode) },
                        enabled = !state.busy && state.phone.count(Char::isDigit) >= 8,
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
