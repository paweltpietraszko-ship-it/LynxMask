package com.lynxmask.app.document

import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// DocxWriter.kt — Faza 1a Document Rebuildera. Podmienia w [DocxArtifact] tekst odpowiadający
// wartościom z tokenMap (wynik PseudonymEngine.pseudonymize) na same tokeny, zapisuje nowy
// DOCX. Silnik NIETKNIĘTY — ta funkcja tylko wyszukuje w plainText gdzie wylądowały już
// znalezione przez silnik wartości, nie robi własnej detekcji PII.

private fun escapeXmlText(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Każdy token może wystąpić w plainText WIĘCEJ NIŻ RAZ — silnik dedupluje (ta sama wartość
 * → ten sam token), ale każde wystąpienie w tekście trzeba podmienić osobno. Zwraca listę
 * (zakres w plainText, token) posortowaną po pozycji.
 */
private fun locateTokenRanges(plainText: String, tokenMap: Map<String, String>): List<Pair<IntRange, String>> {
    val located = mutableListOf<Pair<IntRange, String>>()
    for ((token, original) in tokenMap) {
        if (original.isEmpty()) continue
        var searchFrom = 0
        while (true) {
            val idx = plainText.indexOf(original, searchFrom)
            if (idx < 0) break
            located += (idx until (idx + original.length)) to token
            searchFrom = idx + original.length
        }
    }
    return located.sortedBy { it.first.first }
}

/**
 * @return true jeśli zapis się powiódł.
 *
 * BUG-DOCX-SPLIT-RUN (Faza 1a, znane ograniczenie): jeśli Word rozbił jedną frazę na kilka
 * `<w:t>` (segmentów), a dopasowana encja obejmuje więcej niż jeden segment — pierwszy
 * dostaje token, reszta jest czyszczona (pusty string), żeby fraza nie została zdublowana
 * w wyniku. Faza 1b doda scalanie segmentów PRZED wyszukiwaniem, żeby to nie było potrzebne.
 */
internal suspend fun writeDocxArtifact(
    artifact: DocxArtifact,
    tokenMap: Map<String, String>,
    outputStream: OutputStream,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val located = locateTokenRanges(artifact.plainText, tokenMap)

        // index w artifact.segments -> nowy tekst (null = bez zmian).
        val replacement = arrayOfNulls<String>(artifact.segments.size)
        for ((range, token) in located) {
            val overlapIdx = artifact.segments.indices.filter { i ->
                val seg = artifact.segments[i]
                seg.plainStart < range.last + 1 && range.first < seg.plainEnd
            }
            if (overlapIdx.isEmpty()) continue
            replacement[overlapIdx.first()] = token
            overlapIdx.drop(1).forEach { i -> replacement[i] = "" }
        }

        val documentXml = artifact.zipEntries[DOCX_DOCUMENT_PART]?.toString(Charsets.UTF_8)
        if (artifact.segments.isNotEmpty() && documentXml == null) return@withContext false

        // Segmenty są już w kolejności XML (kolejność regex.findAll przy ekstrakcji) —
        // patchujemy OD KOŃCA, żeby wcześniejsze wtRange zostały prawidłowe mimo że
        // podmieniany tekst ma inną długość niż oryginał (ten sam trik co applyAll
        // w AnchorEngine.kt — fold od końca, wcześniejsze indeksy się nie przesuwają).
        val patchedXml = documentXml?.let { StringBuilder(it) }
        if (patchedXml != null) {
            for (i in artifact.segments.indices.reversed()) {
                val newText = replacement[i] ?: continue
                val seg = artifact.segments[i]
                patchedXml.replace(seg.wtRange.start, seg.wtRange.end, escapeXmlText(newText))
            }
        }

        ZipOutputStream(outputStream).use { zip ->
            for ((name, bytes) in artifact.zipEntries) {
                zip.putNextEntry(ZipEntry(name))
                if (name == DOCX_DOCUMENT_PART && patchedXml != null) {
                    zip.write(patchedXml.toString().toByteArray(Charsets.UTF_8))
                } else {
                    zip.write(bytes)
                }
                zip.closeEntry()
            }
        }
        DebugLogBuffer.log("DocxWriter", "Zapisano DOCX: ${located.size} podmian")
        true
    } catch (e: Exception) {
        DebugLogBuffer.log("DocxWriter", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
