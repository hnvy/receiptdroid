package app.receiptdroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * A PDF receipt, eg an email's attachment, as one picture to read: its first pages one under another,
 * drawn by Android itself on the phone. The OCR engine reads it like a photo; a PDF is sharp, so it reads well.
 */
object PdfPages {
    /** A receipt's total is on its first pages. */
    private const val MAX_PAGES = 3
    /** 200 dots an inch: small print comes out some 30 pixels a line, as the OCR engines like. */
    private const val SCALE = 200f / 72f
    /** At most this many pixels in all (some 50 MB), for memory: the scale comes down for long PDFs. */
    private const val MAX_PIXELS = 12_000_000f

    @Throws(IOException::class)
    fun render(file: File): Bitmap {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: SecurityException) {
            fd.close()
            throw IOException("the PDF is protected by a password")
        } catch (e: IOException) {
            fd.close()
            throw IOException("it isn't a PDF this phone can open")
        }
        return renderer.use { pdf ->
            val count = minOf(pdf.pageCount, MAX_PAGES)
            if (count == 0) throw IOException("the PDF has no pages")
            val sizes = (0 until count).map { i -> pdf.openPage(i).use { it.width to it.height } }
            val width = sizes.maxOf { it.first }.toFloat()
            val height = sizes.sumOf { it.second }.toFloat()
            val scale = minOf(SCALE, kotlin.math.sqrt(MAX_PIXELS / (width * height)))
            val out = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            val canvas = Canvas(out)
            var top = 0
            for (i in 0 until count) {
                pdf.openPage(i).use { page ->
                    val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                    // Pages are drawn on transparent: white beneath, as on paper.
                    val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    sheet.eraseColor(Color.WHITE)
                    page.render(sheet, null, Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    canvas.drawBitmap(sheet, 0f, top.toFloat(), null)
                    sheet.recycle()
                    top += h
                }
            }
            out
        }
    }
}
