package com.rskusum.scanner.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/** Which Tesseract language models to use. */
enum class OcrModel(internal val repo: String) {
    /** Most accurate (about 12-15 MB per language). Default. */
    BEST("tessdata_best"),

    /** Smaller and faster (about 2-4 MB per language), slightly less accurate. */
    FAST("tessdata_fast"),
}

/**
 * Recognised text.
 * @param text the page text, line breaks kept.
 * @param confidence Tesseract's mean word confidence, 0-100.
 */
data class OcrResult(val text: String, val confidence: Int, val languages: String)

/**
 * On-device text recognition with Tesseract (LSTM engine). No ML Kit, no Play services, no cloud.
 *
 * ```
 * val result = RsOcr.recognize(context, pageUri)              // English
 * val result = RsOcr.recognize(context, pageUri, "eng+hin")   // English + Hindi
 * ```
 *
 * Language models (`<lang>.traineddata`, Apache 2.0 by the Tesseract project) are taken from, in order:
 * 1. the app's cache (`files/rsocr/`),
 * 2. the host app's assets, `assets/tessdata/<lang>.traineddata` (bundle them for fully offline use),
 * 3. a one-time download from github.com/tesseract-ocr/tessdata_best (or tessdata_fast).
 *
 * Call [prepare] early (e.g. at app start) so the first scan doesn't wait for the download.
 * Language codes: eng, hin, mar, ben, guj, pan, tam, tel, kan, mal, urd, ara, fra, deu, spa, ...
 */
object RsOcr {
    private const val TAG = "RsOcr"
    private val lock = Mutex()

    /** Tesseract data folder for a model (contains `tessdata/`). */
    private fun dataDir(context: Context, model: OcrModel) = File(context.filesDir, "rsocr/${model.name.lowercase()}")

    private fun modelFile(context: Context, lang: String, model: OcrModel) =
        File(dataDir(context, model), "tessdata/$lang.traineddata")

    /** True when every language in [languages] ("eng+hin") is available offline. */
    @JvmStatic
    @JvmOverloads
    fun isReady(context: Context, languages: String = "eng", model: OcrModel = OcrModel.BEST): Boolean =
        langs(languages).all { modelFile(context, it, model).length() > 0 }

    /**
     * Makes sure the language models are on the device (copies them from assets or downloads them
     * once). Safe to call repeatedly. Throws when a model can't be obtained (e.g. offline first run).
     */
    suspend fun prepare(context: Context, languages: String = "eng", model: OcrModel = OcrModel.BEST) =
        withContext(Dispatchers.IO) {
            for (lang in langs(languages)) {
                val target = modelFile(context, lang, model)
                if (target.length() > 0) continue
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                val fromAssets = try {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        part.outputStream().use { input.copyTo(it) }
                    }
                    true
                } catch (_: FileNotFoundException) {
                    false
                }
                if (!fromAssets) download("https://github.com/tesseract-ocr/${model.repo}/raw/main/$lang.traineddata", part)
                check(part.length() > 0 && part.renameTo(target)) { "Could not store the $lang OCR model" }
                Log.i(TAG, "$lang model ready (${target.length() / 1024} KB, ${if (fromAssets) "assets" else "download"})")
            }
        }

    /** Recognises the text in an image (a scanned page URI, a gallery image, a file URI). */
    suspend fun recognize(
        context: Context,
        image: Uri,
        languages: String = "eng",
        model: OcrModel = OcrModel.BEST,
    ): OcrResult {
        val bitmap = withContext(Dispatchers.IO) { decode(context, image) } ?: error("Cannot read image $image")
        return try {
            recognize(context, bitmap, languages, model)
        } finally {
            bitmap.recycle()
        }
    }

    /** Recognises the text in a bitmap (the bitmap is not recycled). */
    suspend fun recognize(
        context: Context,
        bitmap: Bitmap,
        languages: String = "eng",
        model: OcrModel = OcrModel.BEST,
    ): OcrResult {
        prepare(context, languages, model)
        return withContext(Dispatchers.Default) {
            lock.withLock {
                val input = prepareImage(bitmap)
                val api = TessBaseAPI()
                try {
                    check(api.init(dataDir(context, model).absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
                        "Tesseract could not load '$languages'"
                    }
                    api.setVariable("user_defined_dpi", "300")
                    api.setVariable("preserve_interword_spaces", "1")
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    api.setImage(input)
                    val text = api.getUTF8Text().orEmpty()
                    OcrResult(cleanUp(text), api.meanConfidence(), languages)
                } finally {
                    api.recycle()
                    if (input !== bitmap) input.recycle()
                }
            }
        }
    }

    // --- internals ------------------------------------------------------------------------------

    private fun langs(languages: String) = languages.split('+').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("eng") }

    /** Loads an image, keeping it no larger than needed (Tesseract works best around 300 dpi). */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /**
     * Grayscale, ARGB_8888 and a good size: small images are enlarged (small text is the most
     * common cause of OCR errors), huge ones reduced.
     */
    private fun prepareImage(src: Bitmap): Bitmap {
        val long = max(src.width, src.height)
        val scale = when {
            long < MIN_SIDE -> MIN_SIDE.toFloat() / long
            long > MAX_SIDE -> MAX_SIDE.toFloat() / long
            else -> 1f
        }
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            scale(w.toFloat() / src.width, h.toFloat() / src.height)
            drawBitmap(src, 0f, 0f, paint)
        }
        return out
    }

    /** Trims trailing spaces and collapses runs of blank lines. */
    private fun cleanUp(text: String): String =
        text.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun download(url: String, target: File) {
        var conn = URL(url).openConnection() as HttpURLConnection
        var redirects = 0
        while (true) {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                redirects++
                continue
            }
            check(code == 200) { "OCR model download failed (HTTP $code)" }
            break
        }
        try {
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private const val MIN_SIDE = 2000
    private const val MAX_SIDE = 4000
}
