package app.receiptdroid.ocr

import android.content.Context
import android.graphics.Bitmap
import app.receiptdroid.core.OcrLine

/**
 * What reads the text on a receipt's photo. There are two, and a build has exactly one of them:
 *
 *  - ML Kit, in `src/ocr-mlkit`: Google's recogniser, the more accurate on photos.
 *  - Tesseract, in `src/ocr-tesseract`: open source throughout.
 *
 * Which one is set by `receipts.ocr` in `gradle.properties`, the one place to change. The build then
 * compiles that engine's folder and adds its library, and leaves the other out of the app entirely. Each
 * folder has an `Engine.kt` with [createOcrEngine]; nothing outside these files knows which it is.
 *
 * Either works on the phone, with no connection: the app has no permission to use the network.
 */
interface OcrEngine {
    /** Its name, to show in the app. */
    val name: String

    /**
     * The lines of text on [photo], which is upright, in its pixels. A line may be a whole printed line or
     * part of one: a label and its amount at the far side may come apart, which the reading copes with.
     * In any order; empty when there's no text. Cancelling the call stops the work, as far as the engine can.
     */
    suspend fun read(photo: Bitmap): List<OcrLine>
}

/** The engine this build has (from the folder `receipts.ocr` picks), for [context]'s app. */
private var engine: OcrEngine? = null

fun ocrEngine(context: Context): OcrEngine = synchronized(OcrEngine::class) {
    engine?.let { return it }
    // From src/ocr-<engine>/…/Engine.kt, whichever the build compiled.
    val made: OcrEngine = createOcrEngine(context.applicationContext)
    engine = made
    made
}
