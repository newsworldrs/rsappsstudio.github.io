package com.rskusum.scanner.camera

import com.rskusum.scanner.vision.DocumentDetector
import com.rskusum.scanner.vision.Quad

enum class CapturePhase { SEARCHING, TOO_SMALL, HOLD_STEADY, CAPTURING, NEXT_PAGE }

data class TrackerState(
    val quad: Quad? = null,
    val phase: CapturePhase = CapturePhase.SEARCHING,
    /** 0..1 progress of the auto-capture countdown (drawn as the arc around the shutter). */
    val progress: Float = 0f,
)

/**
 * Decides when to fire the shutter automatically, Adobe-Scan style:
 * the document must be large enough and held steady for [stableMillis]. After a capture it
 * waits until the page content changes (page turned / new sheet) or the page leaves the view
 * before arming again, so a batch of pages can be scanned hands-free.
 */
class AutoCaptureTracker(
    private val stableMillis: Long = 1100,
    private val motionTolerance: Float = 0.018f,
    private val minArea: Float = 0.12f,
) {
    private var display: Quad? = null
    private var last: Quad? = null
    private var stableSince = 0L
    private var lostSince = 0L
    private var capturingSince = 0L
    private var lastSig: ByteArray? = null
    private var capturedSig: ByteArray? = null

    @Synchronized
    fun update(quad: Quad?, sig: ByteArray?, now: Long, autoEnabled: Boolean): Pair<TrackerState, Boolean> {
        if (capturingSince != 0L && now - capturingSince > 4000) capturingSince = 0L // capture failed/timed out
        if (quad == null) {
            if (lostSince == 0L) lostSince = now
            if (now - lostSince > 600) capturedSig = null
            // keep the outline briefly to avoid flicker on a single missed frame
            val keep = now - lostSince < 250
            if (!keep) { display = null; last = null }
            stableSince = 0L
            return TrackerState(if (keep) display else null, if (capturingSince != 0L) CapturePhase.CAPTURING else CapturePhase.SEARCHING) to false
        }
        lostSince = 0L
        val prev = last
        last = quad
        display = display?.lerp(quad, 0.55f) ?: quad
        lastSig = sig
        val shown = display!!

        if (capturingSince != 0L) return TrackerState(shown, CapturePhase.CAPTURING, 1f) to false

        val captured = capturedSig
        if (captured != null) {
            if (sig != null && DocumentDetector.signatureDistance(sig, captured) > 14.0) capturedSig = null
            else return TrackerState(shown, CapturePhase.NEXT_PAGE) to false
        }

        if (quad.area() < minArea) {
            stableSince = 0L
            return TrackerState(shown, CapturePhase.TOO_SMALL) to false
        }

        val motion = prev?.distanceTo(quad) ?: 1f
        if (motion > motionTolerance || stableSince == 0L) {
            stableSince = now
            return TrackerState(shown, CapturePhase.HOLD_STEADY, 0f) to false
        }
        val progress = ((now - stableSince).toFloat() / stableMillis).coerceIn(0f, 1f)
        if (autoEnabled && progress >= 1f) {
            capturingSince = now
            return TrackerState(shown, CapturePhase.CAPTURING, 1f) to true
        }
        return TrackerState(shown, CapturePhase.HOLD_STEADY, if (autoEnabled) progress else 0f) to false
    }

    /** Call when a capture (auto or manual) finished; blocks re-capture of the same page. */
    @Synchronized
    fun onCaptured(success: Boolean) {
        capturingSince = 0L
        stableSince = 0L
        if (success) capturedSig = lastSig
    }

    @Synchronized
    fun onManualCaptureStarted(now: Long) {
        capturingSince = now
    }

    @Synchronized
    fun reset() {
        display = null; last = null; stableSince = 0L; lostSince = 0L; capturingSince = 0L; capturedSig = null
    }
}
