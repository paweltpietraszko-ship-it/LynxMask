package com.lynxmask.app.document

import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// DocxWriter.kt — Document Rebuilder, wersja uproszczona (10.07).
//
// DECYZJA WŁAŚCICIELA (10.07): pełne odwzorowanie oryginalnego formatowania (tabele,
// pogrubienia, obrazki, nagłówki) to poziom ambicji "chmurowej konkurencji" — dla appki
// lokalnej na telefonie wystarczy, żeby zamaskowany tekst z tokenami dało się otworzyć i
// edytować jako docx. Poprzednia wersja (patchowanie oryginalnego word/document.xml przez
// dopasowanie zakresów) wymagała dokładnego zlokalizowania każdej wartości z tokenMap w
// SUROWYM tekście — a normalizacja OCR w PseudonymEngine (Warstwa 0, wewnątrz
// pseudonymize()) zmienia ten tekst, więc dopasowanie non-trywialnie zawodziło (patrz
// historia BUG-DOCX-NORMALIZER-MISMATCH w commitach 09.07). Ta wersja tego problemu nie ma:
// zapisujemy WPROST gotowy, już zamaskowany tekst (ten sam co widać w podglądzie) jako
// świeży, minimalny docx — bez szukania czegokolwiek w oryginalnym pliku.
//
// Bezpieczeństwo: skoro nic z oryginalnego dokumentu nie jest kopiowane 1:1 do wyniku
// (nagłówki/stopki/przypisy po prostu nie trafiają do nowego pliku), znika cała klasa ryzyk
// z poprzedniej wersji (MissingTokens, SourceTextMismatch, UnhandledDocumentParts,
// word-boundary w surowym XML) — nie ma czego przeoczyć, bo nie kopiujemy niczego poza
// tekstem, który PseudonymEngine już przetworzył.

private fun escapeXmlText(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

/** Buduje word/document.xml — jeden akapit `<w:p>` na linię tekstu (podział po "\n"). */
private fun buildDocumentXml(text: String): String {
    val body = StringBuilder()
    for (line in text.split("\n")) {
        body.append("<w:p>")
        if (line.isNotEmpty()) {
            body.append("<w:r><w:t xml:space=\"preserve\">")
            body.append(escapeXmlText(line))
            body.append("</w:t></w:r>")
        }
        body.append("</w:p>")
    }
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$body<w:sectPr/></w:body>
</w:document>"""
}

/**
 * Zapisuje [text] (gotowy, już zamaskowany tekst — dokładnie ten, który user widzi w
 * podglądzie) jako nowy, samodzielny plik docx. Nie dotyka żadnego oryginalnego pliku —
 * nie ma więc czego przeoczyć ani czym wyciec.
 */
internal suspend fun writeDocxFromText(
    text: String,
    outputStream: OutputStream,
): DocumentWriteResult = withContext(Dispatchers.IO) {
    try {
        val documentXml = buildDocumentXml(text)
        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(ROOT_RELS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        DebugLogBuffer.log("DocxWriter", "Zapisano nowy DOCX: ${text.length} znaków")
        DocumentWriteResult.Success
    } catch (e: Exception) {
        DebugLogBuffer.log("DocxWriter", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
        DocumentWriteResult.Error("${e.javaClass.simpleName}: ${e.message}")
    }
}
