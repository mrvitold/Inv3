package com.vitol.inv3.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import timber.log.Timber

data class OcrBlock(
    val text: String,
    val boundingBox: Rect? = null  // Bounding box in image coordinates
)

class InvoiceTextRecognizer(
    private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(uri: Uri): Result<List<OcrBlock>> {
        return try {
            // First, get image dimensions to ensure we load full resolution
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            // Calculate sample size to ensure good resolution for OCR (ML Kit works best with 1000-2000px)
            val maxDimension = 2000
            val sampleSize = when {
                options.outWidth > maxDimension || options.outHeight > maxDimension -> {
                    val widthRatio = options.outWidth / maxDimension
                    val heightRatio = options.outHeight / maxDimension
                    maxOf(widthRatio, heightRatio)
                }
                else -> 1
            }
            
            Timber.d("OCR: Image dimensions ${options.outWidth}x${options.outHeight}, using sampleSize=$sampleSize")
            
            // Decode with calculated sample size
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Faster decoding, still good quality
            }
            
            val srcBitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return Result.failure(IllegalStateException("Cannot decode image"))
            
            Timber.d("OCR: Decoded bitmap size ${srcBitmap.width}x${srcBitmap.height}")
            
            val bitmap = preprocess(srcBitmap)
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()
            
            // Extract text with bounding boxes
            val blocks = visionText.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    line.elements.map { element ->
                        OcrBlock(
                            text = element.text,
                            boundingBox = element.boundingBox
                        )
                    }
                }
            }
            
            Timber.d("OCR: Extracted ${blocks.size} text blocks")
            if (blocks.isNotEmpty()) {
                Timber.d("OCR: First 10 blocks: ${blocks.take(10).map { it.text }}")
            }
            
            Result.success(blocks.filter { it.text.isNotBlank() })
        } catch (e: Exception) {
            Timber.e(e, "OCR recognition failed")
            Result.failure(e)
        }
    }

    private fun preprocess(src: Bitmap): Bitmap {
        // Enhanced contrast and brightness for better OCR
        val contrast = 1.5f  // Increased from 1.2f
        val brightness = 0f  // Changed from -10f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val ret = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(ret)
        val paint = Paint().apply { 
            colorFilter = ColorMatrixColorFilter(cm)
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return ret
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it, onCancellation = null) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }

