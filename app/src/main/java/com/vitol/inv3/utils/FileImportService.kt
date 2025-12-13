package com.vitol.inv3.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

class FileImportService(private val context: Context) {
    
    companion object {
        private const val MAX_FILE_SIZE_MB = 15
        private const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024
    }

    /**
     * Validates file size (must be <= 15 MB)
     */
    suspend fun validateFileSize(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val size = getFileSize(uri)
            if (size > MAX_FILE_SIZE_BYTES) {
                Result.failure(
                    IllegalArgumentException(
                        "File size (${formatFileSize(size)}) exceeds maximum allowed size of ${MAX_FILE_SIZE_MB} MB"
                    )
                )
            } else {
                Result.success(size)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate file size")
            Result.failure(e)
        }
    }

    /**
     * Gets file size in bytes
     */
    private suspend fun getFileSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize
        } ?: throw IllegalStateException("Cannot get file size")
    }

    /**
     * Formats file size for display
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /**
     * Determines MIME type from URI
     */
    fun getMimeType(uri: Uri): String? {
        return context.contentResolver.getType(uri)
    }

    /**
     * Checks if file is a PDF
     */
    fun isPdf(uri: Uri): Boolean {
        val mimeType = getMimeType(uri)
        return mimeType == "application/pdf" || 
               uri.path?.endsWith(".pdf", ignoreCase = true) == true
    }

    /**
     * Checks if file is an image
     */
    fun isImage(uri: Uri): Boolean {
        val mimeType = getMimeType(uri)
        return mimeType?.startsWith("image/") == true
    }

    /**
     * Checks if file is HEIC/HEIF format
     */
    fun isHeic(uri: Uri): Boolean {
        val mimeType = getMimeType(uri)
        return mimeType == "image/heic" || 
               mimeType == "image/heif" ||
               uri.path?.endsWith(".heic", ignoreCase = true) == true ||
               uri.path?.endsWith(".heif", ignoreCase = true) == true
    }
    
    /**
     * Gets the display name (filename) from a URI
     * Works with document tree URIs, content URIs, and file URIs
     */
    fun getDisplayName(uri: Uri): String? {
        return try {
            // Try DocumentsContract first (for document tree URIs)
            if (DocumentsContract.isDocumentUri(context, uri)) {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) {
                        cursor.getString(0)
                    } else null
                }
            } else {
                // Try OpenableColumns for regular content URIs
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) {
                        cursor.getString(0)
                    } else null
                } ?: uri.lastPathSegment // Fallback to last path segment
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to get display name for URI: $uri")
            uri.lastPathSegment // Fallback to last path segment
        }
    }

    /**
     * Extracts all pages from a PDF and converts them to image URIs
     * Returns list of URIs for each page
     */
    suspend fun extractPdfPages(uri: Uri): Result<List<Uri>> = withContext(Dispatchers.IO) {
        try {
            val pages = mutableListOf<Uri>()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext Result.failure(IllegalStateException("Cannot open PDF file"))

            pfd.use { fileDescriptor ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val renderer = PdfRenderer(fileDescriptor)
                    
                    try {
                        val pageCount = renderer.pageCount
                        Timber.d("PDF has $pageCount pages")
                        
                        if (pageCount == 0) {
                            return@withContext Result.failure(IllegalStateException("PDF has no pages"))
                        }

                        val outputDir = File(context.cacheDir, "pdf_pages").apply { mkdirs() }
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

                        for (i in 0 until pageCount) {
                            val page = renderer.openPage(i)
                            try {
                                // Render page to bitmap (use reasonable resolution for OCR)
                                val width = page.width * 2 // 2x for better OCR quality
                                val height = page.height * 2
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                // Save bitmap to file
                                val pageFile = File(outputDir, "pdf_page_${timestamp}_${i + 1}.jpg")
                                FileOutputStream(pageFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                bitmap.recycle()

                                // Create URI using FileProvider
                                val pageUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    pageFile
                                )
                                pages.add(pageUri)
                                Timber.d("Extracted page ${i + 1}/$pageCount")
                            } finally {
                                page.close()
                            }
                        }
                    } finally {
                        renderer.close()
                    }
                } else {
                    return@withContext Result.failure(UnsupportedOperationException("PDF extraction requires Android 5.0+"))
                }
            }

            if (pages.isEmpty()) {
                Result.failure(IllegalStateException("No pages extracted from PDF"))
            } else {
                Result.success(pages)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract PDF pages")
            Result.failure(e)
        }
    }

    /**
     * Converts HEIC/HEIF image to JPEG format
     * Android 9+ (API 28) has built-in HEIC support, but we'll convert to JPEG for consistency
     */
    suspend fun convertHeicToJpeg(uri: Uri): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            // Read HEIC image
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                return@withContext Result.failure(IllegalStateException("Cannot open HEIC file"))
            }

            inputStream.use { stream ->
                // Decode HEIC to bitmap (Android 9+ supports HEIC natively)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeStream(stream, null, options)
                    ?: return@withContext Result.failure(IllegalStateException("Cannot decode HEIC image"))

                // Save as JPEG
                val outputDir = File(context.cacheDir, "converted_images").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
                val jpegFile = File(outputDir, "heic_converted_${timestamp}.jpg")
                
                FileOutputStream(jpegFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                bitmap.recycle()

                // Create URI using FileProvider
                val jpegUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    jpegFile
                )
                
                Timber.d("Converted HEIC to JPEG: ${jpegFile.name}")
                Result.success(jpegUri)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert HEIC to JPEG")
            Result.failure(e)
        }
    }

    /**
     * Processes a file and returns list of image URIs ready for OCR
     * - If PDF: extracts all pages
     * - If HEIC: converts to JPEG
     * - If other image: returns as-is
     */
    suspend fun processFile(uri: Uri): Result<List<Uri>> = withContext(Dispatchers.IO) {
        try {
            // Validate file size first
            validateFileSize(uri).getOrElse { error ->
                return@withContext Result.failure(error)
            }

            when {
                isPdf(uri) -> {
                    Timber.d("Processing PDF file")
                    extractPdfPages(uri)
                }
                isHeic(uri) -> {
                    Timber.d("Processing HEIC file")
                    convertHeicToJpeg(uri).map { listOf(it) }
                }
                isImage(uri) -> {
                    Timber.d("Processing image file")
                    Result.success(listOf(uri))
                }
                else -> {
                    Result.failure(IllegalArgumentException("Unsupported file type. Please select an image or PDF file."))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process file")
            Result.failure(e)
        }
    }
    
    /**
     * Recursively scans a folder (via DocumentTree URI) for invoice files (PDFs and images)
     * Returns list of URIs for all invoice files found, sorted alphabetically by display name
     */
    suspend fun scanFolderForInvoices(treeUri: Uri): Result<List<Uri>> = withContext(Dispatchers.IO) {
        try {
            // Store URI and display name pairs for proper sorting
            val invoiceFilesWithNames = mutableListOf<Pair<Uri, String>>()
            val contentResolver = context.contentResolver
            
            // Take persistable URI permission
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                Timber.w(e, "Could not take persistable URI permission (may already be granted)")
            }
            
            // Recursively scan the folder
            scanDirectoryRecursive(treeUri, treeUri, contentResolver, invoiceFilesWithNames)
            
            // Log files BEFORE sorting
            Timber.d("=== FILES BEFORE SORTING ===")
            Timber.d("Found ${invoiceFilesWithNames.size} invoice file(s) in folder")
            invoiceFilesWithNames.take(10).forEachIndexed { idx, (_, name) ->
                val lowerName = name.lowercase()
                Timber.d("  [$idx] '$name' (lowercase: '$lowerName')")
            }
            
            // Sort files alphabetically by display name (case-insensitive)
            invoiceFilesWithNames.sortBy { (_, displayName) ->
                displayName.lowercase()
            }
            
            // Log files AFTER sorting
            Timber.d("=== FILES AFTER SORTING (alphabetically by display name) ===")
            Timber.d("First 10 files in sorted order:")
            invoiceFilesWithNames.take(10).forEachIndexed { idx, (_, name) ->
                val lowerName = name.lowercase()
                Timber.d("  [$idx] '$name' (lowercase: '$lowerName')")
            }
            
            // Extract just the URIs after sorting
            val invoiceFiles = invoiceFilesWithNames.map { it.first }
            
            // Verify first file
            if (invoiceFiles.isNotEmpty()) {
                val firstUri = invoiceFiles[0]
                val firstName = invoiceFilesWithNames[0].second
                Timber.d("First file in sorted list: '$firstName' (URI: ${firstUri.lastPathSegment})")
            }
            
            Result.success(invoiceFiles)
        } catch (e: Exception) {
            Timber.e(e, "Failed to scan folder for invoices")
            Result.failure(e)
        }
    }
    
    /**
     * Recursively scans a directory for invoice files
     * Stores URI and display name pairs for proper sorting
     */
    private fun scanDirectoryRecursive(
        treeUri: Uri,
        directoryUri: Uri,
        contentResolver: ContentResolver,
        invoiceFiles: MutableList<Pair<Uri, String>>
    ) {
        try {
            // Get document ID from the directory URI
            // If it's a tree URI, extract the document ID; if it's a document URI, use it directly
            val documentId = if (DocumentsContract.isTreeUri(directoryUri)) {
                DocumentsContract.getTreeDocumentId(directoryUri)
            } else {
                DocumentsContract.getDocumentId(directoryUri)
            }
            
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            
            val cursor: Cursor? = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    val childDocumentId = it.getString(0)
                    val mimeType = it.getString(1)
                    val displayName = it.getString(2)
                    
                    val childDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId)
                    
                    // Check if it's a directory
                    if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                        // Recursively scan subdirectory
                        scanDirectoryRecursive(treeUri, childDocumentUri, contentResolver, invoiceFiles)
                    } else {
                        // Check if it's an invoice file (PDF or image)
                        if (isPdf(childDocumentUri) || isImage(childDocumentUri)) {
                            // Try to take persistable URI permission on the document URI
                            // This ensures we can read the file later
                            try {
                                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                // For document URIs from a tree, we need to use the tree URI
                                // But we can't take persistable permission on individual documents
                                // Instead, we'll rely on the tree permission and ensure we can read it
                                // Test if we can read the file
                                contentResolver.openInputStream(childDocumentUri)?.use {
                                    // File is readable, add it with display name for sorting
                                    invoiceFiles.add(Pair(childDocumentUri, displayName ?: ""))
                                    Timber.d("Found invoice file: $displayName")
                                } ?: run {
                                    Timber.w("Cannot read file: $displayName, skipping")
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Error checking file access: $displayName")
                                // Still try to add it - permission might work later
                                invoiceFiles.add(Pair(childDocumentUri, displayName ?: ""))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error scanning directory: $directoryUri")
        }
    }
}

