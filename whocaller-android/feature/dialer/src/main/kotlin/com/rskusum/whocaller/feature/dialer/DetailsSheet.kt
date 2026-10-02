@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.rskusum.whocaller.feature.dialer

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Contact details card: numbers, email, company, location and every action for the person. */
@Composable
fun DetailsSheet(target: DetailsTarget, viewModel: DialerViewModel, actions: CallActions, onDismiss: () -> Unit) {
    val palette = LocalDialerPalette.current
    val scope = rememberCoroutineScope()
    var data by remember(target) { mutableStateOf<DetailsData?>(null) }
    LaunchedEffect(target) {
        // Phone data first (instant), then the WhoCaller lookup fills in name/spam info.
        val local = viewModel.loadDetailsLocal(target)
        data = local
        val result = viewModel.identifyDetails(local.number)
        data = data?.copy(result = result)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.surface) {
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = palette.accent) }
        } else {
            DetailsContent(
                d,
                viewModel,
                actions,
                onUpdate = { data = it },
                onHistoryChanged = {
                    scope.launch {
                        delay(300) // let the call-log delete land
                        data = data?.let { viewModel.reloadHistory(it) }
                    }
                },
            )
        }
    }
}

@Composable
private fun DetailsContent(
    d: DetailsData,
    viewModel: DialerViewModel,
    actions: CallActions,
    onUpdate: (DetailsData) -> Unit,
    onHistoryChanged: () -> Unit,
) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val card = d.card
    val result = d.result
    val number = d.number
    val display = result?.let { CallerDisplayFormatter.from(context, number, it) }
    val warning = display?.warning == true
    val name = card?.name ?: result?.displayName
    val title = name ?: number?.let { NumberTools.format(it, viewModel.countryIso) }.orEmpty()
    val business = result?.label == CallerLabel.VERIFIED_BUSINESS || result?.label == CallerLabel.BUSINESS || result?.info?.identityType == IdentityType.BUSINESS
    val whatsApp = TelecomActions.whatsAppPackage(context) != null

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(124.dp).border(3.dp, Brush.sweepGradient(if (warning) listOf(WarnRed, Color(0xFFFF9F43), WarnRed) else listOf(Indigo, Violet, Sky, Indigo)), CircleShape),
            contentAlignment = Alignment.Center,
        ) { ContactAvatar(name ?: number, card?.photoUri, 108.dp, warning = warning) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = palette.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (result?.info?.whoCallerVerified == true) {
                Spacer(Modifier.width(6.dp))
                com.rskusum.whocaller.core.ui.component.VerifiedTick(22.dp, description = stringResource(com.rskusum.whocaller.core.ui.R.string.label_verified_id))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                warning -> StatusChip(Icons.Filled.Warning, display?.label.orEmpty(), WarnRed)
                business -> StatusChip(Icons.Filled.Verified, stringResource(R.string.dialer_business_profile), Indigo)
                card != null -> StatusChip(Icons.Filled.CheckCircle, stringResource(R.string.dialer_saved_contact), OkGreen)
                display?.label != null -> StatusChip(null, display.label, palette.subtle)
            }
            if (d.blocked) StatusChip(Icons.Filled.Block, stringResource(R.string.dialer_blocked), WarnRed)
        }
        card?.company?.let {
            Spacer(Modifier.height(6.dp))
            Text(listOfNotNull(card.jobTitle, it).joinToString(" · "), color = palette.text, style = MaterialTheme.typography.bodyMedium)
        }

        if (number != null) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(stringResource(R.string.dialer_call), CallGreen, onClick = { actions.call(number) }) { QuickActionIcon(Icons.Filled.Call, CallGreen) }
                // WHOCALLER VIDEO (experimental)
                QuickAction(stringResource(com.rskusum.whocaller.core.ui.R.string.action_whocaller_video), Violet, onClick = { TelecomActions.whoCallerVideo(context, number) }) {
                    QuickActionIcon(Icons.Filled.Videocam, Violet)
                }
                QuickAction(stringResource(R.string.dialer_sms), Indigo, onClick = { ActionIntents.message(context, number) }) { QuickActionIcon(Icons.AutoMirrored.Filled.Message, Indigo) }
                if (whatsApp) {
                    QuickAction(stringResource(com.rskusum.whocaller.core.ui.R.string.action_whatsapp_call), WhatsAppGreen, onClick = { actions.whatsApp(number, video = false) }) { WhatsAppLogo(26.dp) }
                    QuickAction(stringResource(com.rskusum.whocaller.core.ui.R.string.action_whatsapp_video), WhatsAppGreen, onClick = { actions.whatsApp(number, video = true) }) {
                        QuickActionIcon(Icons.Filled.Videocam, WhatsAppGreen)
                    }
                }
                QuickAction(stringResource(R.string.dialer_share), Sky, onClick = { shareNumber(context, listOfNotNull(name, number).joinToString("\n")) }) {
                    QuickActionIcon(Icons.Filled.Share, Sky)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.actionBg.copy(alpha = 0.5f)).padding(vertical = 4.dp),
        ) {
            val phones = card?.phones?.takeIf { it.isNotEmpty() } ?: listOfNotNull(number?.let { LabeledValue(it, null) })
            phones.forEach { p ->
                InfoRow(
                    icon = Icons.Filled.Phone,
                    label = p.label ?: stringResource(R.string.dialer_phone_number),
                    value = NumberTools.format(p.value, viewModel.countryIso),
                    onClick = { actions.call(p.value) },
                    onLongClick = { copyNumber(context, p.value) },
                    trailing = {
                        MiniAction(Icons.AutoMirrored.Filled.Message, stringResource(R.string.dialer_sms), Indigo) { ActionIntents.message(context, p.value) }
                    },
                )
            }
            card?.emails?.forEach { e ->
                InfoRow(Icons.Filled.Email, e.label ?: stringResource(R.string.dialer_email), e.value, onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${e.value}"))) }
                })
            }
            card?.company?.let { InfoRow(Icons.Filled.Business, stringResource(R.string.dialer_company), it) }
            card?.address?.let { address ->
                InfoRow(Icons.Filled.LocationOn, stringResource(R.string.dialer_address), address, onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address)))) }
                })
            }
            d.facts?.location?.let { InfoRow(Icons.Filled.LocationOn, stringResource(R.string.dialer_location), it) }
            // Current network confirmed by the number's owner (WhoCaller), else the original network
            // from the prefix, which can be wrong after the number was ported.
            val currentOperator = result?.info?.carrier?.takeIf { it.isNotBlank() }
            (currentOperator ?: d.facts?.carrier?.let { stringResource(com.rskusum.whocaller.core.ui.R.string.operator_original_long, it) })
                ?.let { InfoRow(Icons.Filled.SignalCellularAlt, stringResource(R.string.dialer_operator), it) }
            result?.info?.reportCount?.takeIf { it > 0 }?.let { n ->
                InfoRow(Icons.Filled.Flag, stringResource(R.string.dialer_whocaller_reports), context.resources.getQuantityString(R.plurals.call_reported_by, n, n))
            }
        }

        CallHistorySection(d, viewModel, onHistoryChanged)

        // This person's call recordings (saved on the phone only).
        number?.let { n ->
            RecordingsList(n, showNames = false, hideWhenEmpty = true) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.rec_title),
                    color = palette.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (card != null) {
                val star = rememberStarToggle(viewModel)
                StarButton(card.starred) {
                    star(card.contactId, card.starred) { starred -> onUpdate(d.copy(card = card.copy(starred = starred))) }
                }
                TextButton(onClick = { ActionIntents.editContact(context, card.contactId) }) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dialer_edit))
                }
            } else if (number != null) {
                TextButton(onClick = { ActionIntents.saveContact(context, number, result?.displayName) }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dialer_add_contact))
                }
            }
            if (number != null) {
                TextButton(onClick = {
                    viewModel.setBlocked(number, d.numberKey ?: result?.number?.key, name, block = !d.blocked)
                    onUpdate(d.copy(blocked = !d.blocked))
                }) {
                    Icon(Icons.Filled.Block, contentDescription = null, tint = WarnRed)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (d.blocked) R.string.dialer_unblock else R.string.dialer_block), color = WarnRed)
                }
            }
        }
        if (number != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { openWhoCaller(context, "report/${numberPath(number)}") }) {
                    Icon(Icons.Filled.Flag, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dialer_report))
                }
                TextButton(onClick = { openWhoCaller(context, "number/${numberPath(number)}") }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dialer_open_profile))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDialerPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = onClick != null || onLongClick != null, onLongClick = onLongClick) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = palette.subtle, style = MaterialTheme.typography.labelMedium)
            Text(value, color = palette.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun MiniAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)).combinedClickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** Every call with this person (all their numbers): type, date, time and how long you talked. */
@Composable
private fun CallHistorySection(d: DetailsData, viewModel: DialerViewModel, onChanged: () -> Unit) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val withDeletePermission = rememberCallLogDeleter(viewModel)
    var confirmAll by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val history = d.history

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.dialer_call_history),
            color = palette.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (history.isNotEmpty()) {
            TextButton(onClick = { confirmAll = true }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = WarnRed)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dialer_delete_all), color = WarnRed)
            }
        }
    }
    if (history.isEmpty()) {
        Text(stringResource(R.string.dialer_no_history), color = palette.subtle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        return
    }
    val answered = history.count { it.durationSeconds > 0 }
    Text(
        pluralStringResource(R.plurals.dialer_history_summary, history.size, history.size) +
            if (answered > 0) " · " + stringResource(R.string.dialer_talk_time, talkTime(context, d.totalTalkSeconds)) else "",
        color = palette.subtle,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
    val multipleNumbers = history.map { it.numberKey }.distinct().size > 1
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.actionBg.copy(alpha = 0.5f)).padding(vertical = 4.dp)) {
        (if (showAll) history else history.take(HISTORY_PREVIEW)).forEach { entry ->
            HistoryRow(entry, showNumber = multipleNumbers) {
                withDeletePermission {
                    viewModel.deleteCalls(listOf(entry.id))
                    onChanged()
                }
            }
        }
        if (!showAll && history.size > HISTORY_PREVIEW) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.dialer_show_all, history.size))
            }
        }
    }

    if (confirmAll) {
        ConfirmDialog(
            title = stringResource(R.string.dialer_delete_history_title),
            text = stringResource(R.string.dialer_delete_history_confirm),
            confirm = stringResource(R.string.dialer_delete),
            onDismiss = { confirmAll = false },
        ) {
            confirmAll = false
            withDeletePermission {
                viewModel.deleteHistory(d.historyKeys)
                onChanged()
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: CallLogEntry, showNumber: Boolean, onDelete: () -> Unit) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val (icon, color, typeLabel) = callTypeLook(entry.type)
    val whenText = DateUtils.formatDateTime(
        context,
        entry.timestamp,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
    )
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(stringResource(typeLabel), entry.displayNumber.takeIf { showNumber }).joinToString(" · "),
                color = palette.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(whenText, color = palette.subtle, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (entry.durationSeconds > 0) talkTime(context, entry.durationSeconds) else stringResource(R.string.dialer_not_connected),
            color = if (entry.durationSeconds > 0) palette.text else palette.subtle,
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dialer_delete_call), tint = palette.subtle, modifier = Modifier.size(20.dp))
        }
    }
}

private const val HISTORY_PREVIEW = 8
