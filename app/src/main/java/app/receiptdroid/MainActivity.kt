package app.receiptdroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import app.receiptdroid.core.TextReceipt
import app.receiptdroid.ui.ReceiptViewModel
import app.receiptdroid.ui.ReceiptsApp
import app.receiptdroid.ui.ReceiptsTheme

class MainActivity : ComponentActivity() {
    private val vm: ReceiptViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ReceiptsTheme { ReceiptsApp(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Receipts given by another app: photos or PDFs shared, one or several (from the gallery, a mail's
     * attachments, a file manager); an email's text shared; or a PDF opened with this app.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val type = intent.type
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    vm.add(listOf(stream), type)
                    return
                }
                // An email's body: as HTML if the app gives it that way, which keeps a table's rows apart.
                val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
                val text = html?.let { TextReceipt.fromHtml(it) } ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) vm.addText(intent.getStringExtra(Intent.EXTRA_SUBJECT), text)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                // Each by its own type: a mixed share says only */*.
                vm.add(uris)
            }
            Intent.ACTION_VIEW -> intent.data?.let { vm.add(listOf(it), type) }
        }
        // Handled: not again when the activity is made anew (eg on turning the phone).
        setIntent(Intent(this, MainActivity::class.java))
    }
}
