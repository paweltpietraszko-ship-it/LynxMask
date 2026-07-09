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

/** Wynik zapisu — [missingTokens] niepuste = ODMOWA zapisu, nie ostrzeżenie po fakcie. */
internal sealed interface DocxWriteResult {
    data object Success : DocxWriteResult
    /** Wartość z tokenMap nie znaleziona w plainText — zwykle OcrNormalizer coś zmienił
     *  (patrz PseudonymEngine.kt:129, normalize() woła się WEWNĄTRZ pseudonymize, poza
     *  kontrolą wołającego) — plainText w artefakcie jest SUROWY, tokenMap odnosi się do
     *  tekstu PO normalizacji. Fail-closed: wolimy odmówić zapisu niż cicho zostawić PII
     *  jawne w wyeksportowanym DOCX. Diagnoza Cursor 09.07. */
    data class MissingTokens(val tokens: Set<String>) : DocxWriteResult
    data class Error(val message: String) : DocxWriteResult
}

/**
 * BUG-DOCX-SPLIT-RUN (Faza 1a, znane ograniczenie): jeśli Word rozbił jedną frazę na kilka
 * `<w:t>` (segmentów), a dopasowana encja obejmuje więcej niż jeden segment — pierwszy
 * dostaje token, reszta jest czyszczona (pusty string), żeby fraza nie została zdublowana
 * w wyniku. Faza 1b doda scalanie segmentów PRZED wyszukiwaniem, żeby to nie było potrzebne.
 */
internal suspend fun writeDocxArtifact(
    artifact: DocxArtifact,
    tokenMap: Map<String, String>,
    outputStream: OutputStream,
): DocxWriteResult = withContext(Dispatchers.IO) {
    try {
        val located = locateTokenRanges(artifact.plainText, tokenMap)

        // BUG-DOCX-NORMALIZER-MISMATCH-FIX (09.07, diagnoza Cursor): jeśli jakiś token z
        // tokenMap nie ma ANI JEDNEGO wystąpienia w plainText, znaczy że OcrNormalizer
        // zmienił tekst na tyle, że wartość już nie pasuje 1:1 — nie wiemy CZY i GDZIE
        // ta encja realnie wylądowała w dokumencie. Fail-closed zamiast eksportu z dziurą.
        val missing = tokenMap.keys.filterNot { token -> located.any { it.second == token } }.toSet()
        if (missing.isNotEmpty()) {
            DebugLogBuffer.log("DocxWriter", "ODMOWA zapisu — brak dopasowania dla: $missing")
            return@withContext DocxWriteResult.MissingTokens(missing)
        }

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
        if (artifact.segments.isNotEmpty() && documentXml == null) {
            return@withContext DocxWriteResult.Error("Brak $DOCX_DOCUMENT_PART w artefakcie")
        }

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
        DocxWriteResult.Success
    } catch (e: Exception) {
        DebugLogBuffer.log("DocxWriter", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
        DocxWriteResult.Error("${e.javaClass.simpleName}: ${e.message}")
    }
}
