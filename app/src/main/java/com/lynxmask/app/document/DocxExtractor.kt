package com.lynxmask.app.document

import android.content.Context
import android.net.Uri
import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

// DocxExtractor.kt — Faza 1a Document Rebuildera (gałąź feature/document-export).
// Nie zastępuje DocumentExtractor.extractTextFromDocx (zostaje, używana wszędzie tam gdzie
// wystarczy płaski tekst bez pamięci pozycji). Ta funkcja buduje DODATKOWO mapę segmentów,
// żeby DocxWriter mógł podmienić tekst z powrotem w oryginalnym pliku.
//
// Faza 1a świadomie NIE obsługuje: nagłówków/stopek (tylko word/document.xml), scalania
// sąsiednich <w:r> w jednej frazie (Word czasem dzieli "Kowalski" na "Kowal"+"ski" —
// faza 1b), globalnego collapse spacji (kosmetyczne, nie wpływa na wykrywanie encji przez
// PseudonymEngine). Offsety w plainText są ważne tylko wewnątrz tego artefaktu — nie muszą
// zgadzać się bajt-w-bajt z DocumentExtractor.extractTextFromDocx.
//
// BUG-DOCX-TRIM-OFFSET-SAFETY: celowo BRAK .trim()/collapse na końcu budowy plainText —
// każda pass po zbudowaniu segmentów przesuwałaby ich offsety. Bezpieczniej zostawić
// ewentualną wiodącą/końcową pustą linię niż ryzykować rozjazd segment ↔ plainText.

private val DOCX_TOKEN_REGEX = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|</w:p>|<w:br[^/]*/?>""")
internal const val DOCX_DOCUMENT_PART = "word/document.xml"

private fun decodeXmlEntities(raw: String): String =
    raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

internal suspend fun extractDocxArtifact(uri: Uri, context: Context): DocxArtifact? =
    withContext(Dispatchers.IO) {
        try {
            val zipEntries = mutableMapOf<String, ByteArray>()
            var documentXmlBytes: ByteArray? = null
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val bytes = zip.readBytes()
                        zipEntries[name] = bytes
                        if (name == DOCX_DOCUMENT_PART) documentXmlBytes = bytes
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            val xmlBytes = documentXmlBytes ?: run {
                DebugLogBuffer.log("DocxExtractor", "Brak $DOCX_DOCUMENT_PART w archiwum")
                return@withContext null
            }
            val xml = xmlBytes.toString(Charsets.UTF_8)

            val plainText = StringBuilder()
            val segments = mutableListOf<DocxTextSegment>()
            DOCX_TOKEN_REGEX.findAll(xml).forEach { match ->
                val textGroup = match.groups[1]
                if (textGroup != null) {
                    val decoded = decodeXmlEntities(textGroup.value)
                    if (decoded.isNotEmpty()) {
                        val plainStart = plainText.length
                        plainText.append(decoded)
                        segments += DocxTextSegment(
                            partPath = DOCX_DOCUMENT_PART,
                            wtRange = XmlRange(textGroup.range.first, textGroup.range.last + 1),
                            plainStart = plainStart,
                            plainEnd = plainText.length,
                            text = decoded,
                        )
                    }
                } else {
                    // </w:p> lub <w:br/> — jeden \n, bez duplikatów pod rząd.
                    if (plainText.isNotEmpty() && plainText.last() != '\n') plainText.append('\n')
                }
            }

            DebugLogBuffer.log(
                "DocxExtractor",
                "$DOCX_DOCUMENT_PART: ${segments.size} segmentów, ${plainText.length} znaków"
            )
            DocxArtifact(
                sourceUri = uri,
                zipEntries = zipEntries,
                plainText = plainText.toString(),
                segments = segments,
            )
        } catch (e: Exception) {
            DebugLogBuffer.log("DocxExtractor", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
