package app.receiptdroid.ocr

import android.content.Context
import android.graphics.Bitmap
import app.receiptdroid.core.OcrLine
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/*
 * The OCR engine when `receipts.ocr=tesseract` (gradle.properties): Tesseract, through Tesseract4Android.
 * Open source throughout (Apache 2.0), with no Google code in the app. Its language data is in this folder's
 * assets/tessdata, inside the app: put another language's .traineddata there (from tesseract-ocr/tessdata_fast,
 * or tessdata_best for more accuracy at four times the size) and it's built in as well. People can also add
 * their own in Settings, from files on the phone (see Languages, in TessLanguageFiles.kt).
 */

/** This build's engine: Tesseract. */
fun createOcrEngine(context: Context): OcrEngine = TesseractEngine(context)

private class TesseractEngine(private val context: Context) : OcrEngine {
    override val name = "Tesseract"

    override val languages = Languages(context)

    override suspend fun read(photo: Bitmap): List<OcrLine> = recognise(photo, languages.install())

    /**
     * Read [photo] on a thread of its own, as Tesseract works: one call that runs until it's done.
     * Cancelling stops it once recognition has started; before that, it's not started.
     */
    private suspend fun recognise(photo: Bitmap, data: TessData): List<OcrLine> = suspendCancellableCoroutine { waiting ->
        val api = TessBaseAPI()
        val lock = Any()
        var working = true // Guarded by lock: the api may be stopped until it's recycled.
        waiting.invokeOnCancellation { synchronized(lock) { if (working) api.stop() } }
        thread(name = "Tesseract", isDaemon = true) {
            val result = runCatching { lines(api, photo, data) { waiting.isActive } }
            synchronized(lock) {
                working = false
                api.recycle()
            }
            // When the reading was cancelled, these are ignored.
            result.onSuccess { waiting.resume(it) }.onFailure { waiting.resumeWithException(it) }
        }
    }

    private fun lines(api: TessBaseAPI, photo: Bitmap, data: TessData, going: () -> Boolean): List<OcrLine> {
        if (!api.init(data.path, data.languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            throw IOException("Tesseract couldn't load its language data (${data.languages})")
        }
        // A receipt is short lines spread across the paper, often a label at the left and its amount at the
        // right: sparse text finds each of them wherever it is, and ignores the table around the receipt.
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        val argb = if (photo.config == Bitmap.Config.ARGB_8888) photo else photo.copy(Bitmap.Config.ARGB_8888, false)
        api.setImage(argb)

        // Small print loses its decimal points (13.10 comes out as 13 and 10). Tesseract reads best with lines
        // some 30 pixels tall: find how tall they are (layout only, which is quick) and enlarge if they're small.
        val scale = scaleFor(api, argb)
        val image = if (scale == 1f) argb
        else Bitmap.createScaledBitmap(argb, (argb.width * scale).roundToInt(), (argb.height * scale).roundToInt(), true)
        try {
            if (image !== argb) api.setImage(image)
            if (!going()) return emptyList()
            // Recognises, and unlike getUTF8Text can be stopped (see recognise).
            api.getHOCRText(0)
            if (!going()) return emptyList()
            return collect(api, scale)
        } finally {
            if (image !== argb) image.recycle()
            if (argb !== photo) argb.recycle()
        }
    }

    /** How much to enlarge the image by for its text lines to be about [LINE_HEIGHT] pixels tall: 1 or more. */
    private fun scaleFor(api: TessBaseAPI, image: Bitmap): Float {
        val textlines = api.textlines ?: return 1f
        val heights = try {
            textlines.boxRects.map { it.height() }.filter { it > 0 }.sorted()
        } finally {
            textlines.recycle()
        }
        if (heights.isEmpty()) return 1f
        val median = heights[heights.size / 2]
        if (median >= SMALL_LINE) return 1f
        val longest = maxOf(image.width, image.height)
        return (LINE_HEIGHT / median).coerceAtMost(LONGEST_SIDE / longest).coerceIn(1f, MAX_SCALE)
    }

    /** Each line read, back in the photo's pixels, leaving out what's likely the paper's grain or the table. */
    private fun collect(api: TessBaseAPI, scale: Float): List<OcrLine> {
        val it = api.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        val out = ArrayList<OcrLine>()
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(level)?.trim().orEmpty()
                if (text.none { c -> c.isLetterOrDigit() } || it.confidence(level) < MIN_CONFIDENCE) continue
                // Left, top, right, bottom.
                val box = it.getBoundingBox(level) ?: continue
                out.add(OcrLine(text, box[0] / scale, box[1] / scale, box[2] / scale, box[3] / scale))
            } while (it.next(level))
        } finally {
            it.delete()
        }
        return out
    }

    private companion object {
        /** Text lines shorter than this (in pixels) are enlarged… */
        const val SMALL_LINE = 22
        /** …to about this. */
        const val LINE_HEIGHT = 30f
        const val MAX_SCALE = 3f
        /** But not past this, for memory: an image this long takes some 40 MB. */
        const val LONGEST_SIDE = 3600f
        /** Lines Tesseract is less sure of than this (0–100) are left out. */
        const val MIN_CONFIDENCE = 30f
    }
}
