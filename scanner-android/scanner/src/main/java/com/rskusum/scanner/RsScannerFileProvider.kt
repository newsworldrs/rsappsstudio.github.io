package com.rskusum.scanner

import androidx.core.content.FileProvider

/**
 * The library's own FileProvider (authority `<applicationId>.rsscanner.fileprovider`), so it never
 * clashes with a FileProvider the host app declares itself.
 */
class RsScannerFileProvider : FileProvider()
