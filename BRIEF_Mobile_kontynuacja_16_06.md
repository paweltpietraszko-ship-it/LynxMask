# BRIEF Mobile — kontynuacja sesji 16.06.2026

## 1. WYNIKI SESJI 16.06

### Benchmark baseline → końcowy (dataset: ground_truth_lvl03.json, 68 dok., lvl 0–3)

| Metryka | Baseline | Końcowy | Delta |
|---|---|---|---|
| Recall ogółem | 63.0% | 70.2% | +7.2 pp |
| Lvl 0 (skany czyste) | 84.9% | 89.6% | +4.7 pp |
| Lvl 1 | 82.2% | 88.8% | +6.6 pp |
| CLR (critical left rate) | 17 | 15 | −2 |

### CLR szczegóły (15 pozostałych braków)
- 14/15 to braki OCR — encja niewidoczna w tekście Tesseract (bug OCR / degradacja obrazu)
- 1/15 — bug silnika: `FOH614892` (format `seria XXX nr NNNNNN`) → **naprawiony w tej sesji**

---

## 2. ZMIANY W KODZIE

### StructuralEngine.kt (v1.8 → v1.9)

| Zmiana | Opis |
|---|---|
| PESEL lookaround | `\b\d{11}\b` → `(?<!\d)\d{11}(?!\d)` — łapie PESEL bezpośrednio po słowie (`PESEL72030375656`) |
| NIP OCR-tolerant | `[-\s]?` → `[-\s.]?` + lookaround — obsługa NIP z kropką jako artefaktem OCR (`766-444-75.06`) |
| Sygnatura administracyjna | Nowy wzorzec `\b[A-Z]{2}\.\d{6}\.\d{4}\b` — `PT.070433.2020`, `OW.085394.2025` |
| Sygnatura komornicza | `[A-Z]{1,3}` → `[A-Z][A-Za-z]{0,2}` — teraz łapie `Km`, `Ko`, `Kms`, `Kmp` (wcześniej tylko uppercase) |
| Dowód osobisty z "nr" | Nowy wzorzec `\b[A-Z]{3}\s+nr\s+\d{6}\b` — `seria FOH nr 614892` |
| ADDRESS_PATTERNS | Nowa lista na końcu pliku — 4 wzorce TOKEN_ADRES przeniesione z STRUCTURAL_PATTERNS |

### PseudonymEngine.kt (v2.2 → v2.3)

| Zmiana | Opis |
|---|---|
| Warstwa 3d | Pętla `ADDRESS_PATTERNS` wstawiona PO `applyContextualBlacklist` i propagacji nazwisk — NameEngine widzi pełne adresy przed ich zamaskowanie (analogia Desktop: `extract_ner_results` przed `apply_address_layer`) |
| Log tymczasowy | `Log.d("LynxMask", "STRUCTURAL_PATTERNS: ${STRUCTURAL_PATTERNS.size}")` i `ADDRESS_PATTERNS` — do usunięcia po weryfikacji |

### BenchmarkInstrumentedTest.kt

| Zmiana | Opis |
|---|---|
| Diagnostyka OCR | `.take(5)` → `.forEach` w sekcji `[DIAGNOSTYKA OCR]` — teraz pokazuje wszystkie dokumenty z brakami krytycznymi |

### PseudonymEngineTest.kt

| Zmiana | Opis |
|---|---|
| `testWyciekiV2` | 5 przypadków: IBAN, sygnatury sądowe, Km, sygnatura admin, PT — wszystkie 5 PASS |
| `testDowodOsobisty` | 4 przypadki formatów dowodu — 3 PASS, 1 świadoma anomalia (`ABC123456` słusznie maskowany) |

---

## 3. OTWARTE PROBLEMY W KOLEJNOŚCI PRIORYTETU

### a) OutputGuard — nie wykrywa wycieków które przeżyły pipeline (WYSOKI)
OutputGuard (v1.6) sprawdza imiona z LookupTables ale nie patrzy na wzorce strukturalne PII.
**Pomysł**: dodać do OutputGuard wykrywanie wzorców które silnik powinien był złapać:
- `@` w tekście (niezamaskowany email)
- `PL` + 26 cyfr (niezamaskowany IBAN)
- 11 cyfr bez tokenu (niezamaskowany PESEL)
- format sygnatury `[A-Z]+ \d+/\d+` (niezamaskowana sygnatura)
Wymagałoby integracji z `STRUCTURAL_PATTERNS` lub osobnej listy wzorców guard.

### b) Samouczenie — program nie uczy się na zaznaczonych encjach (ŚREDNI)
Gdy użytkownik ręcznie zaznacza pominięte PII w edytorze, ta informacja nie trafia z powrotem do silnika.
**Pomysł**: zapisywać ręczne korekty w UserDictionary lub osobnej tabeli SQLCipher i uwzględniać przy kolejnym przetwarzaniu tego samego dokumentu.

### c) OSOBA 57.6% recall — wymaga lepszego NER (ŚREDNI)
NameEngine oparty na listach imion/nazwisk nie radzi sobie z rzadkimi nazwiskami i obcobrzmiącymi imionami.
**Opcje**: rozbudowa LookupTables, integracja modelu NER (np. HerBERT-NER), kontekstowe heurystyki (tytuł + wielka litera).

### d) doc_00021 lvl=0 IBAN — BRAK w OCR mimo qs=100 (NISKI, anomalia)
Dokument z jakością OCR 100%, ale IBAN `PL04325000035633956078831852` nie pojawia się w tekście Tesseract.
Lokalna weryfikacja Tesseract potwierdziła że IBAN jest widoczny na obrazku — prawdopodobnie błąd w ground truth lub problem z tym konkretnym plikiem.
**Status**: do zbadania przy kolejnym uruchomieniu benchmark z `diagnostyka_faile`.

### e) BUG-AL-OPEN — adresy z `al.` nadal nie maskowane (NISKI, odłożony)
Test `adres z al jest maskowany` — 1 permanentny FAIL w suite.
Wzorzec `al. Niepodległości 12` nie przechodzi przez ADDRESS_PATTERNS z powodu niewyjaśnionego edge case'u.
**Status**: odłożony świadomie. Nie ruszać.

---

## 4. CZEGO NIE RUSZAĆ

| Element | Powód |
|---|---|
| `BUG-AL-OPEN` | Odłożony świadomie, edge case w wzorcu adresu z al. |
| Logika AES / SQLCipher (`SessionStore.kt`, `UserDictionary.kt`) | Audyt bezpieczeństwa przeszedł — zmiana wymaga pełnego re-audytu |
| UI (Compose screens, theme) | Nie dotyczy silnika pseudonimizacji |
| `isMinifyEnabled` | Wyłączone celowo (R8 może złamać refleksję SQLCipher) |
| `LookupTables.initializeForTesting()` | Wzorzec testowy — nie zmieniać bez aktualizacji wszystkich testów |

---

## 5. WERSJE PLIKÓW PO SESJI 16.06

| Plik | Wersja przed | Wersja po | Zmieniony |
|---|---|---|---|
| `StructuralEngine.kt` | v1.8 | v1.9 | TAK |
| `PseudonymEngine.kt` | v2.2 | v2.3 | TAK |
| `NameEngine.kt` | v1.11 | v1.11 | NIE |
| `OcrNormalizer.kt` | v1.3 | v1.3 | NIE |
| `OutputGuard.kt` | v1.6 | v1.6 | NIE |
| `LookupTables.kt` | v1.2 | v1.2 | NIE |
| `BenchmarkInstrumentedTest.kt` | — | — | TAK (diagnostyka OCR) |
| `PseudonymEngineTest.kt` | — | — | TAK (2 nowe testy) |
| `AndroidManifest.xml` (androidTest) | — | — | NIE (z poprzedniej sesji) |

### Rozmiar list wzorców po sesji
- `STRUCTURAL_PATTERNS`: 50 wzorców (było 54 przed wydzieleniem adresów)
- `ADDRESS_PATTERNS`: 4 wzorce (TOKEN_ADRES, uruchamiane po NameEngine)
- Łącznie: 54 wzorce — bez regresji

### Stan testów jednostkowych
```
97 tests, 1 FAIL (BUG-AL-OPEN — permanentny, znany)
```

---

## 6. INFRASTRUKTURA BENCHMARK

### Uruchamianie

```bat
run_benchmark.bat
```

Skrypt:
1. `gradlew :app:installDebugAndroidTest` — instaluje APK testowy
2. `adb shell rm -f /storage/emulated/0/Documents/LynxMask/…` — czyści poprzednie wyniki
3. `adb push dataset\ground_truth_lvl03.json …` — przepycha dataset i obrazy
4. `adb shell am instrument -w …BenchmarkInstrumentedTest…` — uruchamia benchmark
5. `adb pull /storage/emulated/0/Documents/LynxMask/…` → `benchmark_results\`

### Datasety

| Plik | Dokumenty | Poziomy | Zastosowanie |
|---|---|---|---|
| `ground_truth_lvl03.json` | 68 dok. | lvl 0–3 | Benchmark główny (aktywny) |
| `ground_truth.json` | 100 dok. | lvl 0–5 | Pełny dataset z OCR-heavy |

Przełączanie: stała `GROUND_TRUTH_FILE` w `companion object` w `BenchmarkInstrumentedTest.kt`.

### Wyniki po benchmarku
- `benchmark_results\benchmark_report.txt` — metryki ogólne + per-dokument
- `benchmark_results\benchmark_bugs.txt` — braki krytyczne + diagnostyka OCR (od tej sesji: wszystkie dokumenty, nie tylko 5)

### Testy jednostkowe (bez telefonu)
```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
.\gradlew :app:testDebugUnitTest
```

### Tymczasowe logi do usunięcia
W `PseudonymEngine.kt` przed Warstwą 2:
```kotlin
android.util.Log.d("LynxMask", "STRUCTURAL_PATTERNS: ${STRUCTURAL_PATTERNS.size}")
android.util.Log.d("LynxMask", "ADDRESS_PATTERNS: ${ADDRESS_PATTERNS.size}")
```
Usunąć po weryfikacji na telefonie (oczekiwane: 50 i 4).
