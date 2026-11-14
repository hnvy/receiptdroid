package app.receiptdroid

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.receiptdroid.core.OcrLine
import app.receiptdroid.core.TextReceipt
import app.receiptdroid.ocr.ocrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A receipt that's been read: the lines of text on it, and the picture they're on (a photo, or a PDF's
 * pages), in its pixels. A receipt that came as text has no picture: its lines are one under another.
 */
class ReadReceipt(val picture: Bitmap?, val lines: List<OcrLine>) {
    companion object {
        /** Read [q] with this build's OCR engine, or as text. */
        suspend fun of(context: Context, q: Queued): ReadReceipt = when (q.kind) {
            Kind.PHOTO -> picture(context, withContext(Dispatchers.IO) { Photos.load(context, Uri.fromFile(q.file)) })
            Kind.PDF -> picture(context, withContext(Dispatchers.IO) { PdfPages.render(q.file) })
            Kind.TEXT -> withContext(Dispatchers.IO) {
                // The subject, if there was one, is the first line (see ReceiptQueue.addText).
                ReadReceipt(null, TextReceipt.asOcr(TextReceipt.lines(null, q.file.readText())))
            }
        }

        private suspend fun picture(context: Context, picture: Bitmap) = ReadReceipt(picture, ocrEngine(context).read(picture))
    }
}
