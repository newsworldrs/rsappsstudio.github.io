package com.rskusum.scanner.data

import com.rskusum.scanner.vision.DocumentDetector
import com.rskusum.scanner.vision.ScanFilter

private val A4 = Math.sqrt(2.0) // 297 / 210

enum class ScanMode(
    @androidx.annotation.StringRes val labelRes: Int,
    val defaultFilter: ScanFilter,
    /** Output page ratio (long side / short side) to enforce, or null to measure it from the photo. */
    val forcedAspect: Double? = null,
    /** Orientation of the output page ([DocumentDetector.ORIENT_AUTO] = as photographed). */
    val pageOrientation: Int = DocumentDetector.ORIENT_AUTO,
    /** Split the flattened open book into two pages. */
    val splitBook: Boolean = false,
    /** Long side / short side of the on-screen guide frame. */
    val frameRatio: Double = A4,
    /** Orientation the guide frame starts in for this mode. */
    val defaultOrientation: FrameOrientation = FrameOrientation.PORTRAIT,
    /** AI Text: text areas are highlighted live and every capture is read with OCR. */
    val extractText: Boolean = false,
) {
    /** Whiteboards come out as A4 landscape pages. */
    WHITEBOARD(
        com.rskusum.scanner.R.string.rs_scanner_mode_whiteboard, ScanFilter.WHITEBOARD, forcedAspect = A4, pageOrientation = DocumentDetector.ORIENT_LANDSCAPE,
        frameRatio = A4, defaultOrientation = FrameOrientation.LANDSCAPE,
    ),
    /**
     * Open book = two A4 pages side by side (420 x 297 mm, ratio sqrt 2). The frame uses the phone's
     * long side (phone held across the book, spine as a horizontal line); every half becomes its
     * own A4 page.
     */
    BOOK(com.rskusum.scanner.R.string.rs_scanner_mode_book, ScanFilter.AUTO, forcedAspect = A4, splitBook = true, frameRatio = A4, defaultOrientation = FrameOrientation.PORTRAIT),
    /** Front/back cover of a book, notebook or folder, as an A4 page in its original colours. */
    BOOK_COVER(com.rskusum.scanner.R.string.rs_scanner_mode_book_cover, ScanFilter.ORIGINAL, forcedAspect = A4),
    DOCUMENT(com.rskusum.scanner.R.string.rs_scanner_mode_document, ScanFilter.AUTO, forcedAspect = A4),
    ID_CARD(com.rskusum.scanner.R.string.rs_scanner_mode_id_card, ScanFilter.AUTO, forcedAspect = 85.60 / 53.98, frameRatio = 85.60 / 53.98, defaultOrientation = FrameOrientation.LANDSCAPE),
    BUSINESS_CARD(com.rskusum.scanner.R.string.rs_scanner_mode_business_card, ScanFilter.AUTO, forcedAspect = 85.0 / 55.0, frameRatio = 85.0 / 55.0, defaultOrientation = FrameOrientation.LANDSCAPE),
    /**
     * AI Text: live text highlighting in the camera; each capture is flattened, read on the device
     * (Tesseract) and opened in the AI Text screen with the text, the document type and key
     * details (links, e-mails, phone numbers, dates, amounts, ...). Page keeps its real proportions.
     */
    AI_TEXT(com.rskusum.scanner.R.string.rs_scanner_mode_ai_text, ScanFilter.AUTO, extractText = true),
}

/**
 * How the camera finds the page.
 * PORTRAIT / LANDSCAPE: a fixed guide frame; the user fits the page in it and edges are searched
 * only near the frame (reliable on any surface). FREE: find the page anywhere in view.
 */
enum class FrameOrientation { PORTRAIT, LANDSCAPE, FREE }
