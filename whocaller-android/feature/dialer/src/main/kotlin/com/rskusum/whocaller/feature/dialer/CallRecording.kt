package com.rskusum.whocaller.feature.dialer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.rskusum.whocaller.core.ui.notification.NotificationChannels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rskusum.whocaller.core.ui.R as UiR

/** One saved recording. */
data class CallRecordingFile(
    val file: File,
    /** Digits of the other party's number, or null for a private number. */
    val number: String?,
    val startedAt: Long,
    val durationMs: Long,
)

/**
 * Local call recorder. Recordings are saved in WhoCaller's private storage on this phone only:
 * never uploaded, never sent to WhoCaller's servers, and left out of Google backups.
 *
 * Android doesn't let apps record the phone line directly, so this records the microphone. Your
 * voice is always clear; the other person is clear on speaker and quieter on the earpiece.
 */
object CallRecorder {

    data class Active(val file: File, val startedAt: Long, val number: String?)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var service: WeakReference<Service>? = null

    private const val DIR = "call_recordings"
    private val NAME = Regex("""^(\d{8}_\d{6})_(\w+)\.m4a$""")

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Starts recording (from the call screen, which is in the foreground). */
    fun start(context: Context, number: String?) {
        if (_active.value != null) return
        val intent = Intent(context, CallRecordingService::class.java)
            .setAction(CallRecordingService.ACTION_START)
            .putExtra(CallRecordingService.EXTRA_NUMBER, number)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Stops and saves. Safe to call when nothing is recording. */
    fun stop() {
        val r = recorder
        recorder = null
        if (r != null) {
            runCatching { r.stop() }.onFailure {
                // Stopped too soon to hold any audio: drop the empty file.
                _active.value?.file?.delete()
            }
            runCatching { r.release() }
        }
        _active.value = null
        service?.get()?.let { s ->
            ServiceCompat.stopForeground(s, ServiceCompat.STOP_FOREGROUND_REMOVE)
            s.stopSelf()
        }
        service = null
    }

    /** Called by the service once it is in the foreground. Returns false if no microphone source worked. */
    internal fun begin(s: Service, number: String?): Boolean {
        val digits = number?.filter { it.isDigit() || it == '+' }?.replace("+", "")?.takeIf { it.isNotEmpty() } ?: "private"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir(s), "${stamp}_$digits.m4a")
        // Unprocessed mic first (keeps the other person's voice from the speaker), then plain mic.
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
            val r = open(s, file, source) ?: continue
            recorder = r
            service = WeakReference(s)
            _active.value = Active(file, System.currentTimeMillis(), number)
            return true
        }
        file.delete()
        return false
    }

    private fun open(context: Context, file: File, source: Int): MediaRecorder? {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            r
        } catch (_: Exception) {
            runCatching { r.release() }
            null
        }
    }

    /** All recordings, newest first. Call off the main thread. */
    fun list(context: Context): List<CallRecordingFile> =
        dir(context).listFiles().orEmpty()
            .filter { it.isFile && it.length() > 0 && it != _active.value?.file }
            .mapNotNull { f ->
                val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
                val started = runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.groupValues[1])?.time }.getOrNull() ?: f.lastModified()
                CallRecordingFile(f, m.groupValues[2].takeIf { it != "private" }, started, durationOf(f))
            }
            .sortedByDescending { it.startedAt }

    /** Recordings with this number (matched on the last digits, so +91 and 0-prefixed forms agree). */
    fun forNumber(context: Context, number: String): List<CallRecordingFile> {
        val tail = number.filter(Char::isDigit).takeLast(8)
        if (tail.length < 3) return emptyList()
        return list(context).filter { it.number?.endsWith(tail) == true }
    }

    private fun durationOf(file: File): Long = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    } catch (_: Exception) {
        0L
    }

    /** Shares a recording through the system share sheet (the user chooses where it goes). */
    fun share(context: Context, recording: CallRecordingFile) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".recordings", recording.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, context.getString(R.string.rec_share))
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}

/** Keeps the microphone available while recording (Android requires a foreground service for that). */
class CallRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CallRecorder.stop()
                stopSelf()
            }
            ACTION_START -> {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }.isSuccess
                if (!started || !CallRecorder.begin(this, intent.getStringExtra(EXTRA_NUMBER))) {
                    android.widget.Toast.makeText(this, R.string.rec_failed, android.widget.Toast.LENGTH_LONG).show()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (CallRecorder.active.value != null) CallRecorder.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 30, InCallActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 31, Intent(this, CallRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.ONGOING_CALLS)
            .setSmallIcon(UiR.drawable.ic_stat_whocaller)
            .setContentTitle(getString(R.string.rec_notification))
            .setContentText(getString(R.string.rec_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.rec_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.rskusum.whocaller.action.RECORD_START"
        const val ACTION_STOP = "com.rskusum.whocaller.action.RECORD_STOP"
        const val EXTRA_NUMBER = "number"
        private const val NOTIFICATION_ID = 4_101
    }
}

/** Serves recordings to the share sheet without exposing other app files. */
class RecordingFileProvider : FileProvider()
