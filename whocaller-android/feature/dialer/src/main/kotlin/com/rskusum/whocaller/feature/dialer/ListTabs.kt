@file:OptIn(ExperimentalFoundationApi::class)

package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.whocaller.core.model.CallLogEntry
import com.rskusum.whocaller.core.model.CallType
import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.ui.util.ActionIntents
import com.rskusum.whocaller.core.ui.util.TelecomActions

@Composable
internal fun TabHeader(title: String) {
    val palette = LocalDialerPalette.current
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = palette.text,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun PermissionPrompt(text: String, permissions: Array<String>, onResult: () -> Unit) {
    val palette = LocalDialerPalette.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, color = palette.subtle, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { launcher.launch(permissions) }) { Text(stringResource(R.string.dialer_allow)) }
    }
}

@Composable
private fun EmptyText(text: String) {
    val palette = LocalDialerPalette.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = palette.subtle, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SmallRoundButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

// ---------- Recents ----------

@Composable
fun RecentsTab(viewModel: DialerViewModel, actions: CallActions, onShowDetails: (DetailsTarget) -> Unit) {
    val palette = LocalDialerPalette.current
    val filter by viewModel.recentsFilter.collectAsState()
    val calls by viewModel.recents.collectAsState()
    val permitted by viewModel.callLogPermission.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val withDeletePermission = rememberCallLogDeleter(viewModel)
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmSelected by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            // Selection bar: "3 selected · Select all · Delete".
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.dialer_cancel), tint = palette.text) }
                Text(
                    pluralStringResource(R.plurals.dialer_selected, selected.size, selected.size),
                    color = palette.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.dialer_select_all), tint = palette.accent) }
                IconButton(onClick = { confirmSelected = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.dialer_delete), tint = WarnRed) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TabHeader(stringResource(R.string.dialer_tab_recents)) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.dialer_more_options), tint = palette.accent)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(Icons.Filled.Checklist, R.string.dialer_select_calls) {
                            menu = false
                            calls.firstOrNull()?.let { viewModel.toggleSelected(it.id) }
                        }
                        MenuItem(Icons.Filled.DeleteSweep, R.string.dialer_clear_history) {
                            menu = false
                            confirmClear = true
                        }
                    }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RecentsFilter.entries) { f ->
                val isOn = f == filter
                Text(
                    stringResource(f.label()),
                    color = if (isOn) Color.White else palette.text,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isOn) palette.accent else palette.actionBg)
                        .combinedClickable(role = Role.Tab) { viewModel.setRecentsFilter(f) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            !permitted -> PermissionPrompt(
                stringResource(R.string.dialer_recents_permission),
                arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS),
                viewModel::refreshPermissions,
            )
            calls.isEmpty() -> EmptyText(stringResource(R.string.dialer_recents_empty))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(calls, key = { it.id }) { entry ->
                    RecentRow(
                        entry = entry,
                        selecting = selecting,
                        isSelected = entry.id in selected,
                        onOpen = {
                            if (selecting) viewModel.toggleSelected(entry.id) else onShowDetails(DetailsTarget(number = entry.rawNumber))
                        },
                        onSelect = { viewModel.toggleSelected(entry.id) },
                        onCall = { actions.call(entry.rawNumber) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.dialer_clear_history),
            text = stringResource(R.string.dialer_clear_history_confirm),
            confirm = stringResource(R.string.dialer_delete),
            onDismiss = { confirmClear = false },
        ) {
            confirmClear = false
            withDeletePermission { viewModel.clearCallLog() }
        }
    }
    if (confirmSelected) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.dialer_delete_calls_title, selected.size, selected.size),
            text = stringResource(R.string.dialer_delete_calls_confirm),
            confirm = stringResource(R.string.dialer_delete),
            onDismiss = { confirmSelected = false },
        ) {
            confirmSelected = false
            withDeletePermission { viewModel.deleteSelected() }
        }
    }
}

private fun RecentsFilter.label() = when (this) {
    RecentsFilter.ALL -> R.string.dialer_filter_all
    RecentsFilter.MISSED -> R.string.dialer_filter_missed
    RecentsFilter.OUTGOING -> R.string.dialer_filter_outgoing
    RecentsFilter.INCOMING -> R.string.dialer_filter_incoming
}

/** Icon, colour and label for a call type, shared by Recents and the per-number history. */
@Composable
internal fun callTypeLook(type: CallType): Triple<ImageVector, Color, Int> {
    val palette = LocalDialerPalette.current
    return when (type) {
        CallType.INCOMING -> Triple(Icons.AutoMirrored.Filled.CallReceived, OkGreen, R.string.dialer_type_incoming)
        CallType.OUTGOING -> Triple(Icons.AutoMirrored.Filled.CallMade, palette.accent, R.string.dialer_type_outgoing)
        CallType.MISSED -> Triple(Icons.AutoMirrored.Filled.CallMissed, MissedRed, R.string.dialer_type_missed)
        CallType.REJECTED -> Triple(Icons.AutoMirrored.Filled.CallMissed, MissedRed, R.string.dialer_type_rejected)
        CallType.BLOCKED -> Triple(Icons.Filled.Block, MissedRed, R.string.dialer_type_blocked)
        CallType.VOICEMAIL -> Triple(Icons.Filled.Voicemail, palette.subtle, R.string.dialer_type_voicemail)
        CallType.UNKNOWN -> Triple(Icons.Filled.Call, palette.subtle, R.string.dialer_type_call)
    }
}

@Composable
private fun RecentRow(
    entry: CallLogEntry,
    selecting: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onCall: () -> Unit,
) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val name = entry.contactName ?: entry.cachedName
    val (icon, color, typeLabel) = callTypeLook(entry.type)
    val details = buildList {
        add(stringResource(typeLabel))
        add(relativeTime(context, entry.timestamp))
        if (entry.durationSeconds > 0) add(talkTime(context, entry.durationSeconds))
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isSelected) palette.accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onLongClickLabel = stringResource(R.string.dialer_select), onLongClick = onSelect, onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ContactAvatar(name ?: entry.displayNumber, entry.photoUri, 54.dp, warning = entry.isSpam)
            if (isSelected) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (entry.isHidden) stringResource(R.string.call_private) else name ?: entry.displayNumber,
                color = if (entry.type == CallType.MISSED) MissedRed else palette.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(details, color = palette.subtle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.isSpam) {
                Text(stringResource(R.string.call_label_spam), color = WarnRed, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!entry.isHidden && !selecting) SmallRoundButton(Icons.Filled.Call, stringResource(R.string.dialer_call), CallGreen, onCall)
    }
}

/** Runs [action] once WhoCaller may delete call log entries, asking for the permission if needed. */
@Composable
internal fun rememberCallLogDeleter(viewModel: DialerViewModel): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pending
        pending = null
        if (granted) next?.invoke() else Toast.makeText(context, R.string.dialer_delete_permission, Toast.LENGTH_LONG).show()
    }
    return { action ->
        if (viewModel.canDeleteCalls()) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.WRITE_CALL_LOG)
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = WarnRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialer_cancel)) } },
    )
}

// ---------- Contacts ----------

@Composable
fun ContactsTab(viewModel: DialerViewModel, actions: CallActions, onShowDetails: (DetailsTarget) -> Unit) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val query by viewModel.contactQuery.collectAsState()
    val contacts by viewModel.contactList.collectAsState()
    val recentMatches by viewModel.recentMatches.collectAsState()
    val permitted by viewModel.contactsPermission.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TabHeader(stringResource(R.string.dialer_tab_contacts)) }
            IconButton(onClick = { ActionIntents.saveContact(context, "") }, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Filled.PersonAdd, stringResource(R.string.dialer_add_contact), tint = palette.accent)
            }
        }
        SearchField(query, viewModel::setContactQuery)
        Spacer(Modifier.height(8.dp))
        when {
            !permitted -> PermissionPrompt(
                stringResource(R.string.dialer_contacts_permission),
                arrayOf(Manifest.permission.READ_CONTACTS),
                viewModel::refreshPermissions,
            )
            contacts.isEmpty() && recentMatches.isEmpty() ->
                EmptyText(stringResource(if (query.isBlank()) R.string.dialer_contacts_empty else R.string.dialer_no_matches))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                var lastLetter: Char? = null
                contacts.forEach { contact ->
                    val letter = contact.initial
                    if (query.isBlank() && letter != lastLetter) {
                        lastLetter = letter
                        item(key = "h$letter") {
                            Text(
                                letter.toString(),
                                color = palette.accent,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                    }
                    item(key = contact.id) {
                        ContactRow(
                            contact = contact,
                            onOpen = { onShowDetails(DetailsTarget(contactId = contact.id, number = contact.phones.firstOrNull()?.number)) },
                            onCall = { contact.phones.firstOrNull()?.number?.let { actions.call(it) } },
                        )
                    }
                }
                // Numbers you've called or that called you, not saved as contacts.
                if (recentMatches.isNotEmpty()) {
                    item(key = "recent-header") {
                        Text(
                            stringResource(R.string.dialer_from_recents),
                            color = palette.accent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 2.dp),
                        )
                    }
                    items(recentMatches, key = { "r" + it.numberKey }) { entry ->
                        RecentRow(
                            entry = entry,
                            selecting = false,
                            isSelected = false,
                            onOpen = { onShowDetails(DetailsTarget(number = entry.rawNumber)) },
                            onSelect = {},
                            onCall = { actions.call(entry.rawNumber) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val palette = LocalDialerPalette.current
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = palette.subtle, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.dialer_search_contacts), color = palette.subtle, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onOpen: () -> Unit, onCall: () -> Unit) {
    val palette = LocalDialerPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact.displayName, contact.photoUri, 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(contact.displayName, color = palette.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            contact.phones.firstOrNull()?.let { p ->
                Text(listOfNotNull(p.label, p.number).joinToString(" · "), color = palette.subtle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        if (contact.starred) Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
        if (contact.phones.isNotEmpty()) SmallRoundButton(Icons.Filled.Call, stringResource(R.string.dialer_call), CallGreen, onCall)
    }
}

// ---------- Favorites ----------

@Composable
fun FavoritesTab(viewModel: DialerViewModel, actions: CallActions, onShowDetails: (DetailsTarget) -> Unit) {
    val palette = LocalDialerPalette.current
    val favorites by viewModel.favorites.collectAsState()
    val permitted by viewModel.contactsPermission.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.dialer_tab_favorites))
        when {
            !permitted -> PermissionPrompt(
                stringResource(R.string.dialer_contacts_permission),
                arrayOf(Manifest.permission.READ_CONTACTS),
                viewModel::refreshPermissions,
            )
            favorites.isEmpty() -> EmptyText(stringResource(R.string.dialer_favorites_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(favorites, key = { it.id }) { contact ->
                    val number = contact.phones.firstOrNull()?.number
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.surface)
                            .combinedClickable(
                                onClickLabel = stringResource(R.string.dialer_call),
                                onLongClick = { onShowDetails(DetailsTarget(contactId = contact.id, number = number)) },
                                onClick = { number?.let { actions.call(it) } },
                            )
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ContactAvatar(contact.displayName, contact.photoUri, 72.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(contact.displayName, color = palette.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            SmallRoundButton(Icons.Filled.Call, stringResource(R.string.dialer_call), CallGreen) { number?.let { actions.call(it) } }
                            SmallRoundButton(Icons.Filled.Videocam, stringResource(com.rskusum.whocaller.core.ui.R.string.action_whatsapp_video), WhatsAppGreen) { number?.let { actions.whatsApp(it, video = true) } }
                        }
                    }
                }
            }
        }
    }
}

/** Star with a pop animation and haptic tick, so the tap is felt and seen at once. */
@Composable
internal fun StarButton(starred: Boolean, onToggle: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(starred) {
        if (first) {
            first = false
        } else {
            scale.animateTo(1.4f, androidx.compose.animation.core.tween(110))
            scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f))
        }
    }
    IconButton(onClick = {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        onToggle()
    }) {
        Icon(
            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = stringResource(if (starred) R.string.dialer_unfavorite else R.string.dialer_favorite),
            tint = Color(0xFFF59E0B),
            modifier = Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        )
    }
}

/**
 * Favourite toggle: shows the new state immediately, asks for "edit contacts" permission the first
 * time, saves in the background and reverts (with a message) if saving fails.
 */
@Composable
internal fun rememberStarToggle(viewModel: DialerViewModel): (contactId: Long, current: Boolean, show: (Boolean) -> Unit) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val next = pending
        pending = null
        if (granted) next?.invoke() else android.widget.Toast.makeText(context, R.string.dialer_star_permission, android.widget.Toast.LENGTH_LONG).show()
    }
    return remember(viewModel) {
        fun toggle(contactId: Long, current: Boolean, show: (Boolean) -> Unit) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                pending = { toggle(contactId, current, show) }
                launcher.launch(Manifest.permission.WRITE_CONTACTS)
                return
            }
            val target = !current
            show(target)
            scope.launch {
                if (viewModel.setStarred(contactId, target)) {
                    android.widget.Toast.makeText(context, if (target) R.string.dialer_star_added else R.string.dialer_star_removed, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    show(current)
                    android.widget.Toast.makeText(context, R.string.dialer_star_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        ::toggle
    }
}
