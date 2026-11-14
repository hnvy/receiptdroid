package app.receiptdroid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Handing the entry's text to Ledgedroid, the way any app shares text to it. */
object Ledgedroid {
    const val PACKAGE = "app.ledgedroid"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Share [text] straight to Ledgedroid, which opens quick add filled in from it. Only the text is sent,
     * with no subject: Ledgedroid reads a subject as a line of the message. False if Ledgedroid isn't there.
     */
    fun send(context: Context, text: String): Boolean = try {
        context.startActivity(textIntent(text).setPackage(PACKAGE))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** Share [text] to whichever app is chosen. */
    fun share(context: Context, text: String) {
        try {
            context.startActivity(Intent.createChooser(textIntent(text), null))
        } catch (e: ActivityNotFoundException) {
            // Nothing on the phone takes text.
        }
    }

    /** Open Ledgedroid as from its icon, eg to add the Share pattern. False if it isn't installed. */
    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun textIntent(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
}
