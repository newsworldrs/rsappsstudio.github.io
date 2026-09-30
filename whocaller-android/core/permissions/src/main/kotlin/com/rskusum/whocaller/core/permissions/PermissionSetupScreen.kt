package com.rskusum.whocaller.core.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rskusum.whocaller.core.ui.component.PrimaryWideButton
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme

/**
 * Staged permission setup: one card per feature, each with Explain / Allow / Not now. Nothing is
 * requested until the user taps Allow on that card.
 */
@Composable
fun PermissionSetupScreen(
    permissionManager: PermissionManager,
    onFinished: () -> Unit,
    onResult: (AppPermission, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    permissions: List<AppPermission> = AppPermission.entries,
) {
    val activity = LocalContext.current.findActivity()
    // Bumped on resume so statuses refresh after returning from system settings.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val skipped = remember { mutableStateMapOf<AppPermission, Boolean>() }

    LazyColumn(
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.perm_setup_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.perm_setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(permissions, key = { it.name }) { permission ->
            // Read refresh so the card recomposes on resume.
            val status = refresh.let { permissionManager.status(activity, permission) }
            PermissionCard(
                permission = permission,
                status = status,
                skipped = skipped[permission] == true,
                permissionManager = permissionManager,
                onResult = { granted ->
                    refresh++
                    onResult(permission, granted)
                },
                onSkip = { skipped[permission] = true },
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            PrimaryWideButton(text = stringResource(R.string.perm_setup_done), onClick = onFinished)
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionCard(
    permission: AppPermission,
    status: PermissionStatus,
    skipped: Boolean,
    permissionManager: PermissionManager,
    onResult: (Boolean) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showExplanation by remember { mutableStateOf(false) }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result.values.all { it })
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onResult(permissionManager.hasCallScreeningRole())
    }

    fun request() {
        permissionManager.markRequested(permission)
        when {
            status == PermissionStatus.PERMANENTLY_DENIED -> context.startActivity(permissionManager.appSettingsIntent())
            permission.usesCallScreeningRole -> {
                val intent = permissionManager.callScreeningRoleIntent()
                if (intent != null) roleLauncher.launch(intent) else onResult(false)
            }
            permission.runtimePermissions.isEmpty() -> onResult(true)
            else -> runtimeLauncher.launch(permission.runtimePermissions)
        }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(permission.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(permission.titleRes), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(permission.summaryRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status == PermissionStatus.GRANTED) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.perm_granted),
                        tint = WhoCallerTheme.riskColors.safe,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (status != PermissionStatus.GRANTED) {
                if (skipped || status == PermissionStatus.DENIED || status == PermissionStatus.PERMANENTLY_DENIED) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(permission.deniedFallbackRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showExplanation = true }) { Text(stringResource(R.string.perm_explain)) }
                    if (!skipped) {
                        OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.perm_not_now)) }
                    }
                    Button(onClick = ::request) {
                        Text(
                            stringResource(
                                if (status == PermissionStatus.PERMANENTLY_DENIED) R.string.perm_open_settings else R.string.perm_allow,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            title = { Text(stringResource(permission.titleRes)) },
            text = { Text(stringResource(permission.explanationRes)) },
            confirmButton = { TextButton(onClick = { showExplanation = false }) { Text(stringResource(R.string.perm_got_it)) } },
        )
    }
}

fun AppPermission.icon(): ImageVector = when (this) {
    AppPermission.CALLER_ID -> Icons.Outlined.Shield
    AppPermission.CALL_HISTORY -> Icons.Outlined.History
    AppPermission.CONTACTS -> Icons.Outlined.Contacts
    AppPermission.NOTIFICATIONS -> Icons.Outlined.Notifications
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
