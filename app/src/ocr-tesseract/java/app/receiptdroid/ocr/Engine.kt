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

    override suspend fun read(photo: Bitmap): List<OcrLine> {
        val data = try {
            languages.install()
        } catch (e: IOException) {
            throw OcrException(e.message ?: "Tesseract's language data couldn't be put in place", e)
        }
        return recognise(photo, data)
    }

    /*
     * A note on Tesseract4Android's native calls, since a mistake with them doesn't throw: it ends the app
     * at once, with no error. Its JNI hands Tesseract's strings to Java unchecked, and Tesseract returns
     * null for no text (getUTF8Text on an iterator where there's no word; getHOCRText when recognition
     * was stopped), which aborts the app. And Tesseract asserts (aborting too) if an iterator's asked for
     * the text or confidence of words that were never recognised: what's left after layout analysis alone,
     * which getHOCRText then takes for a finished recognition. So below: recognition always on a fresh
     * image, through meanConfidence (which returns a number, and recognises whatever's been done before);
     * an iterator's text read only where its confidence says there's a word; and recognition never stopped.
     */

    /**
     * Read [photo] with Tesseract, on its own thread, one reading at a time: a reading given up can't be
     * stopped once it's running (see above), and two at once would need twice the memory. One given up
     * before its turn isn't started.
     */
    private suspend fun recognise(photo: Bitmap, data: TessData): List<OcrLine> = suspendCancellableCoroutine { waiting ->
        worker.execute {
            if (!waiting.isActive) return@execute
            val api = TessBaseAPI()
            val result = try {
                Result.success(lines(api, photo, data) { waiting.isActive })
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                api.recycle()
            }
            // When the reading was given up, these are ignored.
            result.onSuccess { waiting.resume(it) }.onFailure { waiting.resumeWithException(it) }
        }
    }

    private fun lines(api: TessBaseAPI, photo: Bitmap, data: TessData, going: () -> Boolean): List<OcrLine> {
        if (!api.init(data.path, data.languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            throw OcrException("Tesseract couldn't load its language data (${data.languages}). Check the languages in Settings.")
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
            // Set again even when it's the same picture: that clears the layout analysis, which recognition
            // would otherwise take as done, leaving words never recognised (see above).
            api.setImage(image)
            if (!going()) return emptyList()
            api.meanConfidence()
            if (!going()) return emptyList()
            return collect(api, scale)
        } finally {
            if (image !== argb) image.recycle()
            if (argb !== photo) argb.recycle()
        }
    }

    /** How much to enlarge the image by for its text lines to be about [LINE_HEIGHT] pixels tall: 1 or more. */
    private fun scaleFor(api: TessBaseAPI, image: Bitmap): Float {
        val textlines = api.textlines
        val heights = try {
            // No layout found (eg a blank photo): the Pixa wraps nothing, which must not be read.
            if (textlines.nativePixa == 0L) return 1f
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
                // Confidence first: it's 0 where there's no word, where the text would be null (see above).
                if (it.confidence(level) < MIN_CONFIDENCE) continue
                val text = it.getUTF8Text(level)?.trim().orEmpty()
                if (text.none { c -> c.isLetterOrDigit() }) continue
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
        /** Where Tesseract works: one reading at a time. */
        val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "Tesseract").apply { isDaemon = true }
        }
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
