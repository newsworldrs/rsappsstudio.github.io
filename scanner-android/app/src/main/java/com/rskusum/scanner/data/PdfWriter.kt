package com.rskusum.scanner.data

import java.io.OutputStream
import java.util.Locale

/**
 * Minimal PDF 1.4 writer that embeds JPEG pages directly (DCTDecode) — no re-rasterizing,
 * so pages stay sharp while the file stays small (unlike android.graphics.pdf.PdfDocument,
 * which stores bitmaps losslessly).
 */
object PdfWriter {

    class JpegPage(val jpeg: ByteArray, val width: Int, val height: Int)

    private const val A4_SHORT = 595.28
    private const val A4_LONG = 841.89

    fun write(out: OutputStream, pages: List<JpegPage>, title: String) {
        val w = CountingStream(out)
        val offsets = ArrayList<Long>()
        fun obj(body: () -> Unit) {
            offsets += w.count
            w.ascii("${offsets.size} 0 obj\n")
            body()
            w.ascii("\nendobj\n")
        }

        w.ascii("%PDF-1.4\n")
        w.raw(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        val n = pages.size
        val kids = (0 until n).joinToString(" ") { "${4 + it * 3} 0 R" }
        obj { w.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { w.ascii("<< /Type /Pages /Kids [$kids] /Count $n >>") }
        obj { w.ascii("<< /Title (${escape(title)}) /Producer (RS Kusum Scanner) >>") }

        pages.forEachIndexed { i, p ->
            val pageObj = 4 + i * 3
            val landscape = p.width > p.height
            val pw = if (landscape) A4_LONG else A4_SHORT
            val ph = pw * p.height / p.width
            val content = "q ${f(pw)} 0 0 ${f(ph)} 0 0 cm /Im0 Do Q"
            obj {
                w.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                        "/Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> /ProcSet [/PDF /ImageC] >> " +
                        "/Contents ${pageObj + 1} 0 R >>"
                )
            }
            obj {
                w.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            }
            obj {
                w.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n"
                )
                w.raw(p.jpeg)
                w.ascii("\nendstream")
            }
        }

        val xref = w.count
        w.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w.ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
        w.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        w.flush()
    }

    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun escape(s: String) = buildString {
        s.forEach { c ->
            when {
                c == '(' || c == ')' || c == '\\' -> { append('\\'); append(c) }
                c.code in 32..126 -> append(c)
                else -> append('_')
            }
        }
    }

    private class CountingStream(private val out: OutputStream) {
        var count = 0L
            private set

        fun ascii(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
        fun raw(b: ByteArray) {
            out.write(b); count += b.size
        }
        fun flush() = out.flush()
    }
}
