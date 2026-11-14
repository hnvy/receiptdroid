package app.receiptdroid.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.receiptdroid.Ledgedroid
import app.receiptdroid.R
import app.receiptdroid.core.Amounts
import app.receiptdroid.core.OcrLine
import app.receiptdroid.core.Part
import app.receiptdroid.core.Reading
import app.receiptdroid.core.ReceiptEntry
import app.receiptdroid.core.ShareText
import app.receiptdroid.core.Suggest
import app.receiptdroid.ocr.ocrEngine

/** Types of file a receipt can come as, for choosing one from your files. */
private val RECEIPT_FILES = arrayOf("application/pdf", "image/*", "text/plain", "text/html")

/**
 * The whole app: a start screen to take, choose, share or paste receipts, then each receipt in turn, with
 * its text marked to tap the payee, date and total from, above the entry's boxes and the button that sends
 * it to Ledgedroid. Settings has tidying payee names and suggesting from your journal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsApp(vm: ReceiptViewModel) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val recent by vm.accounts.collectAsStateWithLifecycle()
    val setUp by vm.setUp.collectAsStateWithLifecycle()
    val tidy by vm.tidy.collectAsStateWithLifecycle()
    val learned by vm.learned.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showText by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // Send was pressed before Ledgedroid was set up: it goes once that's done.
    var sendAfterSetup by rememberSaveable { mutableStateOf(false) }
    // The photo the camera is taking, and whether to take another after it.
    var photoId by rememberSaveable { mutableStateOf<String?>(null) }
    var several by rememberSaveable { mutableStateOf(false) }
    // Looked at again on coming back, eg from installing it; and the journal, which Ledgedroid may have added to.
    var installed by remember { mutableStateOf(Ledgedroid.installed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = Ledgedroid.installed(context)
        vm.resumed()
    }
    val engine = remember { ocrEngine(context).name }

    var takePhoto: () -> Unit = {}
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        photoId?.let { vm.photoTaken(it, saved) }
        photoId = null
        // Taking several: the camera comes back for the next, until you back out of it.
        if (saved && several) takePhoto() else several = false
    }
    takePhoto = {
        val (id, uri) = vm.newPhoto()
        photoId = id
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            vm.photoTaken(id, false)
            photoId = null
            several = false
            vm.say("There's no camera app to take the photo with")
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED)) { uris -> vm.add(uris) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.add(uris) }
    val journalFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.chooseJournal(uris) }

    val actions = Actions(
        takePhoto = { several = false; takePhoto() },
        takeSeveral = { several = true; takePhoto() },
        choosePhotos = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        chooseFiles = {
            try {
                files.launch(RECEIPT_FILES)
            } catch (e: ActivityNotFoundException) {
                vm.say("There's no app on this phone to choose files with")
            }
        },
        paste = {
            val text = clipboard.getText()?.text.orEmpty()
            if (text.isBlank()) vm.say("There's no text to paste. Copy the receipt's email first.") else vm.addText(null, text)
        },
    )
    val chooseJournal: () -> Unit = {
        try {
            journalFiles.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            vm.say("There's no app on this phone to choose files with")
        }
    }
    val send: () -> Unit = {
        val text = ShareText.of(form.entry)
        // Straight to Ledgedroid; without it, to whichever app is chosen.
        if (!Ledgedroid.send(context, text)) Ledgedroid.share(context, text)
        vm.sent(text)
    }

    // Cleared once it's been shown (not before: clearing it would end this effect, and the snackbar with it).
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            m.text,
            actionLabel = if (m.resend != null) "Send again" else null,
            withDismissAction = m.resend != null,
            duration = if (m.resend != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        vm.messageShown(m)
        if (result == SnackbarResult.ActionPerformed) m.resend?.let { if (!Ledgedroid.send(context, it)) Ledgedroid.share(context, it) }
    }
    BackHandler(enabled = !showSettings && stage !is Stage.Start) { vm.home() }
    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(
            engine = engine,
            tidy = tidy,
            learned = learned.size,
            journal = journal,
            onTidy = vm::setTidy,
            onForgetLearned = vm::forgetLearned,
            onJournal = { on -> if (on && journal.files.isEmpty()) chooseJournal() else vm.setJournal(on) },
            onChooseJournal = chooseJournal,
            onForgetJournal = vm::forgetJournal,
            onSetup = { showSetup = true },
            onBack = { showSettings = false },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (stage !is Stage.Start) IconButton(onClick = vm::home) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to the start") }
                    },
                    title = {
                        val more = waiting.size - 1
                        Text(
                            when {
                                stage is Stage.Start -> "Receiptdroid"
                                more > 0 -> "Receipt · $more more"
                                else -> "Receipt"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    actions = {
                        if (stage !is Stage.Start) {
                            IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.Default.Delete, contentDescription = "Discard this receipt") }
                            IconButton(onClick = actions.takePhoto) { Icon(painterResource(R.drawable.ic_camera), contentDescription = "Add a photo") }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val inside = Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()
            when (val s = stage) {
                Stage.Start -> StartScreen(
                    engine = engine,
                    waiting = waiting.size,
                    setUp = setUp,
                    installed = installed,
                    actions = actions,
                    onContinue = vm::continueWaiting,
                    onDiscardAll = vm::discardAll,
                    onSetup = { showSetup = true },
                    modifier = inside,
                )
                is Stage.Busy -> Box(inside, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Reading the receipt…")
                    }
                }
                is Stage.Ready -> {
                    val suggestions = rememberSuggestions(form.entry, journal, recent)
                    ReceiptScreen(
                        picture = s.picture,
                        lines = s.lines,
                        form = form,
                        tidy = tidy,
                        suggestions = suggestions,
                        installed = installed,
                        onTap = vm::tap,
                        onPickFor = vm::pickFor,
                        onEdit = vm::edit,
                        onShowText = { showText = true },
                        // The first time, make sure Ledgedroid will know what to do with it.
                        onSend = {
                            if (setUp) send() else {
                                sendAfterSetup = true
                                showSetup = true
                            }
                        },
                        modifier = inside,
                    )
                }
            }
        }
    }

    if (showSetup) {
        SetupDialog(
            installed = installed,
            onDone = {
                vm.setUpDone()
                showSetup = false
                if (sendAfterSetup && vm.stage.value is Stage.Ready) send()
                sendAfterSetup = false
            },
            onDismiss = {
                showSetup = false
                sendAfterSetup = false
            },
        )
    }
    if (showText) TextDialog(ShareText.of(form.entry), onDismiss = { showText = false })
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this receipt?") },
            text = { Text("It's deleted without being sent. Any receipts waiting after it stay.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

private const val MAX_PICKED = 30

/** The ways to add receipts. */
private class Actions(
    val takePhoto: () -> Unit,
    val takeSeveral: () -> Unit,
    val choosePhotos: () -> Unit,
    val chooseFiles: () -> Unit,
    val paste: () -> Unit,
)

/** Names to offer under the payee and account boxes. */
private class Suggestions(val payees: List<String>, val accounts: List<String>, val usual: String?)

@Composable
private fun rememberSuggestions(entry: ReceiptEntry, journal: JournalState, recent: List<String>): Suggestions {
    val names = journal.names.takeIf { journal.on }
    val payees = remember(entry.payee, names) { names?.let { Suggest.payees(entry.payee, it.payees) }.orEmpty() }
    val usual = remember(entry.payee, names) { names?.usualFor(entry.payee) }
    val accounts = remember(entry.account, usual, names, recent) {
        // Your journal's accounts, the payee's usual one first, then the ones you sent lately; or just those.
        Suggest.accounts(entry.account, (recent + names?.accounts.orEmpty()).distinct(), listOfNotNull(usual))
    }
    return Suggestions(payees, accounts, usual)
}

@Composable
private fun StartScreen(
    engine: String,
    waiting: Int,
    setUp: Boolean,
    installed: Boolean,
    actions: Actions,
    onContinue: () -> Unit,
    onDiscardAll: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rc = LocalReceiptColors.current
    var confirmDiscardAll by remember { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Photograph a receipt, or bring in an emailed one, tap its payee, date and total, and send it to Ledgedroid as a transaction ready to add.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (waiting > 0) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (waiting == 1) "1 receipt waiting" else "$waiting receipts waiting", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onContinue) { Text("Continue") }
                        TextButton(onClick = { confirmDiscardAll = true }) { Text("Discard all") }
                    }
                }
            }
        }
        Text("Photos", style = MaterialTheme.typography.titleMedium)
        Button(onClick = actions.takePhoto, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Take a photo")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.takeSeveral, modifier = Modifier.weight(1f)) { Text("Take several") }
            OutlinedButton(onClick = actions.choosePhotos, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_image), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose")
            }
        }
        Text(
            "Taking several: the camera opens again after each photo. Go back from the camera when you're done, and they're read in turn.",
            style = MaterialTheme.typography.bodySmall,
            color = rc.mutedInk,
        )
        Text("Emailed receipts", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.chooseFiles, modifier = Modifier.weight(1f)) { Text("Choose a PDF") }
            OutlinedButton(onClick = actions.paste, modifier = Modifier.weight(1f)) { Text("Paste text") }
        }
        Text(
            "Or share the email, its text or its PDF to Receiptdroid from your mail app. A link to a receipt online can't be opened: " +
                "Receiptdroid has no internet access.",
            style = MaterialTheme.typography.bodySmall,
            color = rc.mutedInk,
        )
        if (!installed) {
            Text(
                "Ledgedroid isn't installed on this phone. Until it is, each entry's text can be shared to another app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!setUp) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Once: set up Ledgedroid", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ledgedroid reads what this app sends with a Share pattern. Add it once, and every receipt opens quick add filled in.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = onSetup) { Text("Show me how") }
                }
            }
        }
        Text(
            "Everything happens on this phone: the text is read by $engine, and the app has no permission to use the internet. " +
                "Receipts waiting are kept in the app's own storage, and deleted once sent or discarded.",
            style = MaterialTheme.typography.bodySmall,
            color = rc.mutedInk,
        )
    }
    if (confirmDiscardAll) {
        AlertDialog(
            onDismissRequest = { confirmDiscardAll = false },
            title = { Text("Discard all $waiting?") },
            text = { Text("They're deleted without being sent.") },
            confirmButton = { TextButton(onClick = { confirmDiscardAll = false; onDiscardAll() }) { Text("Discard all") } },
            dismissButton = { TextButton(onClick = { confirmDiscardAll = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ReceiptScreen(
    picture: ImageBitmap?,
    lines: List<OcrLine>,
    form: Form,
    tidy: Boolean,
    suggestions: Suggestions,
    installed: Boolean,
    onTap: (Int) -> Unit,
    onPickFor: (Part) -> Unit,
    onEdit: (ReceiptEntry) -> Unit,
    onShowText: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoomed by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        // The receipt keeps at least a third of the height, also with the keyboard up: the boxes scroll.
        val boxesAtMost = maxHeight * 0.66f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (picture != null) {
                    Photo(picture, lines, form.marks, zoomed, onTap, Modifier.fillMaxSize())
                    FilledTonalButton(onClick = { zoomed = !zoomed }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        Text(if (zoomed) "Smaller" else "Larger")
                    }
                } else {
                    TextLines(lines, form.marks, onTap, Modifier.fillMaxSize())
                }
            }
            HorizontalDivider()
            EntryBoxes(form, tidy, suggestions, installed, onPickFor, onEdit, onShowText, onSend, Modifier.heightIn(max = boxesAtMost))
        }
    }
}

/**
 * The photo or PDF, as wide as the screen (twice that when [zoomed]) and scrolling, with a box drawn round
 * each line of text found: a thin one, or filled in the colour of the part it was picked for. A tap on a
 * line calls [onTap] with its place in [lines].
 */
@Composable
private fun Photo(
    photo: ImageBitmap,
    lines: List<OcrLine>,
    marks: Map<Part, Int>,
    zoomed: Boolean,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rc = LocalReceiptColors.current
    val tap by rememberUpdatedState(onTap)
    BoxWithConstraints(modifier) {
        val shown = if (zoomed) maxWidth * 2f else maxWidth
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(shown).aspectRatio(photo.width.toFloat() / photo.height.toFloat())) {
                Image(bitmap = photo, contentDescription = "The receipt", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(lines, photo) {
                        detectTapGestures { at ->
                            val toPhoto = photo.width / size.width.toFloat()
                            // A finger's width of leeway round each line, since receipt print is small.
                            Reading.hit(lines, at.x * toPhoto, at.y * toPhoto, slop = 10.dp.toPx() * toPhoto)?.let { tap(it) }
                        }
                    },
                ) {
                    val toScreen = size.width / photo.width
                    val partAt = marks.entries.associate { it.value to it.key }
                    lines.forEachIndexed { i, line ->
                        val topLeft = Offset(line.left * toScreen, line.top * toScreen)
                        val box = Size((line.right - line.left) * toScreen, (line.bottom - line.top) * toScreen)
                        val part = partAt[i]
                        if (part == null) {
                            drawRect(rc.unpicked, topLeft, box, style = Stroke(1.dp.toPx()))
                        } else {
                            drawRect(rc.of(part), topLeft, box, alpha = 0.3f)
                            drawRect(rc.of(part), topLeft, box, style = Stroke(2.5.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

/** A receipt that came as text: its lines one under another, to tap, marked in the colour of the part each was picked for. */
@Composable
private fun TextLines(lines: List<OcrLine>, marks: Map<Part, Int>, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rc = LocalReceiptColors.current
    val partAt = marks.entries.associate { it.value to it.key }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        lines.forEachIndexed { i, line ->
            val part = partAt[i]
            Row(
                Modifier.fillMaxWidth().clickable { onTap(i) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(4.dp).heightIn(min = 20.dp).background(part?.let { rc.of(it) } ?: rc.unpicked.copy(alpha = 0.25f)))
                Spacer(Modifier.width(8.dp))
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).background(part?.let { rc.of(it).copy(alpha = 0.18f) } ?: rc.unpicked.copy(alpha = 0f)).padding(4.dp),
                )
            }
        }
    }
}

/** Which part a tap on the receipt fills, the entry's boxes with names to suggest, and the button that sends it. */
@Composable
private fun EntryBoxes(
    form: Form,
    tidy: Boolean,
    suggestions: Suggestions,
    installed: Boolean,
    onPickFor: (Part) -> Unit,
    onEdit: (ReceiptEntry) -> Unit,
    onShowText: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rc = LocalReceiptColors.current
    val e = form.entry
    val problems = ShareText.problems(e)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Tap the receipt for", style = MaterialTheme.typography.bodySmall, color = rc.mutedInk)
            Part.entries.forEach { part ->
                FilterChip(
                    selected = form.target == part,
                    onClick = { onPickFor(part) },
                    label = { Text(part.label) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(rc.of(part), CircleShape)) },
                )
            }
        }
        OutlinedTextField(
            value = e.payee,
            onValueChange = { onEdit(e.copy(payee = it)) },
            label = { Text("Payee") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        // As printed, when tidying changed it; and your journal's payees like it.
        val printed = form.payeeRead?.takeIf { tidy && it != e.payee }
        if (printed != null || suggestions.payees.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (printed != null) AssistChip(onClick = { onEdit(e.copy(payee = printed)) }, label = { Text("As printed: $printed") })
                suggestions.payees.forEach { name -> AssistChip(onClick = { onEdit(e.copy(payee = name)) }, label = { Text(name) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = e.date,
                // A number pad may have no dash: a dot or slash does as well.
                onValueChange = { typed -> onEdit(e.copy(date = typed.replace('/', '-').replace('.', '-').filter { it.isDigit() || it == '-' }.take(10))) },
                label = { Text("Date") },
                placeholder = { Text("today") },
                isError = problems.date != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1.3f),
            )
            OutlinedTextField(
                value = e.amount,
                onValueChange = { onEdit(e.copy(amount = Amounts.typed(it))) },
                label = { Text("Amount") },
                isError = e.amount.isNotEmpty() && problems.amount != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = e.currency,
                onValueChange = { onEdit(e.copy(currency = it.trim().take(3).uppercase())) },
                label = { Text("Cur.") },
                isError = problems.currency != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.weight(0.8f),
            )
        }
        OutlinedTextField(
            value = e.account,
            onValueChange = { onEdit(e.copy(account = it)) },
            label = { Text("Account (optional)") },
            placeholder = { Text("expenses:groceries") },
            isError = problems.account != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.accounts.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.accounts.forEach { a ->
                    FilterChip(
                        selected = e.account.trim() == a,
                        onClick = { onEdit(e.copy(account = if (e.account.trim() == a) "" else a)) },
                        label = { Text(if (a == suggestions.usual) "$a · usual" else a) },
                    )
                }
            }
        }
        // What has to be put right, one thing at a time; an empty amount just leaves the button off.
        listOfNotNull(
            problems.date?.let { "Date: $it" },
            problems.amount?.takeIf { e.amount.isNotEmpty() }?.let { "Amount: $it" },
            problems.currency?.let { "Currency: $it" },
            problems.account?.let { "Account: $it" },
        ).firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShowText, enabled = problems.none) { Text("Text") }
            Button(onClick = onSend, enabled = problems.none, modifier = Modifier.weight(1f)) {
                Text(if (installed) "Send to Ledgedroid" else "Share")
            }
        }
    }
}

/** The text exactly as it's sent, to see, copy, or share somewhere other than Ledgedroid. */
@Composable
private fun TextDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's sent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Code(text)
                Text(
                    "Ledgedroid's Receipt pattern reads these lines into quick add: the date, the payee, then the first account and its amount. " +
                        "The other account is chosen there, and its amount balances.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)); onDismiss() }) { Text("Copy") } },
        dismissButton = { TextButton(onClick = { Ledgedroid.share(context, text); onDismiss() }) { Text("Share elsewhere") } },
    )
}

/** How to add the Share pattern in Ledgedroid, with the pattern to copy. */
@Composable
private fun SetupDialog(installed: Boolean, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up Ledgedroid") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ledgedroid reads what this app sends with a Share pattern. Add it once:", style = MaterialTheme.typography.bodyMedium)
                Text("1. Copy the pattern.", style = MaterialTheme.typography.bodyMedium)
                Code(ShareText.PATTERN)
                FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(ShareText.PATTERN)); copied = "pattern" }) {
                    Text(if (copied == "pattern") "Copied" else "Copy the pattern")
                }
                Text("2. In Ledgedroid, open Settings › Share patterns and tap +.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "3. Name it ${ShareText.PATTERN_NAME}, paste into Pattern, and save. Leave \"start from a template\" on None: " +
                        "a template brings its own payee in place of the receipt's.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("4. If you have other patterns, move this one first with its up arrow, so it's tried before them.", style = MaterialTheme.typography.bodyMedium)
                if (installed) OutlinedButton(onClick = { Ledgedroid.open(context) }) { Text("Open Ledgedroid") }
                Text(
                    "To check it, copy a sample and paste it under \"Try it\" on that screen. It should find the amount, payee, date and account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(ShareText.of(ShareText.SAMPLE))); copied = "sample" }) {
                    Text(if (copied == "sample") "Copied" else "Copy a sample")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("I've added it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Text to be read character for character: fixed width, on a darker panel, and selectable. */
@Composable
internal fun Code(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
    }
}
