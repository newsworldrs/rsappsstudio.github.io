package com.rskusum.scanner.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Minimal PDF writer for scanned pages: every page is one JPEG embedded as-is (DCTDecode, no
 * re-compression), scaled to fill the page. Replaces a full PDF library (PDFBox + BouncyCastle,
 * ~6 MB) with ~100 lines; the output is a standard PDF 1.4 file readable everywhere.
 */
internal object JpegPdfWriter {

    /** One page: JPEG bytes, pixel size, and page size in PDF points (1/72 inch). */
    class PageImage(val jpeg: ByteArray, val width: Int, val height: Int, val pageWidth: Float, val pageHeight: Float)

    /** Writes [pageCount] pages; [page] makes each one only when it is written (one in memory at a time). */
    fun write(file: File, pageCount: Int, title: String, producer: String, page: (Int) -> PageImage) {
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { raw ->
            val out = CountingStream(raw)
            val offsets = HashMap<Int, Long>()
            fun obj(n: Int, dict: String, stream: ByteArray? = null) {
                offsets[n] = out.count
                out.ascii("$n 0 obj\n$dict")
                if (stream != null) {
                    out.ascii("\nstream\n")
                    out.write(stream)
                    out.ascii("\nendstream")
                }
                out.ascii("\nendobj\n")
            }

            out.ascii("%PDF-1.4\n")
            out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
            // 1 = catalog, 2 = page tree (written last, once all pages are known), 3 = info.
            obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
            obj(3, "<< /Title ${utf16Hex(title)} /Producer ${utf16Hex(producer)} >>")
            val kids = StringBuilder()
            for (i in 0 until pageCount) {
                val p = page(i)
                val pageObj = 4 + 3 * i
                val imageObj = pageObj + 1
                val contentObj = pageObj + 2
                kids.append("$pageObj 0 R ")
                val pw = num(p.pageWidth)
                val ph = num(p.pageHeight)
                obj(
                    pageObj,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                        "/Resources << /XObject << /Im0 $imageObj 0 R >> >> /Contents $contentObj 0 R >>",
                )
                obj(
                    imageObj,
                    "<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} /ColorSpace /DeviceRGB " +
                        "/BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>",
                    p.jpeg,
                )
                val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q".toByteArray(Charsets.US_ASCII)
                obj(contentObj, "<< /Length ${content.size} >>", content)
            }
            obj(2, "<< /Type /Pages /Kids [${kids.toString().trim()}] /Count $pageCount >>")

            val size = (offsets.keys.maxOrNull() ?: 3) + 1
            val xref = out.count
            val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
            for (n in 1 until size) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[n] ?: 0L))
            sb.append("trailer\n<< /Size $size /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
            out.ascii(sb.toString())
        }
    }

    private fun num(v: Float) = String.format(Locale.US, "%.2f", v)

    /** PDF text string as UTF-16BE hex with BOM: safe for any language. */
    private fun utf16Hex(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (c in s) sb.append(String.format(Locale.US, "%04X", c.code))
        return sb.append('>').toString()
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        fun ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
