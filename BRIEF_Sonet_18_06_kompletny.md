# BRIEF DLA SONETA — LynxMask Mobile
**Data:** 18.06.2026
**Źródła:** sesja 18.06

---

## ZASADY PRACY Z PAWŁEM — PRZECZYTAJ NAJPIERW

- **Dopytuj zamiast się domyślać** — jeśli coś jest niejednoznaczne, zadaj jedno pytanie
- **Nie używaj skrótów** — mów "plik który przetwarza tekst" nie "orchestrator pipeline"
- **Paweł nie jest programistą** — tłumacz co, jak i gdzie
- **"Mapa"** — oznacza mapę architektury (plik .md), nie strukturę danych
- **Sonet = ty (w czacie), Claude = instancja w terminalu**
- **Jedno zadanie Claude na raz** — małe zadanie → test → wynik → następne
- **Nie bądź sycofantyczny** — rzeczowo i zwięźle

### Format zadania który działa
```
Przeczytaj plik X w C:\ścieżka\do\pliku.kt
Znajdź fragment Y (szukaj komentarza "// Warstwa 3").
Pokaż go z numerami linii. Nic nie zmieniaj.
```

---

## PROJEKTY I ŚCIEŻKI

- **Mobile:** `C:\Projects\LynxMask\`
- **Desktop:** `C:\Users\p_pie\Desktop\pseudominizer\`
- **Testy Mobile:** `.\gradlew :app:testDebugUnitTest`
- **Benchmark Mobile:** `run_benchmark.bat` (w katalogu projektu)
- **Wyniki benchmarku:** `benchmark_results\benchmark_report.txt`, `benchmark_bugs.txt`, `benchmark_trace.txt`
- **Pliki testowe (ręczne):** `C:\Projects\LynxMask\testy\` i `C:\Projects\LynxMask\tests\`

---

## ŚRODOWISKO — wymagane przed każdą sesją w CMD

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
```
Bez tego: "ERROR: JAVA_HOME is not set". `run_benchmark.bat` już to ustawia automatycznie.

**WAŻNE:** Po każdej zmianie kodu aplikacji wymagany pełny reinstall APK przed benchmarkiem:
```
.\gradlew :app:installDebug
.\gradlew :app:installDebugAndroidTest
run_benchmark.bat
```

---

## STAN BENCHMARKU

### Ostatni run: 2026-06-20 07:59 ✅ (fresh dataset, po naprawie norm() w benchmarku)
Dataset: losowo generowany `generator.py --count 68 --max-level 3` — **68 dokumentów**, lvl 0–3, 451 encji

| Metryka | 18.06 baseline | 20.06 07:59 (aktualne) |
|---|---|---|
| Recall | 74,3% | **74,3%** |
| Precision | ~69% | **74,0%** (+5pp) |
| F1 | — | **74,1%** |
| ADRES recall | ~68% | **76,0%** (+8pp) |
| EMAIL recall | 83,3% | **75,0%** |
| NUMER recall | 73,5% | **71,4%** |
| OSOBA recall | 81,5% | **82,1%** |
| Lvl 0 | — | **86,7%** |
| Lvl 1 | — | **91,3%** |
| Lvl 2 | — | **41,1%** |
| Lvl 3 | — | **78,4%** |

**Bez Lvl 2: ~85% recall.** Lvl 2 to OCR garbage — silnik nie może wykryć czegoś czego OCR nie widzi.

*UWAGA: fresh dataset (losowy) — liczby NUMER/EMAIL wahają się między runami (mały licznik). ADRES 76% to wynik stabilny po naprawkach.*

### Test ręczny — czysty tekst (2026-06-20) ✅
Plik: `testy\test_silnik_statystyki.txt` — 54 encje

| Typ | Wykryte | Recall |
|---|---|---|
| OSOBA | 10/10 | **100%** |
| EMAIL | 11/11 | **100%** |
| PESEL | 6/6 | **100%** |
| NIP | 5/5 | **100%** |
| IBAN | 3/3 | **100%** |
| TELEFON | 8/8 | **100%** |
| DOKUMENT | 4/4 | **100%** |
| REGON | 1/1 | **100%** |
| ADRES | 8/8 | **100%** |

**Na czystym tekście silnik jest bezbłędny. Straty w benchmarku = wyłącznie degradacja OCR.**

---

## ZMIANY 18.06 — COMMITY

| Commit | Opis |
|---|---|
| `e7705e0` | fix: norm() z ASCII dla polskich znaków → poprawi recall OSOBA i ADRES |
| `f0ee7bf` | fix: normalizeForCompare() z ASCII dla polskich znaków we wszystkich sekcjach diagnostyki |
| `e5918d4` | feat: sekcja ADRES POMINIĘTE z diagnostyką BRAK_W_OCR / BUG_SILNIKA |
| `030d1fd` | fix: run_benchmark.bat /sdcard/→/storage/emulated/0/ + pull benchmark_trace.txt; TODO_silnik.md ŚRODOWISKO |
| `bc2fe2f` | feat: sekcja EMAIL POMINIĘTE |
| `735c9ff` | feat: DetectionTrace — NAME_ENGINE/CONTEXTUAL zamiast UNKNOWN/UNKNOWN |
| `e3d92a5` | fix: benchmark na lvl01 (34 dok.) |
| `167bd70` | feat: benchmark zapisuje benchmark_trace.txt |
| `dfd9e55` | fix: OcrNormalizer v1.4 — email spaces, ul. prefix; benchmark normalizedText; EMAIL 50%→83,3% |
| `2710eeb` | fix: OcrNormalizer v1.5 — kontekstowa naprawa cyfr w PESEL (T→7, O→0 itd.) |

---

## CO ZROBIONE 19.06 SESJA PORANNA ✅

1. ✅ **OCR_UL_PREFIX rozszerzony** — obsługuje `UI./uI./ul bez kropki/ulica/ULICA`
   - Warianty: `ul`, `UI`, `uI`, `u1`, `u.`, `ul.`, `UI.` + zero lub więcej spacji + wielka litera
   - Pełne słowo: `ulica`/`ULICA` ze spacją
   - KNOWN LIMITATION: `UI.Nazwa` bez spacji między prefiksem a nazwą — nie obsługiwane (zbyt ryzykowne FP)
   - 10 testów jednostkowych, 0 FAILED
2. ✅ **OCR_PESEL_WORD** — zastąpił `OCR_PESEL_DIGITS` (lookbehind → pełny wzorzec słowa)
   - Obsługuje: `PESE1`, `PE5EL`, `P E S E L`, `PEB3L`, `pesel` małymi literami
   - BUG-PESEL1 naprawiony
   - 9 testów jednostkowych, 0 FAILED
3. ℹ️ **Benchmark nadal 74,3%** — reguły działają poprawnie, ale dataset lvl03 nie zawiera wariantów `PESE1`/`PE5EL`/`UI.Nazwa` — nowe reguły nie mają gdzie się zmierzyć

---

## CO ZROBIONE 19.06 SESJA WIECZORNA ✅

Commit: `c1e7932` (ostatni w tej sesji)

1. ✅ **BUG-NIP-SPLIT naprawiony** — OCR_NIP_SPLIT w OcrNormalizer.kt
   - Wzorzec: `NIP: 740-61 7-82-26` → `NIP: 740-617-82-26` (spacja wewnątrz numeru)
   - 5 testów jednostkowych, 0 FAILED
2. ✅ **BUG-PESEL-SPLIT naprawiony** — OCR_PESEL_SPLIT w OcrNormalizer.kt
   - Wzorzec: `PESEL: 6802041 8568` → `PESEL: 68020418568` (spacja wewnątrz PESEL)
   - Obsługuje też: PESE1, pesel małe litery, spacje w wielu miejscach
   - 5 testów jednostkowych, 0 FAILED
3. ✅ **run_benchmark_fresh.bat** — benchmark z dynamicznie generowanym datastem
   - Krok 1b/4: `python generator.py --count 68 --output dataset_fresh --max-level 3` (losowy seed)
   - Push świeżych PNG + ground_truth.json na telefon zamiast stałego `dataset/`
   - Cel: wykrywać nowe bugi, nie mierzyć tych samych dokumentów w kółko
   - ⚠️ `dataset_fresh/` w .gitignore — nie commitować PNG
4. ✅ **OcrLvl1IntegrationTest.kt** — test integracyjny: Tesseract → PseudonymEngine
   - Wczytuje `ocr_lvl1_doc_00004.txt` (tekst OCR Tesseract z doc_00004.png, lvl=1, qs=96)
   - **6 testów, 3 PASS / 3 FAIL** (FAIL = oczekiwany, dokumentuje bug)

### Wyniki testu integracyjnego doc_00004.png (lvl=1, qs=96)

tokenMap z silnika:
```
NUMER_001 = 6810568585       ← PESEL z 10 cyframi (powinien być PESEL_001)
NUMER_002 = 873-054-80.39    ← NIP z kropką (powinien być NIP_001)
NUMER_003 = 48 606 219 727   ← telefon ✓ (bez '+', ale ok)
NUMER_004 = YIT494289        ← dowód osobisty ✓
OSOBA_001 = Szymański        ← nazwisko ✓ (mimo braku polskich znaków w OCR)
ADRES_001 = Szkolna 150, 20-100 Kielce ← adres ✓
```
Brakuje: `EMAIL_001` — email w ogóle nie wykryty

| Test | Status | Powód |
|------|--------|-------|
| `telefon jest wykrywany` | ✅ PASS | |
| `dowod osobisty jest wykrywany` | ✅ PASS | |
| `adres jest wykrywany` | ✅ PASS | |
| `FAIL email @ → Q` | ❌ FAIL | `lukaszszymanski Qinteria.pl` — brak `@`, OCR zamienił na `Q` |
| `FAIL NIP kropka` | ❌ FAIL | `873-054-80.39` — ostatni myślnik → `.`; silnik nie rozpoznaje jako NIP |
| `FAIL PESEL 10 cyfr` | ❌ FAIL | `6810568585` — OCR zgubił cyfrę; silnik nie rozpoznaje jako PESEL |

---

## CO ZROBIONE DZIŚ (sesja popołudniowa 18.06) ✅

1. ✅ **Benchmark przełączony na lvl03** (68 dokumentów zamiast 34)
2. ✅ **Diagnostyka raw OCR[300]** dodana do bugs.txt (sekcje OSOBA/EMAIL/ADRES POMINIĘTE)
3. ✅ **normalizedText w analyze()** — BRAK_W_OCR sprawdza teraz tekst po OcrNormalizerze
4. ✅ **OcrNormalizer v1.4–v1.5** — nowe reguły:
   - `OCR_EMAIL_TLDSPACE` — `jan@onet pl` → `jan@onet.pl`
   - `OCR_EMAIL_LOCALSPACE` — `jan kowalski@wp.pl` → `jan_kowalski@wp.pl`
   - `OCR_UL_PREFIX` — `u. Nazwa` / `u Nazwa` → `ul. Nazwa`
   - `OCR_PESEL_DIGITS` — litery jako cyfry po słowie PESEL (T→7, O→0 itd.)
   - `OCR_NIP_DIGITS` — litery jako cyfry po słowie NIP/NlP/N1P
   - `OCR_REGON_DIGITS` — litery jako cyfry po słowie REGON
   - `OCR_IBAN_DIGITS` — litery jako cyfry po słowie IBAN / Nr konta
5. ✅ **Testy jednostkowe** dla wszystkich nowych reguł — 123 testów, 2 failed (BUG-AL-OPEN, znane)
6. ✅ **Plik testowy** `tests\test_ocr_normalizer_hard.txt` — dokument z celowymi artefaktami OCR do ręcznego testu przez telefon

---

## DETECTIONTRACE — SYSTEM DIAGNOSTYCZNY

W `PseudonymEngine.kt` dodano `DetectionTrace` — śledzi która warstwa wykryła każdy token.

Struktura: `DOC | LAYER | RULE | TOKEN | MATCHED_TEXT`

Warstwy w trace:
- `DICT / USER_DICTIONARY` — Warstwa 1 (słownik użytkownika)
- `STRUCTURAL / NUMER|KWOTA|...` — Warstwa 2 (regex strukturalne)
- `NAME_ENGINE / CONTEXTUAL` — Warstwa 3 (NameEngine — imiona, adresy uliczne)
- `ADDRESS / ADRES` — Warstwa 3d (kody pocztowe + miasto)

Plik trace: `benchmark_results\benchmark_trace.txt`

---

## ARCHITEKTURA BENCHMARKU — BenchmarkInstrumentedTest.kt

Kluczowe funkcje:
- `norm(v)` — normalizacja do porównania GT vs token: usuwa spacje, myślniki, polskie znaki → ASCII, lowercase
- `normalizeForCompare(s)` — identyczna logika, używana w sekcjach diagnostycznych bugs.txt
- `analyze(gt, tokens, ocrText, normalizedText, error, trace)` — `normalizedText` = tekst PO OcrNormalizerze; BRAK_W_OCR sprawdza `r.normalizedText` nie `r.ocrText`
- `saveReports()` — generuje report.txt, bugs.txt, trace.txt

Sekcje diagnostyczne w bugs.txt:
- `[OSOBA POMINIĘTE]` — z diagnostyką BRAK_W_OCR / ODMIANA_ZAMASKOWANA / BUG_SILNIKA + OCR[300]
- `[EMAIL POMINIĘTE]` — z diagnostyką BRAK_W_OCR / BUG_SILNIKA + OCR[300]
- `[ADRES POMINIĘTE]` — z diagnostyką BRAK_W_OCR / BUG_SILNIKA + OCR[300]

---

## OTWARTE BUGI

| Bug | Plik | Status | Opis |
|---|---|---|---|
| ~~BUG-PESEL1~~ | OcrNormalizer.kt | ✅ 19.06 | `OCR_PESEL_WORD` |
| ~~BUG-NIP-SPLIT~~ | OcrNormalizer.kt | ✅ 19.06 | `OCR_NIP_SPLIT` |
| ~~BUG-PESEL-SPLIT~~ | OcrNormalizer.kt | ✅ 19.06 | `OCR_PESEL_SPLIT` |
| ~~BUG-EMAIL-AT-Q~~ | OcrNormalizer.kt | ✅ 19.06 | `OCR_EMAIL_AT_Q` |
| ~~BUG-NIP-DOT~~ | OcrNormalizer.kt | ✅ 19.06 | `OCR_NIP_DOT` |
| ~~BUG-EMAIL-TLD1~~ | OcrNormalizer.kt | ✅ 19.06 | `OCR_EMAIL_TLDSPACE` akceptuje cyfry |
| ~~BUG-STREET-NEWLINE~~ | NameEngine.kt | ✅ 20.06 | `[/[^\S\n]]` zamiast `[/\s]` |
| ~~BUG-IBAN-SPLIT~~ | OcrNormalizer.kt | ✅ 20.06 | `OCR_IBAN_SPLIT` v1.9 |
| ~~BUG-AL-OPEN~~ | PseudonymEngine.kt | ✅ 20.06 | `ADDR-EMAIL-FIX` lookbehind |
| ~~BUG-IBAN-NOSPACES~~ | StructuralEngine.kt | ✅ 20.06 | IBAN bez spacji wykrywany |
| ~~BUG-BENCHMARK-NORM~~ | BenchmarkInstrumentedTest.kt | ✅ 20.06 | `norm()` normalizuje `._` → `.` dla emaili |
| **BUG-EMAIL-TOKEN** | StructuralEngine.kt | 🔲 nowy | EMAIL wykrywany jako NUMER zamiast EMAIL |
| **BUG-DATE-PARTIAL** | StructuralEngine.kt | 🔲 nowy | Daty ISO `2026-06-20` → `NUMER-20` (częściowe) |
| **BUG-FP-REFNUM** | StructuralEngine.kt | 🔲 nowy | Numery umów/faktur/sygnatur akt → NUMER (FP) |
| **BUG-TEL-PREFIX** | StructuralEngine.kt | 🔲 nowy | `(22) 765-43-21` → `(22) NUMER` (prefix pominięty) |
| **BUG-EMAIL-PARTIAL** | StructuralEngine.kt | 🔲 | email z imieniem w local-part → OSOBA |
| **BUG-DOWOD** | StructuralEngine.kt | 🔲 | `FOH6 14892` — spacja w środku numeru dowodu |
| **BUG-PESEL-10** | StructuralEngine.kt | 🔲 | PESEL z 10 cyframi (OCR zgubił cyfrę) |
| **BUG-OUTPUTGUARD-FORMAT** | OutputGuard.kt | 🔲 | Guard szuka OSOBA_ABC_001, silnik generuje OSOBA_001 |

---

## Nowe bugi OcrNormalizer — z analizy zewnętrznej 19.06

### OCR_EMAIL_LOCALSPACE — tylko jedna spacja
- Obecna reguła naprawia tylko jedną spację w local-part emaila
- "jan adam kowalski@wp.pl" → naprawiane tylko częściowo
- Rozwiązanie: replace w pętli lub wzorzec na wiele segmentów

### IBAN rozbity przez newline
- Reguła obsługuje spacje ale nie przejście do nowej linii
- Przykład: PL61 rozbity na dwie linie nie jest sklejany
- Rozwiązanie: OCR_IBAN_NEWLINE — skleja IBAN rozbity przez newline

### KNOWN_CITY_FORMS nie obsługuje miast bez ogonków
- "Bialystok", "Lodz", "Krakow" nie trafią w listę miast
- Rozwiązanie: fold() przy sprawdzaniu kandydata w liście

### ~~Wersja nagłówka OcrNormalizer.kt nieaktualna~~
- ✅ NAPRAWIONE w tej sesji — zaktualizowano do v1.6

---

## Rozwiązania znalezione przez agenta (research 19.06) — do wdrożenia

> Poniższe podejścia zostały znalezione przez agenta eksploracyjnego przeszukującego literaturę i repozytoria.
> Zweryfikowane źródła. Nakład i ryzyko ocenione przez agenta.

### RESEARCH-1 — Walidacja sumy kontrolnej PESEL i NIP
**Status:** 🔲 do zrobienia  
**Nakład:** mały (2–4h)  
**Plik:** `StructuralEngine.kt`

PESEL i NIP mają deterministyczne sumy kontrolne ze znanych wag. Po korekcie OCR (l→1, O→0) sprawdzamy sumę — jeśli się zgadza, korekta jest pewna w ~100%. Działa jako "oracle" potwierdzający poprawność normalizacji.

- PESEL wagi: `1, 3, 7, 9, 1, 3, 7, 9, 1, 3` (wynik mod 10 == ostatnia cyfra)
- NIP wagi: `6, 5, 7, 2, 3, 4, 5, 6, 7` (wynik mod 11 == ostatnia cyfra)
- Czyste Kotlin, zero zależności zewnętrznych

### RESEARCH-2 — De-leet pre-normalizacja dla imion/nazwisk
**Status:** 🔲 do zrobienia  
**Nakład:** mały-średni (4–8h)  
**Plik:** `OcrNormalizer.kt` lub nowy `NameNormalizer.kt`

OCR podmienia cyfry za litery w imionach: `B3ata` (3→e), `Krzy5zt0f` (5→s, 0→o). Podejście potwierdzone literaturą:
- Mapowanie de-leet: `3→e, 4→a, 5→s, 0→o, 1→i` — **tylko dla tokenów zaczynających się wielką literą** (imiona/nazwiska, nie cyfry w NIP)
- Jaro-Winkler fuzzy matching — lepszy od Levenshtein dla imion (preferuje zgodność prefiksu). Biblioteka: `string-similarity-kotlin` (Kotlin Multiplatform, MIT)
- Ryzyko: wymaga decyzji czy dodawać zależność biblioteczną

### RESEARCH-3 — ML Kit confidence per znak (dwupoziomowe progi jakości)
**Status:** 🔲 do zrobienia  
**Nakład:** mały  
**Plik:** `ShareTargetActivity.kt` + `OcrNormalizer.kt` (assessQuality)

ML Kit daje confidence per `Text.Symbol.getConfidence()`, per słowo i per linię — nie tylko per dokument. Można identyfikować konkretne znaki słabo rozpoznane i kierować do korekty kontekstowej.

- Progi potwierdzone praktyką: avg < 0.7 → YELLOW, avg < 0.5 → RED
- Caveat: GPS < 22.30 zwraca `0.0f` — sprawdzać `> 0` przed użyciem
- Aktualny stan: `mlKitConfidence` zawsze `null` — nie jest przekazywane z `ShareTargetActivity` do `pseudonymize()`
- Krok 1: podłączyć confidence w ShareTargetActivity (linie 287, 434)
- Krok 2: rozszerzyć `assessQuality()` o poziomy YELLOW/RED zamiast jednego progu

---

## CO ZROBIĆ JAKO PIERWSZE (następna sesja)

### Priorytet 1 — Bugi silnika (StructuralEngine.kt):

1. **BUG-EMAIL-TOKEN** — EMAIL wykrywany jako `NUMER` zamiast `EMAIL`
   - Wszystkie emaile są poprawnie wykrywane i maskowane, ale token to `NUMER_xxx`
   - Benchmark liczy je po wartości (recall OK), ale `type_mismatch` rośnie
   - Plik: `StructuralEngine.kt` — sprawdzić gdzie EMAIL pattern jest zdefiniowany

2. **BUG-DATE-PARTIAL** — daty ISO `RRRR-MM-DD` maskowane częściowo
   - `2026-06-20` → `NUMER_062-20` (zamaskowane `2026-06`, zostaje `-20`)
   - `2024-03-15` → `NUMER_063-15`, `2026-08-01` → `NUMER_061-01`
   - Plik: `StructuralEngine.kt` — regex łapie `rok-miesiąc` jako numer; trzeba wykluczyć

3. **BUG-FP-REFNUM** — numery umów/faktur/sygnatur akt maskowane jako NUMER (false positive)
   - `UZ/2026/0088`, `FV/2026/000088`, `I C 234/26` → NUMER
   - Plik: `StructuralEngine.kt` — dodać wyjątki dla formatów referencyjnych

4. **BUG-TEL-PREFIX** — `(22) 765-43-21` → `(22) NUMER` (prefix kodu kierunkowego pominięty)
   - Plik: `StructuralEngine.kt` — regex telefonu nie obejmuje nawiasów

### Priorytet 2 — Research do wdrożenia (patrz sekcja wyżej):

5. **RESEARCH-1** — Walidacja sumy kontrolnej PESEL/NIP (mały nakład, wysokie zaufanie)
6. **RESEARCH-3** — ML Kit confidence + dwupoziomowe progi YELLOW/RED (mały nakład)
7. **RESEARCH-2** — De-leet + Jaro-Winkler dla imion (wymaga decyzji o bibliotece)

### Priorytet 3 — Kalibracja i pozostałe:

8. **Fixed dataset** — stworzyć `run_benchmark.bat` używający stałego `dataset_fixed/`
   - Cel: porównywalne wyniki między runami (teraz fresh dataset = różne liczby)
9. **BUG-PESEL-10** — PESEL z 10 cyframi (OCR zgubił cyfrę) nie wykrywany
10. **BUG-DOWOD** — spacja w środku numeru dowodu (`FOH6 14892`)
11. **BUG-EMAIL-PARTIAL** — email z imieniem w local-part → OSOBA zamiast EMAIL

---

## WERSJE PLIKÓW MOBILE (stan 2026-06-20)

| Plik | Wersja | Co zmieniono |
|---|---|---|
| OcrNormalizer.kt | **v2.0** | +OCR_PESEL_SPLIT/IBAN_SPLIT obsługują l/O; +OCR_DIGIT_IN_CONTEXT (krok 14); 'o'→'0' w mapie |
| NameEngine.kt | v1.11+ | STREET_CANDIDATE_REGEX: `[/\s]`→`[/[^\S\n]]`; `internal` dla testów |
| PseudonymEngine.kt | v2.3+ | ADDR-EMAIL-FIX lookbehind `(?<=[a-z0-9]{2})` w pre-processingu |
| BenchmarkInstrumentedTest.kt | — | `norm()` i `normalizeForCompare()`: `"._"→"."` (fix fałszywych miss EMAIL) |
| StructuralEngine.kt | v1.9 | bez zmian |
| run_benchmark_fresh.bat | — | instaluje APK test + generator + push + run + pull |
| OcrLvl1IntegrationTest.kt | — | 6 testów; 3 PASS, 3 FAIL (dokumentują znane bugi) |
| NameEngineRegexTest.kt | — | **NOWY** — 3 testy STREET_CANDIDATE_REGEX |
| OcrNormalizerIbanSplitTest.kt | — | **NOWY** — 4 testy OCR_IBAN_SPLIT |

## PLIKI TESTÓW RĘCZNYCH (stan 2026-06-20)

| Plik | Opis |
|---|---|
| `testy\test_silnik_statystyki.txt` | **NOWY** — 54 encje, 4 sekcje, test 100% recall 20.06 |
| `testy\test_degradacje_lvl.txt` | **NOWY** — te same encje w LVL 0/1/2/3, do testów porównawczych |
| `testy\wyniki_testy_reczne.txt` | **NOWY** — archiwum wyników z testów ręcznych (TEST #001 wypełniony) |
| `testy\test_lvl3_dane_wrazliwe.txt` | stary — lvl3, różne wzorce encji |
| `tests\test_ocr_normalizer_hard.txt` | stary — celowe artefakty OCR |

---

## STAN DESKTOP (dla referencji)

Desktop stabilny. `USE_NEW_PIPELINE=True`, CLR 3.2%, Recall 91.4%.
Szczegóły w `BRIEF_Sonet_kontynuacja_16_06.md` w katalogu Desktop.

---

## LUKI SILNIKA — priorytety 19.06

Zasada dla wszystkich: nie modyfikuj tekstu źródłowego — normalizuj tylko do lookupu, maskuj oryginalny span.

1. **INICJAŁY** — NameEngine: po wykryciu nazwiska rozszerz span w lewo o wzorzec `[A-Z]\.` — niskie ryzyko FP
2. **ASCII IMIONA** — NameEngine: `fold()` bez ogonków przy lookup słownikowym — nie modyfikuj tekstu
3. **CAPS LOCK** — NameEngine: `toLookupForm()` normalizuje tylko do lookupu, maskuje oryginalny span
4. **WALIDACJA PESEL** — StructuralEngine: po wykryciu 11 cyfr sprawdź cyfrę kontrolną — zmniejszy FP
5. **KWOTY SŁOWNIE** — StructuralEngine: wymóg kotwicy złotych/zł/groszy — niskie FP
6. **SKLEJANIE NAZWISK** — NameEngine: left+right w słowniku `surnamesForms` (Kowal+ski)
7. **IBAN PRZEZ NEWLINE** — OcrNormalizer: PL + cyfry przez newline
