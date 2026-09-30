package com.rskusum.whocaller.core.domain.usecase

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.model.BlockSource
import com.rskusum.whocaller.core.model.BlockedNumber
import com.rskusum.whocaller.core.model.SpamCategory
import javax.inject.Inject

class BlockNumberUseCase @Inject constructor(
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val blockRepository: BlockRepository,
    private val analytics: AnalyticsTracker,
    private val clock: Clock,
) {
    suspend fun block(rawNumber: String, label: String? = null, category: SpamCategory? = null): AppResult<BlockedNumber> {
        val parsed = normalizer.normalize(rawNumber, countryRepository.defaultRegion())
        val number = (parsed as? NormalizationResult.Parsed)?.number
            ?: return AppResult.Failure(AppError.INVALID_NUMBER)
        val blocked = BlockedNumber(
            numberKey = number.key,
            displayNumber = number.display,
            label = label?.trim()?.take(80)?.takeIf { it.isNotEmpty() },
            category = category,
            blockedAt = clock.now(),
            source = BlockSource.USER,
        )
        blockRepository.block(blocked)
        analytics.track(AnalyticsEvent.NumberBlocked)
        return AppResult.Success(blocked)
    }

    suspend fun unblock(numberKey: String) = blockRepository.unblock(numberKey)
}
