package com.rskusum.scanner.data

import com.rskusum.scanner.vision.ScanFilter

enum class ScanMode(
    val label: String,
    val defaultFilter: ScanFilter,
    /** Physical width/height ratio to enforce, or null to measure it from the photo. */
    val forcedAspect: Double? = null,
    /** Split the flattened spread into left + right pages. */
    val splitBook: Boolean = false,
) {
    WHITEBOARD("Whiteboard", ScanFilter.WHITEBOARD),
    BOOK("Book", ScanFilter.AUTO, splitBook = true),
    DOCUMENT("Document", ScanFilter.AUTO),
    ID_CARD("ID card", ScanFilter.AUTO, forcedAspect = 85.60 / 53.98),
    BUSINESS_CARD("Business card", ScanFilter.AUTO, forcedAspect = 85.0 / 55.0),
}
