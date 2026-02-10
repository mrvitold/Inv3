package com.vitol.inv3.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Copies document-picker image URIs into app cache and returns FileProvider URIs.
 * This avoids SecurityException when the picker's permission is not persistable
 * and the URI is read later (e.g. in AzureDocumentIntelligenceService).
 */
object ImportImageCache {

    private const val CACHE_SUBDIR = "import_images"

    /**
     * Copies the content at [uri] into the app cache and returns a content URI
     * for that file. Call when building import pages from picker URIs so
     * downstream code always reads from app-owned storage.
     */
    fun copyToCache(context: Context, uri: Uri): Uri {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val mime = context.contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
        val ext = when {
            mime.contains("png") -> ".png"
            mime.contains("webp") -> ".webp"
            mime.contains("bmp") -> ".bmp"
            else -> ".jpg"
        }
        val file = File(cacheDir, "img_${UUID.randomUUID()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not read from URI: $uri")
        Timber.d("ImportImageCache: copied to ${file.absolutePath}")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Deletes cached import images. Call when import session is cleared.
     */
    fun clearCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to clear import image cache")
        }
    }
}
