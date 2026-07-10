package com.lynxmask.app.document

import android.net.Uri

// DocumentArtifact.kt — typy nośne dla Document Rebuildera (DOCX round-trip).
//
// Uproszczone 10.07 (decyzja właściciela): eksport nie patchuje już oryginalnego pliku —
// zapisuje świeży docx z gotowego, zamaskowanego tekstu (patrz DocxWriter.kt). Artefakt
// istnieje więc tylko po to, żeby dostarczyć [plainText] do PseudonymEngine — nie musi
// nieść segmentów/zakresów XML ani oryginalnych bajtów ZIP.

/**
 * Wynik ekstrakcji DOCX — [plainText] trafia do PseudonymEngine.pseudonymize().
 */
internal data class DocxArtifact(
    val plainText: String,
    // Nullable celowo: rdzeń parsujący (parseDocxDocument) jest czysty, testowalny w JVM
    // bez Androida — nie dotyka Uri. sourceUri dokleja dopiero cienki wrapper I/O.
    val sourceUri: Uri? = null,
) : DocumentArtifact

/** Wspólny typ nośny dla wszystkich formatów round-trip (DOCX teraz, PDF w fazie 2). */
internal sealed interface DocumentArtifact
