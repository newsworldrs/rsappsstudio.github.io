package com.rskusum.whocaller.feature.dialer

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rskusum.whocaller.core.model.ThemeMode

@Composable
fun MoreTab(viewModel: DialerViewModel, actions: CallActions) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    val theme by viewModel.themeMode.collectAsState()
    var isDefault by remember { mutableStateOf(isDefaultDialer(context)) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = isDefaultDialer(context)
        if (!isDefault) {
            Toast.makeText(context, R.string.dialer_pick_in_settings, Toast.LENGTH_LONG).show()
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        }
    }
    val sims = remember { Sims.list(context) }
    var showRecordings by remember { mutableStateOf(false) }
    var showVideoDiag by remember { mutableStateOf(false) }
    if (showVideoDiag) VideoDiagnosticsSheet { showVideoDiag = false }
    if (showRecordings) RecordingsSheet(number = null) { showRecordings = false }
    // Picked in system settings? Re-check when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isDefault = isDefaultDialer(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TabHeader(stringResource(R.string.dialer_tab_more))

        Section(stringResource(R.string.dialer_section_phone)) {
            MoreRow(
                Icons.Filled.Phone,
                stringResource(R.string.dialer_default_phone_app),
                stringResource(if (isDefault) R.string.dialer_default_yes else R.string.dialer_default_no),
                if (isDefault) OkGreen else palette.accent,
            ) {
                if (!isDefault) {
                    val intent = defaultDialerIntent(context)
                    if (intent != null) roleLauncher.launch(intent) else runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                }
            }
            if (sims.isNotEmpty()) {
                MoreRow(Icons.Filled.SimCard, stringResource(R.string.dialer_sims), sims.joinToString(" · ") { it.label }, Sky) {
                    runCatching { context.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
                }
            }
            MoreRow(Icons.Filled.Voicemail, stringResource(R.string.dialer_call_voicemail), null, Violet) { actions.voicemail() }
            MoreRow(Icons.Filled.FiberManualRecord, stringResource(R.string.rec_title), stringResource(R.string.rec_local_note), WarnRed) { showRecordings = true }
        }

        Section(stringResource(R.string.dialer_section_protection)) {
            MoreRow(Icons.Filled.Shield, stringResource(R.string.dialer_caller_id_spam), null, OkGreen) { openWhoCaller(context, "protection") }
            MoreRow(Icons.Filled.Block, stringResource(R.string.dialer_block_list), null, WarnRed) { openWhoCaller(context, "blocked") }
            MoreRow(Icons.AutoMirrored.Filled.Message, stringResource(R.string.dialer_messages), null, Indigo) { openWhoCaller(context, "messages") }
            val postCall by viewModel.postCallPrompt.collectAsState()
            MoreRow(
                Icons.Filled.Flag,
                stringResource(R.string.dialer_post_call),
                stringResource(if (postCall) R.string.dialer_post_call_on else R.string.dialer_post_call_off),
                Color(0xFFEC4899),
            ) { viewModel.setPostCallPrompt(!postCall) }
        }

        Section(stringResource(R.string.dialer_section_app)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowIcon(Icons.Filled.DarkMode, Color(0xFF6366F1))
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.dialer_theme), color = palette.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val selected = mode == theme
                    Text(
                        stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> R.string.dialer_theme_system
                                ThemeMode.LIGHT -> R.string.dialer_theme_light
                                ThemeMode.DARK -> R.string.dialer_theme_dark
                            },
                        ),
                        color = if (selected) Color.White else palette.text,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) palette.accent else palette.actionBg)
                            .clickable(role = Role.RadioButton) { viewModel.setThemeMode(mode) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            MoreRow(Icons.Filled.AccountCircle, stringResource(R.string.dialer_profile), null, Sky) { openWhoCaller(context, "profile") }
            MoreRow(Icons.Filled.Lock, stringResource(R.string.dialer_privacy), null, OkGreen) { openWhoCaller(context, "privacy") }
            MoreRow(Icons.Filled.Settings, stringResource(R.string.dialer_settings), null, palette.subtle) { openWhoCaller(context, "settings") }
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val palette = LocalDialerPalette.current
    Text(title, color = palette.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 6.dp))
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.surface)) { content() }
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, subtitle: String?, tint: Color, onClick: () -> Unit) {
    val palette = LocalDialerPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = palette.subtle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

internal fun isDefaultDialer(context: Context): Boolean =
    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true) ||
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == context.packageName

private fun defaultDialerIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    context.getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) }
        ?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
} else {
    Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VideoDiagnosticsSheet(onDismiss: () -> Unit) {
    val palette = LocalDialerPalette.current
    val context = LocalContext.current
    var events by remember { mutableStateOf(VideoDiagnostics.list(context)) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.video_diag_title), style = MaterialTheme.typography.titleLarge, color = palette.text)
            Text(stringResource(R.string.video_diag_desc), style = MaterialTheme.typography.bodySmall, color = palette.subtle)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
            if (events.isEmpty()) {
                Text(stringResource(R.string.video_diag_empty), color = palette.subtle, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    events.forEach { line ->
                        Text(line, color = palette.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = {
                        VideoDiagnostics.clear(context)
                        events = emptyList()
                    }) { Text(stringResource(R.string.video_diag_clear)) }
                    androidx.compose.material3.TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, events.joinToString("\n"))
                        runCatching { context.startActivity(Intent.createChooser(send, null)) }
                    }) { Text(stringResource(R.string.video_diag_share)) }
                }
            }
        }
    }
}
