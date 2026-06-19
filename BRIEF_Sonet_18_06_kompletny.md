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

### Ostatni run: 2026-06-18 16:13 ✅
Dataset: `ground_truth_lvl03.json` — **68 dokumentów**, lvl 0–3

| Metryka | Poprzednio (lvl01) | Teraz (lvl03) |
|---|---|---|
| Recall | 82,6% | **74,3%** |
| Precision | 66,4% | ~69% |
| EMAIL recall | 50% | **83,3%** |
| NUMER recall | 90,0% | **73,5%** |
| OSOBA recall | 64,0% | **81,5%** |
| ADRES recall | 82,9% | ~68% |
| Lvl 3 recall | — | **71,7%** |
| Krytyczne braki | 1 | **13** |

*Spadek Recall 82,6%→74,3% nie jest regresją — dataset lvl03 zawiera trudniejsze dokumenty (lvl 2–3) których wcześniej nie było.*

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

## CO ZROBIONE 19.06 ✅

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

| Bug | Plik | Opis |
|---|---|---|
| ~~**BUG-PESEL1**~~ | OcrNormalizer.kt | ✅ NAPRAWIONE 19.06 — `OCR_PESEL_WORD` obsługuje wszystkie warianty słowa PESEL |
| **BUG-NIP-SPLIT** | OcrNormalizer.kt + StructuralEngine.kt | NIP naprawiony częściowo → silnik łapie fragmenty osobno jako dwa NUMER |
| **BUG-EMAIL-TLD1** | OcrNormalizer.kt | `@wp p1` — TLD `p1` zawiera cyfrę, `OCR_EMAIL_TLDSPACE` szuka tylko liter; fix: dodać cyfry do TLD |
| **BUG-EMAIL-PARTIAL** | StructuralEngine.kt | Email częściowo zamaskowany — wyciek nazwiska+domeny gdy local-part to imię |
| **BUG-DOWOD** | StructuralEngine.kt | `FOH6 14892` — OCR spacja w środku, wzorzec nie łapie |
| **BUG-AL-OPEN** | PseudonymEngine.kt | Adresy z "al." — nie dotykać bez planu |
| **BUG-OUTPUTGUARD-FORMAT** | OutputGuard.kt | Guard szuka OSOBA_ABC_001, silnik generuje OSOBA_001 |
| **BUG-IBAN-NOSPACES** | StructuralEngine.kt | IBAN bez spacji (PL36...) nie łapany |

---

## CO ZROBIĆ JAKO PIERWSZE (następna sesja)

1. **BUG-EMAIL-TLD1** — rozszerzyć `OCR_EMAIL_TLDSPACE` o cyfry w TLD
   - Plik: `OcrNormalizer.kt` linia 159
   - Obecny: `([a-zA-Z]{2,4})\b` → zmienić na `([a-zA-Z0-9]{2,4})\b`

2. **BUG-NIP-SPLIT** — zbadać w bugs.txt które NIPy są rozbite na tokeny
   - Sprawdzić trace dla dokumentów z krytycznym brakiem NUMER

3. **ADRES recall ~68%** — kolejny duży cel; przejrzeć bugs.txt sekcja ADRES POMINIĘTE

4. **Nowy dataset** — wygenerować dokumenty z wariantami OCR które naprawiliśmy (PESE1, PE5EL, UI.Nazwa, NlP, emaile ze spacją) — benchmark mierzy tylko znane dokumenty, nowe reguły nie mają gdzie się zmierzyć

5. **Zdjęcia z aparatu** — zebrać realne artefakty OCR z różnych typów dokumentów → analiza → nowe reguły OcrNormalizera

6. **Dataset overfitting** — obecny benchmark nie wykrywa nowych luk, mierzy tylko wcześniej znane przypadki

---

## WERSJE PLIKÓW MOBILE (stan 2026-06-18 wieczór)

| Plik | Wersja | Co zmieniono |
|---|---|---|
| OcrNormalizer.kt | v1.5 | OCR_EMAIL, OCR_UL_PREFIX, OCR_PESEL/NIP/REGON/IBAN_DIGITS, OCR_NUMERIC_CHAR_MAP |
| StructuralEngine.kt | v1.9 | bez zmian 18.06 |
| NameEngine.kt | v1.11+ | bez zmian 18.06 |
| PseudonymEngine.kt | v2.3+ | DetectionTrace, traceMode |
| BenchmarkInstrumentedTest.kt | — | lvl03, normalizedText w analyze(), OCR[300] w bugs.txt |
| run_benchmark.bat | — | /storage/emulated/0/, pull benchmark_trace.txt |
| TODO_silnik.md | — | aktualizacja 18.06 |

---

## STAN DESKTOP (dla referencji)

Desktop stabilny. `USE_NEW_PIPELINE=True`, CLR 3.2%, Recall 91.4%.
Szczegóły w `BRIEF_Sonet_kontynuacja_16_06.md` w katalogu Desktop.
