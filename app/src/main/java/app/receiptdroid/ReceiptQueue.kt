package app.receiptdroid

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.receiptdroid.core.TextReceipt
import java.io.File
import java.io.IOException

/** What a waiting receipt is: a photo, a PDF (eg an email's attachment), or text (an email's body). */
enum class Kind(val extension: String) {
    PHOTO("photo"), PDF("pdf"), TEXT("txt");

    companion object {
        fun of(mime: String?): Kind? = when {
            mime == null -> null
            mime.startsWith("image/") -> PHOTO
            mime == "application/pdf" -> PDF
            mime.startsWith("text/") -> TEXT
            else -> null
        }
    }
}

/** A receipt waiting to be read and sent, kept as [file] until then. */
data class Queued(val id: String, val kind: Kind, val file: File)

/**
 * The receipts waiting, oldest first, as files in the app's own storage: what was taken, chosen, shared or
 * pasted is copied here, so it's still there if the phone closes the app while you're in Ledgedroid. Each
 * is deleted once it's sent or discarded. Private to the app, never backed up, and never sent anywhere.
 */
class ReceiptQueue(private val context: Context) {
    private val dir = File(context.filesDir, "queue").apply { mkdirs() }
    private var last = 0L

    /** The receipts waiting, oldest first. A photo the camera hasn't saved yet isn't one. */
    fun list(): List<Queued> {
        val files = dir.listFiles().orEmpty()
        val now = System.currentTimeMillis()
        // A camera that was given a file and never saved to it: cleared after a day.
        files.filter { it.length() == 0L && now - it.lastModified() > DAY }.forEach { it.delete() }
        return files.filter { it.length() > 0 }.mapNotNull { f ->
            val kind = Kind.entries.firstOrNull { it.extension == f.extension } ?: return@mapNotNull null
            Queued(f.nameWithoutExtension, kind, f)
        }.sortedBy { it.id }
    }

    /** A new, empty file for the camera to save a photo in, and the address to give the camera. */
    fun forCamera(): Pair<Queued, Uri> {
        val q = new(Kind.PHOTO)
        q.file.createNewFile()
        return q to FileProvider.getUriForFile(context, context.packageName + ".files", q.file)
    }

    /** Copy what's at [uri] in as a receipt, of the kind its type says; null if it's no kind of receipt. */
    @Throws(IOException::class)
    fun add(uri: Uri, mime: String?): Queued? {
        // What it was shared as, else what its provider or its name says: a share of several says only */*.
        val type = sequenceOf({ mime }, { context.contentResolver.getType(uri) }, { guessType(uri) })
            .map { it() }.firstOrNull { Kind.of(it) != null } ?: return null
        val kind = Kind.of(type) ?: return null
        if (kind == Kind.TEXT) {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
            return addText(null, if (type == "text/html") TextReceipt.fromHtml(text) else text)
        }
        val q = new(kind)
        val part = File(dir, q.file.name + ".part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out ->
                    val copied = input.copyTo(out)
                    if (copied > MAX_BYTES) throw IOException("it's larger than ${MAX_BYTES / 1_000_000} MB")
                }
            } ?: throw IOException("it couldn't be opened")
            if (!part.renameTo(q.file)) throw IOException("it couldn't be saved")
        } finally {
            part.delete()
        }
        return q
    }

    /** Add a receipt that's text: an email's [subject] (on a line of its own) and [body]. Null if there's nothing in it. */
    fun addText(subject: String?, body: String): Queued? {
        val text = listOfNotNull(subject?.let { TextReceipt.subject(it) }?.takeIf { it.isNotEmpty() }, body.trim()).joinToString("\n\n")
        if (text.none { it.isLetterOrDigit() }) return null
        val q = new(Kind.TEXT)
        val part = File(dir, q.file.name + ".part")
        part.writeText(text)
        part.renameTo(q.file)
        return q
    }

    fun remove(q: Queued) {
        q.file.delete()
    }

    fun removeAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Ids in the order they're made, so the queue's order is the order receipts came. */
    private fun new(kind: Kind): Queued = synchronized(this) {
        var t = System.currentTimeMillis()
        if (t <= last) t = last + 1
        last = t
        val id = "%013d".format(t)
        Queued(id, kind, File(dir, "$id.${kind.extension}"))
    }

    private fun guessType(uri: Uri): String? {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: return null
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg", "png", "webp", "heic", "heif" -> "image/*"
            "txt", "eml" -> "text/plain"
            "htm", "html" -> "text/html"
            else -> null
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val MAX_BYTES = 50_000_000L
    }
}
