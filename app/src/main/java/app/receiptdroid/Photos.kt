package app.receiptdroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException

/** Opening a photo of a receipt the right way up, and not too large. */
object Photos {
    // A photo is opened at half size, a quarter… while its longer side stays at least this many pixels:
    // plenty to read small print by, without holding a whole 50-megapixel picture in memory.
    private const val LONG_SIDE_AT_LEAST = 1600

    /** The picture at [uri], scaled down if it's very large and turned the way it was taken. */
    @Throws(IOException::class)
    fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("it isn't a picture this phone can open")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE_AT_LEAST) sample *= 2
        // 8 bits a channel, as both OCR engines take it (a 16-bit PNG would otherwise come out as floats).
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("it isn't a picture this phone can open")

        val turn = turn(context, uri)
        if (turn.isIdentity) return bitmap
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    /** How to turn the stored picture to show it as taken: cameras often store it sideways and say so. */
    private fun turn(context: Context, uri: Uri): Matrix {
        val matrix = Matrix()
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: IOException) {
            null
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        return matrix
    }
}
