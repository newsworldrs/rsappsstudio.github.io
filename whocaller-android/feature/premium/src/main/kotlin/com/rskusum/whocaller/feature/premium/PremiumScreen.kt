package com.rskusum.whocaller.feature.premium

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
    Feature(R.string.premium_feature_no_ads, false),
    Feature(R.string.premium_feature_stats, false),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumScreen(onBack: () -> Unit, viewModel: PremiumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val premium by viewModel.isPremium.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
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
                TextButton(onClick = {
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
                    is BillingState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.products.forEach { p ->
                            val period = stringResource(
                                if (p.billingPeriod.endsWith("Y")) R.string.premium_period_year else R.string.premium_period_month,
                            )
                            Button(
                                onClick = { context.findActivity()?.let { viewModel.buy(it, p) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.premium_subscribe, stringResource(R.string.premium_per_period, p.formattedPrice, period))) }
                        }
                    }
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
