# Propozycja: OcrQuality hard reject (Zadanie 5 — bez implementacji)

**Status:** do zatwierdzenia przez właściciela  
**Plik:** `OcrQuality.kt` — `OCR_CONF_THRESHOLD = 0.60f`, `isOcrQualityAcceptable()`  
**Data:** 2026-06-25

---

## Stan obecny

Pipeline OCR (`ShareTargetActivity.finishWithText` / ścieżka Review → pseudonimizacja):

1. ML Kit zwraca tekst + opcjonalnie `mlKitConfidence`
2. `OcrNormalizer.assessQuality()` → **banner** (`qualityWarning` w UI)
3. Dokument **zawsze** idzie do `PseudonymEngine.pseudonymize()` — brak twardego stopu

---

## Proponowana implementacja

### Miejsce w kodzie

`ShareTargetActivity.kt` — funkcja `finishWithText()`, **po** ekstrakcji OCR, **przed** `PseudonymEngine.pseudonymize()`:

```kotlin
// W processImage / extractRawText — przekazać pełny obiekt Text z ML Kit, nie tylko String
val conf = calcOcrConfidence(visionText)
if (conf > 0f && !isOcrQualityAcceptable(visionText)) {
    setState(ShareScreenState.Error(
        "Jakość skanu jest zbyt niska (${(conf * 100).toInt()}%).\n\n" +
        "Spróbuj ponownie: lepsze oświetlenie, prostsze ułożenie dokumentu, " +
        "skaner GMS zamiast zdjęcia z galerii."
    ))
    return
}
```

Analogicznie w ścieżce PDF (średnia confidence ze stron) i GMS Document Scanner.

**Nie stosować** hard reject dla:

- `text/plain`, DOCX (tekst natywny — confidence = N/A, bramka oparta na długości)
- schowka (`ClipboardCheckActivity`)

### Fallback gdy confidence = 0

`isOcrQualityAcceptable()` już ma regułę: `conf == 0` → akceptuj jeśli `chars >= 50`.  
Hard reject tylko gdy ML Kit **podaje** confidence **i** jest < 0.60.

---

## Koszt UX

| Scenariusz | Bez hard reject | Z hard reject |
|---|---|---|
| Dobre zdjęcie (conf 0.85) | OK | OK |
| Średnie (conf 0.55) | Banner + częściowa maska → **fałszywe poczucie bezpieczeństwa** | **Blokada** + komunikat „zrób lepsze zdjęcie” |
| Zła jakość, użytkownik ignoruje banner | Może wysłać PII | Nie przejdzie pipeline |
| DOCX / tekst | OK | OK (bez zmian) |
| Schowek | OK | OK (bez zmian) |

**Ryzyko UX:** użytkownik ze słabym aparatem / rozmazanym PDF dostanie **ścianę** częściej niż dziś — to **zamierzone** (minimalizacja ryzyka RODO vs wygoda).

**Rekomendacja produktowa:** hard reject + przycisk „Przetwórz mimo niskiej jakości” (świadome odkrycie, jak YELLOW flags) — **osobna decyzja**, nie w pierwszej iteracji.

---

## Metryki po wdrożeniu

- Benchmark v2: sekcja **REJECTED** — dokumenty odrzucone przez bramkę (oczekiwane zachowanie)
- Nie liczyć reject jako „miss silnika”

---

## Szacunek pracy

- Kod: ~40–60 linii (`ShareTargetActivity`, ewentualnie `ImageRedactionScreen` OCR path)
- Testy: 2–3 JVM (mock confidence) + 1 instrumented smoke
- **Bez zatwierdzenia właściciela — nie merge**
