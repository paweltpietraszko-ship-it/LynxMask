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

---

## ŚRODOWISKO — wymagane przed każdą sesją w CMD

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
```
Bez tego: "ERROR: JAVA_HOME is not set". `run_benchmark.bat` już to ustawia automatycznie.

---

## STAN BENCHMARKU

### Ostatni run: 2026-06-18 08:23 (po commitach 18.06) ✅
Dataset: `ground_truth_lvl01.json` — 34 dokumenty, lvl 0-1

| Metryka | Wartość |
|---|---|
| Recall | **82,6%** |
| Precision | 66,4% |
| F1 | 73,6% |
| FP | 89 |
| CLR (krytyczne braki) | 1 |
| OSOBA recall | 64,0% (32/50) |
| ADRES recall | 82,9% (34/41) |
| NUMER recall | 90,0% (108/120) |
| EMAIL recall | 100% (2/2) |
| Lvl 0 | 80,2% |
| Lvl 1 | 85,0% |

ADRES wzrósł z 80,5% → 82,9% dzięki fix norm() z polskimi znakami. OSOBA bez zmiany — wymaga pracy w silniku.

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

---

## DETECTIONTRACE — NOWY SYSTEM DIAGNOSTYCZNY

W `PseudonymEngine.kt` dodano `DetectionTrace` — śledzi która warstwa wykryła każdy token.

Struktura: `DOC | LAYER | RULE | TOKEN | MATCHED_TEXT`

Warstwy w trace:
- `DICT / USER_DICTIONARY` — Warstwa 1 (słownik użytkownika)
- `STRUCTURAL / NUMER|KWOTA|...` — Warstwa 2 (regex strukturalne)
- `NAME_ENGINE / CONTEXTUAL` — Warstwa 3 (NameEngine — imiona, adresy uliczne)
- `ADDRESS / ADRES` — Warstwa 3d (kody pocztowe + miasto)

`applyStreetLookup()` w NameEngine.kt wykrywa "ulica + numer" (np. Szkolna 150) i przechodzi przez NAME_ENGINE/CONTEXTUAL.
Warstwa 3d (ADDRESS_PATTERNS) wykrywa tylko kody pocztowe + miasto.

Plik trace: `benchmark_results\benchmark_trace.txt`

---

## ARCHITEKTURA BENCHMARKU — BenchmarkInstrumentedTest.kt

Kluczowe funkcje:
- `norm(v)` — normalizacja do porównania GT vs token: usuwa spacje, myślniki, polskie znaki → ASCII, lowercase. **Zaktualizowana 18.06** — teraz z konwersją polskich znaków (ą→a itd.)
- `normalizeForCompare(s)` — identyczna logika, używana w sekcjach diagnostycznych bugs.txt
- `analyze()` — logika `found`: typ-agnostyczna, length≥6 + substring match + fuzzyMatch dla numerów
- `saveReports()` — generuje report.txt, bugs.txt, trace.txt

Sekcje diagnostyczne w bugs.txt:
- `[OSOBA POMINIĘTE]` — z diagnostyką BRAK_W_OCR / ODMIANA_ZAMASKOWANA / BUG_SILNIKA
- `[EMAIL POMINIĘTE]` — z diagnostyką BRAK_W_OCR / BUG_SILNIKA
- `[ADRES POMINIĘTE]` — z diagnostyką BRAK_W_OCR / BUG_SILNIKA **[NOWE 18.06]**

---

## NOWE BUGI ODKRYTE 18.06

### BUG-EMAIL-PARTIAL — KRYTYCZNY (nowy)
**Objaw:** `anna.grabowska@interia.pl` → `OSOBA_008.grabowska@interia.pl`
- NameEngine zamaskował imię (`anna` → `OSOBA_008`)
- Ale email regex (Warstwa 2) NIE złapał całego adresu email
- Wynik: w tekście widoczne `.grabowska@interia.pl` — wyciek nazwiska + domeny
- **ZBADANO 18.06 (diagnostyka, bez fixa):**
  - Wzorzec email pozycja 0 (`\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b`) — MATCHUJE `joanna.grabowska@interia.pl` poprawnie
  - Kolejność warstw w PseudonymEngine.kt: W2 (STRUCTURAL) działa **przed** W3 (NameEngine) — kolejność prawidłowa
  - `"pl"` jest na `WHITE_LIST_COMMON_WORDS` (jako skrót placu) — może interferować z domeną `.pl`
  - Hipoteza: konkretny dokument ma artefakt OCR przed emailem który psuje `\b` lub wzorzec pozycji 1 matchuje fragment przed `@` i oddaje go NameEngine
  - Do zrobienia: zajrzeć do `benchmark_bugs.txt` sekcja `[EMAIL POMINIĘTE]` — zobaczyć dokładny tekst który przepadł

### BUG-EMAIL-SPLIT — średni (nowy)
**Objaw:** `e-mail:` → `NUMER_001-mail:`
- Strukturalny regex złapał `e` jako NUMER, zostawiając `-mail:` w tekście
- Prawdopodobnie wzorzec email dopasowuje za krótkie ciągi
- **ZBADANO 18.06 (wstępnie):** wzorzec pozycji 1 w STRUCTURAL_PATTERNS: `(?i)\be[- ]?mail\s*[:–\-]\s*[a-zA-Z0-9._%+\-@]+\.[a-zA-Z]{2,}\b` — do weryfikacji czy `\be` nie jest łapane przez inny wzorzec wcześniej

---

## OTWARTE BUGI (ze starszych sesji)

| Bug | Plik | Opis |
|---|---|---|
| **BUG-EMAIL-PARTIAL** | StructuralEngine.kt | **NOWY** email częściowo zamaskowany — wyciek nazwiska+domeny |
| **BUG-EMAIL-SPLIT** | StructuralEngine.kt | **NOWY** `e-mail:` → `NUMER_001-mail:` |
| BUG-DOWOD | StructuralEngine.kt | FOH614892 — OCR daje `FOH6 14892` ze spacją, wzorzec nie łapie |
| BUG-AL-OPEN | PseudonymEngine.kt | Adresy z "al." — nie dotykać bez planu |
| BUG-OUTPUTGUARD-FORMAT | OutputGuard.kt | Guard szuka OSOBA_ABC_001, silnik generuje OSOBA_001 |
| BUG-IBAN-NOSPACES | StructuralEngine.kt | IBAN bez spacji (PL36...) nie łapany |

---

## CO ZROBIĆ JAKO PIERWSZE (następna sesja)

1. ~~**Uruchom nowy benchmark**~~ ✅ DONE — run 08:23, Recall 82,6%, ADRES 82,9%

2. **BUG-EMAIL-PARTIAL** — zajrzeć do `benchmark_bugs.txt` sekcja `[EMAIL POMINIĘTE]`
   - Wzorzec i kolejność warstw zbadane — są poprawne
   - Potrzebny: dokładny tekst dokumentu który przepada (z bugs.txt)
   - Hipoteza: artefakt OCR przed emailem lub interferencia wzorca pozycji 1 z NameEngine

3. **BUG-EMAIL-SPLIT** — przy okazji BUG-EMAIL-PARTIAL
   - Sprawdzić czy wzorzec pozycji 1 (`\be[- ]?mail...`) nie jest blokowany przez wcześniejszy wzorzec STRUCTURAL

---

## WAŻNE ZASADY BENCHMARKU

- Przed benchmarkiem po zmianie kodu: pełny reinstall APK testowego:
  ```
  .\gradlew :app:uninstallDebugAndroidTest
  .\gradlew :app:installDebugAndroidTest
  run_benchmark.bat
  ```
- `UserDictionary.clear(context)` na początku `runBenchmark()` — nie usuwać
- Android 16 (Samsung SM-A536B): `getExternalFilesDir()` = `/storage/emulated/0/Android/data/...`
- JAVA_HOME musi być ustawiony przed gradlew (run_benchmark.bat robi to automatycznie)

---

## WERSJE PLIKÓW MOBILE (stan 2026-06-18)

| Plik | Wersja | Co zmieniono |
|---|---|---|
| StructuralEngine.kt | v1.9 | bez zmian 18.06 |
| NameEngine.kt | v1.11+ | bez zmian 18.06 |
| PseudonymEngine.kt | v2.3+ | DetectionTrace, traceMode, assignToken z layer/rule |
| BenchmarkInstrumentedTest.kt | — | norm() z ASCII, normalizeForCompare(), sekcje ADRES/EMAIL POMINIĘTE, trace.txt, lvl01 |
| run_benchmark.bat | — | /storage/emulated/0/, pull benchmark_trace.txt |
| TODO_silnik.md | — | sekcja ŚRODOWISKO + sekcja EMAIL edge case (18.06 sesja 2) |

---

## STAN DESKTOP (dla referencji)

Desktop stabilny. `USE_NEW_PIPELINE=True`, CLR 3.2%, Recall 91.4%.
Szczegóły w `BRIEF_Sonet_kontynuacja_16_06.md` w katalogu Desktop.
