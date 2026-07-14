package com.lynxmask.app.document

import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// XlsxWriter.kt — Document Rebuilder, ten sam minimalistyczny wzorzec co DocxWriter.kt:
// zapisujemy gotowy, już zamaskowany tekst jako nowy, samodzielny xlsx — jeden arkusz,
// wiersz na linię tekstu, kolumny z podziału po tabulatorze (tak samo ekstrahowane w
// XlsxExtractor.kt). Bez formuł/stylów/wielu arkuszy oryginału.

private fun escapeXmlText(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

private const val WORKBOOK_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Arkusz1" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

/** A1-owa nazwa kolumny dla indeksu 0-based (0 -> A, 25 -> Z, 26 -> AA...). */
private fun columnName(index: Int): String {
    var n = index
    val sb = StringBuilder()
    do {
        sb.insert(0, ('A' + (n % 26)))
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

/** Buduje xl/worksheets/sheet1.xml — jeden wiersz na linię tekstu, komórki z podziału po "\t". */
private fun buildSheetXml(text: String): String {
    val rowsXml = StringBuilder()
    text.split("\n").forEachIndexed { rowIdx, line ->
        val rowNum = rowIdx + 1
        rowsXml.append("<row r=\"$rowNum\">")
        if (line.isNotEmpty()) {
            line.split("\t").forEachIndexed { colIdx, cell ->
                if (cell.isNotEmpty()) {
                    val ref = "${columnName(colIdx)}$rowNum"
                    rowsXml.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    rowsXml.append(escapeXmlText(cell))
                    rowsXml.append("</t></is></c>")
                }
            }
        }
        rowsXml.append("</row>")
    }
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>$rowsXml</sheetData>
</worksheet>"""
}

/**
 * Zapisuje [text] (gotowy, już zamaskowany tekst — wiersze po "\n", komórki po "\t") jako
 * nowy, samodzielny plik xlsx. Nie dotyka żadnego oryginalnego pliku.
 */
internal suspend fun writeXlsxFromText(
    text: String,
    outputStream: OutputStream,
): DocumentWriteResult = withContext(Dispatchers.IO) {
    try {
        val sheetXml = buildSheetXml(text)
        ZipOutputStream(outputStream).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(ROOT_RELS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(WORKBOOK_XML.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(WORKBOOK_RELS.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        DebugLogBuffer.log("XlsxWriter", "Zapisano nowy XLSX: ${text.length} znaków")
        DocumentWriteResult.Success
    } catch (e: Exception) {
        DebugLogBuffer.log("XlsxWriter", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
        DocumentWriteResult.Error("${e.javaClass.simpleName}: ${e.message}")
    }
}
