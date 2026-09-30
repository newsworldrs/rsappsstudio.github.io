package com.rskusum.whocaller.feature.spam

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.domain.usecase.ReportNumberUseCase
import com.rskusum.whocaller.core.model.ReportReason
import com.rskusum.whocaller.core.testing.FakeBlockRepository
import com.rskusum.whocaller.core.testing.FakeCountryRepository
import com.rskusum.whocaller.core.testing.FakeSpamRepository
import com.rskusum.whocaller.core.testing.FakeStatsRepository
import com.rskusum.whocaller.core.testing.RecordingAnalytics
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReportTest {

    @get:Rule val compose = createComposeRule()

    private val spam = FakeSpamRepository()
    private val blocks = FakeBlockRepository()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(number: String) = ReportViewModel(
        SavedStateHandle(mapOf(ReportViewModel.ARG_NUMBER to number)),
        PhoneNumberNormalizer(),
        FakeCountryRepository("IN"),
        ReportNumberUseCase(spam, FakeStatsRepository(), RecordingAnalytics()) { 1_000L },
        BlockNumberUseCase(PhoneNumberNormalizer(), FakeCountryRepository("IN"), blocks, RecordingAnalytics()) { 1_000L },
    )

    @Test
    fun submitReportsAndBlocks() = runTest {
        val vm = viewModel("09876543210")
        assertEquals("+919876543210", vm.state.value.number?.key)
        vm.selectReason(ReportReason.TELEMARKETING)
        vm.updateComment("  calls every day ")
        vm.submit()
        val report = spam.reports.single()
        assertEquals("+919876543210", report.numberKey)
        assertEquals("calls every day", report.comment)
        assertTrue(blocks.blocked.value.containsKey("+919876543210"))
        assertTrue(vm.state.value.submittedAndSynced != null)
    }

    @Test
    fun invalidNumberShowsError() = runTest {
        val vm = viewModel("PRIVATE")
        assertEquals(null, vm.state.value.number)
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun submitButtonNeedsAReason() {
        val number = (PhoneNumberNormalizer().normalize("+919876543210", "IN") as NormalizationResult.Parsed).number
        var state = ReportUiState(number = number)
        compose.setContent {
            WhoCallerTheme {
                ReportNumberContent(
                    state = state,
                    onReason = { state = state.copy(reason = it) },
                    onComment = {},
                    onAlsoBlock = {},
                    onSubmit = {},
                )
            }
        }
        compose.onNodeWithText("Submit Report").assertIsNotEnabled()
        compose.onNodeWithText("Report this number").assertExists()
    }

    @Test
    fun submitButtonEnabledWithReason() {
        val number = (PhoneNumberNormalizer().normalize("+919876543210", "IN") as NormalizationResult.Parsed).number
        var clicked = false
        compose.setContent {
            WhoCallerTheme {
                ReportNumberContent(
                    state = ReportUiState(number = number, reason = ReportReason.SPAM),
                    onReason = {},
                    onComment = {},
                    onAlsoBlock = {},
                    onSubmit = { clicked = true },
                )
            }
        }
        compose.onNodeWithText("Submit Report").performScrollTo().assertIsEnabled().performClick()
        assertTrue(clicked)
    }
}
