package com.rskusum.whocaller.feature.postcall

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.whocaller.core.model.ReportCategory
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.ActionIntents
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** "Know this caller?" — shown after an unknown caller was answered or declined. */
@dagger.hilt.android.AndroidEntryPoint
class PostCallActivity : ComponentActivity() {

    private val viewModel: PostCallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_NUMBER)?.let { PostCallNotifier.cancel(this, it) }
        setContent {
            WhoCallerTheme(dynamicColor = false) {
                PostCallScreen(viewModel, onClose = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_ANSWERED = "answered"

        fun intent(context: Context, number: String, answered: Boolean): Intent =
            Intent(context, PostCallActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_ANSWERED, answered)
    }
}

// ---------- Look ----------

private val Background = Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF131A3F), Color(0xFF0B1026)))
private val Card = Color(0xFF161D3F)
private val CardBorder = Color(0xFF26305C)
private val TextMain = Color(0xFFF1F3FF)
private val TextSub = Color(0xFFA3ABCF)
private val Red = Color(0xFFFF5A5F)
private val Mint = Color(0xFF2DD4BF)
private val Blue = Color(0xFF3B82F6)
private val Violet = Color(0xFF8B5CF6)
private val Pink = Color(0xFFEC4899)

private data class CategoryLook(val label: Int, val icon: ImageVector, val color: Color)

private fun look(c: ReportCategory): CategoryLook = when (c) {
    ReportCategory.SPAM -> CategoryLook(R.string.postcall_cat_spam, Icons.Filled.Warning, Color(0xFFEF4444))
    ReportCategory.TELEMARKETING -> CategoryLook(R.string.postcall_cat_telemarketing, Icons.Filled.Call, Blue)
    ReportCategory.FINANCIAL_SCAM -> CategoryLook(R.string.postcall_cat_financial_scam, Icons.Filled.CurrencyRupee, Color(0xFFF59E0B))
    ReportCategory.FRAUD_FAKE_OFFER -> CategoryLook(R.string.postcall_cat_fraud_offer, Icons.Filled.CardGiftcard, Pink)
    ReportCategory.IMPERSONATION -> CategoryLook(R.string.postcall_cat_impersonation, Icons.Filled.Person, Color(0xFFA855F7))
    ReportCategory.BANKING_SCAM -> CategoryLook(R.string.postcall_cat_banking_scam, Icons.Filled.AccountBalance, Color(0xFF0EA5E9))
    ReportCategory.BUSINESS_SERVICE -> CategoryLook(R.string.postcall_cat_business, Icons.Filled.BusinessCenter, Violet)
    ReportCategory.DELIVERY -> CategoryLook(R.string.postcall_cat_delivery, Icons.Filled.LocalShipping, Color(0xFF10B981))
    ReportCategory.ROBOCALL -> CategoryLook(R.string.postcall_cat_robocall, Icons.Filled.SmartToy, Color(0xFF94A3B8))
    ReportCategory.HARASSMENT -> CategoryLook(R.string.postcall_cat_harassment, Icons.Filled.SentimentVeryDissatisfied, Color(0xFFF43F5E))
    ReportCategory.OTHER -> CategoryLook(R.string.postcall_cat_other, Icons.Filled.MoreHoriz, Color(0xFFCBD5E1))
    ReportCategory.NOT_SPAM -> CategoryLook(R.string.postcall_cat_not_spam, Icons.Filled.CheckCircle, Color(0xFF22C55E))
}

// ---------- Screen ----------

@Composable
private fun PostCallScreen(viewModel: PostCallViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val limitHit by viewModel.limitHit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshContact() }
    LaunchedEffect(limitHit) {
        if (limitHit > 0) Toast.makeText(context, R.string.postcall_limit, Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(state.done) {
        if (state.done) {
            delay(1_600)
            onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        AnimatedContent(state.done, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "done") { done ->
            if (done) {
                ThanksContent()
            } else {
                FormContent(
                    state = state,
                    onClose = {
                        viewModel.skip()
                        onClose()
                    },
                    onToggle = viewModel::toggle,
                    onAddContact = { ActionIntents.saveContact(context, state.number, state.knownName) },
                    onViewContact = { state.savedContactId?.let { ActionIntents.viewContact(context, it) } },
                    onSubmit = viewModel::submit,
                    onSkip = {
                        viewModel.skip()
                        onClose()
                    },
                )
            }
        }
    }
}

@Composable
private fun FormContent(
    state: PostCallUiState,
    onClose: () -> Unit,
    onToggle: (ReportCategory) -> Unit,
    onAddContact: () -> Unit,
    onViewContact: () -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Header
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Filled.Close, stringResource(R.string.postcall_close), tint = TextMain)
            }
            Column(Modifier.align(Alignment.Center).padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.postcall_title),
                    color = TextMain,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.postcall_subtitle), color = TextSub, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }

        // Caller
        Spacer(Modifier.height(18.dp))
        AvatarWithWaves(state.knownName)
        Spacer(Modifier.height(14.dp))
        state.knownName?.let {
            Text(it, color = TextMain, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(state.display.ifEmpty { state.number }, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Red.copy(alpha = 0.16f)).border(1.dp, Red.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Error, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.spamWarning && state.reportCount > 0) {
                    pluralStringResource(R.plurals.postcall_suspected_spam, state.reportCount, state.reportCount)
                } else {
                    stringResource(R.string.postcall_unknown_caller)
                },
                color = Red,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (state.location != null || state.operator != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.location?.let {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, tint = TextSub, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(it, color = TextSub, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.location != null && state.operator != null) {
                    Text("  |  ", color = TextSub.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                }
                state.operator?.let { Text(it, color = TextSub, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        // Save contact (separate from reporting)
        Spacer(Modifier.height(20.dp))
        SaveContactCard(state.savedContactId != null, onAddContact, onViewContact)

        // Spam categories
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.postcall_is_spam), color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.postcall_pick_up_to), color = TextSub, style = MaterialTheme.typography.bodyMedium)
            }
            Counter(state.selected.size)
        }
        Spacer(Modifier.height(12.dp))
        CategoryGrid(state.selected, onToggle)

        if (state.selected.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.postcall_latest_selection), color = TextSub, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.selected.forEach { c ->
                            val l = look(c)
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(l.color.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = l.color, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(l.label), color = TextMain, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (state.savedContactId != null) {
            Spacer(Modifier.height(14.dp))
            AlreadyKnownCard(onViewContact)
        }

        if (state.failed) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.postcall_failed), color = Red, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }

        // Actions
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .alpha(if (state.canSubmit || state.submitting) 1f else 0.45f)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Pink, Violet, Blue)))
                .clickable(enabled = state.canSubmit, role = Role.Button, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            if (state.submitting) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.postcall_submit), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(50))
                .border(1.5.dp, TextSub.copy(alpha = 0.6f), RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onSkip),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.postcall_skip), color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SaveContactCard(saved: Boolean, onAdd: () -> Unit, onView: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.dp, CardBorder, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Mint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (saved) Icons.Filled.CheckCircle else Icons.Filled.PersonAdd, contentDescription = null, tint = Mint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (saved) R.string.postcall_saved else R.string.postcall_know_caller),
                color = TextMain,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(if (saved) R.string.postcall_saved_desc else R.string.postcall_save_desc),
                color = TextSub,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.5.dp, if (saved) Mint else Blue, RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = if (saved) onView else onAdd)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (saved) Icons.Filled.Person else Icons.Filled.PersonAdd, contentDescription = null, tint = TextMain, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (saved) R.string.postcall_view_contact else R.string.postcall_add_contact),
                color = TextMain,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AlreadyKnownCard(onView: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onView).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Mint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Mint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.postcall_already_known), color = TextMain, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.postcall_already_known_desc), color = TextSub, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.postcall_view_contact), color = Blue, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Blue)
    }
}

@Composable
private fun Counter(count: Int) {
    val full = count >= ReportCategory.MAX_PER_REPORT
    Text(
        stringResource(R.string.postcall_counter, count, ReportCategory.MAX_PER_REPORT),
        color = if (full) Mint else TextSub,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, if (full) Mint else CardBorder, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun CategoryGrid(selected: List<ReportCategory>, onToggle: (ReportCategory) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 380.dp) 4 else 3
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReportCategory.entries.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c -> CategoryCard(c, c in selected, Modifier.weight(1f)) { onToggle(c) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(category: ReportCategory, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val l = look(category)
    val label = stringResource(l.label)
    Box(
        modifier
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) l.color.copy(alpha = 0.18f) else Card)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) l.color else CardBorder, RoundedCornerShape(16.dp))
            .clickable(role = Role.Checkbox, onClickLabel = label, onClick = onClick)
            .semantics { this.selected = isSelected }
            .padding(10.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Icon(l.icon, contentDescription = null, tint = l.color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = TextMain, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (isSelected) {
            Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(l.color),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}

/** Gradient ring that turns, with animated sound-wave bars on both sides. */
@Composable
private fun AvatarWithWaves(name: String?) {
    val transition = rememberInfiniteTransition(label = "avatar")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "ring")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "wave")
    val pulse by transition.animateFloat(0.92f, 1f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "pulse")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Waves(phase, listOf(Blue, Violet), mirrored = true)
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(132.dp).alpha(0.35f * pulse).background(Brush.radialGradient(listOf(Violet, Color.Transparent)), CircleShape))
            Box(Modifier.size(118.dp).rotate(angle).border(4.dp, Brush.sweepGradient(listOf(Blue, Violet, Pink, Blue)), CircleShape))
            Box(
                Modifier.size(102.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF1E2A5E), Color(0xFF2B1F5C)))),
                contentAlignment = Alignment.Center,
            ) {
                val initial = name?.firstOrNull { it.isLetter() }?.uppercaseChar()
                if (initial != null) {
                    Text(initial.toString(), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
                }
            }
        }
        Waves(phase, listOf(Violet, Pink), mirrored = false)
    }
}

@Composable
private fun Waves(phase: Float, colors: List<Color>, mirrored: Boolean) {
    Canvas(Modifier.width(64.dp).height(44.dp)) {
        val bars = 9
        val gap = size.width / bars
        val barWidth = gap * 0.45f
        for (i in 0 until bars) {
            // Taller bars next to the avatar, fading outwards.
            val distance = if (mirrored) bars - 1 - i else i
            val envelope = 1f - distance / bars.toFloat() * 0.8f
            val wave = 0.35f + 0.65f * abs(sin(phase + i * 0.7f))
            val h = size.height * envelope * wave
            val color = colors[if (i % 2 == 0) 0 else 1].copy(alpha = 0.4f + 0.6f * envelope)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap + (gap - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

@Composable
private fun ThanksContent() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Mint, Blue))), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.postcall_thanks), color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.postcall_thanks_desc), color = TextSub, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
