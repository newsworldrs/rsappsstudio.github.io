package com.rskusum.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.rskusum.scanner.data.PdfQuality
import com.rskusum.scanner.data.ScanMode
import kotlinx.parcelize.Parcelize
import org.opencv.android.OpenCVLoader

/**
 * Entry point of the RS Kusum Scanner library.
 *
 * Kotlin / Compose:
 * ```
 * val scanner = rememberLauncherForActivityResult(ScanDocument()) { result ->
 *     result?.pdfUri      // the finished PDF (null if returnPdf = false)
 *     result?.pageUris    // one JPEG per page (empty if returnJpegs = false)
 * }
 * scanner.launch(ScannerOptions(modes = listOf(ScanMode.DOCUMENT, ScanMode.ID_CARD)))
 * ```
 * Java: `registerForActivityResult(new ScanDocument(), result -> { ... })`, then
 * `launcher.launch(new ScannerOptions())`.
 */
object RsScanner {
    const val EXTRA_OPTIONS = "com.rskusum.scanner.OPTIONS"
    const val EXTRA_RESULT = "com.rskusum.scanner.RESULT"

    /** Non-null when the vision engine (OpenCV) could not be loaded on this device. */
    @Volatile
    @JvmStatic
    var openCvError: String? = null
        private set

    @Volatile private var initialized = false

    /**
     * Loads the native vision engine. Called automatically by the scanner screen; call it
     * yourself (e.g. in `Application.onCreate`) only to warm up, or before using the advanced
     * API ([com.rskusum.scanner.vision.DocumentDetector], [com.rskusum.scanner.vision.ImageEnhancer]).
     * Safe to call any number of times. Returns true when the engine is ready.
     */
    @JvmStatic
    fun init(context: Context): Boolean {
        if (initialized) return openCvError == null
        synchronized(this) {
            if (!initialized) {
                openCvError = try {
                    if (OpenCVLoader.initLocal()) null else "OpenCV native library failed to load"
                } catch (t: Throwable) {
                    "OpenCV: ${t.javaClass.simpleName}: ${t.message}"
                }
                openCvError?.let { Log.e("RsScanner", it) }
                initialized = true
            }
        }
        return openCvError == null
    }

    /** Intent that opens the scanner; prefer the [ScanDocument] contract. */
    @JvmStatic
    @JvmOverloads
    fun createIntent(context: Context, options: ScannerOptions = ScannerOptions()): Intent =
        Intent(context, ScannerActivity::class.java).putExtra(EXTRA_OPTIONS, options)
}

/**
 * What the scanner offers and returns.
 *
 * @param modes scan modes shown in the mode carousel, in this order (at least one).
 * @param initialMode mode selected when the camera opens (must be in [modes]).
 * @param pageLimit maximum number of pages in one scan, 0 = unlimited.
 * @param galleryImport show the "import from gallery" button.
 * @param autoCapture start with automatic capture switched on.
 * @param returnPdf create a PDF of the scan ([ScanResult.pdfUri]).
 * @param returnJpegs return every finished page as a JPEG ([ScanResult.pageUris]).
 * @param pdfQuality size / quality preset preselected for the PDF.
 * @param standalone full app behaviour: home screen with the PDF library, QR scanner, and
 *   saving to the public Downloads folder. Leave false when embedding the scanner.
 */
@Parcelize
data class ScannerOptions @JvmOverloads constructor(
    val modes: List<ScanMode> = ScanMode.entries.toList(),
    val initialMode: ScanMode = ScanMode.DOCUMENT,
    val pageLimit: Int = 0,
    val galleryImport: Boolean = true,
    val autoCapture: Boolean = true,
    val returnPdf: Boolean = true,
    val returnJpegs: Boolean = true,
    val pdfQuality: PdfQuality = PdfQuality.BALANCED,
    val standalone: Boolean = false,
) : Parcelable {
    init {
        require(modes.isNotEmpty()) { "ScannerOptions.modes must not be empty" }
        require(pageLimit >= 0) { "ScannerOptions.pageLimit must be >= 0" }
    }

    /** The mode the camera starts in. */
    internal val startMode: ScanMode get() = if (initialMode in modes) initialMode else modes.first()
}

/**
 * A finished scan. The files live in the app's private storage (`files/rsscanner/`); the URIs
 * are `content://` URIs from the library's FileProvider, readable with `contentResolver` and
 * shareable with other apps (add `Intent.FLAG_GRANT_READ_URI_PERMISSION`). Move or delete the
 * files when you no longer need them.
 */
@Parcelize
data class ScanResult(
    val name: String,
    val pdfUri: Uri?,
    val pageUris: List<Uri>,
    val pageCount: Int,
) : Parcelable

/** Activity-result contract: launch with [ScannerOptions], get a [ScanResult] or null when cancelled. */
class ScanDocument : ActivityResultContract<ScannerOptions, ScanResult?>() {
    override fun createIntent(context: Context, input: ScannerOptions): Intent = RsScanner.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): ScanResult? =
        if (resultCode == Activity.RESULT_OK && intent != null) {
            IntentCompat.getParcelableExtra(intent, RsScanner.EXTRA_RESULT, ScanResult::class.java)
        } else {
            null
        }
}
