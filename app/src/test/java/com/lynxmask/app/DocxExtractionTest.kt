package com.lynxmask.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Weryfikacja BUG-DOCX-PARTIAL — regex i ekstrakcja z ZIP w pamięci.
 * Logika extractTextFromDocx() jest private w ShareTargetActivity; helper
 * poniżej jest lustrzanym odpowiednikiem pętli z ShareTargetActivity.kt L537–562.
 */
class DocxExtractionTest {

    private val docxTargetPattern = Regex("""word/(document|(header|footer)\d*)\.xml""")

    /** Lustrzane odpowiednik extractTextFromDocx() — bez Context/Uri. */
    private fun extractTextFromDocxZip(zipBytes: ByteArray): String {
        val sb = StringBuilder()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            val textRegex = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>""")
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.matches(docxTargetPattern)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val withBreaks = xml.replace(Regex("""<w:p[ >]"""), "\n<w:p ")
                    textRegex.findAll(withBreaks).forEach { match ->
                        sb.append(match.groupValues[1])
                    }
                    if (name != "word/document.xml") sb.append("\n")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return sb.toString()
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex(""" {2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun buildDocxZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun wT(text: String) = """<w:document><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"""

    @Test
    fun `regex docxTargetPattern matchuje oczekiwane sciezki`() {
        listOf(
            "word/document.xml",
            "word/header1.xml",
            "word/header2.xml",
            "word/header.xml",
            "word/footer1.xml",
            "word/footer.xml",
        ).forEach { path ->
            assertTrue("powinno matchowac: $path", path.matches(docxTargetPattern))
        }
    }

    @Test
    fun `regex docxTargetPattern nie matchuje poza word document header footer`() {
        listOf(
            "word/comments.xml",
            "word/settings.xml",
            "docProps/core.xml",
            "_rels/.rels",
            "[Content_Types].xml",
        ).forEach { path ->
            assertFalse("nie powinno matchowac: $path", path.matches(docxTargetPattern))
        }
    }

    @Test
    fun `ekstrakcja z document xml i header1 xml zawiera tekst z obu`() {
        val zip = buildDocxZip(
            "word/document.xml" to wT("Tresc glowna dokumentu"),
            "word/header1.xml" to wT("Naglowek strony"),
            "word/comments.xml" to wT("Komentarz ukryty"),
        )
        val result = extractTextFromDocxZip(zip)
        assertTrue("brak tresci document.xml", result.contains("Tresc glowna dokumentu"))
        assertTrue("brak tresci header1.xml", result.contains("Naglowek strony"))
        assertFalse("comments.xml nie powinien trafic do wyniku", result.contains("Komentarz ukryty"))
    }
}
