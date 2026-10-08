package com.rskusum.whocaller.core.ui.component

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.rskusum.whocaller.core.ui.R
import com.rskusum.whocaller.core.ui.util.TelecomActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * WHOCALLER VIDEO (experimental): knows which numbers are on WhoCaller, so the video button can
 * look disabled for the others, and invites those people (WhatsApp card + SMS link).
 *
 * The app module plugs in the actual lookup and SMS sending (they need Firebase / the SMS store).
 */
object WhoCallerVideo {

    /** Is [number] a verified WhoCaller user? null = can't tell (offline, signed out, own number). */
    @Volatile var lookup: (suspend (Context, String) -> Boolean?)? = null

    /** Sends a plain SMS; true when it was handed to the network. */
    @Volatile var smsSender: (suspend (address: String, body: String) -> Boolean)? = null

    private val cache = ConcurrentHashMap<String, Boolean>()

    suspend fun isOnWhoCaller(context: Context, number: String): Boolean? {
        val key = number.filter { it.isDigit() }.takeLast(10)
        if (key.length < 7) return null
        cache[key]?.let { return it }
        val result = runCatching { lookup?.invoke(context.applicationContext, number) }.getOrNull()
        if (result != null) cache[key] = result
        return result
    }

    /** Store link people install WhoCaller from. */
    const val INSTALL_LINK = "https://play.google.com/store/apps/details?id=com.rskusum.whocaller"

    fun inviteText(context: Context, name: String?): String =
        if (name.isNullOrBlank()) context.getString(R.string.wc_invite_message, INSTALL_LINK)
        else context.getString(R.string.wc_invite_message_named, name, INSTALL_LINK)

    /**
     * Opens WhatsApp straight in [number]'s chat with the feature card and invite text ready to
     * send, and sends the same invite with the link by SMS.
     */
    suspend fun invite(context: Context, number: String, name: String?) {
        val text = inviteText(context, name)
        val card = withContext(Dispatchers.Default) { runCatching { featureCard(context) }.getOrNull() }
        val pkg = TelecomActions.whatsAppPackage(context)
        val digits = TelecomActions.internationalDigits(context, number).filter { it.isDigit() }
        val send = Intent(Intent.ACTION_SEND).apply {
            if (card != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, card)
                clipData = ClipData.newRawUri(null, card)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            putExtra(Intent.EXTRA_TEXT, text)
            if (pkg != null) {
                setPackage(pkg)
                // Opens this person's chat directly instead of the contact picker.
                putExtra("jid", "$digits@s.whatsapp.net")
            }
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(if (pkg != null) send else Intent.createChooser(send, null))
        }.onFailure {
            // WhatsApp refused the direct chat (e.g. number not on WhatsApp): let the user pick.
            runCatching { context.startActivity(Intent.createChooser(send.setPackage(null).apply { removeExtra("jid") }, null)) }
        }

        val sent = runCatching { smsSender?.invoke(number, text) }.getOrNull() == true
        if (sent) {
            Toast.makeText(context, context.getString(R.string.wc_invite_sms_sent, name ?: number), Toast.LENGTH_SHORT).show()
        } else if (pkg == null) {
            // No WhatsApp and no SMS permission: open the SMS composer with the invite.
            val sms = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${android.net.Uri.encode(number)}"))
                .putExtra("sms_body", text)
            if (context !is android.app.Activity) sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(sms) }
        }
    }

    /** Square promotional card (PNG in the cache folder), shared through [ShareFileProvider]. */
    private fun featureCard(context: Context): android.net.Uri {
        val size = 1080
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), intArrayOf(0xFF4F46E5.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt()), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)

        // App icon on a white rounded tile.
        val tile = RectF(80f, 80f, 260f, 260f)
        c.drawRoundRect(tile, 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        runCatching {
            val icon = context.packageManager.getApplicationIcon(context.packageName).toBitmap(150, 150)
            c.drawBitmap(icon, 95f, 95f, null)
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawText("WhoCaller", 300f, 175f, Paint(white).apply { textSize = 92f; typeface = Typeface.DEFAULT_BOLD })
        c.drawText(context.getString(R.string.wc_card_tagline), 302f, 240f, Paint(white).apply { textSize = 40f; alpha = 220 })

        val features = listOf(
            R.string.wc_card_feature_video,
            R.string.wc_card_feature_callerid,
            R.string.wc_card_feature_spam,
            R.string.wc_card_feature_sms,
            R.string.wc_card_feature_dialer,
        )
        val panel = RectF(80f, 330f, 1000f, 860f)
        c.drawRoundRect(panel, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF })
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 40f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val line = Paint(white).apply { textSize = 46f }
        features.forEachIndexed { i, res ->
            val y = 425f + i * 96f
            c.drawCircle(150f, y - 15f, 30f, check)
            c.drawText("✓", 150f, y, tick)
            c.drawText(context.getString(res), 210f, y, line)
        }

        // Call to action.
        val button = RectF(80f, 905f, 1000f, 1010f)
        c.drawRoundRect(button, 52f, 52f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        c.drawText(
            context.getString(R.string.wc_card_cta),
            540f, 975f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F46E5.toInt(); textSize = 46f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER },
        )

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "whocaller-invite.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return FileProvider.getUriForFile(context, context.packageName + ".share", file)
    }
}

/** State of a "WhoCaller video" button: [available] false = the person isn't on WhoCaller. */
class WhoCallerVideoButton(val available: Boolean, val onClick: () -> Unit)

/**
 * For a "WhoCaller video" button: checks whether [number] is on WhoCaller and returns how the
 * button should look and act. Not on WhoCaller → tapping asks to share WhoCaller with them.
 * [name] is shown exactly as saved; without one the number is shown.
 */
@Composable
fun rememberWhoCallerVideo(number: String?, name: String?): WhoCallerVideoButton {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var onWhoCaller by remember(number) { mutableStateOf<Boolean?>(null) }
    var asking by remember(number) { mutableStateOf(false) }
    LaunchedEffect(number) {
        if (number.isNullOrBlank()) return@LaunchedEffect
        delay(300) // the keypad changes the number on every key press
        onWhoCaller = WhoCallerVideo.isOnWhoCaller(context, number)
    }
    val who = name?.takeIf { it.isNotBlank() } ?: number.orEmpty()
    if (asking && number != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.wc_invite_title, who)) },
            text = { Text(stringResource(R.string.wc_invite_body, who)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    scope.launch { WhoCallerVideo.invite(context, number, name?.takeIf { it.isNotBlank() }) }
                }) { Text(stringResource(R.string.wc_invite_yes)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.wc_invite_no)) } },
        )
    }
    return WhoCallerVideoButton(available = onWhoCaller != false) {
        when {
            number.isNullOrBlank() -> Unit
            onWhoCaller == false -> asking = true
            else -> TelecomActions.whoCallerVideo(context, number)
        }
    }
}

/** Shares the invite card with WhatsApp and other apps. */
class ShareFileProvider : FileProvider()
