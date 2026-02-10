package com.vitol.inv3.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Resolves PDF URIs to page count and renders individual PDF pages to image files
 * so they can be sent to Azure Document Intelligence (one page = one invoice).
 */
object PdfPageResolver {

    private const val CACHE_SUBDIR = "pdf_pages"
    /** PNG for PDF pages: no JPEG artifacts, sharper text for OCR. Quality 100 for PNG (ignored but kept for any JPEG path). */
    private const val IMAGE_QUALITY = 100
    /** Allow higher resolution so small receipt/invoice text is readable by Azure OCR (~200 DPI equivalent for A4). */
    private const val MAX_BITMAP_DIMENSION = 3500
    /** Minimum long side for OCR. Dense receipts need ~200+ DPI; 72-DPI A4 ≈ 595×842 → scale to 2520 on long side. */
    private const val MIN_LONG_SIDE_FOR_OCR = 2520

    /**
     * Returns the number of pages in the PDF at the given URI.
     * Returns 0 if the URI is not a PDF or cannot be opened.
     */
    suspend fun getPageCount(context: Context, pdfUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(pdfUri, "r")?.use { pfd ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    PdfRenderer(pfd).use { renderer ->
                        renderer.pageCount
                    }
                } else {
                    0
                }
            } ?: 0
        } catch (e: Exception) {
            Timber.e(e, "Failed to get PDF page count: $pdfUri")
            0
        }
    }

    /**
     * Renders the given PDF page to a temporary JPEG file in the app cache
     * and returns a content URI for that file (for use with Azure and ContentResolver).
     * Uses a stable filename (hash of uri + pageIndex) so the same page can be reused.
     */
    suspend fun renderPageToUri(context: Context, pdfUri: Uri, pageIndex: Int): Uri =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            val stableKey = "${pdfUri.toString()}_$pageIndex"
            val hash = MessageDigest.getInstance("SHA-256").digest(stableKey.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(16)
            val file = File(cacheDir, "page_${hash}.png")

            context.contentResolver.openFileDescriptor(pdfUri, "r")?.use { pfd ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    throw UnsupportedOperationException("PDF rendering requires API 21+")
                }
                val renderer = PdfRenderer(pfd)
                try {
                    val page = renderer.openPage(pageIndex)
                    try {
                        val width = page.width
                        val height = page.height
                        val maxDim = maxOf(width, height)
                        val scaleDown = if (maxDim > MAX_BITMAP_DIMENSION) {
                            MAX_BITMAP_DIMENSION.toFloat() / maxDim
                        } else {
                            1f
                        }
                        val scaleUp = if (maxDim < MIN_LONG_SIDE_FOR_OCR) {
                            MIN_LONG_SIDE_FOR_OCR.toFloat() / maxDim
                        } else {
                            1f
                        }
                        val scale = scaleDown * scaleUp
                        val bmWidth = (width * scale).toInt().coerceIn(1, MAX_BITMAP_DIMENSION)
                        val bmHeight = (height * scale).toInt().coerceIn(1, MAX_BITMAP_DIMENSION)
                        val bitmap = Bitmap.createBitmap(bmWidth, bmHeight, Bitmap.Config.ARGB_8888)
                        @Suppress("DEPRECATION")
                        val renderMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                        } else {
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        }
                        page.render(bitmap, null, null, renderMode)
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        bitmap.recycle()
                        Timber.d("PdfPageResolver: rendered page $pageIndex at ${bmWidth}x$bmHeight (PNG) for OCR")
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                }
            } ?: throw IllegalStateException("Could not open PDF: $pdfUri")

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    /**
     * Deletes temporary PDF page images in the cache subdir.
     * Call when import session is cleared to free space.
     */
    fun clearCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to clear PDF page cache")
        }
    }
}
