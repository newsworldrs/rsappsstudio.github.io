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

/** A subscription plan with its price exactly as Google Play formats it. Nothing is hard-coded. */
data class PremiumProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    /** ISO 8601 period, e.g. P1M / P1Y. */
    val billingPeriod: String,
    internal val details: ProductDetails,
    internal val offerToken: String,
)

sealed interface BillingState {
    data object Loading : BillingState
    data object Unavailable : BillingState
    data class Ready(val products: List<PremiumProduct>) : BillingState
}

/**
 * Google Play Billing for WhoCaller Premium subscriptions.
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
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PRODUCT_IDS.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        val result = client.queryProductDetails(params)
        val products = result.productDetailsList.orEmpty().mapNotNull { details ->
            val offer = details.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
            PremiumProduct(
                productId = details.productId,
                title = details.name,
                formattedPrice = phase.formattedPrice,
                billingPeriod = phase.billingPeriod,
                details = details,
                offerToken = offer.offerToken,
            )
        }
        _state.value = if (products.isEmpty()) BillingState.Unavailable else BillingState.Ready(products)
    }

    fun launchPurchase(activity: Activity, product: PremiumProduct) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product.details)
                        .setOfferToken(product.offerToken)
                        .build(),
                ),
            )
            .build()
        _lastError.value = null
        val result = client.launchBillingFlow(activity, params)
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

    suspend fun refreshPurchases() {
        if (!ensureConnected()) return
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        val active = result.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (active.isEmpty()) {
            setPremium(false)
        } else {
            active.forEach { handlePurchase(it) }
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
                setPremium(true)
            }
            else -> Unit
        }
    }

    private fun setPremium(value: Boolean) {
        _isPremium.value = value
        secureStorage.putString(KEY_PREMIUM, if (value) "1" else null)
    }

    companion object {
        /** Subscription product ids configured in Play Console (see docs/RELEASE.md). */
        val PRODUCT_IDS = listOf("whocaller_premium_monthly", "whocaller_premium_yearly")
        private const val KEY_PREMIUM = "premium_entitlement"
    }
}
