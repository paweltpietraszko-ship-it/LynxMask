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

// DocxRoundTripTest.kt — test golden zalecony przez Cursora (code review 09.07) przed
// wpięciem UI: syntetyczny ZIP w pamięci -> parseDocxDocument -> PseudonymEngine.pseudonymize
// -> writeDocxArtifact -> odczyt wynikowego ZIP-a, sprawdzenie że document.xml ma token,
// nie oryginalną wartość. "Jan Kowalski"/"Kowalski" i PESEL są w minimalnym słowniku
// testowym LookupTables.initializeForTesting() — patrz PseudonymEngineTest.kt.
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
        DOCX_DOCUMENT_PART to documentXml.toByteArray(Charsets.UTF_8),
        "word/styles.xml" to "<w:styles/>".toByteArray(Charsets.UTF_8),
        "[Content_Types].xml" to "<Types/>".toByteArray(Charsets.UTF_8),
    )

    @Test
    fun `parseDocxDocument buduje segmenty i plainText`() {
        val artifact = parseDocxDocument(syntheticZipEntries())
        assertTrue("Artefakt powinien się sparsować", artifact != null)
        assertTrue("plainText powinien zawierać Jan Kowalski", artifact!!.plainText.contains("Jan Kowalski"))
        assertTrue("plainText powinien zawierać PESEL", artifact.plainText.contains("44051401459"))
        assertEquals(2, artifact.segments.size)
    }

    @Test
    fun `round-trip - token w wyniku, oryginal znika, inne czesci ZIP bez zmian`() {
        val artifact = parseDocxDocument(syntheticZipEntries())!!
        val engineResult = PseudonymEngine.pseudonymize(artifact.plainText)

        val out = ByteArrayOutputStream()
        val writeResult = runBlocking {
            writeDocxArtifact(artifact, engineResult.tokenMap, artifact.plainText, out)
        }
        assertTrue("Zapis powinien się udać: $writeResult", writeResult is DocxWriteResult.Success)

        val writtenEntries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                writtenEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val outXml = writtenEntries[DOCX_DOCUMENT_PART] ?: error("Brak document.xml w wyniku")
        assertFalse("Jan Kowalski nie powinien zostać jawny w XML", outXml.contains("Jan Kowalski"))
        assertFalse("PESEL nie powinien zostać jawny w XML", outXml.contains("44051401459"))
        assertTrue("XML powinien zawierać token OSOBA", outXml.contains("OSOBA_"))
        assertTrue("XML powinien zawierać token NUMER", outXml.contains("NUMER_"))

        // Części niedotknięte patchem muszą przejść 1:1 — kluczowe dla realnych plików
        // Worda (styles/media/rels), nie tylko document.xml.
        assertEquals("<w:styles/>", writtenEntries["word/styles.xml"])
        assertEquals("<Types/>", writtenEntries["[Content_Types].xml"])
    }

    @Test
    fun `brak dopasowania w tokenMap odmawia zapisu (fail-closed)`() {
        val artifact = parseDocxDocument(syntheticZipEntries())!!
        // Token, którego wartości NIE MA w plainText — symuluje rozjazd po normalizacji.
        val fakeTokenMap = mapOf("OSOBA_099" to "Nieistniejąca Wartość")

        val out = ByteArrayOutputStream()
        val writeResult = runBlocking {
            writeDocxArtifact(artifact, fakeTokenMap, artifact.plainText, out)
        }
        assertTrue(
            "Powinno odmówić zapisu gdy token nie ma dopasowania: $writeResult",
            writeResult is DocxWriteResult.MissingTokens
        )
        assertEquals(setOf("OSOBA_099"), (writeResult as DocxWriteResult.MissingTokens).tokens)
    }

    // Audyt Cursora 09.07 (KRYTYCZNE #1): user edytuje tekst na ekranie Review PRZED
    // maskowaniem — jeśli edycja usunie fragment PII, silnik go nie zobaczy, tokenMap nic
    // o nim nie wie, a bez tej blokady writeDocxArtifact skopiowałby oryginalny, jawny
    // fragment do wyniku ze statusem Success.
    @Test
    fun `sourceText rozny od artifact plainText odmawia zapisu (Review edycja)`() {
        val artifact = parseDocxDocument(syntheticZipEntries())!!
        val editedText = artifact.plainText.replace("Jan Kowalski", "X")
        val engineResult = PseudonymEngine.pseudonymize(editedText)

        val out = ByteArrayOutputStream()
        val writeResult = runBlocking {
            writeDocxArtifact(artifact, engineResult.tokenMap, editedText, out)
        }
        assertTrue(
            "Powinno odmówić zapisu gdy sourceText != artifact.plainText: $writeResult",
            writeResult is DocxWriteResult.SourceTextMismatch
        )
    }

    // Audyt Cursora 09.07 (KRYTYCZNE #3): faza 1a patchuje tylko word/document.xml — jeśli
    // nagłówek ma tekst, mogłoby tam siedzieć PII którego eksport nigdy nie dotknie.
    @Test
    fun `naglowek z tekstem odmawia zapisu`() {
        val zipEntries = syntheticZipEntries() + mapOf(
            "word/header1.xml" to """<w:hdr xmlns:w="ns"><w:p><w:r><w:t>Kancelaria XYZ</w:t></w:r></w:p></w:hdr>"""
                .toByteArray(Charsets.UTF_8)
        )
        val artifact = parseDocxDocument(zipEntries)!!
        assertTrue("Artefakt powinien wykryć tekst w nagłówku", artifact.hasUnhandledTextParts)

        val engineResult = PseudonymEngine.pseudonymize(artifact.plainText)
        val out = ByteArrayOutputStream()
        val writeResult = runBlocking {
            writeDocxArtifact(artifact, engineResult.tokenMap, artifact.plainText, out)
        }
        assertTrue(
            "Powinno odmówić zapisu gdy nagłówek ma tekst: $writeResult",
            writeResult is DocxWriteResult.UnhandledDocumentParts
        )
    }

    // Pusty nagłówek (bez realnego tekstu w <w:t>) nie powinien fałszywie blokować eksportu.
    @Test
    fun `pusty naglowek nie blokuje zapisu`() {
        val zipEntries = syntheticZipEntries() + mapOf(
            "word/header1.xml" to """<w:hdr xmlns:w="ns"><w:p><w:r><w:t></w:t></w:r></w:p></w:hdr>"""
                .toByteArray(Charsets.UTF_8)
        )
        val artifact = parseDocxDocument(zipEntries)!!
        assertFalse("Pusty nagłówek nie powinien ustawiać flagi", artifact.hasUnhandledTextParts)
    }

    // Audyt Cursora 09.07 (WYSOKIE #5): krótka wartość nie może trafić w środek innego słowa.
    @Test
    fun `token nie podmienia sie w srodku niepowiazanego slowa`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="ns"><w:body>
              <w:p><w:r><w:t>Rezydencja Lisowski nie jest tym samym co Lis</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        val zipEntries = mapOf(DOCX_DOCUMENT_PART to xml.toByteArray(Charsets.UTF_8))
        val artifact = parseDocxDocument(zipEntries)!!
        // "Lis" jako fikcyjny token — nie powinien trafić w środek "Lisowski".
        val tokenMap = mapOf("OSOBA_001" to "Lis")

        val out = ByteArrayOutputStream()
        val writeResult = runBlocking {
            writeDocxArtifact(artifact, tokenMap, artifact.plainText, out)
        }
        assertTrue("Zapis powinien się udać: $writeResult", writeResult is DocxWriteResult.Success)

        val writtenEntries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                writtenEntries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val outXml = writtenEntries[DOCX_DOCUMENT_PART]!!
        assertTrue("Lisowski powinien zostać nietknięty", outXml.contains("Lisowski"))
        assertTrue("Samotne Lis powinno zostać podmienione na token", outXml.contains("OSOBA_001"))
        assertFalse("Samotne słowo Lis nie powinno zostać jawne", outXml.contains(Regex("""\bLis\b""")))
    }
}
