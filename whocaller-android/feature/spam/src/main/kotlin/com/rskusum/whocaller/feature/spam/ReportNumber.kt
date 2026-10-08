package com.rskusum.whocaller.feature.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.domain.usecase.ReportNumberUseCase
import com.rskusum.whocaller.core.model.PhoneNumber
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.model.SyncState
import com.rskusum.whocaller.core.ui.component.PrimaryWideButton
import com.rskusum.whocaller.core.ui.util.labelRes
import com.rskusum.whocaller.core.ui.util.messageRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReportUiState(
    val number: PhoneNumber? = null,
    val reason: ReportReason? = null,
    val comment: String = "",
    val alsoBlock: Boolean = true,
    val submitting: Boolean = false,
    val error: AppError? = null,
    /** Non-null once submitted; true if the report reached the server. */
    val submittedAndSynced: Boolean? = null,
) {
    val canSubmit: Boolean get() = number != null && reason != null && !submitting && submittedAndSynced == null
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val reportNumber: ReportNumberUseCase,
    private val blockNumber: BlockNumberUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        val raw = savedStateHandle.get<String>(ARG_NUMBER).orEmpty()
        viewModelScope.launch {
            val parsed = normalizer.normalize(raw, countryRepository.defaultRegion())
            _state.update {
                if (parsed is NormalizationResult.Parsed) it.copy(number = parsed.number) else it.copy(error = AppError.INVALID_NUMBER)
            }
        }
    }

    fun selectReason(reason: ReportReason) = _state.update { it.copy(reason = reason, error = null) }

    fun updateComment(value: String) =
        _state.update { it.copy(comment = value.take(ReportNumberUseCase.MAX_COMMENT_LENGTH)) }

    fun setAlsoBlock(value: Boolean) = _state.update { it.copy(alsoBlock = value) }

    fun submit() {
        val s = _state.value
        val number = s.number ?: return
        val reason = s.reason ?: return
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val r = reportNumber(number, reason, s.comment)) {
                is AppResult.Success -> {
                    if (s.alsoBlock) blockNumber.block(number.e164 ?: number.raw, category = reason.category)
                    _state.update { it.copy(submitting = false, submittedAndSynced = r.data.syncState == SyncState.SYNCED) }
                }
                is AppResult.Failure -> _state.update { it.copy(submitting = false, error = r.error) }
            }
        }
    }

    companion object {
        const val ARG_NUMBER = "number"
    }
}

/** Report form, shown as a bottom sheet/dialog destination. */
@Composable
fun ReportNumberRoute(onDone: () -> Unit, viewModel: ReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.submittedAndSynced) {
        if (state.submittedAndSynced != null) {
            kotlinx.coroutines.delay(1_500)
            onDone()
        }
    }
    ReportNumberContent(
        state = state,
        onReason = viewModel::selectReason,
        onComment = viewModel::updateComment,
        onAlsoBlock = viewModel::setAlsoBlock,
        onSubmit = viewModel::submit,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportNumberContent(
    state: ReportUiState,
    onReason: (ReportReason) -> Unit,
    onComment: (String) -> Unit,
    onAlsoBlock: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            stringResource(R.string.report_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        state.number?.let {
            Text(it.display, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.report_question), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportReason.entries.forEach { reason ->
                val selected = state.reason == reason
                FilterChip(
                    selected = selected,
                    onClick = { onReason(reason) },
                    label = { Text(stringResource(reason.labelRes())) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(0.dp)) }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.comment,
            onValueChange = onComment,
            label = { Text(stringResource(R.string.report_comment)) },
            placeholder = { Text(stringResource(R.string.report_comment_hint)) },
            supportingText = {
                Text(stringResource(R.string.report_counter, state.comment.length, ReportNumberUseCase.MAX_COMMENT_LENGTH))
            },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = state.alsoBlock, role = Role.Checkbox, onValueChange = onAlsoBlock)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.alsoBlock, onCheckedChange = null)
            Text(stringResource(R.string.report_also_block), modifier = Modifier.padding(start = 8.dp))
        }
        state.error?.let {
            Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
        when (state.submittedAndSynced) {
            true -> Text(stringResource(R.string.report_thanks), color = MaterialTheme.colorScheme.primary)
            false -> Text(stringResource(R.string.report_saved_offline), color = MaterialTheme.colorScheme.primary)
            null -> Unit
        }
        Spacer(Modifier.height(12.dp))
        if (state.submitting) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            PrimaryWideButton(text = stringResource(R.string.report_submit), onClick = onSubmit, enabled = state.canSubmit)
        }
        Spacer(Modifier.height(16.dp))
    }
}
