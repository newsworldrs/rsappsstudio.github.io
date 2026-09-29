package com.rskusum.scanner.vision

import kotlin.math.hypot
import kotlin.math.max

/** A point in normalized [0,1] image coordinates. */
data class NPoint(val x: Float, val y: Float)

/**
 * A document outline in normalized image coordinates, ordered
 * top-left, top-right, bottom-right, bottom-left.
 */
data class Quad(val tl: NPoint, val tr: NPoint, val br: NPoint, val bl: NPoint) {

    val points: List<NPoint> get() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: NPoint): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    /** Largest corner displacement between two quads (normalized units). */
    fun distanceTo(other: Quad): Float {
        var d = 0f
        points.zip(other.points).forEach { (a, b) -> d = max(d, hypot(a.x - b.x, a.y - b.y)) }
        return d
    }

    fun lerp(to: Quad, t: Float): Quad {
        fun l(a: NPoint, b: NPoint) = NPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        return Quad(l(tl, to.tl), l(tr, to.tr), l(br, to.br), l(bl, to.bl))
    }

    /** Area in normalized units (shoelace). */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(s) / 2f
    }

    companion object {
        val FULL = Quad(NPoint(0f, 0f), NPoint(1f, 0f), NPoint(1f, 1f), NPoint(0f, 1f))

        /** Inset full-frame quad, used when the user asks for a manual crop with no detection. */
        fun inset(f: Float) = Quad(NPoint(f, f), NPoint(1 - f, f), NPoint(1 - f, 1 - f), NPoint(f, 1 - f))
    }
}
