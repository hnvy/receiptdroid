package app.receiptdroid.ocr

import android.content.Context
import android.graphics.Bitmap
import app.receiptdroid.core.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * The OCR engine when `receipts.ocr=mlkit` (gradle.properties): Google's ML Kit text recognition, with its
 * Latin-script model inside the app, so it needs no download and no Google Play services. Its usage logging
 * would use the network, but the app has no permission to (see AndroidManifest.xml), so it can only fail.
 */

/** This build's engine: ML Kit. */
fun createOcrEngine(context: Context): OcrEngine = MlKitEngine

private object MlKitEngine : OcrEngine {
    override val name = "ML Kit"

    override suspend fun read(photo: Bitmap): List<OcrLine> = suspendCancellableCoroutine { waiting ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        // The photo is already upright, so no turning to tell it about.
        recognizer.process(InputImage.fromBitmap(photo, 0))
            .addOnSuccessListener { text ->
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val words = line.text.trim()
                    if (words.isEmpty()) null
                    else OcrLine(words, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                }
                if (waiting.isActive) waiting.resume(lines)
            }
            .addOnFailureListener { if (waiting.isActive) waiting.resumeWithException(it) }
            .addOnCompleteListener { recognizer.close() }
    }
}
