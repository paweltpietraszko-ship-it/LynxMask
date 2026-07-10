package com.lynxmask.app.document

import com.lynxmask.app.LookupTables
import com.lynxmask.app.PseudonymEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

// DocxRoundTripTest.kt — uproszczone 10.07 (decyzja właściciela: eksport zapisuje gotowy
// zamaskowany tekst jako świeży docx, nie patchuje oryginału — patrz DocxWriter.kt).
// Test golden: parseDocxDocument (ekstrakcja do silnika) i writeDocxFromText (zapis wyniku)
// są teraz niezależne od siebie — nie ma już wspólnego "artefaktu" niosącego oba kierunki.
class DocxRoundTripTest {

    @Before fun setup() { LookupTables.initializeForTesting() }
    @After fun teardown() { LookupTables.resetForTesting() }

    private val documentXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:p><w:r><w:t>Klient: Jan Kowalski</w:t></w:r></w:p>
            <w:p><w:r><w:t>PESEL: 44051401459</w:t></w:r></w:p>
          </w:body>
        </w:document>
    """.trimIndent()

    private fun syntheticZipEntries(): Map<String, ByteArray> = mapOf(
        "word/document.xml" to documentXml.toByteArray(Charsets.UTF_8),
        "word/styles.xml" to "<w:styles/>".toByteArray(Charsets.UTF_8),
        "[Content_Types].xml" to "<Types/>".toByteArray(Charsets.UTF_8),
    )

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `parseDocxDocument buduje plainText z document xml`() {
        val artifact = parseDocxDocument(syntheticZipEntries())
        assertTrue("Artefakt powinien się sparsować", artifact != null)
        assertTrue("plainText powinien zawierać Jan Kowalski", artifact!!.plainText.contains("Jan Kowalski"))
        assertTrue("plainText powinien zawierać PESEL", artifact.plainText.contains("44051401459"))
    }

    @Test
    fun `pelny przeplyw - ekstrakcja, maskowanie, zapis jako nowy docx`() {
        val artifact = parseDocxDocument(syntheticZipEntries())!!
        val engineResult = PseudonymEngine.pseudonymize(artifact.plainText)

        val out = ByteArrayOutputStream()
        val writeResult = runBlocking { writeDocxFromText(engineResult.pseudonymizedText, out) }
        assertTrue("Zapis powinien się udać: $writeResult", writeResult is DocumentWriteResult.Success)

        val outXml = readZip(out.toByteArray())["word/document.xml"] ?: error("Brak document.xml w wyniku")
        assertFalse("Jan Kowalski nie powinien zostać jawny w XML", outXml.contains("Jan Kowalski"))
        assertFalse("PESEL nie powinien zostać jawny w XML", outXml.contains("44051401459"))
        assertTrue("XML powinien zawierać token OSOBA", outXml.contains("OSOBA_"))
        assertTrue("XML powinien zawierać token NUMER", outXml.contains("NUMER_"))
    }

    @Test
    fun `nowy plik ma wymagane trzy czesci OOXML`() {
        val out = ByteArrayOutputStream()
        runBlocking { writeDocxFromText("Tresc", out) }
        val entries = readZip(out.toByteArray())
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("word/document.xml"))
    }

    @Test
    fun `kazda linia staje sie osobnym akapitem`() {
        val out = ByteArrayOutputStream()
        runBlocking { writeDocxFromText("Linia 1\nLinia 2\nLinia 3", out) }
        val xml = readZip(out.toByteArray())["word/document.xml"]!!
        assertEquals(3, Regex("""<w:p>""").findAll(xml).count())
        assertTrue(xml.contains("Linia 1"))
        assertTrue(xml.contains("Linia 2"))
        assertTrue(xml.contains("Linia 3"))
    }

    @Test
    fun `znaki specjalne XML sa escapowane`() {
        val out = ByteArrayOutputStream()
        runBlocking { writeDocxFromText("A & B < C > D", out) }
        val xml = readZip(out.toByteArray())["word/document.xml"]!!
        assertTrue(xml.contains("A &amp; B &lt; C &gt; D"))
    }

    @Test
    fun `pusta linia nie wywala zapisu`() {
        val out = ByteArrayOutputStream()
        val writeResult = runBlocking { writeDocxFromText("Przed\n\nPo", out) }
        assertTrue(writeResult is DocumentWriteResult.Success)
        val xml = readZip(out.toByteArray())["word/document.xml"]!!
        assertEquals(3, Regex("""<w:p>""").findAll(xml).count())
    }
}
