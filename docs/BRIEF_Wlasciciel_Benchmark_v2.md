# ZADANIE WŁAŚCICIELA — Benchmark v2 (realistyczny kontrakt jakości)

**Autor:** Paweł (właściciel produktu)  
**Data:** 2026-06-22  
**Adresat:** Claude Code / Sonet (benchmark, silnik, dokumentacja)  
**Priorytet:** P1 — przed kolejnymi optymalizacjami silnika pod syntetyczny dataset  
**Status:** DECYZJA + zakres prac do wdrożenia

---

## 1. Decyzja właściciela

**Przepisujemy benchmark.** Nie dopasowujemy silnika do generatora dokumentów — **dopasowujemy obietnicę produktu i poziom dokumentów w benchmarku do realnych możliwości lokalnego pseudonimizera** (OCR ML Kit + OcrNormalizer + silnik tekstowy + opcjonalnie IMAGE-REDACT).

Obecny KPI typu „recall 78,9% na `ground_truth_lvl03.json`” **nie jest już metryką release**. Miesza:
- jakość wejścia (OCR),
- zakres produktowy (co obiecujemy użytkownikowi),
- jakość silnika (czy maskujemy, gdy tekst jest czytelny).

To prowadzi do błędnej presji: łatanie regexów pod artefakty OCR zamiast **odrzucania dokumentów poniżej progu** albo **korygowania oczekiwań**.

**Lvl 2 generatora pozostaje odrzucony** (decyzja z MASTER, 21.06). Nie wracamy do preprocessingu obrazu pod lvl 2.

---

## 2. Filozofia nowego podejścia

### 2.1 Produkt ≠ silnik w izolacji

LynxMask Mobile to **pipeline end-to-end**, ale odpowiedzialność jest warstwowa:

| Warstwa | Pytanie | Kto odpowiada |
|---|---|---|
| **Jakość wejścia** | Czy OCR dał tekst wystarczająco dobry? | ML Kit + bramka `OcrQuality` |
| **Silnik tekstowy** | Czy z dobrego tekstu maskujemy wszystkie encje in-scope? | NameEngine, StructuralEngine, OutputGuard |
| **Warstwa obrazu** | Czy użytkownik może ręcznie zredagować to, czego OCR nie odczytał? | IMAGE-REDACT (Cursor) |
| **Kontrakt z użytkownikiem** | Czy dokument został przyjęty, odrzucony, czy ostrzeżony? | UI + OutputGuard |

Benchmark v2 musi **mierzyć każdą warstwę osobno**, nie jedną liczbą recall na całym worku śmieci i skanów.

### 2.2 Nie my dopasowujemy się do dokumentów — dokumenty dopasowujemy do nas

Generator syntetyczny jest **narzędziem regresji**, nie definicją rynku. Realny użytkownik:
- robi zdjęcie telefonem (artefakty inne niż w generatorze),
- czasem używa skanera GMS (lepsze wejście),
- czasem wysyła obraz do sieci (IMAGE-REDACT bez pełnego OCR),
- czasem dostaje nieczytelny JPEG — i **powinien dostać odmowę**, nie pół-maskę.

**In-scope** to dokumenty, które produkt **świadomie obiecuje** przetworzyć. Reszta to **out-of-scope** (informacyjnie) lub **reject** (oczekiwane zachowanie).

### 2.3 BRAK_W_OCR to nie bug silnika

Na obecnym benchmarku **18/18 krytycznych braków = BRAK_W_OCR** (sufit OCR, głównie lvl 2). To nie jest regresja NameEngine — to **sygnał, że dokument nie powinien wejść do głównego KPI**.

Nowy benchmark:
- **krytyczne braki in-scope** → blokują release (prawdziwe luki silnika),
- **BRAK_W_OCR na odrzuconym dokumencie** → sukces bramki,
- **BRAK_W_OCR na dokumencie in-scope bez bramki** → luka w definicji zbioru (błąd benchmarku, nie silnika).

### 2.4 Jedno zdanie strategii (obowiązuje w MASTER i BACKLOG)

> **LynxMask nie musi maskować wszystkiego, co ML Kit wyczyta — musi maskować wszystko, co obiecuje przyjąć, albo odrzucić dokument z jasnym komunikatem.**

Benchmark mierzy **zgodność z tą obietnicą**. Testy JVM mierzą **maksimum silnika przy czytelnym tekście**.

### 2.5 Regresja = ten sam fixed core, nie losowy fresh

Świeży dataset (`run_benchmark_fresh`) nadal ma sens do **wykrywania overfittingu**, ale **nie jest głównym KPI**.

Regresja release = gorszy wynik na **fixed core in-scope** (ten sam plik ground truth, te same PNG).

Wariancja ±5% na fresh = szum, nie regresja (reguła z MASTER pozostaje).

---

## 3. Definicja „in-scope” (SLA produktu)

Dokument trafia do **głównego KPI benchmarku**, gdy spełnia **wszystkie** warunki:

1. **Poziom degradacji:** lvl **0, 1 lub 3** (generator) **albo** realne zdjęcie/PDF z korpusu „fixed core”.
2. **Bramka OCR:** `isOcrQualityAcceptable()` = true (`OCR_CONF_THRESHOLD = 0.60`, min. 50 znaków) — benchmark v2 **musi symulować tę samą bramkę** co produkt.
3. **Typ wejścia:** obraz → OCR → silnik **lub** tekst/DOCX (silnik w izolacji — osobna ścieżka metryk).

Dokument **out-of-scope** (nie wchodzi do głównego recall):

- lvl **2** (noise+blur — ślepa uliczka),
- lvl 4–5 / qs poniżej progu,
- obrazy z confidence &lt; 0.60 (oczekiwany **REJECT**).

Dokument **in-scope po reject** = błąd implementacji bramki (fail testu).

---

## 4. Architektura metryk Benchmark v2

Raport (`benchmark_report.txt`) dzieli wyniki na **cztery sekcje**:

```
A. ENGINE-ONLY (JVM — PseudonymEngineTest + macierze)
   recall / precision per typ encji
   → „Czy silnik działa, gdy tekst jest?”

B. IN-SCOPE ACCEPTED (instrumented, OCR → bramka OK → silnik)
   recall / precision / krytyczne braki
   → GŁÓWNY KPI RELEASE

C. REJECTED (instrumented, bramka OCR odrzuciła)
   liczba dokumentów / % zbioru
   oczekiwane: 0 encji przetworzonych, komunikat reject
   → „Czy chronimy użytkownika przed pół-maską?”

D. OUT-OF-SCOPE (informacyjnie, bez blokowania release)
   recall na lvl 2, lvl 4–5, dokumenty poniżej progu BEZ bramki
   → tylko do śledzenia sufitu OCR w czasie
```

**Krytyczne braki** liczone wyłącznie w sekcji **B**.

**False positives** liczone w **A** i **B** — FP w in-scope = regresja.

---

## 5. Korpus danych

### 5.1 Fixed core (obowiązkowy, regresja)

- **20–30 dokumentów** — realne zdjęcia/PDF (dowód, faktura, umowa, wypis, stary dowód).
- Ręcznie zweryfikowany ground truth.
- Plik: np. `ground_truth_v2_fixed.json` + folder `images/fixed/`.
- **Nie rotować** między runami — porównywalność.

### 5.2 Adversarial engine (JVM, nie OCR)

- Przypadki z briefów: `e-nnail`, `ul,`, NIP split, paszport guard, CAPS solo nazwisko.
- Już częściowo w `PseudonymEngineTest` — **spiąć listę jako „adversarial manifest”** w raporcie JVM.

### 5.3 Fresh opcjonalny (generator)

- Tylko **lvl 0, 1, 3** — bez lvl 2.
- Osobny ground truth: `ground_truth_v2_fresh.json`.
- Metryka informacyjna + wykrywanie overfittingu.

### 5.4 Reject set

- 10–15 dokumentów **celowo** poniżej progu (lvl 2, rozmazane, qs &lt; 60).
- Oczekiwany wynik: **100% reject**, 0 tokenów w output.
- Fail testu, jeśli silnik przetwarza mimo bramki.

---

## 6. Progi akceptacji (propozycja startowa — właściciel zatwierdza po pierwszym runie)

| Sekcja | Metryka | Próg release |
|---|---|---|
| **B — IN-SCOPE** | Recall encji krytycznych (PESEL, NIP, REGON, dowód, paszport) | ≥ **95%** |
| **B — IN-SCOPE** | Recall ogólny | ≥ **90%** |
| **B — IN-SCOPE** | Krytyczne braki silnika (tekst był w OCR, encja in-scope) | **0** |
| **B — IN-SCOPE** | False positives | **0** (OutputGuard) |
| **C — REJECTED** | Poprawnie odrzucone | **100%** |
| **A — ENGINE-ONLY** | Recall per typ (testy JVM istniejące) | **100%** pass suite |

Progi można skorygować po pierwszym runie v2 na fixed core — **nie przed** wdrożeniem podziału metryk.

---

## 7. Zakres implementacji (deliverables)

### 7.1 Kod

| Plik / obszar | Zmiana |
|---|---|
| `BenchmarkInstrumentedTest.kt` | Podział raportu na sekcje A–D; filtr in-scope; symulacja `isOcrQualityAcceptable()` przed silnikiem; osobny test `benchmark_v2_fixed_core` |
| `OcrQuality.kt` | Bez zmian funkcjonalnych — benchmark używa tych samych stałych |
| Skrypt `run_benchmark_fresh.bat` | Wariant `run_benchmark_v2.bat` z jawnym ground truth v2 |
| Generator (jeśli istnieje w repo) | Eksport lvl 0/1/3 only dla fresh; oznaczenie `degradation_level` + `quality_score` w JSON |

### 7.2 Dokumentacja

| Plik | Zmiana |
|---|---|
| `MASTER_LynxMask_Mobile.md` | Sekcja benchmark: filozofia v2, progi, definicja regresji; stary recall 78,9% → „archiwum baseline v1” |
| `BACKLOG.md` | Wpis: BENCHMARK-V2, zamknąć po pierwszym green run fixed core |

### 7.3 Nie w scope tego zadania

- Preprocessing obrazu pod lvl 2
- Zbieranie 30 realnych zdjęć od właściciela — **Paweł dostarcza pierwszą paczkę (min. 10)**; resztę można uzupełnić generator lvl 0/1/3
- Zmiany w silniku „pod benchmark” bez case z JVM/adversarial

---

## 8. Czego NIE robić (antywzorce)

1. **Nie podnosić recall v1 na lvl03 jako celu sprintu.**
2. **Nie traktować BRAK_W_OCR na lvl 2 jako buga do naprawy w OcrNormalizer.**
3. **Nie dodawać regexów wyłącznie dlatego, że „padł” syntetyczny dokument z generatora** — najpierw: czy dokument jest in-scope? czy case jest w ENGINE-ONLY?
4. **Nie mieszać metryk IMAGE-REDACT z recall tekstowym** — osobny checklist manualny (BACKLOG §9).
5. **Nie blokować release jedną liczbą recall na 68 dokumentach** bez podziału na sekcje.

---

## 9. Kolejność prac

1. **Dokumentacja** — zaktualizować MASTER (filozofia + SLA) — ten brief jako źródło.
2. **BenchmarkInstrumentedTest** — sekcje raportu + bramka OCR + `ground_truth_v2_fixed.json` (min. placeholder 10 dok. z lvl 0/1/3 dopóki brak realnych zdjęć).
3. **Pierwszy run v2** — zapisać baseline v2 w MASTER (osobna tabela, nie porównywać z 78,9% v1).
4. **Dopiero potem** — kolejne bugfixy silnika (DATE-PARTIAL, FP-REFNUM, stary dowód CAPS) mierzone **tylko** na sekcji B + JVM.

---

## 10. Kryterium ukończenia

Zadanie uznane za **DONE**, gdy:

- [ ] Raport benchmarku ma sekcje A / B / C / D zgodnie z §4
- [ ] Główny KPI = sekcja **B** (IN-SCOPE ACCEPTED)
- [ ] Reject set: 100% odrzuceń
- [ ] MASTER opisuje filozofię v2 i progi z §6
- [ ] Baseline v2 zapisany (data + liczby per sekcja)
- [ ] `run_benchmark_v2.bat` (lub równoważny) udokumentowany w sekcji 11 MASTER

---

## 11. Kontekst (dlaczego teraz)

- Benchmark v1 dał cenną diagnozę (sufit OCR, BRAK_W_OCR), ale **wyczerpał się jako KPI produktowy**.
- Silnik na lvl 0/1/3: NUMER ~93%, encje krytyczne — dobre; na lvl 2 — ~27% (**sufit wejścia**, nie silnika).
- Bramka `OcrQuality.kt` jest w kodzie — benchmark powinien ją **egzekwować**, nie omijać.
- IMAGE-REDACT (Cursor) zamyka część use-case’ów „obraz do sieci” bez wymogu pełnego OCR recall.

---

## 12. Addendum techniczne (2026-06-25)

**Autor addendum:** Cursor (sesja napraw benchmarku v1 + analiza runu)  
**Status:** obowiązuje implementatora v2 (Claude Code / Sonet) — uzupełnia §4–§7, nie zmienia decyzji właściciela z §1.

### 12.1 Baseline v1 — archiwum (nie KPI release)

Ostatni udany run **stały dataset**, `dataset_staly`, `ground_truth_lvl03.json`, 68 dokumentów, data **2026-06-25 07:47**:

| Metryka | Wartość |
|---|---|
| Recall ogólny | 79,2% |
| Precision | 67,9% |
| F1 | 73,1% |
| Krytyczne braki | 17 |
| False positives (token vs GT) | 160 |
| Błędy pipeline (crash) | 0 |

**Recall per poziom degradacji:**

| Lvl | Opis | Recall | docs | encje |
|---|---|---|---|---|
| 0 | perfect scan | 95,2% | 17 | 105 |
| 1 | light noise | 93,8% | 17 | 112 |
| 2 | noise+blur | 42,0% | 17 | 100 |
| 3 | phone (good) | 82,7% | 17 | 110 |

**Wniosek:** v1 potwierdził tezę z §1 — jedna liczba recall na 68 dok. nie nadaje się jako KPI. Baseline v1 zapisujemy w MASTER jako **archiwum**, baseline v2 startuje od zera (sekcje A–D).

Źródło: `benchmark_results/staly/2026-06-25_0747/`.

---

### 12.2 Klasyfikacja braków (obowiązkowa w raporcie v2)

Każdy pominięty hit w ground truth musi dostać **jedną** etykietę:

| Etykieta | Definicja | Wlicza się do KPI release? |
|---|---|---|
| `BRAK_W_OCR` | Encji nie ma w znormalizowanym tekście OCR (exact match po `normalizeForCompare`) | **Nie** — jeśli dokument in-scope i bramka go przepuściła → błąd bramki lub błąd zbioru |
| `OCR_ZNIEKSZTAŁCONY` | Encji nie ma exact, ale fuzzy match na runach numerycznych (`extractNumericRuns` + `fuzzyMatch`) | **Tak** — to bug silnika/normalizera, nie OCR |
| `BUG_SILNIKA` | Encja jest w OCR (exact), silnik nie zamaskował | **Tak** — blokuje release w sekcji B |

**Krytyczne braki release** = wyłącznie `OCR_ZNIEKSZTAŁCONY` + `BUG_SILNIKA` na dokumentach **in-scope ACCEPTED** (sekcja B).

Implementacja częściowa już istnieje w `BenchmarkInstrumentedTest.kt` (fuzzy dla NUMER, diagnostyka `≈ OCR zniekształcony`). v2 **ujednolica nazewnictwo** i liczy KPI per sekcja.

Przykład z runu 2026-06-25: `doc_00013` lvl 2 — PESEL `57092787506` → `≈ OCR zniekształcony` (silnik); pozostałe 16 krytycznych → `BRAK_W_OCR` (głównie lvl 2).

---

### 12.3 `quality_score` generatora ≠ bramka produktu

Pole `qs` w `ground_truth.json` pochodzi z generatora syntetycznego. Na runie 2026-06-25 dokumenty **lvl 2** mają qs **79–87**, a OCR ML Kit i tak produkuje nieczytelny tekst.

**Reguła v2:** decyzja ACCEPT / REJECT opiera się **wyłącznie** na:

```kotlin
isOcrQualityAcceptable(mlKitText)  // OCR_CONF_THRESHOLD = 0.60, OCR_MIN_CHARS = 50
```

Pole `qs` z JSON może trafić do raportu sekcji D (informacyjnie), **nie** zastępuje bramki. Jeśli lvl 2 przechodzi bramkę ML Kit — to sygnał do obniżenia progu lub rozszerzenia reject setu, nie do łatania OcrNormalizer.

Plik: `OcrQuality.kt` — bez zmian funkcjonalnych w tym zadaniu.

---

### 12.4 Bootstrap korpusów (do czasu realnych zdjęć od Pawła)

Dopóki brak paczki 10+ realnych zdjęć/PDF (§5.1), użyć istniejącego `dataset_staly`:

| Zbiór | Plik docelowy | Skład | Dokumentów |
|---|---|---|---|
| **Fixed core tymczasowy** | `ground_truth_v2_fixed.json` + `images/fixed/` | lvl **0, 1, 3** z `dataset_staly` | **51** (68 − 17 lvl 2) |
| **Reject set** | `ground_truth_v2_reject.json` + `images/reject/` | lvl **2** z `dataset_staly` | **17** |
| **Fresh opcjonalny** | `ground_truth_v2_fresh.json` | generator lvl 0/1/3 only | wg generatora |

Skrypt filtrowania: wyciągnąć z JSON wpisy gdzie `degradation_level != 2` (lub równoważne pole). Obrazki — symlink/kopia odpowiednich PNG.

**Reject set oczekiwany wynik:** 100% dokumentów → sekcja C (odrzucone), 0 tokenów w output, 0 encji przetworzonych. Fail testu, jeśli silnik maskuje mimo reject.

---

### 12.5 Definicja False Positive — dwa pojęcia, dwa progi

v1 miesza dwa różne pojęcia:

| Pojęcie | Jak mierzone w v1 | Próg v2 (§6) |
|---|---|---|
| **FP metryczne** | Token w `tokenMap` bez dopasowania do GT (precision 67,9%, ~160 FP na 68 dok.) | Informacyjnie w sekcji B; **nie** blokować release samym progiem precision |
| **Guard RED (wyciek)** | `OutputGuard` — surowy PII w `pseudonymizedText` po maskowaniu | **0** — blokuje release |

Raport v2 **musi mieć obie kolumny**:

```
B. IN-SCOPE ACCEPTED
  Recall encji krytycznych:     …
  BUG_SILNIKA (krytyczne):      …   ← główny blocker
  Guard RED hits:               0   ← główny blocker
  FP metryczne (token vs GT):   …   ← informacyjnie / trend
```

Nie stawiać progu „FP metryczne = 0” bez definicji — tokenizacja silnika zawsze produkuje tokeny poza GT (np. fragmenty adresów).

---

### 12.6 Smoke test Android regex (obowiązkowy przed pętlą benchmarku)

**Incydent 2026-06-25:** benchmark v1 padał z `ExceptionInInitializerError` / `PatternSyntaxException` w `OcrNormalizer.kt` — lookbehind z `\w*` i `\s*` jest **niedozwolony** w Android ICU regex (JVM przechodzi, telefon nie).

**Wymaganie v2:**

1. Przed przetwarzaniem dokumentów: wymusić inicjalizację `OcrNormalizer` + `StructuralEngine` regexów na urządzeniu (np. `OcrNormalizer.normalize("test")` lub dedykowany `@Test fun regexSmokeTest()`).
2. W CI dokumentacji: **testy JVM ≠ wystarczające** dla nowych regexów w `OcrNormalizer` / `StructuralEngine`.
3. Zakaz wzorców: nieograniczony lookbehind `(?<=…\w*…)`, lookahead z katastroficznym backtrackingiem (ReDoS — incydent w `StructuralEngine` IBAN, naprawiony na `\bPL[A-Z0-9]{20,40}\b`).

---

### 12.7 Znane in-scope case’y do fixed core (nie łatać pod v1)

Po filtracji lvl 2 zostają realne tematy silnika — **w fixed core**, nie w reject:

| Dokument | Lvl | Encja | Klasa | Uwaga |
|---|---|---|---|---|
| `doc_00026` | 3 | PESEL, dowód `LKW771657` | BRAK_W_OCR / do weryfikacji | jedyny krytyczny miss lvl 3 w runie 2026-06-25 |
| `doc_00013` | 2→reject | PESEL `57092787506` | OCR_ZNIEKSZTAŁCONY | w reject secie; case silnika jeśli bramka kiedyś przepuści podobny tekst |
| `doc_00016`, `00020`, `00030`, `00042` | 0–1 | EMAIL | BRAK_W_OCR fałszywy? | OCR ma `e-nnail`, spacje w adresie — comparator musi używać `normalizeForCompare` + tolerancja OCR email (patrz `TODO_silnik.md` § EMAIL) |

Bugfixy silnika **po** wdrożeniu v2, mierzone tylko na sekcji B + JVM adversarial — zgodnie z §9 pkt 4.

---

### 12.8 Poza scope Benchmark v2 (explicit)

Nie wliczać do sekcji A–D ani progów release:

| Obszar | Gdzie testować |
|---|---|
| Schowek / `ClipSafety` | `ClipSafetyTest.kt`, test ręczny ClipboardCheckActivity |
| IMAGE-REDACT (blur twarzy, podpis) | BACKLOG §9, checklist manualny |
| Depseudonymizacja / SessionStore | testy JVM + UI |
| `LookupTables` niezainicjalizowane w sesji | RAPORT_Security_RODO — Tier A |
| GuardAllowlist fałszywe „nie maskuj” | test ręczny; nie benchmark OCR |

---

### 12.9 Aktualizacja §9 — kolejność z addendum

1. MASTER — filozofia v2 + baseline v1 z §12.1 (archiwum).
2. `BenchmarkInstrumentedTest` — sekcje A–D, bramka `isOcrQualityAcceptable`, klasyfikacja §12.2, bootstrap §12.4, smoke §12.6.
3. `run_benchmark_v2.bat` — fixed core + reject set + opcjonalnie fresh.
4. Pierwszy run v2 → baseline v2 w MASTER (osobna tabela).
5. Bugfixy silnika (§12.7) — tylko na sekcji B + JVM.

---

*To jest decyzja właściciela. Pytania interpretacyjne — do Pawła. Implementacja benchmarku i aktualizacja MASTER — Sonet / Claude Code.*
