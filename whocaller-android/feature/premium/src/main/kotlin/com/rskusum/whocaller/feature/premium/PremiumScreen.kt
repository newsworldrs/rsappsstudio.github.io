package com.rskusum.whocaller.feature.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rskusum.whocaller.core.common.analytics.AnalyticsEvent
import com.rskusum.whocaller.core.common.analytics.AnalyticsTracker
import com.rskusum.whocaller.core.permissions.findActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.rskusum.whocaller.core.ui.R as UiR

@HiltViewModel
class PremiumViewModel @Inject constructor(
    private val billing: BillingManager,
    analytics: AnalyticsTracker,
) : ViewModel() {
    val state: StateFlow<BillingState> = billing.state
    val isPremium: StateFlow<Boolean> = billing.isPremium
    val pending: StateFlow<Boolean> = billing.pending
    val error: StateFlow<Int?> = billing.lastError
    val activePlan: StateFlow<PlanKind?> = billing.activePlan

    init {
        analytics.track(AnalyticsEvent.PremiumViewed)
        viewModelScope.launch { billing.loadProducts() }
    }

    fun buy(activity: android.app.Activity, product: PremiumProduct) = billing.launchPurchase(activity, product)

    fun restore() = viewModelScope.launch { billing.refreshPurchases() }
}

private data class Feature(val label: Int, val free: Boolean)

private val FEATURES = listOf(
    Feature(R.string.premium_feature_callerid, true),
    Feature(R.string.premium_feature_basic_spam, true),
    Feature(R.string.premium_feature_search, true),
    Feature(R.string.premium_feature_basic_block, true),
    Feature(R.string.premium_feature_history, true),
    Feature(R.string.premium_feature_advanced_spam, false),
    Feature(R.string.premium_feature_lookup, false),
    Feature(R.string.premium_feature_more_searches, false),
    Feature(R.string.premium_feature_advanced_block, false),
    Feature(R.string.premium_feature_business, false),
    // ADS OFF (first two years): there are no ads to remove.
    // Feature(R.string.premium_feature_no_ads, false),
    Feature(R.string.premium_feature_stats, false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumScreen(onBack: () -> Unit, viewModel: PremiumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val premium by viewModel.isPremium.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val activePlan by viewModel.activePlan.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.premium_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.premium_subtitle), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.premium_free), Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.premium_plan), Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    FEATURES.forEach { f ->
                        val label = stringResource(f.label)
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f))
                            Box72(f.free)
                            Box72(true)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (premium) {
                Text(stringResource(R.string.premium_active), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (activePlan == PlanKind.LIFETIME) {
                    Text(stringResource(R.string.premium_lifetime_owned), style = MaterialTheme.typography.bodyMedium)
                } else TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions?package=${context.packageName}")),
                    )
                }) { Text(stringResource(R.string.premium_manage)) }
            } else {
                when (val s = state) {
                    BillingState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.premium_loading_prices))
                    }
                    BillingState.Unavailable -> Text(stringResource(R.string.premium_unavailable))
                    is BillingState.Ready -> PlanPicker(s.products) { p -> context.findActivity()?.let { viewModel.buy(it, p) } }
                }
                if (pending) Text(stringResource(R.string.premium_pending), color = MaterialTheme.colorScheme.tertiary)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { viewModel.restore() }) { Text(stringResource(R.string.premium_restore)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.premium_legal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Box72(included: Boolean) {
    val description = if (included) "✓" else "–"
    Icon(
        if (included) Icons.Filled.Check else Icons.Filled.Remove,
        contentDescription = null,
        tint = if (included) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier.width(72.dp).semantics { contentDescription = description },
    )
}

/** Monthly / Yearly (best value) / Lifetime cards with Google Play's prices, then one buy button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanPicker(products: List<PremiumProduct>, onBuy: (PremiumProduct) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(products.firstOrNull { it.kind == PlanKind.YEARLY }?.kind ?: products.first().kind) }
    val monthly = products.firstOrNull { it.kind == PlanKind.MONTHLY }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        products.forEach { p ->
            val isSelected = p.kind == selected
            val saving = if (p.kind == PlanKind.YEARLY && monthly != null && monthly.priceMicros > 0) {
                (100 - p.priceMicros * 100 / (monthly.priceMicros * 12)).toInt().takeIf { it in 5..90 }
            } else {
                null
            }
            OutlinedCard(
                onClick = { selected = p.kind },
                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = { selected = p.kind })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    when (p.kind) {
                                        PlanKind.MONTHLY -> R.string.premium_plan_monthly
                                        PlanKind.YEARLY -> R.string.premium_plan_yearly
                                        PlanKind.LIFETIME -> R.string.premium_plan_lifetime
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (p.kind == PlanKind.YEARLY) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.premium_best_value),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        val sub = when {
                            p.kind == PlanKind.LIFETIME -> stringResource(R.string.premium_lifetime_desc)
                            p.freeTrialPeriod != null -> stringResource(R.string.premium_trial, trialDays(p.freeTrialPeriod))
                            saving != null -> stringResource(R.string.premium_save, saving)
                            else -> stringResource(R.string.premium_cancel_anytime)
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(p.formattedPrice, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                when (p.kind) {
                                    PlanKind.MONTHLY -> R.string.premium_per_month
                                    PlanKind.YEARLY -> R.string.premium_per_year
                                    PlanKind.LIFETIME -> R.string.premium_one_time
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val chosen = products.firstOrNull { it.kind == selected } ?: products.first()
        Button(onClick = { onBuy(chosen) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(
                stringResource(
                    when {
                        chosen.kind == PlanKind.LIFETIME -> R.string.premium_buy_lifetime
                        chosen.freeTrialPeriod != null -> R.string.premium_start_trial
                        else -> R.string.premium_continue
                    },
                ),
            )
        }
    }
}

/** "P7D" → 7, "P1W" → 7, "P1M" → 30. */
private fun trialDays(period: String): Int {
    val m = Regex("""P(\d+)([DWM])""").find(period) ?: return 7
    val n = m.groupValues[1].toInt()
    return when (m.groupValues[2]) {
        "W" -> n * 7
        "M" -> n * 30
        else -> n
    }
}
