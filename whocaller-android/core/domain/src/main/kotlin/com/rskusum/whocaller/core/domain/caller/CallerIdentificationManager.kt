package com.rskusum.whocaller.core.domain.caller

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.phone.IndianCallSeries
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.common.spam.SpamScoreEngine
import com.rskusum.whocaller.core.common.spam.SpamSignals
import com.rskusum.whocaller.core.domain.repository.BlockRepository
import com.rskusum.whocaller.core.domain.repository.CallerRepository
import com.rskusum.whocaller.core.domain.repository.ContactsRepository
import com.rskusum.whocaller.core.domain.repository.CountryRepository
import com.rskusum.whocaller.core.domain.repository.IdentifiedCallRepository
import com.rskusum.whocaller.core.domain.repository.LookupPolicy
import com.rskusum.whocaller.core.domain.repository.SettingsRepository
import com.rskusum.whocaller.core.domain.repository.SpamRepository
import com.rskusum.whocaller.core.domain.repository.StatKey
import com.rskusum.whocaller.core.domain.repository.StatsRepository
import com.rskusum.whocaller.core.model.AppSettings
import com.rskusum.whocaller.core.model.BlockedCall
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.CallerResult
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.model.IdentifiedCall
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.model.PhoneNumber
import com.rskusum.whocaller.core.model.RiskLevel
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.SpamScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incoming call → normalize → user block list → contacts → local cache → backend (time-boxed)
 * → [SpamScoreEngine] → decision.
 *
 * Designed for Android's CallScreeningService, which must answer within a few seconds: every local
 * step is a single indexed lookup and the network call has a hard budget ([networkBudgetMs]).
 */
@Singleton
class CallerIdentificationManager @Inject constructor(
    private val normalizer: PhoneNumberNormalizer,
    private val countryRepository: CountryRepository,
    private val settingsRepository: SettingsRepository,
    private val contactsRepository: ContactsRepository,
    private val blockRepository: BlockRepository,
    private val callerRepository: CallerRepository,
    private val spamRepository: SpamRepository,
    private val identifiedCallRepository: IdentifiedCallRepository,
    private val statsRepository: StatsRepository,
    private val engine: SpamScoreEngine,
    private val analytics: AnalyticsTracker,
    private val clock: Clock,
) {

    /**
     * @param rawNumber number as presented by Telecom; null/empty when the caller withheld it.
     * @param networkBudgetMs maximum time spent waiting for the backend.
     */
    suspend fun identify(
        rawNumber: String?,
        networkBudgetMs: Long = DEFAULT_NETWORK_BUDGET_MS,
        record: Boolean = true,
    ): CallerResult {
        val settings = settingsRepository.current()
        val region = countryRepository.defaultRegion()

        val number = when (val n = normalizer.normalize(rawNumber, region)) {
            NormalizationResult.Hidden -> return hiddenResult(settings, record)
            is NormalizationResult.Invalid -> return unknownResult(null)
            is NormalizationResult.Parsed -> n.number
        }

        if (!settings.callerIdEnabled && !settings.spamProtectionEnabled) {
            return unknownResult(number, DecisionReason.PROTECTION_DISABLED)
        }

        val userBlocked = safe(false) { blockRepository.isBlocked(number.key) }
        val canCheckContacts = settings.contactAccessEnabled && contactsRepository.hasPermission()
        val contactName = if (canCheckContacts) {
            safe(null) { contactsRepository.lookupContactName(rawNumber!!) }
        } else {
            null
        }

        if (contactName != null && !userBlocked) {
            return CallerResult(
                number = number,
                contactName = contactName,
                info = null,
                spamScore = SpamScore.CONTACT,
                decision = CallDecision.ALLOW,
                reason = DecisionReason.CONTACT,
                label = CallerLabel.CONTACT,
            )
        }

        val info = if (settings.callerIdEnabled || settings.spamProtectionEnabled) {
            lookupWithBudget(number.key, networkBudgetMs)
                // TRAI's 140 (telemarketing) and 1600 (bank/financial service) series.
                ?: IndianCallSeries.infoFor(number.key, number.e164 ?: rawNumber, clock.now())
        } else {
            null
        }
        val myReport = safe(null) { spamRepository.latestReportFor(number.key) }

        val score = engine.score(
            SpamSignals.from(
                info = info,
                isContact = false,
                userBlocked = userBlocked,
                userReported = myReport?.primaryCategory,
            ),
        )

        val (decision, reason) = decide(settings, score, userBlocked, canCheckContacts, listOnly = info?.flaggedOnlyByList == true)
        val result = CallerResult(
            number = number,
            contactName = null,
            info = info,
            spamScore = score,
            decision = decision,
            reason = reason,
            label = labelFor(info, score, userBlocked),
        )
        if (record) recordOutcome(result)
        return result
    }

    /** Pure decision logic, exposed for tests. */
    fun decide(
        settings: AppSettings,
        score: SpamScore,
        userBlocked: Boolean,
        canCheckContacts: Boolean,
        /** Flagged only by an outside spam list: warn, but never block automatically. */
        listOnly: Boolean = false,
    ): Pair<CallDecision, DecisionReason> {
        if (userBlocked) return CallDecision.BLOCK to DecisionReason.USER_BLOCK_LIST
        // Without contacts access every caller would look "unknown", so this rule needs it.
        if (settings.blockUnknownCallers && canCheckContacts) {
            return CallDecision.BLOCK to DecisionReason.UNKNOWN_CALLER
        }
        if (!settings.spamProtectionEnabled) return CallDecision.ALLOW to DecisionReason.NONE

        val risk = score.riskLevel
        if (listOnly) {
            return if (score.hasEvidence && risk >= RiskLevel.HIGH) CallDecision.WARN to DecisionReason.SUSPECTED_SPAM else CallDecision.ALLOW to DecisionReason.NONE
        }
        if (score.hasEvidence && risk >= RiskLevel.HIGH && score.category in settings.blockedCategories) {
            return CallDecision.BLOCK to DecisionReason.BLOCKED_CATEGORY
        }
        if (settings.autoBlockHighRisk && score.hasEvidence && risk == RiskLevel.VERY_HIGH) {
            return CallDecision.BLOCK to DecisionReason.HIGH_RISK
        }
        if (score.hasEvidence && risk >= RiskLevel.HIGH) {
            return CallDecision.WARN to DecisionReason.SUSPECTED_SPAM
        }
        return CallDecision.ALLOW to DecisionReason.NONE
    }

    private suspend fun lookupWithBudget(key: String, budgetMs: Long): CallerInfo? {
        val cached = safe(null) { callerRepository.getCached(key) }
        if (budgetMs <= 0) return cached
        val remote = withTimeoutOrNull(budgetMs) {
            callerRepository.lookup(key, LookupPolicy.CACHE_FIRST)
        }
        return (remote as? AppResult.Success)?.data ?: cached
    }

    private suspend fun hiddenResult(settings: AppSettings, record: Boolean): CallerResult {
        val block = settings.blockHiddenNumbers
        val result = CallerResult(
            number = null,
            contactName = null,
            info = null,
            spamScore = SpamScore.NONE,
            decision = if (block) CallDecision.BLOCK else CallDecision.ALLOW,
            reason = if (block) DecisionReason.HIDDEN_NUMBER else DecisionReason.NONE,
            label = CallerLabel.HIDDEN,
            isHidden = true,
        )
        if (block && record) recordOutcome(result)
        return result
    }

    private fun unknownResult(number: PhoneNumber?, reason: DecisionReason = DecisionReason.NONE) = CallerResult(
        number = number,
        contactName = null,
        info = null,
        spamScore = SpamScore.NONE,
        decision = CallDecision.ALLOW,
        reason = reason,
        label = CallerLabel.UNKNOWN,
    )

    private suspend fun recordOutcome(result: CallerResult) {
        val now = clock.now()
        val key = result.number?.key ?: HIDDEN_KEY
        val display = result.number?.display ?: HIDDEN_KEY
        safe(Unit) {
            if (result.decision == CallDecision.BLOCK) {
                blockRepository.recordBlockedCall(
                    BlockedCall(0, key, display, result.reason, result.spamScore.category, now),
                )
                statsRepository.increment(StatKey.SPAM_BLOCKED)
            }
            if (result.displayName != null || result.spamScore.hasEvidence) {
                statsRepository.increment(StatKey.CALLS_IDENTIFIED)
            }
            identifiedCallRepository.record(
                IdentifiedCall(
                    id = 0,
                    numberKey = key,
                    displayNumber = display,
                    name = result.displayName,
                    label = result.label,
                    decision = result.decision,
                    spamScore = result.spamScore.score,
                    timestamp = now,
                ),
            )
        }
        analytics.track(AnalyticsEvent.CallerIdentified(result.label.name))
        if (result.spamScore.riskLevel >= RiskLevel.HIGH) {
            analytics.track(AnalyticsEvent.SpamDetected(result.spamScore.riskLevel.name))
        }
    }

    private suspend inline fun <T> safe(default: T, crossinline block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A broken data source must never break an incoming call.
        default
    }

    companion object {
        /** CallScreeningService must respond within ~5 s; leave headroom for everything else. */
        const val DEFAULT_NETWORK_BUDGET_MS = 2_500L
        const val HIDDEN_KEY = "hidden"

        /** Maps what we know to a presentation label. Never invents a scam label without evidence. */
        fun labelFor(info: CallerInfo?, score: SpamScore, userBlocked: Boolean): CallerLabel {
            val risk = score.riskLevel
            return when {
                score.hasEvidence && score.category.isSevere && risk >= RiskLevel.HIGH -> CallerLabel.POSSIBLE_SCAM
                score.hasEvidence && score.category == SpamCategory.TELEMARKETING && risk >= RiskLevel.MODERATE ->
                    CallerLabel.TELEMARKETING
                score.hasEvidence && risk >= RiskLevel.HIGH -> CallerLabel.SUSPECTED_SPAM
                userBlocked -> CallerLabel.SUSPECTED_SPAM
                info?.verified == true -> CallerLabel.VERIFIED_BUSINESS
                info?.hasIdentity == true && info.identityType == IdentityType.BUSINESS -> CallerLabel.BUSINESS
                info?.hasIdentity == true -> CallerLabel.PERSON
                else -> CallerLabel.UNKNOWN
            }
        }
    }
}
