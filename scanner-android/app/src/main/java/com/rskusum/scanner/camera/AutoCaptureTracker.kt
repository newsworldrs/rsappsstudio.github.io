package com.rskusum.scanner.camera

import com.rskusum.scanner.vision.DocumentDetector
import com.rskusum.scanner.vision.Quad

enum class CapturePhase { SEARCHING, TOO_SMALL, HOLD_STEADY, CAPTURING, NEXT_PAGE }

data class TrackerState(
    val quad: Quad? = null,
    val phase: CapturePhase = CapturePhase.SEARCHING,
    /** 0..1 progress of the auto-capture countdown (drawn as the arc around the shutter). */
    val progress: Float = 0f,
    /** Guide-frame mode: raw live detection (may not be aligned), for on-screen feedback. */
    val rawQuad: Quad? = null,
    /** Guide-frame mode: page detected and matching the frame. */
    val aligned: Boolean = false,
    /** Guide-frame mode: what the user should do, e.g. "Move closer". */
    val guidance: String? = null,
)

/**
 * Decides when to fire the shutter automatically, Adobe-Scan style.
 *
 * Real camera frames are noisy: a detection can wobble by a few pixels, drop out for a frame,
 * or occasionally lock onto something else. So instead of demanding frame-to-frame identity,
 * the tracker keeps a smoothed *anchor* outline:
 *  - a detection close to the anchor refines it and advances the countdown,
 *  - a single outlier or missed frame only pauses the countdown,
 *  - only a sustained different outline (really moved) or a sustained loss resets it.
 *
 * After a capture it waits until the page content changes (page turned / new sheet) or the page
 * leaves the view before arming again, so a batch of pages can be scanned hands-free.
 */
class AutoCaptureTracker(
    private val stableMillis: Long = 1000,
    /** Max corner distance (normalized) from the anchor that still counts as "held steady". */
    private val steadyTolerance: Float = 0.035f,
    /** Beyond this the detection is an outlier; several in a row mean the page really moved. */
    private val jumpTolerance: Float = 0.08f,
    private val minArea: Float = 0.10f,
) {
    private var anchor: Quad? = null
    private var progressMs = 0L
    private var lastFrameAt = 0L
    private var lostSince = 0L
    private var outliers = 0
    private var outlierQuad: Quad? = null
    private var capturingSince = 0L
    private var lastSig: ByteArray? = null
    private var capturedSig: ByteArray? = null

    /** Smoothed outline the detector should prefer next frame (temporal coherence). */
    @get:Synchronized
    val currentAnchor: Quad? get() = anchor

    // Fallback ("capture raw, crop later"): when the live preview can't find the page but the
    // phone is held still, capture anyway; edges are then searched on the sharp full-res photo.
    private var sceneSteadyMs = 0L
    private var prevScene: ByteArray? = null
    private var lastScene: ByteArray? = null
    private var capturedScene: ByteArray? = null

    /**
     * @param scene tiny thumbnail of the whole frame (see [DocumentDetector.sceneSignature]),
     *   used to measure camera steadiness independent of document detection.
     */
    @Synchronized
    fun update(
        quad: Quad?, sig: ByteArray?, now: Long, autoEnabled: Boolean, scene: ByteArray? = null,
        /** False while something is visibly wrong (e.g. page not aligned with the guide frame). */
        allowFallback: Boolean = true,
    ): Pair<TrackerState, Boolean> {
        val dt = if (lastFrameAt == 0L) 0L else (now - lastFrameAt).coerceIn(0L, 200L)
        lastFrameAt = now
        if (capturingSince != 0L && now - capturingSince > 4000) capturingSince = 0L // capture failed/timed out

        if (scene != null) {
            val p = prevScene
            if (p != null && DocumentDetector.signatureDistance(scene, p) < SCENE_STEADY) sceneSteadyMs += dt else sceneSteadyMs = 0L
            prevScene = scene
            lastScene = scene
            val c = capturedScene
            if (c != null && DocumentDetector.signatureDistance(scene, c) > SCENE_CHANGED) capturedScene = null
        }

        if (!allowFallback) sceneSteadyMs = 0L
        if (capturingSince == 0L && scene != null && capturedScene == null && capturedSig == null) {
            val noDoc = quad == null && anchor == null
            // Held still but no (stable) outline: capture anyway, crop on the full-res photo.
            val needed = if (noDoc) FALLBACK_MILLIS else FALLBACK_MILLIS + 600
            if (autoEnabled && sceneSteadyMs >= needed) {
                capturingSince = now
                return TrackerState(anchor, CapturePhase.CAPTURING, 1f) to true
            }
            if (noDoc && sceneSteadyMs > 300) {
                val p = (sceneSteadyMs.toFloat() / needed).coerceIn(0f, 1f)
                return TrackerState(null, CapturePhase.SEARCHING, if (autoEnabled) p else 0f) to false
            }
        }

        if (quad == null) {
            if (lostSince == 0L) lostSince = now
            val lostFor = now - lostSince
            if (lostFor > 700) {
                anchor = null
                progressMs = 0L
                outliers = 0
                capturedSig = null // page left the view -> arm for the next one
            }
            val a = anchor
            val phase = when {
                capturingSince != 0L -> CapturePhase.CAPTURING
                a == null -> CapturePhase.SEARCHING
                capturedSig != null -> CapturePhase.NEXT_PAGE
                else -> CapturePhase.HOLD_STEADY
            }
            return TrackerState(a, phase, if (autoEnabled && a != null) progress() else 0f) to false
        }
        lostSince = 0L
        lastSig = sig

        val a = anchor
        if (a == null) {
            anchor = quad
            progressMs = 0L
            outliers = 0
        } else {
            val d = a.distanceTo(quad)
            if (d <= jumpTolerance) {
                outliers = 0
                // Follow slowly when steady (kills jitter), faster when drifting.
                anchor = a.lerp(quad, if (d <= steadyTolerance) 0.25f else 0.6f)
                if (d <= steadyTolerance) progressMs += dt else progressMs = (progressMs - dt).coerceAtLeast(0L)
            } else {
                // Outlier: needs 3 consistent frames elsewhere before we believe the page moved.
                val prevOutlier = outlierQuad
                outliers = if (prevOutlier != null && prevOutlier.distanceTo(quad) <= jumpTolerance) outliers + 1 else 1
                outlierQuad = quad
                if (outliers >= 3) {
                    anchor = quad
                    progressMs = 0L
                    outliers = 0
                }
            }
        }
        val shown = anchor!!

        if (capturingSince != 0L) return TrackerState(shown, CapturePhase.CAPTURING, 1f) to false

        val captured = capturedSig
        if (captured != null) {
            if (sig != null && DocumentDetector.signatureDistance(sig, captured) > 14.0) {
                capturedSig = null
                progressMs = 0L
            } else {
                progressMs = 0L
                return TrackerState(shown, CapturePhase.NEXT_PAGE) to false
            }
        }

        if (shown.area() < minArea) {
            progressMs = 0L
            return TrackerState(shown, CapturePhase.TOO_SMALL) to false
        }

        val p = progress()
        if (autoEnabled && p >= 1f) {
            capturingSince = now
            return TrackerState(shown, CapturePhase.CAPTURING, 1f) to true
        }
        return TrackerState(shown, CapturePhase.HOLD_STEADY, if (autoEnabled) p else 0f) to false
    }

    private fun progress() = (progressMs.toFloat() / stableMillis).coerceIn(0f, 1f)

    /** Call when a capture (auto or manual) finished; blocks re-capture of the same page. */
    @Synchronized
    fun onCaptured(success: Boolean) {
        capturingSince = 0L
        progressMs = 0L
        sceneSteadyMs = 0L
        if (success) {
            capturedSig = if (anchor != null) lastSig else null
            capturedScene = lastScene
        }
    }

    @Synchronized
    fun onManualCaptureStarted(now: Long) {
        capturingSince = now
    }

    @Synchronized
    fun reset() {
        anchor = null; progressMs = 0L; lastFrameAt = 0L; lostSince = 0L; outliers = 0
        outlierQuad = null; capturingSince = 0L; capturedSig = null
        sceneSteadyMs = 0L; prevScene = null; lastScene = null; capturedScene = null
    }

    private companion object {
        /** Mean abs difference (0..255) of the scene thumbnail between frames that counts as still. */
        const val SCENE_STEADY = 4.0
        /** Scene difference vs. the last capture that re-arms the fallback. */
        const val SCENE_CHANGED = 16.0
        const val FALLBACK_MILLIS = 1800L
    }
}
