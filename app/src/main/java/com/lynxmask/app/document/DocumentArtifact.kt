package com.lynxmask.app.document

import android.net.Uri

// DocumentArtifact.kt — typy nośne dla Document Rebuildera (DOCX/PDF round-trip).
// Odseparowane od DocumentExtractor.kt (płaska ekstrakcja tekstu, bez pamięci pozycji) —
// artefakt niesie WYSTARCZAJĄCO danych żeby po maskowaniu podmienić tekst z powrotem
// w oryginalnej strukturze pliku, bez dotykania PseudonymEngine (patrz brief Cursora 09.07,
// gałąź feature/document-export).

/** Zakres w surowym tekście XML jednej części OOXML (np. word/document.xml), w znakach. */
internal data class XmlRange(val start: Int, val end: Int)

/**
 * Jeden logiczny fragment tekstu z DOCX — w fazie 1a zawsze dokładnie jeden `<w:t>`.
 * Faza 1b doda scalanie sąsiednich `<w:r>` w tym samym akapicie (Word dzieli frazy na
 * kilka runów, np. "Kowal" + "ski").
 *
 * [plainStart]/[plainEnd] to offsety w [DocxArtifact.plainText] — DOKŁADNIE ten sam tekst,
 * który trafia do PseudonymEngine.pseudonymize(). Po znalezieniu dopasowania w plainText
 * mapujemy zakres z powrotem na segmenty przez te offsety.
 */
internal data class DocxTextSegment(
    val partPath: String,
    val wtRange: XmlRange,
    val plainStart: Int,
    val plainEnd: Int,
    val text: String,
)

/**
 * Wynik ekstrakcji DOCX gotowy do (a) wysłania plainText do silnika, (b) późniejszego
 * zapisu przez DocxWriter. [zipEntries] to WSZYSTKIE części oryginalnego pliku bez zmian —
 * patch dotyczy tylko [segments], reszta (styles.xml, media, rels) przechodzi 1:1.
 */
internal data class DocxArtifact(
    val zipEntries: Map<String, ByteArray>,
    val plainText: String,
    val segments: List<DocxTextSegment>,
    // Nullable celowo: rdzeń parsujący (parseDocxDocument) jest czysty, testowalny w JVM
    // bez Androida — nie dotyka Uri. sourceUri dokleja dopiero cienki wrapper I/O.
    val sourceUri: Uri? = null,
) : DocumentArtifact

/** Wspólny typ nośny dla wszystkich formatów round-trip (DOCX teraz, PDF w fazie 2). */
internal sealed interface DocumentArtifact
