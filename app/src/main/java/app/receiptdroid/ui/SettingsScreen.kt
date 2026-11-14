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
