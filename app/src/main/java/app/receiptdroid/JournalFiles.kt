package app.receiptdroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.receiptdroid.core.JournalNames
import java.io.IOException

/**
 * Your journal files, read for names to suggest. Only read, through the permission you give when choosing
 * them: never written, never copied. What's read is kept in memory only, while the app is open.
 */
object JournalFiles {
    /** Keep the permission to read [uris] after the app is closed. */
    fun keep(context: Context, uris: List<Uri>) {
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // A provider that doesn't keep permissions: readable until the app is closed.
            }
        }
    }

    /** Give up the permission to read [uris]. */
    fun release(context: Context, uris: List<Uri>) {
        for (uri in uris) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Not kept in the first place.
            }
        }
    }

    /** The names in [uris]' journal. Throws if a file can't be read (eg it was moved or deleted). */
    @Throws(IOException::class)
    fun read(context: Context, uris: List<Uri>): JournalNames {
        val texts = uris.map { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IOException("${name(context, uri)} couldn't be opened")
            } catch (e: SecurityException) {
                throw IOException("${name(context, uri)} can no longer be read: choose it again")
            }
        }
        return JournalNames.read(texts)
    }

    /** A file's name, to show. */
    fun name(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "journal"
}
