package com.vitol.inv3.ui.scan

import android.net.Uri

/**
 * Represents a single "page" in an import session.
 * - ImagePage: one image file = one invoice
 * - PdfPage: one page of a PDF = one invoice (pageIndex 0-based)
 */
sealed class ImportPage {
    data class ImagePage(val uri: Uri) : ImportPage()
    data class PdfPage(val pdfUri: Uri, val pageIndex: Int) : ImportPage()
}
