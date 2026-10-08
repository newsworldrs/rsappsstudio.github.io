package com.rskusum.whocaller.feature.premium

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.rskusum.whocaller.core.common.ApplicationScope
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.common.result.AppResult
import com.rskusum.whocaller.core.domain.repository.PremiumRepository
import com.rskusum.whocaller.core.network.NetworkDataSource
import com.rskusum.whocaller.core.security.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The three ways to buy Premium. */
enum class PlanKind { MONTHLY, YEARLY, LIFETIME }

/** A plan with its price exactly as Google Play formats it for the user's country. Nothing is hard-coded. */
data class PremiumProduct(
    val kind: PlanKind,
    val productId: String,
    /** Base plan of the subscription ("monthly" / "yearly"); null for the lifetime product. */
    val basePlanId: String?,
    val formattedPrice: String,
    val priceMicros: Long,
    /** ISO 8601 period, e.g. P1M / P1Y; null for lifetime. */
    val billingPeriod: String?,
    /** ISO 8601 free-trial period (e.g. P7D) when Play Console has a trial offer on this base plan. */
    val freeTrialPeriod: String?,
    internal val details: ProductDetails,
    /** Offer token for subscriptions; null for the one-time product. */
    internal val offerToken: String?,
)

sealed interface BillingState {
    data object Loading : BillingState
    data object Unavailable : BillingState
    data class Ready(val products: List<PremiumProduct>) : BillingState
}

/**
 * Google Play Billing for WhoCaller Premium: a subscription (monthly / yearly base plans) and a
 * one-time lifetime purchase.
 *
 * Entitlement: purchases are verified by the backend (`POST /api/v1/billing/verify`) using the
 * Play Developer API with server-held credentials. If no backend is configured, the Play purchase
 * state is used directly (documented as a weaker fallback in docs/RELEASE.md).
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext context: Context,
    private val network: NetworkDataSource,
    private val secureStorage: SecureStorage,
    private val analytics: AnalyticsTracker,
    @ApplicationScope private val scope: CoroutineScope,
) : PremiumRepository, PurchasesUpdatedListener {

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private val connectMutex = Mutex()

    private val _isPremium = MutableStateFlow(secureStorage.getString(KEY_PREMIUM) == "1")
    override val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _state = MutableStateFlow<BillingState>(BillingState.Loading)
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Which plan is active (lifetime wins over a subscription); null when not premium. */
    private val _activePlan = MutableStateFlow<PlanKind?>(null)
    val activePlan: StateFlow<PlanKind?> = _activePlan.asStateFlow()

    @Volatile private var activeSubscriptionToken: String? = null

    private val _lastError = MutableStateFlow<Int?>(null)
    val lastError: StateFlow<Int?> = _lastError.asStateFlow()

    init {
        scope.launch { refreshPurchases() }
    }

    private suspend fun ensureConnected(): Boolean = connectMutex.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    // Reconnect lazily on next call.
                }
            })
        }
    }

    suspend fun loadProducts() {
        _state.value = BillingState.Loading
        if (!ensureConnected()) {
            _state.value = BillingState.Unavailable
            return
        }
        // One query per product type (Play doesn't mix subscriptions and one-time products).
        val subs = client.queryProductDetails(query(SUBSCRIPTION_ID, BillingClient.ProductType.SUBS)).productDetailsList.orEmpty()
        val inApp = client.queryProductDetails(query(LIFETIME_ID, BillingClient.ProductType.INAPP)).productDetailsList.orEmpty()

        val plans = subs.flatMap { details ->
            val offers = details.subscriptionOfferDetails.orEmpty()
            listOf(BASE_PLAN_MONTHLY to PlanKind.MONTHLY, BASE_PLAN_YEARLY to PlanKind.YEARLY).mapNotNull { (basePlan, kind) ->
                val forPlan = offers.filter { it.basePlanId == basePlan }
                // A free-trial offer if Play Console has one for this base plan, else the base plan itself.
                val trial = forPlan.firstOrNull { o -> o.offerId != null && o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                val offer = trial ?: forPlan.firstOrNull { it.offerId == null } ?: forPlan.firstOrNull() ?: return@mapNotNull null
                val recurring = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                PremiumProduct(
                    kind = kind,
                    productId = details.productId,
                    basePlanId = basePlan,
                    formattedPrice = recurring.formattedPrice,
                    priceMicros = recurring.priceAmountMicros,
                    billingPeriod = recurring.billingPeriod,
                    freeTrialPeriod = offer.pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod,
                    details = details,
                    offerToken = offer.offerToken,
                )
            }
        } + inApp.mapNotNull { details ->
            val price = details.oneTimePurchaseOfferDetails ?: return@mapNotNull null
            PremiumProduct(
                kind = PlanKind.LIFETIME,
                productId = details.productId,
                basePlanId = null,
                formattedPrice = price.formattedPrice,
                priceMicros = price.priceAmountMicros,
                billingPeriod = null,
                freeTrialPeriod = null,
                details = details,
                offerToken = null,
            )
        }
        _state.value = if (plans.isEmpty()) BillingState.Unavailable else BillingState.Ready(plans.sortedBy { it.kind.ordinal })
    }

    private fun query(productId: String, type: String) = QueryProductDetailsParams.newBuilder()
        .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(type).build()))
        .build()

    fun launchPurchase(activity: Activity, product: PremiumProduct) {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product.details)
        product.offerToken?.let(productParams::setOfferToken)
        val builder = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams.build()))
        // Monthly ↔ yearly: switch the existing subscription instead of starting a second one.
        val current = activeSubscriptionToken
        if (product.kind != PlanKind.LIFETIME && current != null) {
            builder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(current)
                    .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION)
                    .build(),
            )
        }
        _lastError.value = null
        val result = client.launchBillingFlow(activity, builder.build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) _lastError.value = R.string.premium_error
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> scope.launch { purchases.orEmpty().forEach { handlePurchase(it) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refreshPurchases() }
            else -> _lastError.value = R.string.premium_error
        }
    }

    /** Re-reads what this Google account owns (subscription and lifetime). Also "Restore purchases". */
    suspend fun refreshPurchases() {
        if (!ensureConnected()) return
        val owned = listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).flatMap { type ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()).purchasesList
        }.filter { p -> p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { it in ALL_PRODUCT_IDS } }
        activeSubscriptionToken = owned.firstOrNull { SUBSCRIPTION_ID in it.products }?.purchaseToken
        if (owned.isEmpty()) {
            setPremium(false, null)
        } else {
            owned.forEach { handlePurchase(it) }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> _pending.value = true
            Purchase.PurchaseState.PURCHASED -> {
                _pending.value = false
                val productId = purchase.products.firstOrNull() ?: return
                val verified = when (val r = network.verifyPurchase(productId, purchase.purchaseToken)) {
                    is AppResult.Success -> r.data.valid
                    // No backend in this build: rely on Play's purchase state.
                    is AppResult.Failure -> r.error == AppError.BACKEND_NOT_CONFIGURED
                }
                if (!verified) return
                if (!purchase.isAcknowledged) {
                    client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                    )
                    analytics.track(AnalyticsEvent.SubscriptionStarted(productId))
                }
                val kind = when {
                    LIFETIME_ID in purchase.products -> PlanKind.LIFETIME
                    else -> null // monthly/yearly isn't in the purchase; the Play Console base plan decides
                }
                if (SUBSCRIPTION_ID in purchase.products) activeSubscriptionToken = purchase.purchaseToken
                setPremium(true, kind)
            }
            else -> Unit
        }
    }

    private fun setPremium(value: Boolean, kind: PlanKind?) {
        _isPremium.value = value
        _activePlan.value = when {
            !value -> null
            kind == PlanKind.LIFETIME || _activePlan.value == PlanKind.LIFETIME -> PlanKind.LIFETIME
            else -> kind ?: _activePlan.value
        }
        secureStorage.putString(KEY_PREMIUM, if (value) "1" else null)
    }

    companion object {
        // Play Console ids (docs/PLAY_BILLING.md). Changing them breaks existing purchases.
        /** Subscription with two auto-renewing base plans. */
        const val SUBSCRIPTION_ID = "whocaller_premium"
        const val BASE_PLAN_MONTHLY = "monthly"
        const val BASE_PLAN_YEARLY = "yearly"
        /** One-time, non-consumable in-app product. */
        const val LIFETIME_ID = "whocaller_premium_lifetime"
        val ALL_PRODUCT_IDS = setOf(SUBSCRIPTION_ID, LIFETIME_ID)
        private const val KEY_PREMIUM = "premium_entitlement"
    }
}
