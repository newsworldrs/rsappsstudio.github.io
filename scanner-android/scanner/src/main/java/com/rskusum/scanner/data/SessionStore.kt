package com.rskusum.scanner.data

import android.util.Log
import com.rskusum.scanner.vision.EraseStroke
import com.rskusum.scanner.vision.NPoint
import com.rskusum.scanner.vision.Quad
import com.rskusum.scanner.vision.ScanFilter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The scan in progress, written to disk as a small JSON manifest next to the page photos, so a
 * batch survives the app being killed or the phone restarting: only file paths and edit settings
 * are stored (never images). Removed when the scan is saved or discarded.
 */
internal class SessionStore(private val dir: File) {

    private val file = File(dir, "session.json")
    private val tmp = File(dir, "session.json.tmp")

    class Restored(val name: String?, val pages: List<Page>)

    fun save(name: String, pages: List<Page>) {
        try {
            val arr = JSONArray()
            for (p in pages) arr.put(pageJson(p))
            val root = JSONObject().put("version", 1).put("name", name).put("pages", arr)
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (t: Throwable) {
            Log.e(TAG, "save failed", t)
        }
    }

    fun clear() {
        file.delete(); tmp.delete()
    }

    /** Pages of an interrupted scan whose photos are still on disk; null when there is none. */
    fun restore(): Restored? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("pages")
            val pages = (0 until arr.length()).mapNotNull { pageFrom(arr.getJSONObject(it)) }
            if (pages.isEmpty()) null else Restored(root.optString("name").ifEmpty { null }, pages)
        } catch (t: Throwable) {
            Log.e(TAG, "restore failed", t)
            null
        }
    }

    private fun pageJson(p: Page) = JSONObject().apply {
        put("id", p.id)
        put("original", p.originalFile.absolutePath)
        p.processedFile?.let { put("processed", it.absolutePath) }
        put("quad", quadJson(p.quad))
        p.frame?.let { put("frame", quadJson(it)) }
        put("filter", p.filter.name)
        put("shadow", p.removeShadow)
        put("rotation", p.rotation)
        put("upright", p.uprightPending)
        p.forcedAspect?.let { put("aspect", it) }
        put("orientation", p.forcedOrientation)
        put("bookHalf", p.bookHalf)
        p.idGroup?.let { put("idGroup", it) }
        put("idSide", p.idSide)
        put("hd", p.hd)
        p.smartLabel?.let { put("smart", it) }
        p.ocrText?.let { put("ocr", it) }
        val strokes = JSONArray()
        for (s in p.erasures.toList()) {
            val pts = JSONArray()
            for (pt in s.points) { pts.put(pt.x.toDouble()); pts.put(pt.y.toDouble()) }
            strokes.put(JSONObject().put("r", s.radius.toDouble()).put("keep", s.keepText).put("restore", s.restore).put("p", pts))
        }
        put("erase", strokes)
    }

    private fun pageFrom(o: JSONObject): Page? {
        val original = File(o.getString("original"))
        if (!original.exists()) return null
        val page = Page(
            originalFile = original,
            quad = quadFrom(o.getJSONArray("quad")),
            filter = runCatching { ScanFilter.valueOf(o.getString("filter")) }.getOrDefault(ScanFilter.AUTO),
            forcedAspect = if (o.has("aspect")) o.getDouble("aspect") else null,
            forcedOrientation = o.optInt("orientation", 0),
            bookHalf = o.optInt("bookHalf", -1),
            frame = o.optJSONArray("frame")?.let { quadFrom(it) },
            idGroup = o.optString("idGroup").ifEmpty { null },
            idSide = o.optInt("idSide", -1),
            id = o.getString("id"),
        )
        page.removeShadow = o.optBoolean("shadow")
        page.rotation = o.optInt("rotation")
        page.uprightPending = o.optBoolean("upright")
        page.hd = o.optBoolean("hd")
        page.smartLabel = o.optString("smart").ifEmpty { null }
        page.ocrText = o.optString("ocr").ifEmpty { null }
        page.processedFile = o.optString("processed").ifEmpty { null }?.let(::File)?.takeIf { it.exists() }
        o.optJSONArray("erase")?.let { strokes ->
            for (i in 0 until strokes.length()) {
                val s = strokes.getJSONObject(i)
                val pts = s.getJSONArray("p")
                val points = (0 until pts.length() / 2).map { NPoint(pts.getDouble(2 * it).toFloat(), pts.getDouble(2 * it + 1).toFloat()) }
                page.erasures += EraseStroke(points, s.getDouble("r").toFloat(), s.getBoolean("keep"), s.optBoolean("restore"))
            }
        }
        return page
    }

    private fun quadJson(q: Quad) = JSONArray().apply { q.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }

    private fun quadFrom(a: JSONArray): Quad {
        fun p(i: Int) = NPoint(a.getDouble(2 * i).toFloat(), a.getDouble(2 * i + 1).toFloat())
        return Quad(p(0), p(1), p(2), p(3))
    }

    private companion object { const val TAG = "SessionStore" }
}
