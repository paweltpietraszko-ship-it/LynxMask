# BRIEF dla Cursora — IMAGE-REDACT UI (28.06.2026)

## Co to jest IMAGE-REDACT

LynxMask Mobile to aplikacja do pseudonimizacji PII (danych osobowych) w dokumentach.
IMAGE-REDACT to moduł do anonimizacji **zdjęć** dokumentów (dowód osobisty, paszport,
umowa ze zdjęciem) przed wysłaniem.

**Flow:**
1. Użytkownik udostępnia zdjęcie z galerii lub aparatu → `ShareTargetActivity`
2. ML Kit wykrywa twarze → `ImageRedactionPipeline` → automatyczny blur
3. Użytkownik rysuje ręczny prostokąt na podpisie/pieczątce → blur opcjonalnie
4. OCR (ML Kit TextRecognition) podpowiada tekst w ręcznym prostokącie → opcja dodania do słownika
5. Użytkownik przegląda listę regionów, może odkryć wybrane → „Zapisz do biblioteki" lub „Udostępnij"

**Fazy:**
- F0 ✅ — twarz blur + podgląd + share JPEG
- F1 ✅ — ręczny prostokąt + toggle odkrycia + OCR sugestia do słownika
- F2 🔲 — kolejność redakcja obrazu → OCR tekstu (nie ruszaj teraz)

---

## Problem UI (dlaczego ten brief istnieje)

`ImageRedactionScreen.kt` **działa poprawnie**, ale wygląda jak inna aplikacja.
Reszta LynxMask używa design systemu Lynx (DesignTokens.kt). Ten ekran —
tylko częściowo.

**Co jest nie tak:**
- Nagłówek: `Text("Sprawdź i zapisz", fontWeight = FontWeight.Bold, fontSize = 18.sp)`
  → powinien używać `LynxTypography` i `LynxColors.TextPrimary`
- Kolory bezpośrednio w kodzie: `MaterialTheme.colorScheme.onSurfaceVariant`, `MaterialTheme.colorScheme.error`
  → zastąpić `LynxColors.TextSecondary`, `LynxColors.Red`
- `MaterialTheme.typography.bodySmall`, `MaterialTheme.typography.labelMedium` itp.
  → `LynxTypography` lub dosłowne `fontSize = 12.sp` jak w reszcie apki
- Lista regionów („Co wysłać") — płaski Switch + label → zamienić na chipy/karty
  jak w `PseudonymResultPanel.kt` (wzorzec RED/YELLOW kart)
- Akcje na dole: 4 poziomy przycisków bez hierarchii
  → jeden sticky footer: **primary** Zapisz do biblioteki, **secondary** Udostępnij

**Co już jest dobrze:** `LynxPrimaryButton`, `LynxGhostButton`, `LynxSecondaryButton` są użyte.

---

## Design system (jedyne źródło prawdy)

**Plik:** `app/src/main/java/com/lynxmask/app/ui/theme/DesignTokens.kt`

Kluczowe tokeny:
```kotlin
LynxColors.Background      // tło ekranu
LynxColors.Surface         // karta/panel
LynxColors.TextPrimary     // główny tekst
LynxColors.TextSecondary   // opis, podpis
LynxColors.Blue            // akcent, nagłówki sekcji (mono)
LynxColors.Red             // ostrzeżenie, destrukcja
LynxColors.Border          // HorizontalDivider
LynxSpacing.sm / md / lg
LynxShapes.ButtonRadius    // = 2.dp (ostre przyciski)
LynxTypography.Mono        // czcionka mono (etykiety sekcji)
```

**Wzorce do naśladowania:**
- `LibraryScreen.kt` → `SessionActionButton` (pełna szerokość, sticky footer)
- `PseudonymResultPanel.kt` → karty z typem (OSOBA/ADRES), chipy toggle
- `MainActivity.kt` sekcja SecurityModal → styl nagłówka sekcji (MONO 9sp + Blue)

---

## Zakres zmian

### `ImageRedactionScreen.kt` — JEDYNY plik do edycji

| Co | Jak |
|----|-----|
| Nagłówek „Sprawdź i zapisz" | Styl zgodny z resztą apki — bez `FontWeight.Bold` z powietrza |
| Opis pod nagłówkiem | `LynxColors.TextSecondary`, `fontSize = 12.sp` |
| Ostrzeżenie OCR confidence | `LynxColors.Red` zamiast `MaterialTheme.colorScheme.error` |
| Lista regionów „Co wysłać" | Zamień `RegionToggleRow` (Switch+label) na chipy/karty z ikoną typu |
| Bulk „Zakryj/Odkryj wszystkie" | Segmented control lub 2 chipy — nie `TextButton` |
| Footer akcji | Sticky `Column` na dole: `LynxPrimaryButton` Zapisz + `LynxSecondaryButton` Udostępnij |
| Przycisk Anuluj | `LynxGhostButton` w nagłówku lub `TextButton` u góry — nie w footerze |
| Kolory ramek Canvas | Możesz zostawić hardcoded (to logika wizualna, nie UI) |

### Pliki których NIE ruszasz

| Plik | Powód |
|------|-------|
| `ImageRedactionPipeline.kt` | Logika blur/pixelate/ML Kit — działa poprawnie |
| `SessionStore.kt` | Zapis do biblioteki |
| `PseudonymEngine.kt` | Silnik tekstowy |
| `ShareTargetActivity.kt` | Routing share flow |
| Jakiekolwiek `*Test*.kt` | Testy — Claude Code je zarządza |

---

## Typy regionów (do etykiet na kartach)

```kotlin
enum class RegionType { FACE, MANUAL }
```

- `FACE` → ikona/etykieta „Twarz" (blur domyślny, niebieska ramka)
- `MANUAL` → iketa/etykieta „Maska ręczna" (może być odkryta, pomarańczowa ramka gdy odkryta)

Región ma pole `label: String` — to tekst OCR jeśli wykryty, inaczej „Maska ręczna" lub „Twarz N".

---

## Kryterium sukcesu

Ekran redakcji obrazu **wizualnie należy do tej samej aplikacji** co `LibraryScreen`
i `PseudonymResultPanel`. Użytkownik widzi max 2 oczywiste akcje na dole (Zapisz / Udostępnij),
lista regionów scrolluje się nad obrazem.

**Logika ekranu nie zmienia się** — tylko wygląd.

---

## Uwaga dla Cursora — commitowanie

**NIE commituj sam.** Dostarcz zmiany, Claude Code przejrzy i commituje.
Powód: hook PostToolUse + review przed każdym commitem.
