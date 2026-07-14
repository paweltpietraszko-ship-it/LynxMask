package com.lynxmask.app.document

import android.net.Uri

// DocumentArtifact.kt — typy nośne dla Document Rebuildera (DOCX/PDF/XLSX round-trip).
//
// Uproszczone 10.07 (decyzja właściciela): eksport nie patchuje oryginalnego pliku —
// zapisuje świeży plik z gotowego, zamaskowanego tekstu (patrz DocxWriter/PdfWriter/
// XlsxWriter.kt). Artefakt istnieje więc tylko po to, żeby (a) dostarczyć [plainText] do
// PseudonymEngine, (b) powiedzieć UI jaki format zapisu zaproponować ("Zapisz DOCX" vs
// "Zapisz PDF" vs "Zapisz Excel") — nie niesie segmentów/zakresów/oryginalnych bajtów ZIP.

/** Wynik ekstrakcji DOCX — [plainText] trafia do PseudonymEngine.pseudonymize(). */
internal data class DocxArtifact(
    val plainText: String,
    // Nullable celowo: rdzeń parsujący (parseDocxDocument) jest czysty, testowalny w JVM
    // bez Androida — nie dotyka Uri. sourceUri dokleja dopiero cienki wrapper I/O.
    val sourceUri: Uri? = null,
) : DocumentArtifact

/** Wynik ekstrakcji PDF (przez istniejący OCR `ocrFromPdfUri` — PDF zawsze traktowany jak skan). */
internal data class PdfArtifact(
    val plainText: String,
    val sourceUri: Uri? = null,
) : DocumentArtifact

/** Wynik ekstrakcji XLSX — [plainText] to komórki po wierszach, kolumny rozdzielone tabulatorem. */
internal data class XlsxArtifact(
    val plainText: String,
    val sourceUri: Uri? = null,
) : DocumentArtifact

/** Wspólny typ nośny dla wszystkich formatów round-trip. */
internal sealed interface DocumentArtifact

/** Wspólny wynik zapisu — jeden typ dla DocxWriter/PdfWriter/XlsxWriter, żeby nie duplikować. */
internal sealed interface DocumentWriteResult {
    data object Success : DocumentWriteResult
    data class Error(val message: String) : DocumentWriteResult
}
