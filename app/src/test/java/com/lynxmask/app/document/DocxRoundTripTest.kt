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
            writeDocxArtifact(artifact, engineResult.tokenMap, out)
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
            writeDocxArtifact(artifact, fakeTokenMap, out)
        }
        assertTrue(
            "Powinno odmówić zapisu gdy token nie ma dopasowania: $writeResult",
            writeResult is DocxWriteResult.MissingTokens
        )
        assertEquals(setOf("OSOBA_099"), (writeResult as DocxWriteResult.MissingTokens).tokens)
    }
}
