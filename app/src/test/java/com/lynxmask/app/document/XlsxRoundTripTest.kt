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

// XlsxRoundTripTest.kt — ten sam wzorzec co DocxRoundTripTest: parser (JVM, czysty) +
// writer (JVM, czysty — sam zip/XML, bez Androida) testowane niezależnie od silnika,
// plus jeden pełny przepływ ekstrakcja -> maskowanie -> zapis.
class XlsxRoundTripTest {

    @Before fun setup() { LookupTables.initializeForTesting() }
    @After fun teardown() { LookupTables.resetForTesting() }

    private val sharedStringsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sst xmlns="ns" count="2" uniqueCount="2">
        <si><t>Klient: Jan Kowalski</t></si>
        <si><t>PESEL</t></si>
        </sst>
    """.trimIndent()

    private val sheetXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="ns"><sheetData>
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>44051401459</t></is></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    private fun syntheticZipEntries(): Map<String, ByteArray> = mapOf(
        "xl/sharedStrings.xml" to sharedStringsXml.toByteArray(Charsets.UTF_8),
        "xl/worksheets/sheet1.xml" to sheetXml.toByteArray(Charsets.UTF_8),
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
    fun `parseXlsxDocument odczytuje shared strings i inline string`() {
        val artifact = parseXlsxDocument(syntheticZipEntries())
        assertTrue("Artefakt powinien się sparsować", artifact != null)
        assertEquals("Klient: Jan Kowalski\tPESEL\n44051401459", artifact!!.plainText)
    }

    @Test
    fun `brak arkusza zwraca null`() {
        assertTrue(parseXlsxDocument(emptyMap()) == null)
    }

    @Test
    fun `pelny przeplyw - ekstrakcja, maskowanie, zapis jako nowy xlsx`() {
        val artifact = parseXlsxDocument(syntheticZipEntries())!!
        val engineResult = PseudonymEngine.pseudonymize(artifact.plainText)

        val out = ByteArrayOutputStream()
        val writeResult = runBlocking { writeXlsxFromText(engineResult.pseudonymizedText, out) }
        assertTrue("Zapis powinien się udać: $writeResult", writeResult is DocumentWriteResult.Success)

        val sheetOut = readZip(out.toByteArray())["xl/worksheets/sheet1.xml"] ?: error("Brak sheet1.xml w wyniku")
        assertFalse("Jan Kowalski nie powinien zostać jawny", sheetOut.contains("Jan Kowalski"))
        assertFalse("PESEL nie powinien zostać jawny", sheetOut.contains("44051401459"))
        assertTrue("Powinien zawierać token OSOBA", sheetOut.contains("OSOBA_"))
        assertTrue("Powinien zawierać token NUMER", sheetOut.contains("NUMER_"))
    }

    @Test
    fun `nowy plik ma wymagane czesci OOXML xlsx`() {
        val out = ByteArrayOutputStream()
        runBlocking { writeXlsxFromText("Tresc", out) }
        val entries = readZip(out.toByteArray())
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun `wiersze i kolumny odwzorowane z nowych linii i tabulatorow`() {
        val out = ByteArrayOutputStream()
        runBlocking { writeXlsxFromText("A1\tB1\nA2", out) }
        val xml = readZip(out.toByteArray())["xl/worksheets/sheet1.xml"]!!
        assertTrue(xml.contains("r=\"A1\""))
        assertTrue(xml.contains("r=\"B1\""))
        assertTrue(xml.contains("r=\"A2\""))
        assertTrue(xml.contains(">A1<"))
        assertTrue(xml.contains(">B1<"))
        assertTrue(xml.contains(">A2<"))
    }

    @Test
    fun `znaki specjalne XML sa escapowane w komorce`() {
        val out = ByteArrayOutputStream()
        runBlocking { writeXlsxFromText("A & B < C", out) }
        val xml = readZip(out.toByteArray())["xl/worksheets/sheet1.xml"]!!
        assertTrue(xml.contains("A &amp; B &lt; C"))
    }
}
