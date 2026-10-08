package com.rskusum.whocaller.feature.dialer

import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.core.ui.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Plays one recording at a time. */
private class RecordingPlayer {
    var playing by mutableStateOf<File?>(null)
    var progress by mutableFloatStateOf(0f)
    private var player: MediaPlayer? = null

    fun toggle(file: File) {
        if (playing == file) {
            stop()
            return
        }
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { this@RecordingPlayer.stop() }
                prepare()
                start()
            }
        }.getOrNull()
        if (player != null) playing = file
    }

    fun tick() {
        val p = player ?: return
        progress = runCatching { if (p.duration > 0) p.currentPosition / p.duration.toFloat() else 0f }.getOrDefault(0f)
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
        progress = 0f
    }
}

/** All recordings ([number] null) or one person's, with play / share / delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsSheet(number: String?, onDismiss: () -> Unit) {
    val palette = LocalDialerPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.rec_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = palette.text)
            Text(stringResource(R.string.rec_local_note), style = MaterialTheme.typography.bodySmall, color = palette.subtle)
            Spacer(Modifier.height(12.dp))
            RecordingsList(number, showNames = number == null)
        }
    }
}

/** Recordings for [number] (or all). Shows nothing when [hideWhenEmpty] and there are none. */
@Composable
fun RecordingsList(number: String?, showNames: Boolean, hideWhenEmpty: Boolean = false, header: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val palette = LocalDialerPalette.current
    var version by remember { mutableIntStateOf(0) }
    var items by remember(number) { mutableStateOf<List<Pair<CallRecordingFile, String?>>?>(null) }
    LaunchedEffect(number, version) {
        items = withContext(Dispatchers.IO) {
            val list = if (number == null) CallRecorder.list(context) else CallRecorder.forNumber(context, number)
            list.map { r -> r to if (showNames) r.number?.let { ContactLookup.byNumber(context, it)?.name } else null }
        }
    }
    val player = remember { RecordingPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(player.playing) {
        while (player.playing != null) {
            player.tick()
            delay(250)
        }
    }
    var confirmDelete by remember { mutableStateOf<CallRecordingFile?>(null) }

    val list = items ?: return
    if (list.isEmpty()) {
        if (!hideWhenEmpty) Text(stringResource(R.string.rec_empty), color = palette.subtle, style = MaterialTheme.typography.bodyMedium)
        return
    }
    header?.invoke()
    val rows: @Composable (Pair<CallRecordingFile, String?>) -> Unit = { (rec, name) ->
        val isPlaying = player.playing == rec.file
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(palette.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { player.toggle(rec.file) }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (isPlaying) R.string.rec_pause else R.string.rec_play),
                            tint = palette.accent,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (showNames) {
                        Text(
                            name ?: rec.number?.let { "+$it" } ?: stringResource(R.string.rec_private),
                            color = palette.text,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        DateUtils.formatDateTime(
                            context,
                            rec.startedAt,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                        ) + " · " + formatDuration(rec.durationMs / 1000),
                        color = if (showNames) palette.subtle else palette.text,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { CallRecorder.share(context, rec) }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.rec_share), tint = palette.subtle)
                }
                IconButton(onClick = { confirmDelete = rec }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rec_delete), tint = WarnRed)
                }
            }
            if (isPlaying) {
                LinearProgressIndicator(
                    progress = { player.progress },
                    modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp).clip(RoundedCornerShape(2.dp)),
                    color = palette.accent,
                )
            }
        }
    }
    if (number == null) {
        LazyColumn(Modifier.fillMaxWidth().height(480.dp)) { items(list, key = { it.first.file.name }) { rows(it) } }
    } else {
        Column(Modifier.fillMaxWidth()) { list.forEach { rows(it) } }
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.rec_delete)) },
            text = { Text(stringResource(R.string.rec_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    if (player.playing == rec.file) player.stop()
                    rec.file.delete()
                    confirmDelete = null
                    version++
                }) { Text(stringResource(R.string.dialer_delete), color = WarnRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
