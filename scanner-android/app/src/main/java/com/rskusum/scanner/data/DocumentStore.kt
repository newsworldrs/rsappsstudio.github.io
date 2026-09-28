package com.rskusum.scanner.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class SavedDocument(
    val file: File,
    val name: String,
    val modified: Long,
    val sizeBytes: Long,
    val pageCount: Int,
)

/** Owns the app's PDF library (internal storage) and exports to public Downloads / Pictures. */
class DocumentStore(private val context: Context) {

    val dir: File = File(context.filesDir, "documents").apply { mkdirs() }
    private val publicFolder = "RS Kusum Scanner"

    fun list(): List<SavedDocument> =
        (dir.listFiles { f -> f.extension.equals("pdf", true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { SavedDocument(it, it.nameWithoutExtension, it.lastModified(), it.length(), pageCount(it)) }

    /** Writes the pages as a PDF into the library. Returns the file. */
    fun savePdf(name: String, pageFiles: List<File>): File {
        val file = uniqueFile(sanitize(name), "pdf")
        val pages = pageFiles.map { toPdfPage(it) }
        FileOutputStream(file).use { PdfWriter.write(it, pages, name) }
        return file
    }

    /** Copies a library PDF to the public Downloads/RS Kusum Scanner folder. */
    fun exportToDownloads(file: File): String {
        writePublic(file.name, "application/pdf", Environment.DIRECTORY_DOWNLOADS, { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) downloadsUri() else MediaStore.Files.getContentUri("external") }) { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/$publicFolder"
    }

    /** Saves each page as a JPEG in Pictures/RS Kusum Scanner. */
    fun exportJpegs(name: String, pageFiles: List<File>): String {
        pageFiles.forEachIndexed { i, f ->
            val fileName = "${sanitize(name)}_${i + 1}.jpg"
            writePublic(fileName, "image/jpeg", Environment.DIRECTORY_PICTURES, { MediaStore.Images.Media.EXTERNAL_CONTENT_URI }) { out ->
                f.inputStream().use { it.copyTo(out) }
            }
        }
        return "${Environment.DIRECTORY_PICTURES}/$publicFolder"
    }

    fun delete(doc: SavedDocument) = doc.file.delete()

    fun rename(doc: SavedDocument, newName: String): File {
        val target = uniqueFile(sanitize(newName), "pdf")
        doc.file.renameTo(target)
        return target
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(files: List<File>, mime: String): Intent {
        val uris = ArrayList(files.map { uriFor(it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, "Share")
    }

    fun openIntent(file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** First page preview rendered with the platform PdfRenderer. */
    fun thumbnail(file: File, width: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { p ->
                    val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    // ---------------------------------------------------------------------------------------

    private fun pageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    }.getOrDefault(0)

    /** Re-encodes very large pages to ~300 dpi A4 so PDFs stay shareable. */
    private fun toPdfPage(file: File): PdfWriter.JpegPage {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        val maxSide = 3508
        if (maxOf(o.outWidth, o.outHeight) <= maxSide) {
            return PdfWriter.JpegPage(file.readBytes(), o.outWidth, o.outHeight)
        }
        val bmp = Images.decodeFile(file, maxSide) ?: error("Cannot decode $file")
        val bytes = ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos); bos.toByteArray()
        }
        val page = PdfWriter.JpegPage(bytes, bmp.width, bmp.height)
        bmp.recycle()
        return page
    }

    private fun writePublic(fileName: String, mime: String, publicDir: String, collection: () -> Uri, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$publicFolder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection(), values) ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use(write) ?: error("Cannot open $uri")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val base = File(Environment.getExternalStoragePublicDirectory(publicDir), publicFolder).apply { mkdirs() }
            var target = File(base, fileName)
            var i = 1
            while (target.exists()) target = File(base, "${fileName.substringBeforeLast('.')} ($i).${fileName.substringAfterLast('.')}").also { i++ }
            FileOutputStream(target).use(write)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun downloadsUri(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun uniqueFile(base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i).$ext").also { i++ }
        return f
    }

    private fun sanitize(name: String) = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "Scan" }
}
