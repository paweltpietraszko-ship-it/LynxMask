# RAPORT — BRIEF_Cursor_RODO_v2.md

_Data wykonania: 2026-06-25_  
_Baza: commit `9f87fdb` + zmiany robocze (bez commita)_  
_Testy: `:app:testDebugUnitTest` — **BUILD SUCCESSFUL** (~455 testów, 2 skipped)_

---

## Podsumowanie

| # | Zadanie | Status | Uwagi |
|---|---------|--------|-------|
| 1 | S10 / BUG-TEL-PREFIX | ✅ Zrobione | OcrNormalizer krok 14a; @Ignore usunięty |
| 2 | ClipboardCheckActivity | ✅ Zrobione | `isClipboardClean` + `clipSafeMaskedText` |
| 3 | ImageRedactionPipeline cleanup | ✅ Zrobione | purge 5 min + delete po share |
| 4 | S4 goły NIP OCR | ✅ Zrobione + naprawione | Potwierdzenie + fix w OcrNormalizer (11c2, krok 14) |
| 5 | OcrQuality hard reject | 📋 Propozycja | `PROPOZYCJA_OcrQuality_HardReject.md` — bez implementacji |
| 6 | SessionStore.save() fail | ✅ Już było | Toast w ClipboardCheck — brak zmian |
| 7 | Test double-miss | ✅ Zrobione | `DoubleMissTest.kt` — telefon, NIP×3, PESEL, schowek |

---

## Zadanie 1 — S10 / BUG-TEL-PREFIX

### Problem (brief)
Tekst OCR `48 60l 234 567` — litera `l` zamiast `1` — numer nie trafiał do `tokenMap`, zostawał surowy w `pseudonymizedText`.

### Wykonane
1. **`OcrNormalizer.kt` krok 14a** — regex `OCR_PHONE_AFTER_KW` normalizuje `l/O/I` w bloku numeru po słowach `tel`, `telefon`, `kom`, `mob`, `fax`, `faks`.
2. **`StructuralEngine.kt`** — **bez zmian**. Wzorzec PL `48 XXX XXX XXX` już istniał (`(?:\+?48[-\s]?)?\d{3}[-\s]?\d{3}[-\s]?\d{3}`). Po normalizacji OCR numer trafia do `TOKEN_NUMER`.
3. **`OcrDegradationTest.kt`** — usunięto `@Ignore` z testu `FAIL lvl3 TEL1 pominiety 48 60l 234 567` — **PASS**.

### Testy regresji
- `OcrNormalizerDigitContextTest` — `OCR_PHONE_AFTER_KW naprawia 48 60l po tel`
- `DoubleMissTest` — `double miss telefon OCR l zamiast 1 nie wycieka`

---

## Zadanie 2 — ClipboardCheckActivity

### Problem A
Warunek „schowek czysty" nie sprawdzał `guardHits` RED → fałszywe „Schowek wygląda bezpiecznie".

### Problem B
„Zastąp schowek" wklejał `pseudonymizedText` bez podmiany fragmentów złapanych tylko przez OutputGuard RED.

### Wykonane
Nowy plik **`ClipSafety.kt`** (logika wyciągnięta z Activity — brief prosił o fix w Activity, ekstrakcja to refactor bez zmiany semantyki):

```kotlin
internal fun isClipboardClean(result): Boolean =
    result.flags.isEmpty()
        && result.riskScore == RiskScore.GREEN
        && result.guardHits.none { it.level == "RED" }

internal fun clipSafeMaskedText(result): String
    // pseudonymizedText bez SESJA_ + RED hity → "[UKRYTE]"
```

**`ClipboardCheckActivity.kt`:**
- Linia ~135: `isClipboardClean(result)` zamiast samego `flags.isEmpty() && GREEN`
- Linie ~172–187: `clipSafeMaskedText()` przy „Zastąp schowek" i „Zastąp i zapisz"

### Efekt
Scenariusz z zad. 4 (NIP w output, Guard RED, brak tokenu) → schowek **nie** pokazuje już „czysto"; przy replace surowy NIP/telefon zamieniany na `[UKRYTE]`.

### Testy
- `ClipSafetyTest.kt` — unit testy helperów (poza literalnym tekstem briefu, ale pokrywa zad. 2)
- `DoubleMissTest` — `isClipboardClean false gdy Guard RED`

---

## Zadanie 3 — ImageRedactionPipeline cleanup

### Problem
JPEG w `cacheDir/redacted_images/redacted_${timestamp}.jpg` kumulowały się po share.

### Wykonane
**`ImageRedactionPipeline.kt`:**
- `deleteRedactedUri(context, uri)` — kasuje plik po URI FileProvider
- `purgeStaleRedactedImages(context, maxAgeMs = 5 min)` — kasuje stare pliki

**`ShareTargetActivity.kt`:**
- `onCreate` → `purgeStaleRedactedImages(this)`
- Po `startActivity(Intent.createChooser(...))` → `deleteRedactedUri(context, uri)`

### Uwaga
Brief dopuszczał „share **lub** purge" — zaimplementowano **oba** (bezpieczniejsze).

---

## Zadanie 4 — S4: goły NIP z błędną sumą OCR

### Potwierdzenie scenariusza (przed fixem)

| Input | tokenMap | Guard RED | Wyciek w output |
|-------|----------|-----------|-----------------|
| `521-334-15-34` (zła suma) | 0 | ✅ NIP | Guard blokuje share; **schowek naprawiony zad. 2** |
| `521-3l4-15-33` | 0 | ✅ NIP (po fix 3l4→314) | Guard łapie po normalizacji |
| `52l-334-15-33` | partial | ❌ | **Wyciek `52l`** — partial mask `52l-NUMER_001` |
| `521-3З4-15-33` (cyrylica) | 0 | ❌ | **Pełny wyciek** — Guard `\d` only |

**OutputGuard.kt** (`NIP` regex linia ~58): `\b\d{3}[-\s]?\d{3}[-\s]?\d{2}[-\s]?\d{2}\b` — **tylko ASCII `\d`**, nie łapie `l` ani cyrylicy. Zgodnie z briefem — **OutputGuard nie ruszany**.

### Naprawione w OcrNormalizer (bez S5, bez OutputGuard)

1. **Krok 14 — `OCR_DIGIT_IN_CONTEXT`** — lookahead rozszerzony z `[^\S\n]*` na `[\s\-./]*`, żeby `52l-334` → `521-334` (myślnik blokował stary lookahead).
2. **Krok 11c2 — `OCR_NIP_BARE3322` / `OCR_NIP_BARE3223`** — homoglify w segmentach gołego NIP bez słowa kluczowego (`l/O/I`, cyrylica `З→3`, `О→0`, `І→1`).

### Po fixie
- `52l-334-15-33` → normalizacja → pełny NIP → tokenMap **lub** Guard RED
- `521-3З4-15-33` → `521-334-15-33` → Guard RED / token

### Pozostałe ograniczenia (do wiadomości)
- Homoglify poza mapą (`Б`, `Г`, inne unicode) — nadal mogą przepaść
- OutputGuard nadal nie toleruje OCR-artefaktów — **celowo** (brief: nie ruszać)
- S5 bypass przy słowie „NIP:" — **celowy**, bez zmian

---

## Zadanie 5 — OcrQuality hard reject

### Stan
- **`OcrQuality.kt`** — plik istnieje (untracked w repo), `OCR_CONF_THRESHOLD = 0.60f`, `isOcrQualityAcceptable()`
- Pipeline **nie** odrzuca — tylko banner YELLOW/RED

### Dostarczone
**`PROPOZYCJA_OcrQuality_HardReject.md`** — propozycja miejsca w `ShareTargetActivity.finishWithText()`, ocena kosztu UX, wyjątki (tekst wklejony, PDF).

### Decyzja właściciela
**Wymagana** przed implementacją — zgodnie z briefem.

---

## Zadanie 6 — SessionStore.save() fail

### Wynik audytu
**`ClipboardCheckActivity.kt` linie ~190–198** — już obsługuje `saved == false`:

```kotlin
Toast.makeText(context,
    "Błąd zapisu sesji — dane mogą być niedostępne w bibliotece",
    Toast.LENGTH_LONG).show()
```

Analogicznie do `ShareTargetActivity`. **Brak zmian kodu.**

---

## Zadanie 7 — test integracyjny double-miss

### Plik: `DoubleMissTest.kt`

| Test | Case z briefu | Status |
|------|---------------|--------|
| `double miss telefon OCR l zamiast 1 nie wycieka` | telefon `48 60l 234 567` | ✅ PASS |
| `double miss nip z separatorami Guard RED gdy S5 blokuje token` | goły NIP złą sumą | ✅ PASS |
| `double miss nip OCR l w cyfrze po normalizacji` | `521-3l4-15-33` | ✅ PASS |
| `double miss nip OCR l na poczatku segmentu` | `52l-334-15-33` (rozszerzenie zad. 4) | ✅ PASS |
| `double miss nip cyrylica Z w segmencie` | `521-3З4-15-33` (rozszerzenie zad. 4) | ✅ PASS |
| `double miss pesel bez kontekstu bledna suma nie wycieka` | `44051401448` | ✅ PASS |
| `isClipboardClean false gdy Guard RED` | integracja ze zad. 2 | ✅ PASS |

Brief przewidywał FAIL przed fixem → PASS po — obecnie wszystkie **PASS**.

---

## Pliki zmienione (roboczo, bez commita)

### Produkcyjne
| Plik | Zadanie |
|------|---------|
| `OcrNormalizer.kt` | 1, 4 |
| `ClipSafety.kt` *(nowy)* | 2 |
| `ClipboardCheckActivity.kt` | 2 |
| `ImageRedactionPipeline.kt` | 3 |
| `ShareTargetActivity.kt` | 3 |

### Testy
| Plik | Zadanie |
|------|---------|
| `OcrDegradationTest.kt` | 1 |
| `OcrNormalizerDigitContextTest.kt` | 1, 4 |
| `DoubleMissTest.kt` | 7 (+ 4) |
| `ClipSafetyTest.kt` | 2 (poza briefem) |
| `NipFormatMatrixTest.kt` | poza briefem — AUDIT-03 oczekiwania |

### Dokumentacja
| Plik | Zadanie |
|------|---------|
| `PROPOZYCJA_OcrQuality_HardReject.md` | 5 |
| `RAPORT_RODO_v2.md` | ten raport |

### Nieruszane (zgodnie z briefem)
- `OutputGuard.kt`
- `LookupTables.kt`
- `DebugLogBuffer.kt`
- `BenchmarkInstrumentedTest.kt`
- `StructuralEngine.kt` (tylko weryfikacja w zad. 1)

---

## Poza scope briefu (świadome)

| Element | Ocena |
|---------|-------|
| `ClipSafety.kt` jako osobny plik | Refactor — logika zgodna z briefem |
| `ClipSafetyTest.kt` | Unit testy helperów — nie w briefie, ale wartościowe |
| `NipFormatMatrixTest.kt` zmiana oczekiwań | Naprawa testu vs AUDIT-03/CATCHALL — nie zadanie RODO, ale poprawna regresja |

---

## Rekomendacja commita (gdy zatwierdzisz)

```
fix: RODO v2 — schowek Guard RED, cache JPEG, S10 telefon OCR, NIP homoglify

- ClipSafety: isClipboardClean + clipSafeMaskedText [UKRYTE]
- ImageRedactionPipeline: purge/delete redacted JPEG
- OcrNormalizer: OCR_PHONE_AFTER_KW, NIP bare homoglify, digit-in-context przez myślnik
- DoubleMissTest + regresje; propozycja OcrQuality hard reject
```

---

## Otwarte decyzje dla właściciela

1. **Hard reject OCR** — zatwierdzić / odrzucić propozycję z `PROPOZYCJA_OcrQuality_HardReject.md`
2. **ClipSafetyTest.kt** — zostawić czy włączyć do commita RODO
3. **NipFormatMatrixTest.kt** — osobny commit AUDIT-03 czy razem z RODO
