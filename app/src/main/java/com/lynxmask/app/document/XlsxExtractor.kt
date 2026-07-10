package com.lynxmask.app.document

import android.content.Context
import android.net.Uri
import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

// XlsxExtractor.kt — Document Rebuilder, ten sam wzorzec co DocxExtractor.kt: parsuje
// XLSX (SpreadsheetML) na płaski tekst do wysłania do PseudonymEngine. Wiersze rozdzielone
// "\n", komórki w wierszu rozdzielone "\t" — wystarczające do maskowania i do odtworzenia
// przybliżonej struktury przy zapisie (patrz XlsxWriter.kt), bez parsowania formuł/stylów.

private val SHARED_STRING_RE = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
private val TEXT_RUN_RE = Regex("""<t(?:\s[^>]*)?>([^<]*)</t>""")
private val ROW_RE = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
// Grupa 1 = atrybuty otwierającego tagu <c ...> (skąd wyciągamy t="..."), grupa 2 = wnętrze
// (puste/null dla samo-zamykającego się <c .../> — komórka bez wartości).
private val CELL_RE = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
private val CELL_TYPE_RE = Regex("""\bt="([^"]*)"""")
private val CELL_VALUE_RE = Regex("""<v>([^<]*)</v>""")
private val INLINE_STR_RE = Regex("""<is>(.*?)</is>""", RegexOption.DOT_MATCHES_ALL)

private fun decodeXmlEntities(raw: String): String =
    raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

/** Wyciąga tekst ze wszystkich `<t>` w fragmencie (jeden `<si>` może mieć kilka run-ów). */
private fun textOf(xmlFragment: String): String =
    TEXT_RUN_RE.findAll(xmlFragment).joinToString("") { decodeXmlEntities(it.groupValues[1]) }

private fun parseSharedStrings(xml: String?): List<String> {
    if (xml == null) return emptyList()
    return SHARED_STRING_RE.findAll(xml).map { textOf(it.groupValues[1]) }.toList()
}

private fun cellText(cellInner: String, type: String?, sharedStrings: List<String>): String = when (type) {
    "s" -> CELL_VALUE_RE.find(cellInner)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { sharedStrings.getOrNull(it) } ?: ""
    "inlineStr" -> INLINE_STR_RE.find(cellInner)?.let { textOf(it.groupValues[1]) } ?: ""
    // "str" (wynik formuły), null (liczba) i wszystko inne (bool/error) — surowa wartość <v>.
    else -> CELL_VALUE_RE.find(cellInner)?.groupValues?.get(1)?.let { decodeXmlEntities(it) } ?: ""
}

/**
 * Rdzeń parsujący — CZYSTA funkcja, zero zależności od Androida. Testowalna w zwykłym
 * teście JVM. Zbiera WSZYSTKIE arkusze `xl/worksheets/sheetN.xml` po kolei, każdy oddzielony
 * pustą linią w plainText.
 */
internal fun parseXlsxDocument(zipEntries: Map<String, ByteArray>): XlsxArtifact? {
    val sharedStrings = parseSharedStrings(zipEntries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8))
    val sheetNames = zipEntries.keys
        .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sorted()
    if (sheetNames.isEmpty()) {
        DebugLogBuffer.log("XlsxExtractor", "Brak xl/worksheets/sheetN.xml w archiwum")
        return null
    }

    val plainText = StringBuilder()
    for (sheetName in sheetNames) {
        val xml = zipEntries[sheetName]!!.toString(Charsets.UTF_8)
        val rows = ROW_RE.findAll(xml).map { rowMatch ->
            CELL_RE.findAll(rowMatch.groupValues[1]).joinToString("\t") { cellMatch ->
                val attrs = cellMatch.groupValues[1]
                val type = CELL_TYPE_RE.find(attrs)?.groupValues?.get(1)
                val inner = cellMatch.groupValues.getOrNull(2) ?: ""
                cellText(inner, type, sharedStrings)
            }
        }.toList()
        if (plainText.isNotEmpty()) plainText.append("\n")
        plainText.append(rows.joinToString("\n"))
    }

    DebugLogBuffer.log("XlsxExtractor", "${sheetNames.size} arkusz(y), ${plainText.length} znaków")
    return XlsxArtifact(plainText = plainText.toString())
}

/** Cienki wrapper I/O — czyta ZIP z Uri, deleguje parsowanie do [parseXlsxDocument]. */
internal suspend fun extractXlsxArtifact(uri: Uri, context: Context): XlsxArtifact? =
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
            parseXlsxDocument(zipEntries)?.copy(sourceUri = uri)
        } catch (e: Exception) {
            DebugLogBuffer.log("XlsxExtractor", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
