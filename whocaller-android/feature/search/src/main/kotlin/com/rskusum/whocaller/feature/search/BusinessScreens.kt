package com.rskusum.whocaller.feature.search

import android.text.format.DateUtils
import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.BusinessRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.VerificationRequest
import com.rskusum.whocaller.core.model.Business
import com.rskusum.whocaller.core.ui.component.EmptyState
import com.rskusum.whocaller.core.ui.component.ErrorState
import com.rskusum.whocaller.core.ui.component.LoadingState
import com.rskusum.whocaller.core.ui.component.PrimaryWideButton
import com.rskusum.whocaller.core.ui.component.VerifiedBadge
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

// ---------- Business profile ----------

data class BusinessUiState(val loading: Boolean = true, val business: Business? = null, val error: AppError? = null)

@HiltViewModel
class BusinessProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BusinessRepository,
) : ViewModel() {
    private val id: String = savedStateHandle.get<String>(ARG_ID).orEmpty()
    private val _state = MutableStateFlow(BusinessUiState())
    val state: StateFlow<BusinessUiState> = _state.asStateFlow()

    init { load(false) }

    fun load(force: Boolean) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.getBusiness(id, force)) {
                is AppResult.Success -> _state.value = BusinessUiState(loading = false, business = r.data)
                is AppResult.Failure -> _state.value = BusinessUiState(loading = false, error = r.error)
            }
        }
    }

    companion object {
        const val ARG_ID = "businessId"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessProfileScreen(onBack: () -> Unit, viewModel: BusinessProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { BackTopBar(stringResource(R.string.business_title), onBack) }) { padding ->
        val b = state.business
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            b == null -> ErrorState(state.error ?: AppError.UNKNOWN, Modifier.padding(padding)) { viewModel.load(true) }
            else -> Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(b.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                if (b.verified) {
                    VerifiedBadge()
                    b.verificationDate?.let {
                        Text(
                            stringResource(R.string.business_verified_on, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(stringResource(R.string.business_not_verified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                b.category?.let { InfoRow(stringResource(R.string.business_category), it) }
                b.phoneNumbers.forEach { phone ->
                    InfoRow(stringResource(R.string.business_phone), phone) { ActionIntents.dial(context, phone) }
                }
                listOfNotNull(b.address, b.country?.let { java.util.Locale("", it).displayCountry }).joinToString(", ")
                    .takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.business_location), it) }
                b.website?.let { url -> InfoRow(stringResource(R.string.business_website), url) { ActionIntents.openUrl(context, url) } }
                b.email?.let { mail -> InfoRow(stringResource(R.string.business_email), mail) { ActionIntents.openUrl(context, "mailto:$mail") } }
                b.hours?.let { InfoRow(stringResource(R.string.business_hours), it) }
                val rating = b.rating
                val ratingCount = b.ratingCount ?: 0
                if (rating != null && ratingCount > 0) {
                    InfoRow(stringResource(R.string.business_rating), stringResource(R.string.business_rating_value, rating, ratingCount))
                }
                if (b.name.startsWith("[Demo]")) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(UiR.string.source_demo_notice), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
            }
        },
    )
}

// ---------- Business directory ----------

data class DirectoryUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Business>? = null,
    val error: AppError? = null,
)

@HiltViewModel
class BusinessDirectoryViewModel @Inject constructor(
    private val repository: BusinessRepository,
    private val countryRepository: CountryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DirectoryUiState())
    val state: StateFlow<DirectoryUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQuery(q: String) {
        _state.update { it.copy(query = q.take(60)) }
        job?.cancel()
        if (q.trim().length < 2) {
            _state.update { it.copy(results = null, loading = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(350) // debounce typing
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repository.search(q, countryRepository.defaultRegion())) {
                is AppResult.Success -> _state.update { it.copy(loading = false, results = r.data) }
                is AppResult.Failure -> _state.update { it.copy(loading = false, results = null, error = r.error) }
            }
        }
    }
}

@Composable
fun BusinessDirectoryScreen(
    onBack: () -> Unit,
    onOpenBusiness: (String) -> Unit,
    onRequestVerification: () -> Unit,
    viewModel: BusinessDirectoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.business_directory_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                label = { Text(stringResource(R.string.business_directory_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.onQuery(state.query) }),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            val results = state.results
            when {
                state.loading -> LoadingState(Modifier.weight(1f))
                state.error != null -> ErrorState(state.error ?: AppError.UNKNOWN, Modifier.weight(1f)) { viewModel.onQuery(state.query) }
                results == null -> EmptyState(Icons.Outlined.Storefront, stringResource(R.string.business_directory_empty), modifier = Modifier.weight(1f))
                results.isEmpty() -> EmptyState(Icons.Outlined.Storefront, stringResource(R.string.business_no_results), modifier = Modifier.weight(1f))
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { it.businessId }) { b ->
                        ListItem(
                            headlineContent = { Text(b.name) },
                            supportingContent = { Text(listOfNotNull(b.category, b.address).joinToString(" · ")) },
                            leadingContent = { Icon(Icons.Outlined.Storefront, contentDescription = null) },
                            trailingContent = if (b.verified) {
                                { Icon(Icons.Outlined.Verified, contentDescription = stringResource(UiR.string.label_verified_business)) }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable { onOpenBusiness(b.businessId) },
                        )
                    }
                }
            }
            TextButton(onClick = onRequestVerification, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)) {
                Text(stringResource(R.string.business_verify_cta))
            }
        }
    }
}

// ---------- Verification request ----------

data class VerificationForm(
    val name: String = "",
    val phone: String = "",
    val category: String = "",
    val website: String = "",
    val email: String = "",
    val submitting: Boolean = false,
    val validationError: Int? = null,
    val error: AppError? = null,
    val requestId: String? = null,
)

@HiltViewModel
class BusinessVerificationViewModel @Inject constructor(
    private val repository: BusinessRepository,
    private val countryRepository: CountryRepository,
    private val normalizer: PhoneNumberNormalizer,
) : ViewModel() {
    private val _state = MutableStateFlow(VerificationForm())
    val state: StateFlow<VerificationForm> = _state.asStateFlow()

    fun edit(transform: (VerificationForm) -> VerificationForm) = _state.update { transform(it).copy(validationError = null, error = null) }

    fun submit() {
        val f = _state.value
        if (f.name.isBlank() || f.phone.isBlank() || f.category.isBlank()) {
            _state.update { it.copy(validationError = R.string.verify_required) }
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(f.email.trim()).matches()) {
            _state.update { it.copy(validationError = R.string.verify_invalid_email) }
            return
        }
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val region = countryRepository.defaultRegion()
            val parsed = normalizer.normalize(f.phone, region) as? NormalizationResult.Parsed
            val e164 = parsed?.number?.e164
            if (e164 == null) {
                _state.update { it.copy(submitting = false, error = AppError.INVALID_NUMBER) }
                return@launch
            }
            val result = repository.requestVerification(
                VerificationRequest(f.name, e164, f.category, f.website.ifBlank { null }, f.email, parsed?.number?.regionCode ?: region),
            )
            _state.update {
                when (result) {
                    is AppResult.Success -> it.copy(submitting = false, requestId = result.data)
                    is AppResult.Failure -> it.copy(submitting = false, error = result.error)
                }
            }
        }
    }
}

@Composable
fun BusinessVerificationScreen(onBack: () -> Unit, viewModel: BusinessVerificationViewModel = hiltViewModel()) {
    val f by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.verify_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.verify_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            val enabled = f.requestId == null && !f.submitting
            FormField(f.name, R.string.verify_name, enabled) { v -> viewModel.edit { it.copy(name = v.take(120)) } }
            FormField(f.phone, R.string.verify_phone, enabled, KeyboardType.Phone) { v -> viewModel.edit { it.copy(phone = v.take(24)) } }
            FormField(f.category, R.string.verify_category, enabled) { v -> viewModel.edit { it.copy(category = v.take(60)) } }
            FormField(f.website, R.string.verify_website, enabled, KeyboardType.Uri) { v -> viewModel.edit { it.copy(website = v.take(200)) } }
            FormField(f.email, R.string.verify_email, enabled, KeyboardType.Email) { v -> viewModel.edit { it.copy(email = v.take(120)) } }
            f.validationError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            f.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            f.requestId?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.verify_submitted, it))
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryWideButton(stringResource(R.string.verify_submit), onClick = viewModel::submit, enabled = enabled)
        }
    }
}

@Composable
private fun FormField(
    value: String,
    label: Int,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
