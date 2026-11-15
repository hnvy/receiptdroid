package app.receiptdroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.receiptdroid.ocr.OcrLanguage
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size

/** Tidying payee names, suggesting from your journal, and setting up Ledgedroid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    engine: String,
    tidy: Boolean,
    learned: Int,
    journal: JournalState,
    onTidy: (Boolean) -> Unit,
    onForgetLearned: () -> Unit,
    onJournal: (Boolean) -> Unit,
    onChooseJournal: () -> Unit,
    onForgetJournal: () -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
    languages: List<OcrLanguage>? = null,
    addingLanguage: Boolean = false,
    onAddLanguage: () -> Unit = {},
    onRemoveLanguage: (String) -> Unit = {},
    onUseLanguage: (String, Boolean) -> Unit = { _, _ -> },
    snackbar: SnackbarHostState? = null,
) {
    val rc = LocalReceiptColors.current
    var confirmForget by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Toggle(
                title = "Tidy payee names",
                text = "\"CORNER CAFE LTD\" becomes \"Corner Cafe\", without branch numbers or Ltd and GmbH. When you correct a name, " +
                    "it's remembered for that shop's next receipt.",
                on = tidy,
                onChange = onTidy,
            )
            if (learned > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (learned == 1) "1 name remembered" else "$learned names remembered", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmForget = true }) { Text("Forget them") }
                }
            }
            HorizontalDivider()

            Toggle(
                title = "Suggest from my journal",
                text = "Payees and accounts from your hledger journal, offered as you fill in the entry, with each payee's usual account " +
                    "first. The journal is only read, never changed or copied, and what's read stays in memory while the app is open.",
                on = journal.on,
                onChange = onJournal,
            )
            if (journal.files.isNotEmpty()) {
                Text("Reading " + journal.files.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                when {
                    !journal.on -> {}
                    journal.reading -> Text("Reading…", style = MaterialTheme.typography.bodySmall, color = rc.mutedInk)
                    journal.problem != null -> Text(journal.problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Text(
                        "${journal.names.payees.size} payees, ${journal.names.accounts.size} accounts",
                        style = MaterialTheme.typography.bodySmall,
                        color = rc.mutedInk,
                    )
                }
                if (journal.on && journal.names.includes.isNotEmpty()) {
                    Text(
                        "It includes " + journal.names.includes.joinToString(", ") + ". Included files aren't opened by themselves: " +
                            "choose them too, with the main file, to suggest from them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = rc.mutedInk,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChooseJournal) { Text("Choose files") }
                    TextButton(onClick = onForgetJournal) { Text("Forget the journal") }
                }
            } else {
                Text(
                    "Choose the journal file Ledgedroid uses (and any it includes). You can choose several at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = rc.mutedInk,
                )
            }
            HorizontalDivider()

            if (languages != null) {
                OcrLanguagesSection(languages, addingLanguage, onAddLanguage, onRemoveLanguage, onUseLanguage)
                HorizontalDivider()
            }

            Text("Ledgedroid", style = MaterialTheme.typography.titleMedium)
            Text("Receiptdroid sends each entry to Ledgedroid's quick add, which reads it with a Share pattern.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onSetup) { Text("Set up the Share pattern") }
            HorizontalDivider()

            Text(
                "Text is read on this phone by $engine. Receiptdroid has no permission to use the internet, so nothing it reads leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = rc.mutedInk,
            )
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget remembered names?") },
            text = { Text("Payee names you corrected will be tidied the general way again.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; onForgetLearned() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Toggle(title: String, text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = LocalReceiptColors.current.mutedInk)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Tesseract's languages: switch them on and off, add language data from a file, remove what you added. */
@Composable
private fun OcrLanguagesSection(
    languages: List<OcrLanguage>,
    adding: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onUse: (String, Boolean) -> Unit,
) {
    val rc = LocalReceiptColors.current
    var confirmRemove by remember { mutableStateOf<OcrLanguage?>(null) }
    Text("Text recognition languages", style = MaterialTheme.typography.titleMedium)
    Text(
        "Receipts are read in the languages switched on. To add one, get Tesseract's language file for it (a .traineddata " +
            "file, eg deu.traineddata for German) on any device, put it on this phone, and add it here. Receiptdroid can't " +
            "download it itself: it has no internet access.",
        style = MaterialTheme.typography.bodySmall,
        color = rc.mutedInk,
    )
    Code("github.com/tesseract-ocr/tessdata_fast\ngithub.com/tesseract-ocr/tessdata_best")
    Text(
        "tessdata_fast is what's built in; tessdata_best reads a little better and is about four times the size. Adding " +
            "eng.traineddata replaces the built-in English. Each language on makes reading slower: two or three is plenty.",
        style = MaterialTheme.typography.bodySmall,
        color = rc.mutedInk,
    )
    val onCount = languages.count { it.on }
    languages.forEach { language ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(language.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        language.added && language.builtIn -> "Added by you, in place of the built-in one · ${megabytes(language.bytes)}"
                        language.added -> "Added by you · ${megabytes(language.bytes)}"
                        else -> "Built in · ${megabytes(language.bytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = rc.mutedInk,
                )
            }
            if (language.added) TextButton(onClick = { confirmRemove = language }) { Text("Remove") }
            // The last one on can't be switched off: receipts have to be read in something.
            Switch(checked = language.on, onCheckedChange = { onUse(language.code, it) }, enabled = !(language.on && onCount <= 1))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onAdd, enabled = !adding) { Text("Add language data") }
        if (adding) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Text("Checking it…", style = MaterialTheme.typography.bodySmall, color = rc.mutedInk)
        }
    }
    confirmRemove?.let { language ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${language.name}?") },
            text = {
                Text(
                    if (language.builtIn) "The built-in ${language.name} comes back in its place."
                    else "Receipts won't be read in it any more. You can add it again from its file.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmRemove = null; onRemove(language.code) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
}

private fun megabytes(bytes: Long): String = if (bytes < 1_000_000) "${(bytes / 1000).coerceAtLeast(1)} kB" else "%.1f MB".format(bytes / 1_000_000.0)
