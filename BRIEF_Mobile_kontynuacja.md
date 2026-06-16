# BRIEF — LynxMask Mobile: kontynuacja

Projekt: C:\Projects\LynxMask
Data: 2026-06-14

---

## 1. BASELINE (przed tą sesją)

| Metryka            | Wartość |
|--------------------|---------|
| Recall             | 63.0%   |
| OSOBA recall       | 48.6%   |
| ADRES recall       | 61.9%   |
| EMAIL recall       | 60.0%   |
| Krytyczne braki    | 15      |
| FP (overmasking)   | 96      |
| Testy FAIL         | 2       |

Faile: `konto bez prefiksu PL jest maskowane`, `adres z al jest maskowany`

---

## 2. CO ZROBIONO W TEJ SESJI

- **EMAIL przeniesiony na pozycję 0 w STRUCTURAL_PATTERNS** (StructuralEngine.kt)
  Wzorzec `\b[a-zA-Z0-9._%+\-]+@...` i wzorzec z kontekstem `e-mail:` są teraz
  pierwsze na liście — przed CATCHALL `\d{9}` i VAT EU `[A-Z]{2}\d{8,12}`.
- **Testy: 89 PASS, 2 FAIL** (bez zmian w failach — żadna regresja po przeniesieniu EMAIL)

---

## 3. NASTĘPNE ZADANIA W KOLEJNOŚCI

### a) Deny-lista i filtr długości w NameEngine (redukcja FP i OSOBA)

Wzorzec: przed każdym `assignToken(candidate, TOKEN_OSOBA)` dodaj:

```kotlin
// Filtr długości — artefakty OCR i skróty
if (candidate.length < 4) return@replace match.value

// Deny-lista słów pospolitych
val OSOBA_DENYLIST = setOf(
    "zamieszkania", "zameldowania", "służbowa", "służbowy",
    "wydział", "wydziału", "wydzialu", "wydziatu",
    "informacji", "informacj", "uzyskanych",
    "niejszej", "nin", "icznie",
    "sąd", "sądu", "rejonowy", "okręgowy",
    "urząd", "urzędu", "gminy", "gmina",
    "ulica", "ulicy", "adres", "adresu",
    "imię", "nazwisko", "pesel", "numer",
    "miejscowość", "miejscowości",
)
if (candidate.lowercase() in OSOBA_DENYLIST) return@replace match.value
```

Cel: wyeliminować FP z logów PSE_OSOBA: "SŁU", "Wydzialu", "Wydziatu", "icznie", "zameldowania".

### b) PESEL — walidacja sumy kontrolnej

Przed przypisaniem TOKEN_NUMER dla PESEL dodaj weryfikację checksum algorytmem wag
`[1,3,7,9,1,3,7,9,1,3]`. Fałszywe PESEL-e (11 cyfr bez prawidłowej sumy) → nie tokenizuj.

### c) Granice encji — edge cases

- Dwa imiona: "Jan Maria Kowalski" → jeden token, nie dwa
- Myślnik w nazwisku: "Nowak-Wiśniewska" → jeden token
- Tytuły przed imieniem: "dr hab. Anna Kowalska" → TOKEN_OSOBA obejmuje całość

### d) Pre-ekstrakcja NameEngine przed maskowaniem adresów

Na Desktop pipeline kolejność: `extract_names` → `apply_address` → `apply_names_with_cache`.
Na Mobile odpowiednik: uruchom `applyContextualBlacklist` PRZED przetwarzaniem wzorców
adresowych (Warstwa 2 / StructuralEngine). Zapobiega sytuacji gdzie nazwisko w adresie
jest maskowane jako ADRES zamiast OSOBA.
Referencyjna implementacja: `layers/ner_adapter.py` + `pipeline_core.py` (Desktop).

### e) Napraw 2 faile w testach

**`konto bez prefiksu PL jest maskowane`**
- Input: `"Przelej na: 61 1090 1014 0000 0712 1981 2874"`
- Problem: brak prefiksu PL + spacje — regex `\d{24}` nie dopasowuje
- Diagnoza: TOKENS = `{}`, OUTPUT = sam SESJA token

**`adres z al jest maskowany`**
- Input: `"Siedziba: al. Jerozolimskie 144"`
- Problem: SESJA prefix + newline przed tekstem łamie regex adresowy
- Diagnoza: TOKENS = `{}`, OUTPUT = sam SESJA token

---

## 4. WERSJE PLIKÓW

| Plik                  | Wersja / Status                              |
|-----------------------|----------------------------------------------|
| StructuralEngine.kt   | v1.8 — EMAIL przeniesiony na pozycję 0 (tej sesji) |
| NameEngine.kt         | v1.10 — bez zmian w tej sesji               |
| PseudonymEngine.kt    | v2.2 — bez zmian w tej sesji               |

---

## 5. WAŻNE KONTEKSTY

- **Projekt**: `C:\Projects\LynxMask`
- **Uruchamianie testów**: `.\gradlew :app:testDebugUnitTest`
  (wymagane: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`)
- **Benchmark wymaga telefonu** — uruchomić przez Android Studio z podłączonym urządzeniem.
  Plik wynikowy trafia do: `benchmark_results\log_*.txt`
  **Pawle: uruchom benchmark po każdej zmianie w NameEngine / StructuralEngine.**
- **Mapa architektury**: `MAPA_ARCHITEKTURY_LynxMask_mobile_v2.md` (z 09.06, częściowo nieaktualna)
- **Desktop jest referencją** — rozwiązania z `C:\Users\p_pie\Desktop\pseudominizer`
  przenoś na Mobile. Szczególnie: kolejność warstw, denylisty, walidacja checksum.
- **Diagnostyczny test `diagnostyka_faile`** w PseudonymEngineTest.kt — do usunięcia
  gdy faile zostaną naprawione.
