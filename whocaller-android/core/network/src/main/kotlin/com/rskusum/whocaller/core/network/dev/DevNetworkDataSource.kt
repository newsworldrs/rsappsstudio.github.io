package com.rskusum.whocaller.core.network.dev

import com.rskusum.whocaller.core.common.Clock
import com.rskusum.whocaller.core.common.TimeUnits
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.core.network.model.BusinessDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationRequestDto
import com.rskusum.whocaller.core.network.model.BusinessVerificationResponseDto
import com.rskusum.whocaller.core.network.model.CountryDto
import com.rskusum.whocaller.core.network.model.NumberInfoDto
import com.rskusum.whocaller.core.network.model.PurchaseVerificationResponseDto
import com.rskusum.whocaller.core.network.model.RemoteConfigDto
import com.rskusum.whocaller.core.network.model.ReportRequestDto
import com.rskusum.whocaller.core.network.model.ReportResponseDto
import com.rskusum.whocaller.core.network.model.SpamListResponseDto
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * DEVELOPMENT ONLY backend used by debug builds when no `WHOCALLER_API_BASE_URL` is configured.
 *
 * It knows only a handful of **fictional** numbers from ranges reserved for fiction/testing
 * (North America +1 xxx-555-01xx, UK Ofcom drama range +44 20 7946 0xxx). Every other number
 * returns NOT_FOUND, exactly like a real backend with no data. All names are prefixed "[Demo]".
 * Never shipped in release builds (see app DI).
 */
class DevNetworkDataSource(private val clock: Clock) : NetworkDataSource {

    override val isConfigured = true

    private val reports = ConcurrentHashMap<String, MutableList<ReportRequestDto>>()

    private val fixtures: Map<String, NumberInfoDto> by lazy {
        val now = clock.now()
        listOf(
            NumberInfoDto(
                number = "+12025550142", name = "[Demo] Example Telemarketing", identityType = "BUSINESS",
                category = "TELEMARKETING", spamScore = 68, confidence = 0.8f, reportCount = 42, reportsLast24h = 3,
                reportsLast7d = 11, blockCount = 17, lastReportedAt = now - 2 * TimeUnits.HOUR,
                categoryVotes = mapOf("TELEMARKETING" to 35, "SPAM" to 7), region = "US",
            ),
            NumberInfoDto(
                number = "+12025550199", name = null, identityType = "UNKNOWN", category = "SCAM", spamScore = 88,
                confidence = 0.9f, reportCount = 127, reportsLast24h = 9, reportsLast7d = 40, blockCount = 64,
                lastReportedAt = now - 30 * TimeUnits.MINUTE,
                categoryVotes = mapOf("SCAM" to 90, "FRAUD" to 22, "SPAM" to 15), region = "US",
            ),
            NumberInfoDto(
                number = "+12025550123", name = "[Demo] Example Internet Services", identityType = "BUSINESS",
                category = "BUSINESS", spamScore = 2, confidence = 0.95f, verified = true, businessId = "demo-isp",
                region = "US", carrier = "Demo Carrier", lineType = "FIXED_LINE",
            ),
            NumberInfoDto(
                number = "+442079460321", name = "[Demo] Example Pizza", identityType = "BUSINESS",
                category = "BUSINESS", spamScore = 0, confidence = 0.6f, businessId = "demo-pizza", region = "GB",
            ),
        ).associateBy { it.number }
    }

    private val businesses = listOf(
        BusinessDto(
            businessId = "demo-isp", name = "[Demo] Example Internet Services", phoneNumbers = listOf("+12025550123"),
            category = "Telecommunications", address = "Demo City", website = "https://example.com",
            email = "support@example.com", verified = true, verificationDate = 1_700_000_000_000, country = "US",
            language = "en", hours = "Mon–Fri 09:00–18:00",
        ),
        BusinessDto(
            businessId = "demo-pizza", name = "[Demo] Example Pizza", phoneNumbers = listOf("+442079460321"),
            category = "Restaurant", address = "Demo Street, London", website = "https://example.org",
            verified = false, country = "GB", language = "en", hours = "Daily 11:00–23:00",
        ),
    )

    override suspend fun getNumber(e164: String): AppResult<NumberInfoDto> {
        delay(SIMULATED_LATENCY_MS)
        val fixture = fixtures[e164]
        val local = reports[e164].orEmpty()
        return when {
            fixture != null -> AppResult.Success(fixture.copy(reportCount = fixture.reportCount + local.size))
            local.isNotEmpty() -> AppResult.Success(
                NumberInfoDto(number = e164, reportCount = local.size, lastReportedAt = local.maxOf { it.reportedAt }),
            )
            else -> AppResult.Failure(AppError.NOT_FOUND)
        }
    }

    override suspend fun reportNumber(e164: String, request: ReportRequestDto): AppResult<ReportResponseDto> {
        delay(SIMULATED_LATENCY_MS)
        val list = reports.getOrPut(e164) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.clientReportId == request.clientReportId }) list += request
        }
        return AppResult.Success(ReportResponseDto(reportId = request.clientReportId, accepted = true))
    }

    override suspend fun signalBlock(e164: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun searchBusinesses(query: String, region: String?): AppResult<List<BusinessDto>> {
        delay(SIMULATED_LATENCY_MS)
        val q = query.trim().lowercase()
        return AppResult.Success(
            businesses.filter {
                (region == null || it.country == region) &&
                    (it.name.lowercase().contains(q) || it.category.orEmpty().lowercase().contains(q))
            },
        )
    }

    override suspend fun getBusiness(businessId: String): AppResult<BusinessDto> =
        businesses.firstOrNull { it.businessId == businessId }?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.NOT_FOUND)

    override suspend fun requestBusinessVerification(
        request: BusinessVerificationRequestDto,
    ): AppResult<BusinessVerificationResponseDto> =
        AppResult.Success(BusinessVerificationResponseDto(requestId = "demo-${clock.now()}", status = "PENDING"))

    override suspend fun getConfig(): AppResult<RemoteConfigDto> = AppResult.Success(RemoteConfigDto())

    override suspend fun getCountries(): AppResult<List<CountryDto>> = AppResult.Failure(AppError.NOT_FOUND)

    override suspend fun getSpamList(region: String, since: Long?): AppResult<SpamListResponseDto> =
        AppResult.Success(
            SpamListResponseDto(
                region = region,
                generatedAt = clock.now(),
                entries = fixtures.values.filter { it.region == region && (it.spamScore ?: 0) >= 50 },
            ),
        )

    override suspend fun verifyPurchase(productId: String, purchaseToken: String): AppResult<PurchaseVerificationResponseDto> =
        AppResult.Failure(AppError.BACKEND_NOT_CONFIGURED)

    override suspend fun deleteAccount(): AppResult<Unit> = AppResult.Success(Unit)

    companion object {
        private const val SIMULATED_LATENCY_MS = 250L
    }
}
