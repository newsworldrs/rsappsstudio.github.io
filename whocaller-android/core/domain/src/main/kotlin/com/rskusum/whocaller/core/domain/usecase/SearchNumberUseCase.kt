package com.rskusum.whocaller.core.domain.usecase

import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.common.spam.SpamScoreEngine
import com.rskusum.whocaller.core.common.spam.SpamSignals
import com.rskusum.whocaller.core.domain.caller.CallerIdentificationManager
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.LookupPolicy
import com.rskusum.whocaller.core.domain.repository.SearchHistoryRepository
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.StatKey
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.PhoneNumber
import com.rskusum.whocaller.core.model.SpamReport
import com.rskusum.whocaller.core.model.SpamScore
import javax.inject.Inject

/** Everything the search result screen shows about a number. */
data class NumberLookup(
    val number: PhoneNumber,
    val contactName: String?,
    val info: CallerInfo?,
    val score: SpamScore,
    val label: CallerLabel,
    val isBlocked: Boolean,
    val myReport: SpamReport?,
    /** Set when the backend couldn't be reached and only local data is shown. */
    val remoteError: AppError?,
)

sealed interface SearchOutcome {
    data class Found(val lookup: NumberLookup) : SearchOutcome
    data object HiddenNumber : SearchOutcome
    data class InvalidNumber(val reason: NormalizationResult.Reason) : SearchOutcome
}

class SearchNumberUseCase @Inject constructor(
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val callerRepository: CallerRepository,
    private val contactsRepository: ContactsRepository,
    private val blockRepository: BlockRepository,
    private val spamRepository: SpamRepository,
    private val historyRepository: SearchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val statsRepository: StatsRepository,
    private val engine: SpamScoreEngine,
    private val analytics: AnalyticsTracker,
) {
    /**
     * @param regionOverride region picked in the search screen's country selector.
     * @param recordHistory false when re-opening a result (e.g. from history) to avoid duplicates.
     */
    suspend operator fun invoke(
        query: String,
        regionOverride: String? = null,
        recordHistory: Boolean = true,
    ): SearchOutcome {
        val region = regionOverride ?: countryRepository.defaultRegion()
        val number = when (val n = normalizer.normalize(query, region)) {
            NormalizationResult.Hidden -> return SearchOutcome.HiddenNumber
            is NormalizationResult.Invalid -> return SearchOutcome.InvalidNumber(n.reason)
            is NormalizationResult.Parsed -> n.number
        }
        val settings = settingsRepository.current()

        val contactName = if (settings.contactAccessEnabled && contactsRepository.hasPermission()) {
            runCatching { contactsRepository.lookupContactName(number.e164 ?: number.raw) }.getOrNull()
        } else {
            null
        }

        var remoteError: AppError? = null
        val info = when (val r = callerRepository.lookup(number.key, LookupPolicy.NETWORK_FIRST)) {
            is AppResult.Success -> r.data
            is AppResult.Failure -> {
                remoteError = r.error
                callerRepository.getCached(number.key)
            }
        }
        val isBlocked = blockRepository.isBlocked(number.key)
        val myReport = spamRepository.latestReportFor(number.key)
        val score = engine.score(
            SpamSignals.from(info, isContact = contactName != null, userBlocked = isBlocked, userReported = myReport?.primaryCategory),
        )

        if (recordHistory && settings.searchHistoryEnabled) {
            historyRepository.add(query.trim(), number.key, number.display)
        }
        statsRepository.increment(StatKey.NUMBERS_SEARCHED)
        analytics.track(AnalyticsEvent.NumberSearched(found = contactName != null || info?.hasIdentity == true))

        return SearchOutcome.Found(
            NumberLookup(
                number = number,
                contactName = contactName,
                info = info,
                score = score,
                label = if (contactName != null) CallerLabel.CONTACT else CallerIdentificationManager.labelFor(info, score, isBlocked),
                isBlocked = isBlocked,
                myReport = myReport,
                remoteError = remoteError?.takeUnless { it == AppError.NOT_FOUND },
            ),
        )
    }
}
