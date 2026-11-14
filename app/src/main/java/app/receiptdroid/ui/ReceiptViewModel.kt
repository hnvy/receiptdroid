package app.receiptdroid.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.receiptdroid.JournalFiles
import app.receiptdroid.Kind
import app.receiptdroid.Queued
import app.receiptdroid.ReadReceipt
import app.receiptdroid.ReceiptQueue
import app.receiptdroid.core.Amounts
import app.receiptdroid.core.JournalNames
import app.receiptdroid.core.OcrLine
import app.receiptdroid.core.Part
import app.receiptdroid.core.Payees
import app.receiptdroid.core.Reading
import app.receiptdroid.core.ReadingStyle
import app.receiptdroid.core.ReceiptEntry
import app.receiptdroid.core.ShareText
import app.receiptdroid.core.TextReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/** Where the app is: at the start, reading a receipt, or showing one to pick the entry's parts from. */
sealed interface Stage {
    data object Start : Stage
    data class Busy(val current: Queued) : Stage

    /**
     * [picture] is the photo or PDF the [lines] are on; null for a receipt that came as text. [slope] is
     * how the rows run across it (see [Reading.slope]), for matching labels to amounts.
     */
    class Ready(val current: Queued, val picture: ImageBitmap?, val lines: List<OcrLine>, val slope: Float) : Stage
}

/**
 * The entry being put together: its parts as they stand in their boxes, the line of the receipt each was
 * taken from ([marks]), the part the next tap is for ([target]), and the payee as printed ([payeeRead]),
 * when it came from the receipt, to offer back and to learn from.
 */
data class Form(
    val entry: ReceiptEntry = ReceiptEntry(),
    val marks: Map<Part, Int> = emptyMap(),
    val target: Part = Part.PAYEE,
    val payeeRead: String? = null,
)

/** Something to tell the person once; with [resend], the text of an entry that can be sent again. */
data class Message(val text: String, val resend: String? = null)

/** Suggesting from your journal: whether it's on, which files, and what was read from them. */
data class JournalState(
    val on: Boolean = false,
    val files: List<String> = emptyList(),
    val names: JournalNames = JournalNames(),
    val reading: Boolean = false,
    val problem: String? = null,
)

class ReceiptViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("receipts", Context.MODE_PRIVATE)
    private val queue = ReceiptQueue(app)

    private val _stage = MutableStateFlow<Stage>(Stage.Start)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    /** The receipts waiting, the one being read among them, oldest first. */
    private val _waiting = MutableStateFlow(queue.list())
    val waiting: StateFlow<List<Queued>> = _waiting.asStateFlow()

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()
    /** Said once you're back from Ledgedroid: shown now, it would time out unseen while you're there. */
    private var onReturn: Message? = null

    /** The accounts sent lately, the latest first. */
    private val _accounts = MutableStateFlow(prefs.getString(KEY_ACCOUNTS, "").orEmpty().split('\n').filter { it.isNotBlank() })
    val accounts: StateFlow<List<String>> = _accounts.asStateFlow()

    /** Whether the person has said Ledgedroid has the Share pattern; until then, sending asks first. */
    private val _setUp = MutableStateFlow(prefs.getBoolean(KEY_SET_UP, false))
    val setUp: StateFlow<Boolean> = _setUp.asStateFlow()

    /** Whether payee names are tidied, and the names learned from your corrections. */
    private val _tidy = MutableStateFlow(prefs.getBoolean(KEY_TIDY, true))
    val tidy: StateFlow<Boolean> = _tidy.asStateFlow()
    private val _learned = MutableStateFlow(Payees.decode(prefs.getString(KEY_LEARNED, "").orEmpty()))
    val learned: StateFlow<Map<String, String>> = _learned.asStateFlow()

    private val _journal = MutableStateFlow(JournalState(on = prefs.getBoolean(KEY_JOURNAL, false)))
    val journal: StateFlow<JournalState> = _journal.asStateFlow()
    private var journalUris: List<Uri> = prefs.getString(KEY_JOURNAL_URIS, "").orEmpty().split('\n').filter { it.isNotBlank() }.map(Uri::parse)
    private var journalReadAt = 0L

    private var reading: Job? = null
    /** The next receipt, read ahead while you look at this one. */
    private var ahead: Pair<String, Deferred<ReadReceipt>>? = null

    init {
        _journal.update { it.copy(files = journalUris.map { u -> JournalFiles.name(app, u) }) }
        refreshJournal(force = true)
    }

    // ------------------------------------------------------------------------------------- adding receipts

    /** Add what's at [uris] (photos, PDFs, text files), and open the first of them if nothing's open. */
    fun add(uris: List<Uri>, mime: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val failed = ArrayList<String>()
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        queue.add(uri, mime?.takeIf { uris.size == 1 }).also { if (it == null) failed.add("it isn't a photo, PDF or text") }
                    } catch (e: IOException) {
                        failed.add(e.message ?: "it couldn't be read")
                        null
                    } catch (e: SecurityException) {
                        failed.add("it couldn't be opened")
                        null
                    }
                }
            }
            if (failed.isNotEmpty()) {
                val what = if (uris.size == 1) "That couldn't be added: " + failed.first() else "${failed.size} of ${uris.size} couldn't be added: " + failed.first()
                _message.value = Message(what)
            }
            added(added)
        }
    }

    /** Add a receipt that came as text, eg an email's body. */
    fun addText(subject: String?, body: String) {
        if (TextReceipt.isLink(body)) {
            _message.value = Message("That's a link to a receipt online, which Receiptdroid can't open: it has no internet access. Open the link and share or save the receipt instead.")
            return
        }
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { queue.addText(subject, body) }
            if (q == null) _message.value = Message("There's no text in that to read")
            else added(listOf(q))
        }
    }

    /** A new file for the camera to save a photo in: its id, and the address to give the camera. */
    fun newPhoto(): Pair<String, Uri> = queue.forCamera().let { (q, uri) -> q.id to uri }

    /** The camera's done with the photo [id]: kept if it [saved] it. */
    fun photoTaken(id: String, saved: Boolean) {
        val q = queue.list().firstOrNull { it.id == id }
        if (!saved || q == null) {
            java.io.File(getApplication<Application>().filesDir, "queue/$id.${Kind.PHOTO.extension}").delete()
            return
        }
        added(listOf(q))
    }

    private fun added(new: List<Queued>) {
        _waiting.value = queue.list()
        // Straight to the first of them, unless you're already looking at a receipt: then they wait their turn.
        if (_stage.value is Stage.Start) new.firstOrNull()?.let { open(it) }
        else readAhead()
    }

    // ------------------------------------------------------------------------------------- going through them

    /** Carry on with the receipts waiting. */
    fun continueWaiting() {
        _waiting.value = queue.list()
        _waiting.value.firstOrNull()?.let { open(it) }
    }

    private fun open(q: Queued) {
        reading?.cancel()
        _stage.value = Stage.Busy(q)
        _form.value = Form()
        val pending = ahead?.takeIf { it.first == q.id }?.second
        if (pending == null) ahead?.second?.cancel()
        ahead = null
        reading = viewModelScope.launch {
            try {
                val read = pending?.await() ?: ReadReceipt.of(getApplication<Application>(), q)
                val style = style()
                val (slope, guess) = withContext(Dispatchers.Default) {
                    Reading.slope(read.lines).let { slope -> slope to Reading.guess(read.lines, style, slope) }
                }
                val picks = guess.picks.toMutableMap()
                // An email says who it's from ("Your receipt from Corner Cafe"): better than its first line.
                if (q.kind == Kind.TEXT) {
                    TextReceipt.payee(read.lines.map { it.text })?.let { (line, name) -> picks[Part.PAYEE] = line to Reading.pickedPayee(name) }
                }
                fun value(part: Part) = picks[part]?.second?.value.orEmpty()
                val payeeRead = picks[Part.PAYEE]?.second?.value
                _form.value = Form(
                    entry = ReceiptEntry(
                        date = value(Part.DATE),
                        payee = payeeRead?.let { tidied(it) }.orEmpty(),
                        amount = value(Part.AMOUNT),
                        // The one printed by the total, written as it was sent last time if it's the same one.
                        currency = Amounts.preferred(picks[Part.AMOUNT]?.second?.currency, lastCurrency()),
                    ),
                    marks = picks.mapValues { it.value.first },
                    target = Part.entries.firstOrNull { it !in picks } ?: Part.PAYEE,
                    payeeRead = payeeRead,
                )
                _stage.value = Stage.Ready(q, read.picture?.asImageBitmap(), read.lines, slope)
                if (read.lines.isEmpty()) _message.value = Message("No text found in that. Try a sharper photo, or type the details in.")
                readAhead()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // The file itself can't be read (not a picture, a PDF with a password): no use keeping it.
                _message.value = Message("A receipt couldn't be opened, so it was discarded: " + (e.message ?: "unknown reason"))
                queue.remove(q)
                next()
            } catch (e: Exception) {
                // The reading failed: it stays waiting, to try again.
                home()
                _message.value = Message("A receipt couldn't be read (" + (e.message ?: e.javaClass.simpleName) + "). It's still waiting.")
            }
        }
    }

    /** Read the next receipt waiting while this one's looked at, so it's ready when this one's sent. */
    private fun readAhead() {
        val current = (_stage.value as? Stage.Ready)?.current ?: return
        val next = _waiting.value.firstOrNull { it.id != current.id } ?: return
        if (ahead?.first == next.id) return
        ahead?.second?.cancel()
        val app = getApplication<Application>()
        ahead = next.id to viewModelScope.async { ReadReceipt.of(app, next) }
    }

    /** The receipt on to after this one: the oldest waiting, or the start. */
    private fun next() {
        _waiting.value = queue.list()
        val q = _waiting.value.firstOrNull()
        if (q != null) open(q) else {
            ahead?.second?.cancel()
            ahead = null
            _stage.value = Stage.Start
            _form.value = Form()
        }
    }

    /** A tap on line [line] of the receipt: it fills the part being picked, and the next empty part is picked next. */
    fun tap(line: Int) {
        val ready = _stage.value as? Stage.Ready ?: return
        val f = _form.value
        val picked = Reading.pick(f.target, line, ready.lines, style(), ready.slope)
        if (picked == null) {
            _message.value = Message("No ${f.target.label.lowercase()} in that line")
            return
        }
        val form = when (f.target) {
            Part.PAYEE -> f.copy(entry = f.entry.copy(payee = tidied(picked.value)), payeeRead = picked.value)
            Part.DATE -> f.copy(entry = f.entry.copy(date = picked.value))
            Part.AMOUNT -> f.copy(entry = f.entry.copy(amount = picked.value, currency = Amounts.preferred(picked.currency, f.entry.currency.ifBlank { lastCurrency() })))
        }
        val next = Part.entries.firstOrNull { valueOf(form.entry, it).isBlank() } ?: f.target
        _form.value = form.copy(marks = f.marks + (f.target to line), target = next)
    }

    fun pickFor(part: Part) = _form.update { it.copy(target = part) }

    /**
     * The boxes as typed in. A part that's typed over is no longer the line it was picked from. The marks
     * stay the same map unless one goes, so that the receipt isn't drawn again for every keystroke.
     */
    fun edit(entry: ReceiptEntry) = _form.update { f ->
        val typedOver = Part.entries.filter { it in f.marks && valueOf(entry, it) != valueOf(f.entry, it) }
        f.copy(entry = entry, marks = if (typedOver.isEmpty()) f.marks else f.marks - typedOver.toSet())
    }

    private fun valueOf(entry: ReceiptEntry, part: Part): String = when (part) {
        Part.PAYEE -> entry.payee
        Part.DATE -> entry.date
        Part.AMOUNT -> entry.amount
    }

    /**
     * The entry's text went to Ledgedroid: remember its currency and account, and its payee if it was
     * corrected; the receipt is done with, and the next one waiting comes up.
     */
    fun sent(text: String) {
        val ready = _stage.value as? Stage.Ready ?: return
        val f = _form.value
        val e = ShareText.tidy(f.entry)
        if (e.account.isNotEmpty()) _accounts.value = (listOf(e.account) + _accounts.value.filter { it != e.account }).take(RECENT_ACCOUNTS)
        if (_tidy.value) {
            f.payeeRead?.let { read -> Payees.learn(read, e.payee, _learned.value, journalNames())?.let { (k, name) -> _learned.update { it + (k to name) } } }
        }
        prefs.edit()
            .putString(KEY_CURRENCY, e.currency)
            .putString(KEY_ACCOUNTS, _accounts.value.joinToString("\n"))
            .putString(KEY_LEARNED, Payees.encode(_learned.value))
            .apply()
        queue.remove(ready.current)
        val left = queue.list().size
        val what = e.payee.ifEmpty { "The entry" }
        onReturn = Message(if (left == 0) "$what sent to Ledgedroid" else "$what sent to Ledgedroid · $left more waiting", resend = text)
        next()
    }

    /** Put this receipt aside without sending it: it's deleted, and the next one comes up. */
    fun discard() {
        val ready = _stage.value
        val q = (ready as? Stage.Ready)?.current ?: (ready as? Stage.Busy)?.current ?: return
        reading?.cancel()
        queue.remove(q)
        _message.value = Message("Receipt discarded")
        next()
    }

    /** Back to the start; the receipts waiting stay for later. */
    fun home() {
        reading?.cancel()
        ahead?.second?.cancel()
        ahead = null
        _stage.value = Stage.Start
        _form.value = Form()
        _waiting.value = queue.list()
    }

    fun discardAll() {
        home()
        queue.removeAll()
        _waiting.value = emptyList()
    }

    // ------------------------------------------------------------------------------------- payee names

    /** [printed] as it goes in the payee box: tidied if that's on. */
    private fun tidied(printed: String): String = if (_tidy.value) Payees.tidy(printed, _learned.value, journalNames()) else printed

    fun setTidy(on: Boolean) {
        _tidy.value = on
        prefs.edit().putBoolean(KEY_TIDY, on).apply()
    }

    fun forgetLearned() {
        _learned.value = emptyMap()
        prefs.edit().remove(KEY_LEARNED).apply()
    }

    // ------------------------------------------------------------------------------------- your journal

    private fun journalNames(): JournalNames? = _journal.value.takeIf { it.on }?.names

    /** Suggest from the journal or not. Turning it on with no file chosen yet is for the screen to ask. */
    fun setJournal(on: Boolean) {
        prefs.edit().putBoolean(KEY_JOURNAL, on).apply()
        _journal.update { if (on) it.copy(on = true) else it.copy(on = false, names = JournalNames(), problem = null) }
        if (on) refreshJournal(force = true)
    }

    /** Suggest from these journal files. */
    fun chooseJournal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        JournalFiles.release(app, journalUris - uris.toSet())
        JournalFiles.keep(app, uris)
        journalUris = uris
        prefs.edit().putString(KEY_JOURNAL_URIS, uris.joinToString("\n")).putBoolean(KEY_JOURNAL, true).apply()
        _journal.value = JournalState(on = true, files = uris.map { JournalFiles.name(app, it) })
        refreshJournal(force = true)
    }

    /** Stop suggesting, and give up the permission to read the journal. */
    fun forgetJournal() {
        JournalFiles.release(getApplication<Application>(), journalUris)
        journalUris = emptyList()
        prefs.edit().remove(KEY_JOURNAL_URIS).putBoolean(KEY_JOURNAL, false).apply()
        _journal.value = JournalState()
    }

    /** Read the journal again, eg on coming back from Ledgedroid, which may have added to it. */
    fun refreshJournal(force: Boolean = false) {
        val j = _journal.value
        if (!j.on || journalUris.isEmpty() || j.reading) return
        val now = System.currentTimeMillis()
        if (!force && now - journalReadAt < JOURNAL_FRESH) return
        journalReadAt = now
        _journal.update { it.copy(reading = true) }
        val app = getApplication<Application>()
        val uris = journalUris
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(JournalFiles.read(app, uris))
                } catch (e: IOException) {
                    Result.failure(e)
                }
            }
            _journal.update { state ->
                if (!state.on) state.copy(reading = false)
                else result.fold(
                    { state.copy(names = it, reading = false, problem = null) },
                    { state.copy(reading = false, problem = it.message ?: "The journal couldn't be read") },
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------- the rest

    fun setUpDone() {
        _setUp.value = true
        prefs.edit().putBoolean(KEY_SET_UP, true).apply()
    }

    fun say(message: String) {
        _message.value = Message(message)
    }

    fun messageShown(m: Message) {
        _message.compareAndSet(m, null)
    }

    /** The app is in sight again, eg back from Ledgedroid: what was waiting to be said can be. */
    fun resumed() {
        onReturn?.let { _message.value = it }
        onReturn = null
        refreshJournal()
    }

    private fun lastCurrency(): String = prefs.getString(KEY_CURRENCY, "").orEmpty()

    /** Today, and whether this phone writes dates month first (11/25) or day first (25/11). */
    private fun style(): ReadingStyle {
        val order = DateFormat.getDateFormatOrder(getApplication<Application>())
        return ReadingStyle(LocalDate.now(), monthFirst = order.indexOf('M') < order.indexOf('d'), locale = Locale.getDefault())
    }

    private companion object {
        const val KEY_CURRENCY = "currency"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_SET_UP = "setUp"
        const val KEY_TIDY = "tidy"
        const val KEY_LEARNED = "learned"
        const val KEY_JOURNAL = "journal"
        const val KEY_JOURNAL_URIS = "journalUris"
        const val RECENT_ACCOUNTS = 8
        /** The journal's read again on coming back to the app, at most this often. */
        const val JOURNAL_FRESH = 15_000L
    }
}
