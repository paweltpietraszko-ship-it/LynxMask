package com.lynxmask.app

import com.google.mlkit.vision.text.Text

// Próg pewności ML Kit poniżej którego dokument jest odrzucany.
// ML Kit TextElement.confidence: 0.0 (brak pewności) – 1.0 (pełna pewność).
// Dobre zdjęcia: 0.80–0.95. Telefon z ręki: 0.65–0.80. Mocno zniszczony: 0.30–0.55.
internal const val OCR_CONF_THRESHOLD = 0.60f

// Minimalna liczba znaków wynikowych OCR — zabezpieczenie gdy obraz jest kompletnie
// nieczytelny (np. zdjęcie ściany) i confidence nie jest liczone z braku elementów.
internal const val OCR_MIN_CHARS = 50

// Oblicza średnią pewność OCR z elementów tekstowych ML Kit.
// Zwraca 0f gdy ML Kit nie wypełnił confidence (starsze wersje modelu).
internal fun calcOcrConfidence(result: Text): Float {
    val confidences = result.textBlocks
        .flatMap { it.lines }
        .flatMap { it.elements }
        .mapNotNull { it.confidence }
    return if (confidences.isEmpty()) 0f else confidences.average().toFloat()
}

// Sprawdza czy wynik OCR jest wystarczającej jakości do przetwarzania.
// Zwraca true gdy dokument nadaje się do pipeline pseudonimizacji.
internal fun isOcrQualityAcceptable(result: Text): Boolean {
    val conf = calcOcrConfidence(result)
    val chars = result.text.length
    // Gdy ML Kit nie daje confidence (conf==0f) — używamy samej długości tekstu
    return if (conf > 0f) conf >= OCR_CONF_THRESHOLD else chars >= OCR_MIN_CHARS
}

// Overload dla ścieżek gdzie mamy już obliczone conf i długość tekstu (bez obiektu Text).
internal fun isOcrQualityAcceptable(conf: Float?, chars: Int): Boolean =
    if (conf != null && conf > 0f) conf >= OCR_CONF_THRESHOLD else chars >= OCR_MIN_CHARS
