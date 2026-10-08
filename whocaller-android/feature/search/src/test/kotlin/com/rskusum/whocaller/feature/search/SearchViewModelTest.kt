package com.rskusum.whocaller.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.spam.RuleBasedSpamScoreEngine
import com.rskusum.whocaller.core.domain.usecase.BlockNumberUseCase
import com.rskusum.whocaller.core.domain.usecase.SearchNumberUseCase
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.testing.FakeBlockRepository
import com.rskusum.whocaller.core.testing.FakeCallerRepository
import com.rskusum.whocaller.core.testing.FakeContactsRepository
import com.rskusum.whocaller.core.testing.FakeCountryRepository
import com.rskusum.whocaller.core.testing.FakeSearchHistoryRepository
import com.rskusum.whocaller.core.testing.FakeSettingsRepository
import com.rskusum.whocaller.core.testing.FakeSpamRepository
import com.rskusum.whocaller.core.testing.FakeStatsRepository
import com.rskusum.whocaller.core.testing.RecordingAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchViewModelTest {

    private val callers = FakeCallerRepository()
    private val history = FakeSearchHistoryRepository()
    private val blocks = FakeBlockRepository()
    private val normalizer = PhoneNumberNormalizer()
    private val country = FakeCountryRepository("IN")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(query: String? = null) = SearchViewModel(
        SavedStateHandle(if (query != null) mapOf(SearchViewModel.ARG_QUERY to query) else emptyMap()),
        SearchNumberUseCase(
            normalizer, country, callers, FakeContactsRepository(), blocks, FakeSpamRepository(), history,
            FakeSettingsRepository(), FakeStatsRepository(), RuleBasedSpamScoreEngine { 0L }, RecordingAnalytics(),
        ),
        history,
        blocks,
        BlockNumberUseCase(normalizer, country, blocks, RecordingAnalytics()) { 0L },
        country,
        normalizer,
        FakeSettingsRepository(),
    )

    @Test
    fun foundNumberAndHistoryRecordedOncePerNumber() {
        callers.remote["+919876543210"] = CallerInfo("+919876543210", displayName = "ABC Services", category = SpamCategory.BUSINESS)
        val viewModel = vm("9876543210")
        val found = viewModel.state.value.result as SearchResultState.Found
        assertEquals("ABC Services", found.lookup.info?.displayName)

        viewModel.onQueryChange("+91 98765 43210")
        viewModel.search()
        assertEquals(1, history.items.size)
    }

    @Test
    fun offlineShowsNoIdentityWithoutCrashing() {
        callers.remoteError = AppError.NETWORK_UNAVAILABLE
        val found = vm("9876543210").state.value.result as SearchResultState.Found
        assertEquals(AppError.NETWORK_UNAVAILABLE, found.lookup.remoteError)
        assertFalse(found.lookup.info?.hasIdentity == true)
    }

    @Test
    fun hiddenAndInvalidInput() {
        val viewModel = vm()
        viewModel.onQueryChange("12")
        viewModel.search()
        assertTrue(viewModel.state.value.result is SearchResultState.Invalid)
    }

    @Test
    fun blockToggle() {
        val viewModel = vm("9876543210")
        viewModel.toggleBlock()
        assertTrue((viewModel.state.value.result as SearchResultState.Found).lookup.isBlocked)
        viewModel.toggleBlock()
        assertFalse((viewModel.state.value.result as SearchResultState.Found).lookup.isBlocked)
    }
}
