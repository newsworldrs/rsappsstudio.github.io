package com.rskusum.whocaller.feature.dialer

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.core.ui.util.TelecomActions

/** Keypad for placing calls. Handles ACTION_DIAL / tel: links (required for the default phone app role). */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initial = numberFrom(intent)
        setContent {
            WhoCallerTheme {
                Surface(Modifier.fillMaxSize()) { DialerScreen(initial) }
            }
        }
    }

    private fun numberFrom(intent: Intent?): String =
        intent?.data?.schemeSpecificPart?.filter { it.isDigit() || it in "+*#," }?.take(32).orEmpty()
}

@Composable
private fun DialerScreen(initial: String) {
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf(initial) }
    var pendingVideo by remember { mutableStateOf<Boolean?>(null) }
    // Video calling is offered only when a phone account reports CAPABILITY_VIDEO_CALLING.
    var videoSupported by remember { mutableStateOf(TelecomActions.supportsVideoCalling(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        videoSupported = TelecomActions.supportsVideoCalling(context)
        val video = pendingVideo
        pendingVideo = null
        if (result[Manifest.permission.CALL_PHONE] == true && video != null) {
            TelecomActions.placeCall(context, number, video && videoSupported)
        } else if (result[Manifest.permission.CALL_PHONE] != true) {
            Toast.makeText(context, R.string.dialer_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    fun call(video: Boolean) {
        if (number.isBlank()) return
        if (TelecomActions.canPlaceCalls(context)) {
            TelecomActions.placeCall(context, number, video)
        } else {
            pendingVideo = video
            permissions.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.dialer_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.ifEmpty { stringResource(R.string.dialer_hint) },
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp),
                color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (number.isNotEmpty()) {
                IconButton(onClick = { number = number.dropLast(1) }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.dialer_backspace))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { c ->
                    TextButton(
                        onClick = { if (number.length < 32) number += c },
                        modifier = Modifier.size(76.dp).clip(CircleShape),
                    ) { Text(c.toString(), style = MaterialTheme.typography.headlineMedium) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { if (number.length < 32) number += "+" }) { Text("+") }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (videoSupported) {
                FilledIconButton(
                    onClick = { call(video = true) },
                    enabled = number.isNotBlank(),
                    modifier = Modifier.size(64.dp),
                ) { Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.dialer_video_call)) }
            }
            FilledIconButton(
                onClick = { call(video = false) },
                enabled = number.isNotBlank(),
                modifier = Modifier.size(76.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF1B6D3B), contentColor = Color.White),
            ) { Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialer_call), modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
