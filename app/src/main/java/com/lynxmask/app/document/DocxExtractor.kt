package com.lynxmask.app.document

import android.content.Context
import android.net.Uri
import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

// DocxExtractor.kt — Document Rebuilder (gałąź feature/document-export).
// Nie zastępuje DocumentExtractor.extractTextFromDocx (zostaje, używana wszędzie tam gdzie
// wystarczy płaski tekst bez dalszego przetwarzania). Ta funkcja parsuje word/document.xml
// na płaski tekst do wysłania do PseudonymEngine — eksport (DocxWriter.kt) nie patchuje już
// oryginału, więc nie musimy pamiętać segmentów/zakresów XML (uproszczone 10.07).

private val DOCX_TOKEN_REGEX = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|</w:p>|<w:br[^/]*/?>""")
private const val DOCX_DOCUMENT_PART = "word/document.xml"

private fun decodeXmlEntities(raw: String): String =
    raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

/**
 * Rdzeń parsujący — CZYSTA funkcja, zero zależności od Androida (żadnego Uri/Context).
 * Testowalna w zwykłym teście JVM (src/test) bez Robolectrica.
 */
internal fun parseDocxDocument(zipEntries: Map<String, ByteArray>): DocxArtifact? {
    val xmlBytes = zipEntries[DOCX_DOCUMENT_PART] ?: run {
        DebugLogBuffer.log("DocxExtractor", "Brak $DOCX_DOCUMENT_PART w archiwum")
        return null
    }
    val xml = xmlBytes.toString(Charsets.UTF_8)

    val plainText = StringBuilder()
    DOCX_TOKEN_REGEX.findAll(xml).forEach { match ->
        val textGroup = match.groups[1]
        if (textGroup != null) {
            val decoded = decodeXmlEntities(textGroup.value)
            if (decoded.isNotEmpty()) plainText.append(decoded)
        } else {
            // </w:p> lub <w:br/> — jeden \n, bez duplikatów pod rząd.
            if (plainText.isNotEmpty() && plainText.last() != '\n') plainText.append('\n')
        }
    }

    DebugLogBuffer.log("DocxExtractor", "$DOCX_DOCUMENT_PART: ${plainText.length} znaków")
    return DocxArtifact(plainText = plainText.toString())
}

/** Cienki wrapper I/O — czyta ZIP z Uri, deleguje parsowanie do [parseDocxDocument]. */
internal suspend fun extractDocxArtifact(uri: Uri, context: Context): DocxArtifact? =
    withContext(Dispatchers.IO) {
        try {
            val zipEntries = mutableMapOf<String, ByteArray>()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        zipEntries[entry.name] = zip.readBytes()
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            parseDocxDocument(zipEntries)?.copy(sourceUri = uri)
        } catch (e: Exception) {
            DebugLogBuffer.log("DocxExtractor", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
