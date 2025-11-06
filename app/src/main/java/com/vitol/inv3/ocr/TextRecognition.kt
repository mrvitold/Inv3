package com.vitol.inv3.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrBlock(val text: String)

class InvoiceTextRecognizer(
    private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(uri: Uri): Result<List<OcrBlock>> {
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return Result.failure(IllegalStateException("Cannot open image"))
            val srcBitmap = stream.use { BitmapFactory.decodeStream(it) }
            val bitmap = preprocess(srcBitmap)
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()
            val lines = visionText.textBlocks.flatMap { block -> block.lines.map { it.text } }
            Result.success(lines.filter { it.isNotBlank() }.map { OcrBlock(it) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun preprocess(src: Bitmap): Bitmap {
        // Lightweight contrast enhancement
        val contrast = 1.2f
        val brightness = -10f
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
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return ret
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it, onCancellation = null) }
        addOnFailureListener { cont.resumeWith(Result.failure(it)) }
    }

