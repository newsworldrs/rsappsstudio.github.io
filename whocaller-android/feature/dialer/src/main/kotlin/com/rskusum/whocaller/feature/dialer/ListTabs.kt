@file:OptIn(ExperimentalFoundationApi::class)

package com.rskusum.whocaller.feature.dialer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
    val context = LocalContext.current
    val filter by viewModel.recentsFilter.collectAsState()
    val calls by viewModel.recents.collectAsState()
    val permitted by viewModel.callLogPermission.collectAsState()
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    val whatsApp = TelecomActions.whatsAppPackage(context) != null

    Column(Modifier.fillMaxSize()) {
        TabHeader(stringResource(R.string.dialer_tab_recents))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RecentsFilter.entries) { f ->
                val selected = f == filter
                Text(
                    stringResource(f.label()),
                    color = if (selected) Color.White else palette.text,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) palette.accent else palette.actionBg)
                        .combinedClickable(role = Role.Tab) { viewModel.setRecentsFilter(f) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            !permitted -> PermissionPrompt(
                stringResource(R.string.dialer_recents_permission),
                arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS),
                viewModel::refreshPermissions,
            )
            calls.isEmpty() -> EmptyText(stringResource(R.string.dialer_recents_empty))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(calls, key = { it.id }) { entry ->
                    RecentRow(
                        entry = entry,
                        expanded = expanded == entry.id,
                        whatsApp = whatsApp,
                        onToggle = { expanded = if (expanded == entry.id) null else entry.id },
                        onCall = { actions.call(entry.rawNumber) },
                        onVideo = { actions.video(entry.rawNumber) },
                        onSms = { ActionIntents.message(context, entry.rawNumber) },
                        onWhatsApp = { actions.openWhatsAppChat(entry.rawNumber) },
                        onDetails = { onShowDetails(DetailsTarget(number = entry.rawNumber)) },
                    )
                }
            }
        }
    }
}

private fun RecentsFilter.label() = when (this) {
    RecentsFilter.ALL -> R.string.dialer_filter_all
    RecentsFilter.MISSED -> R.string.dialer_filter_missed
    RecentsFilter.OUTGOING -> R.string.dialer_filter_outgoing
    RecentsFilter.INCOMING -> R.string.dialer_filter_incoming
}

@Composable
private fun RecentRow(
    entry: CallLogEntry,
    expanded: Boolean,
    whatsApp: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onVideo: () -> Unit,
    onSms: () -> Unit,
    onWhatsApp: () -> Unit,
    onDetails: () -> Unit,
) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val name = entry.contactName ?: entry.cachedName
    val (icon, color, typeLabel) = when (entry.type) {
        CallType.INCOMING -> Triple(Icons.AutoMirrored.Filled.CallReceived, OkGreen, R.string.dialer_type_incoming)
        CallType.OUTGOING -> Triple(Icons.AutoMirrored.Filled.CallMade, palette.accent, R.string.dialer_type_outgoing)
        CallType.MISSED -> Triple(Icons.AutoMirrored.Filled.CallMissed, MissedRed, R.string.dialer_type_missed)
        CallType.REJECTED -> Triple(Icons.AutoMirrored.Filled.CallMissed, MissedRed, R.string.dialer_type_rejected)
        CallType.BLOCKED -> Triple(Icons.Filled.Block, MissedRed, R.string.dialer_type_blocked)
        CallType.VOICEMAIL -> Triple(Icons.Filled.Voicemail, palette.subtle, R.string.dialer_type_voicemail)
        CallType.UNKNOWN -> Triple(Icons.Filled.Call, palette.subtle, R.string.dialer_type_call)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (expanded) palette.surface else Color.Transparent)
            .combinedClickable(onClick = onToggle, onLongClick = onDetails)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(name ?: entry.displayNumber, null, 46.dp, warning = entry.isSpam)
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
                    Text(
                        "${stringResource(typeLabel)} · ${relativeTime(context, entry.timestamp)}",
                        color = palette.subtle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                if (entry.isSpam) {
                    Text(stringResource(R.string.call_label_spam), color = WarnRed, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!entry.isHidden) SmallRoundButton(Icons.Filled.Call, stringResource(R.string.dialer_call), CallGreen, onCall)
        }
        AnimatedVisibility(expanded && !entry.isHidden) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(stringResource(R.string.dialer_video_call), Violet, size = 40.dp, onClick = onVideo) { QuickActionIcon(Icons.Filled.Videocam, Violet) }
                QuickAction(stringResource(R.string.dialer_sms), Indigo, size = 40.dp, onClick = onSms) { QuickActionIcon(Icons.AutoMirrored.Filled.Message, Indigo) }
                if (whatsApp) QuickAction(stringResource(R.string.dialer_whatsapp), WhatsAppGreen, size = 40.dp, onClick = onWhatsApp) { WhatsAppLogo(24.dp) }
                QuickAction(stringResource(R.string.dialer_details), Sky, size = 40.dp, onClick = onDetails) { QuickActionIcon(Icons.Filled.Info, Sky) }
            }
        }
    }
}

// ---------- Contacts ----------

@Composable
fun ContactsTab(viewModel: DialerViewModel, actions: CallActions, onShowDetails: (DetailsTarget) -> Unit) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val query by viewModel.contactQuery.collectAsState()
    val contacts by viewModel.contactList.collectAsState()
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
            contacts.isEmpty() -> EmptyText(stringResource(if (query.isBlank()) R.string.dialer_contacts_empty else R.string.dialer_no_matches))
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
        ContactAvatar(contact.displayName, contact.photoUri, 44.dp)
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
                        ContactAvatar(contact.displayName, contact.photoUri, 64.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(contact.displayName, color = palette.text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            SmallRoundButton(Icons.Filled.Call, stringResource(R.string.dialer_call), CallGreen) { number?.let { actions.call(it) } }
                            SmallRoundButton(Icons.Filled.Videocam, stringResource(R.string.dialer_video_call), Violet) { number?.let { actions.video(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StarButton(starred: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = stringResource(if (starred) R.string.dialer_unfavorite else R.string.dialer_favorite),
            tint = Color(0xFFF59E0B),
        )
    }
}
