package app.receiptdroid.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.receiptdroid.core.TessLanguages
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Where Tesseract's language data is ([path], which holds a tessdata folder), and the languages to read in. */
internal class TessData(val path: String, val languages: String)

/**
 * Tesseract's language data: the files built into the app (its assets/tessdata), and the ones people add
 * from files on their phone. Tesseract reads them all from one folder, so they're kept together there,
 * with a list of which were added: an update of the app replaces the built-in ones but not those, and one
 * added in place of a built-in language (eg a more accurate eng) stays in its place. Which languages are
 * switched off is kept beside them. Nothing is ever downloaded: the app has no permission to.
 *
 * This is only files, so that it can be tested anywhere; [Languages] gives it the app's assets and Tesseract.
 *
 * @param builtIn the built-in languages' codes
 * @param openBuiltIn a built-in language's data
 * @param version changes when the app does, to put new built-in data in place
 * @param loads whether Tesseract can load a language's data from a folder holding a tessdata folder
 */
internal class TessFiles(
    private val root: File,
    private val builtIn: () -> Set<String>,
    private val openBuiltIn: (String) -> InputStream,
    private val version: () -> String,
    private val loads: (File, String) -> Boolean,
    private val maxBytes: Long = MAX_BYTES,
) {
    val path: File get() = root
    private val dir = File(root, TESSDATA)
    private val addedList = File(root, "added")
    private val offList = File(root, "off")
    private val versionFile = File(root, "installed")

    /** The languages to read in, with the data in place. */
    fun install(): List<String> {
        val available = installed()
        if (available.isEmpty()) throw IOException("No Tesseract language data: the app's assets/$TESSDATA is empty")
        return TessLanguages.inUse(available, read(offList))
    }

    fun list(): List<OcrLanguage> {
        val available = installed()
        val builtIn = builtIn()
        val added = read(addedList)
        val inUse = TessLanguages.inUse(available, read(offList)).toSet()
        return TessLanguages.ordered(available.filter { it != TessLanguages.DETECTION }).map { code ->
            OcrLanguage(code, TessLanguages.displayName(code), code in builtIn, code in added, code in inUse, fileOf(code).length())
        }
    }

    /** Add the language data called [name], read from [input]: tried first, and kept only if Tesseract loads it. */
    fun add(name: String, input: InputStream): String {
        val code = TessLanguages.codeOf(name)
            ?: throw IOException("\"$name\" isn't a Tesseract language file: choose one named like deu.traineddata, whose name is its language")
        if (code == TessLanguages.DETECTION) {
            throw IOException("osd.traineddata finds which way text runs; it doesn't read it, so Receiptdroid doesn't need it")
        }
        // Copied apart and tried first, so a file that won't load never takes the place of one that does.
        val staging = File(root, "staging")
        staging.deleteRecursively()
        try {
            val trial = File(File(staging, TESSDATA).apply { mkdirs() }, code + TessLanguages.SUFFIX)
            trial.outputStream().use { out -> copy(input, out, name) }
            if (!loads(staging, code)) {
                throw IOException("\"$name\" couldn't be loaded. It may be damaged, or made for an older Tesseract: use one from tessdata_fast or tessdata_best.")
            }
            dir.mkdirs()
            val target = fileOf(code)
            target.delete()
            if (!trial.renameTo(target)) throw IOException("\"$name\" couldn't be saved")
            write(addedList, read(addedList) + code)
            // Added to be used: switched on.
            write(offList, read(offList) - code)
            return code
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Remove a language that was added; the built-in one it was in place of comes back. */
    fun remove(code: String) {
        val added = read(addedList)
        if (code !in added) return
        fileOf(code).delete()
        write(addedList, added - code)
        if (code in builtIn()) copyBuiltIn(code)
    }

    /** Read in [code] or not; the last one on stays on. */
    fun use(code: String, on: Boolean) {
        val off = read(offList)
        val next = if (on) off - code else off + code
        val available = installed().filter { it != TessLanguages.DETECTION }
        if (available.any { it !in next }) write(offList, next)
    }

    /**
     * The built-in data copied into place when the app's new (or anything's missing), but not where one
     * was added in its place; and data left from older versions of the app taken away. The languages there.
     */
    private fun installed(): List<String> {
        dir.mkdirs()
        val builtIn = builtIn()
        val added = read(addedList).filter { fileOf(it).exists() }.toSet()
        val current = version()
        val stale = !versionFile.exists() || versionFile.readText() != current
        for (code in builtIn - added) if (stale || !fileOf(code).exists()) copyBuiltIn(code)
        if (stale) {
            dir.listFiles()?.forEach { f ->
                val code = TessLanguages.codeOf(f.name)
                if (code == null || (code !in builtIn && code !in added)) f.delete()
            }
            versionFile.writeText(current)
        }
        return (builtIn + added).filter { fileOf(it).exists() }
    }

    /** Copied beside, then renamed: never a half-copied file under the real name. */
    private fun copyBuiltIn(code: String) {
        val part = File(dir, code + TessLanguages.SUFFIX + ".part")
        openBuiltIn(code).use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(fileOf(code))) throw IOException("Couldn't install $code${TessLanguages.SUFFIX}")
    }

    /** Copy, giving up past [maxBytes]: nothing that large is language data, and it would fill the phone. */
    private fun copy(input: InputStream, out: java.io.OutputStream, name: String) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("\"$name\" is larger than ${maxBytes / 1_000_000} MB, too large to be a language file")
            out.write(buffer, 0, n)
        }
    }

    private fun fileOf(code: String) = File(dir, code + TessLanguages.SUFFIX)

    private fun read(list: File): Set<String> =
        if (list.exists()) list.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()

    private fun write(list: File, codes: Set<String>) {
        list.writeText(codes.sorted().joinToString("\n"))
    }

    companion object {
        const val TESSDATA = "tessdata"
        /** The largest language data Tesseract has is some 70 MB. */
        const val MAX_BYTES = 200_000_000L
    }
}

/** [TessFiles] with the app's assets and Tesseract, one change at a time and off the main thread. */
internal class Languages(private val context: Context) : OcrLanguages {
    private val files = TessFiles(
        root = File(context.filesDir, "tesseract"),
        builtIn = { context.assets.list(TessFiles.TESSDATA).orEmpty().mapNotNull { TessLanguages.codeOf(it) }.toSet() },
        openBuiltIn = { code -> context.assets.open("${TessFiles.TESSDATA}/$code${TessLanguages.SUFFIX}") },
        version = {
            @Suppress("DEPRECATION") // longVersionCode needs API 28; lastUpdateTime changes with every install.
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        },
        loads = ::loads,
    )
    // One change at a time, and none while the files are being put in place for a reading.
    private val lock = Mutex()

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** The data in place, and the languages to read in. */
    suspend fun install(): TessData = locked { TessData(files.path.absolutePath, files.install().joinToString("+")) }

    override suspend fun list(): List<OcrLanguage> = locked { files.list() }

    override suspend fun add(uri: Uri): OcrLanguage {
        val name = displayName(uri)
        val code = locked {
            try {
                context.contentResolver.openInputStream(uri)?.use { files.add(name, it) } ?: throw IOException("\"$name\" couldn't be opened")
            } catch (e: SecurityException) {
                throw IOException("\"$name\" couldn't be opened")
            }
        }
        return list().first { it.code == code }
    }

    override suspend fun remove(code: String) = locked { files.remove(code) }

    override suspend fun use(code: String, on: Boolean) = locked { files.use(code, on) }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "That file"
}

/** Whether Tesseract can load [code] from [path]'s tessdata, as it will to read receipts. */
private fun loads(path: File, code: String): Boolean {
    val api = TessBaseAPI()
    return try {
        api.init(path.absolutePath, code, TessBaseAPI.OEM_LSTM_ONLY)
    } catch (e: IllegalArgumentException) {
        false
    } finally {
        api.recycle()
    }
}
