package app.receiptdroid.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TessFilesTest {
    /** The app as installed: its built-in data, and its version; Tesseract loads files that start GOOD. */
    private class App {
        val root: File = Files.createTempDirectory("tess").toFile()
        var builtIn = mutableMapOf("eng" to "GOOD built-in eng v1")
        var version = "1"
        fun files(maxBytes: Long = TessFiles.MAX_BYTES) = TessFiles(
            root,
            builtIn = { builtIn.keys.toSet() },
            openBuiltIn = { code -> ByteArrayInputStream(builtIn.getValue(code).toByteArray()) },
            version = { version },
            loads = { path, code -> File(path, "tessdata/$code.traineddata").readText().startsWith("GOOD") },
            maxBytes = maxBytes,
        )
        fun data(code: String) = File(root, "tessdata/$code.traineddata").let { if (it.exists()) it.readText() else null }
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    private fun fails(expect: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected it to fail with $expect")
        } catch (e: IOException) {
            assertTrue("<${e.message}> should mention <$expect>", e.message!!.contains(expect))
        }
    }

    @Test fun builtInFirstTime() {
        val app = App()
        assertEquals(listOf("eng"), app.files().install())
        assertEquals("GOOD built-in eng v1", app.data("eng"))
        val l = app.files().list().single()
        assertTrue(l.builtIn); assertFalse(l.added); assertTrue(l.on)
    }

    @Test fun addingALanguage() {
        val app = App()
        val f = app.files()
        assertEquals("deu", f.add("deu.traineddata", stream("GOOD german")))
        assertEquals(listOf("eng", "deu"), f.install())
        assertEquals(listOf("eng" to false, "deu" to true), f.list().map { it.code to it.added })
        // Its name in the phone's language, with its code: "German (deu)" in English.
        assertTrue(f.list()[1].name.endsWith("(deu)"))
    }

    @Test fun badFilesAreTurnedAwayAndLeaveNothing() {
        val app = App()
        val f = app.files(maxBytes = 1000)
        fails("couldn't be loaded") { f.add("deu.traineddata", stream("garbage")) }
        fails("isn't a Tesseract language file") { f.add("deu.traineddata.bin", stream("GOOD")) }
        fails("doesn't read it") { f.add("osd.traineddata", stream("GOOD")) }
        fails("larger than") { f.add("fra.traineddata", stream("GOOD" + "x".repeat(5000))) }
        assertEquals(listOf("eng"), f.install())
        assertEquals(null, app.data("deu")); assertEquals(null, app.data("fra"))
        assertFalse(File(app.root, "staging").exists())
    }

    @Test fun anUpdateKeepsWhatWasAdded() {
        val app = App()
        app.files().add("deu.traineddata", stream("GOOD german"))
        // The app's updated: new built-in English, and a built-in French.
        app.version = "2"
        app.builtIn = mutableMapOf("eng" to "GOOD built-in eng v2", "fra" to "GOOD built-in fra")
        assertEquals(listOf("eng", "deu", "fra"), app.files().install())
        assertEquals("GOOD german", app.data("deu"))
        assertEquals("GOOD built-in eng v2", app.data("eng"))
        // A later version without French: it goes, what was added stays.
        app.version = "3"
        app.builtIn = mutableMapOf("eng" to "GOOD built-in eng v3")
        assertEquals(listOf("eng", "deu"), app.files().install())
        assertEquals(null, app.data("fra"))
        assertEquals("GOOD german", app.data("deu"))
    }

    @Test fun addingInPlaceOfABuiltInOne() {
        val app = App()
        app.files().add("eng.traineddata", stream("GOOD best eng"))
        val eng = app.files().list().single()
        assertTrue(eng.builtIn && eng.added)
        // An update doesn't put the built-in one back over it.
        app.version = "2"
        app.builtIn = mutableMapOf("eng" to "GOOD built-in eng v2")
        app.files().install()
        assertEquals("GOOD best eng", app.data("eng"))
        // Removing it does.
        app.files().remove("eng")
        assertEquals("GOOD built-in eng v2", app.data("eng"))
        assertFalse(app.files().list().single().added)
    }

    @Test fun builtInOnesCantBeRemoved() {
        val app = App()
        app.files().install()
        app.files().remove("eng")
        assertEquals("GOOD built-in eng v1", app.data("eng"))
    }

    @Test fun switchingOnAndOff() {
        val app = App()
        val f = app.files()
        f.add("deu.traineddata", stream("GOOD german"))
        f.use("eng", false)
        assertEquals(listOf("deu"), f.install())
        // The last one on stays on.
        f.use("deu", false)
        assertEquals(listOf("deu"), f.install())
        f.use("eng", true)
        assertEquals(listOf("eng", "deu"), f.install())
        // A language added again is on again.
        f.use("deu", false)
        f.add("deu.traineddata", stream("GOOD german 2"))
        assertEquals(listOf("eng", "deu"), f.install())
    }

    @Test fun missingOrLeftoverFiles() {
        val app = App()
        app.files().install()
        // Something deleted the built-in data: it's put back.
        File(app.root, "tessdata/eng.traineddata").delete()
        assertEquals(listOf("eng"), app.files().install())
        // Half-copied files from an interrupted copy go at the next update.
        File(app.root, "tessdata/eng.traineddata.part").writeText("half")
        app.version = "2"
        app.files().install()
        assertFalse(File(app.root, "tessdata/eng.traineddata.part").exists())
    }
}
