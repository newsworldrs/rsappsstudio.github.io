package com.rskusum.scanner.data

import com.rskusum.scanner.vision.ScanFilter

enum class ScanMode(
    val label: String,
    val defaultFilter: ScanFilter,
    /** Physical width/height ratio to enforce, or null to measure it from the photo. */
    val forcedAspect: Double? = null,
    /** Split the flattened spread into left + right pages. */
    val splitBook: Boolean = false,
    /** Long side / short side of the on-screen guide frame. */
    val frameRatio: Double = Math.sqrt(2.0),
    /** Orientation the guide frame starts in for this mode. */
    val defaultOrientation: FrameOrientation = FrameOrientation.PORTRAIT,
) {
    WHITEBOARD("Whiteboard", ScanFilter.WHITEBOARD, frameRatio = 4.0 / 3.0, defaultOrientation = FrameOrientation.LANDSCAPE),
    BOOK("Book", ScanFilter.AUTO, splitBook = true, defaultOrientation = FrameOrientation.LANDSCAPE),
    /** Front/back cover of a book, notebook or folder: one colourful page, kept in colour. */
    BOOK_COVER("Book cover", ScanFilter.ORIGINAL, frameRatio = 1.5),
    DOCUMENT("Document", ScanFilter.AUTO),
    ID_CARD("ID card", ScanFilter.AUTO, forcedAspect = 85.60 / 53.98, frameRatio = 85.60 / 53.98, defaultOrientation = FrameOrientation.LANDSCAPE),
    BUSINESS_CARD("Business card", ScanFilter.AUTO, forcedAspect = 85.0 / 55.0, frameRatio = 85.0 / 55.0, defaultOrientation = FrameOrientation.LANDSCAPE),
}

/**
 * How the camera finds the page.
 * PORTRAIT / LANDSCAPE: a fixed guide frame; the user fits the page in it and edges are searched
 * only near the frame (reliable on any surface). FREE: find the page anywhere in view.
 */
enum class FrameOrientation { PORTRAIT, LANDSCAPE, FREE }
