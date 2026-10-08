package com.rskusum.whocaller.core.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.RiskLevel
import com.rskusum.whocaller.core.model.SpamScore
import com.rskusum.whocaller.core.ui.R
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.labelRes
import com.rskusum.whocaller.core.ui.util.messageRes

/** Circle avatar with an initial or an icon that reflects the caller label. */
@Composable
fun CallerAvatar(name: String?, label: CallerLabel, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val warning = label == CallerLabel.POSSIBLE_SCAM || label == CallerLabel.SUSPECTED_SPAM || label == CallerLabel.TELEMARKETING
    GradientAvatar(
        name = name,
        size = size,
        modifier = modifier.clearAndSetSemantics { },
        warning = warning,
        icon = iconFor(label),
    )
}

/** Pill showing the risk band. Colour is never the only signal: the band is always written out. */
@Composable
fun RiskBadge(score: SpamScore, modifier: Modifier = Modifier) {
    val level = score.riskLevel
    val color = WhoCallerTheme.riskColors.forLevel(level)
    val label = stringResource(level.labelRes())
    val description = stringResource(R.string.risk_score_description, label, score.score)
    Surface(
        color = color,
        contentColor = WhoCallerTheme.riskColors.onRisk,
        shape = RoundedCornerShape(50),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (level >= RiskLevel.HIGH) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Verified tick shown only when the backend has verified a business. */
@Composable
fun VerifiedBadge(modifier: Modifier = Modifier) {
    val text = stringResource(R.string.label_verified_business)
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Verified,
            contentDescription = null,
            tint = WhoCallerTheme.riskColors.safe,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = WhoCallerTheme.riskColors.safe)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

@Composable
fun StatCard(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(error: AppError, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    EmptyState(
        icon = if (error == AppError.NETWORK_UNAVAILABLE) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
        title = stringResource(error.messageRes()),
        modifier = modifier,
        actionLabel = onRetry?.let { stringResource(R.string.action_retry) },
        onAction = onRetry,
    )
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = loading }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Softly pulsing dot used for "protection active" status. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, animate: Boolean = true) {
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        a
    } else {
        1f
    }
    Box(modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(color))
}

/** Warning banner for suspected spam/scam. */
@Composable
fun WarningBanner(title: String, message: String?, severe: Boolean, modifier: Modifier = Modifier) {
    val color = if (severe) WhoCallerTheme.riskColors.veryHigh else WhoCallerTheme.riskColors.high
    Surface(
        color = color,
        contentColor = WhoCallerTheme.riskColors.onRisk,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (severe) Icons.Filled.Block else Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun PrimaryWideButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp)) { Text(text) }
}

/** Accessible switch row: the whole row toggles and is announced as a switch. */
@Composable
fun SettingSwitch(
    title: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** Clickable navigation row. */
@Composable
fun NavigationRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Preview
@Composable
private fun ComponentsPreview() {
    WhoCallerTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CallerAvatar("Asha", CallerLabel.CONTACT)
            RiskBadge(SpamScore(82, com.rskusum.whocaller.core.model.SpamCategory.SPAM, 0.9f, 10))
            VerifiedBadge()
            WarningBanner("Suspected spam", "Reported by 84 users", severe = false)
        }
    }
}
