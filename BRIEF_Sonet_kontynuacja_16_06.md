# BRIEF DLA NASTĘPNEGO SONETA — LynxMask Desktop + Mobile
**Data:** 16.06.2026
**Autor:** Claude Sonnet 4.6 (sesja 14-16.06.2026)
**Dla:** następnego Soneta który przejmuje orkiestrację

---

## ZASADY PRACY Z PAWŁEM (WAŻNE — przeczytaj najpierw)

Paweł jest właścicielem projektu, nie programistą. Pisze kod przez Claude Code w terminalu.
- **Dopytuj zamiast się domyślać** — jeśli coś jest niejednoznaczne, zapytaj. Lepiej zadać jedno pytanie niż naprawić złą rzecz.
- **Nie używaj skrótów bez wyjaśnienia** — mów "plik który przetwarza tekst" zamiast "pipeline orchestrator". Wyjaśniaj co, jak i gdzie.
- **Nie bądź sycofantyczny** — Paweł nie lubi pochlebstw i nadmiernej struktury. Odpowiadaj rzeczowo i zwięźle.
- **Kiedy mówisz o instancji w terminalu mów "Claude"**, kiedy mówisz o sobie lub innym Sonecie mów "Sonet".
- **Słowo "mapa"** — Paweł używa go w znaczeniu "mapa architektury programu" (plik .md z opisem struktury), nie mapa danych. Dopytaj jeśli niejednoznaczne.
- **Jeden temat na raz** — nie dawaj Claude Code dużych bloków zadań. Małe zadanie → test → wynik → następne zadanie.

---

## PROJEKTY I ŚCIEŻKI

**Desktop:** `C:\Users\p_pie\Desktop\pseudominizer\`
**Mobile:** `C:\Projects\LynxMask\`
**Testy Mobile:** `.\gradlew :app:testDebugUnitTest`
**Benchmark Mobile:** `run_benchmark.bat` (w katalogu projektu Mobile)

---

## CZĘŚĆ 1 — STAN DESKTOP (po sesjach 14.06.2026)

### Wyniki końcowe (Run H — najlepszy stan)
| Metryka | Stary pipeline | Nowy pipeline |
|---|---|---|
| CLR (krytyczne wycieki) | 5.3% | 3.2% |
| Recall | 90.8% | 91.4% |
| Precision | 58.6% | 70.4% |
| F1 | 71.2% | 78.9% |
| OSOBA recall | 96.3% | 96.3% |

### Wersje plików Desktop (stan 14.06.2026)
| Plik | Wersja | Uwagi |
|---|---|---|
| pipeline.py | v1.21 | USE_NEW_PIPELINE=True domyślnie |
| pipeline_core.py | v0.2+ | TokenAllocator, PipelineState |
| pipeline_new.py | v0.4 | pre-ekstrakcja NER przed address layer |
| layers/identity.py | v1.1 | NIP z kropką OCR |
| layers/numeric.py | v1.0 | NOWY — KL-, FV-/VAT/, UMW/ |
| layers/address.py | v1.5 | słownik SIMC 179370 form miast |
| layers/ner_adapter.py | v1.1 | extract_ner_results() pre-ekstrakcja |
| ner_blocklist.py | v1.3 | 263 wpisów |
| benchmark.py | v1.2+ | fix HTTP 400 |
| cities.json | — | 31117 miast z GUS SIMC |
| cities_forms.json | — | 179370 form morfologicznych (Morfeusz2) |
| pseudominizer_api.py | v1.30-TAURI | bez zmian |

### Otwarte bugi Desktop (do następnego potoku)
| Bug | Plik | Opis | Potok |
|---|---|---|---|
| BUG-ADDR-FP | layers/address.py | ADRES precision 68.3%, FP z multilinii OCR, deduplication po kodzie pocztowym | Bezpieczeństwo |
| BUG-PT-ADMIN-SIG | layers/legal.py | PT.070433.2020 rozcięte przez OCR | OCR-Enhancement |
| BUG-UI-TOKEN-SKLEJANIE | Pseudonimizuj.tsx | Token skleja się z następnym słowem (estetyczny) | UI-2 |
| BUG-3 | anonymizer.py | Token injection — użytkownik może odtworzyć dane | Bezpieczeństwo |
| BUG-10 | pseudominizer_api.py | "Dodaj i zakryj" HTTP 401 | UI-2 |

### Kolejka potoków Desktop
| Potok | Status | Zakres |
|---|---|---|
| NER-fix | ✅ ZAMKNIĘTY | — |
| Coverage-Fix | ✅ ZAMKNIĘTY | — |
| Testy+Słownik | ✅ ZAMKNIĘTY | — |
| **UI-2** | ⏳ czeka | BUG-10, BUG-UI-TOKEN-SKLEJANIE |
| **Bezpieczeństwo** | ⏳ czeka | BUG-3, AUD-*, token injection, PBKDF2 |
| **OCR-Enhancement** | 📋 planowany | preprocessing obrazu przed Tesseract |
| **Express-Mode** | 📋 planowany | tryb free bez logowania, max 3 kliknięcia |
| **Anonimizacja-wizualna** | 📋 planowany | blur twarzy, tablice rej., EXIF/DOCX |

### Pseudominizer-Tauri — znane bugi (nieprzypisane do potoku)
Aplikacja Desktop to Tauri v2 + React/TypeScript frontend + Python FastAPI backend.
Przy debugowaniu Tauri pamiętaj:
- Backend FastAPI działa na porcie 8765, token API w `api_token.txt`
- Komunikacja frontend↔backend przez REST, nie przez WebSocket
- `pseudominizer_api.py` to główny plik API — tam są endpointy
- `main.rs` to Tauri shell — nie dotykać bez potrzeby
- Znane problemy: BUG-10 (401 na /profile/add-entity), BUG-UI-TOKEN-SKLEJANIE (estetyczny)
- Przed debugowaniem Tauri zawsze sprawdź czy serwer FastAPI działa: `curl http://localhost:8765/health`

---

## CZĘŚĆ 2 — STAN MOBILE (po sesji 16.06.2026)

### Wyniki benchmarku (dataset lvl 0-3, 68 dokumentów)
| Metryka | Baseline (przed sesją) | Stan końcowy (16.06) |
|---|---|---|
| Recall | 63.0% | 70.2% |
| CLR | 17 | 15 |
| NUMER recall | 67.9% | 77.0% |
| ADRES recall | 54.3% | 64.2% |
| OSOBA recall | 48.6% | 57.6% |
| Lvl 0 | 84.9% | 89.6% |
| Lvl 1 | 82.2% | 88.8% |

15/15 krytycznych braków to OCR — ML Kit nie widzi tekstu na zdegradowanych obrazach. Silnik nie może tego naprawić bez lepszego OCR.

### Wersje plików Mobile (stan 16.06.2026)
| Plik | Wersja | Co zmieniono |
|---|---|---|
| StructuralEngine.kt | v1.9 | EMAIL na poz. 0, IBAN z myślnikami [-\s]?, PESEL lookaround, NIP z kropką OCR, sygnatura administracyjna, dowód "seria XXX nr NNNNNN", Km/Ko/Kmp małe litery, konto bez PL ze spacjami |
| NameEngine.kt | v1.11 | OSOBA_DENYLIST + filtr długości 4 znaków |
| PseudonymEngine.kt | v2.3 | ADDRESS_PATTERNS jako Warstwa 3d po NameEngine |
| BenchmarkInstrumentedTest.kt | — | typeMap 32 klucze, ENTITY_TYPE_MAP fix, GROUND_TRUTH_FILE stała, diagnostyka wszystkich braków |
| run_benchmark.bat | — | NOWY — automatyczny build+deploy+benchmark+pull wyników |
| dataset/ground_truth_lvl03.json | — | NOWY — 68 dokumentów lvl 0-3 |

### Infrastruktura benchmarku Mobile
```
Kolejność uruchomienia:
1. Podłącz telefon USB
2. Uruchom: run_benchmark.bat (z C:\Projects\LynxMask)
   — instaluje APK testowe przez gradlew
   — przepycha dataset na telefon
   — uruchamia benchmark
   — pobiera wyniki do benchmark_results\
3. Wyniki: benchmark_results\benchmark_report.txt i benchmark_bugs.txt

Zmiana datasetu: w BenchmarkInstrumentedTest.kt zmień stałą:
const val GROUND_TRUTH_FILE = "ground_truth_lvl03.json"  // lvl 0-3
const val GROUND_TRUTH_FILE = "ground_truth.json"         // pełny lvl 0-5
```

### Otwarte bugi Mobile (do następnych potoków)
| Bug | Plik | Opis | Potok |
|---|---|---|---|
| BUG-AL-OPEN | PseudonymEngine.kt | Adresy z "al." nie maskowane — wielokrotne nieudane próby, **nie dotykać bez planu** | TBD |
| BUG-OUTPUTGUARD | OutputGuard.kt | Guard nie wykrywa wycieków które przeżyły pipeline. Pomysł: flagować wzorce @, /, PL+cyfry, PESEL-like, sygnatury jako podejrzane | Potok OCR |
| BUG-SAMOUCZENIE | PseudonymEngine.kt | Program nie uczy się na zaznaczonych encjach — ani Mobile ani Desktop. Sprawdzić jak to działa w Desktop (UserDictionary?) | TBD |
| BUG-OSOBA-58% | NameEngine.kt + LookupTables | OSOBA recall 57.6% — brakuje form fleksyjnych nazwisk, podwójne nazwiska z myślnikiem | Potok LT |
| doc_00021 anomalia | OCR | lvl=0, qs=100, IBAN "BRAK w OCR" mimo idealnego skanu — ML Kit formatuje inaczej niż Tesseract | Potok OCR |
| BUG-28 | NameEngine.kt | lazy regex kompilowany z 200 fallback imion przed LookupTables.init() | po OCR |

### Kolejka potoków Mobile
| Potok | Status | Zakres |
|---|---|---|
| 3b, RODO, BUG-DICT, 3c | ✅ ZAMKNIĘTE | — |
| **3c-FIX** | ✅ ZAMKNIĘTY (sesja 16.06) | StructuralEngine v1.9, NameEngine v1.11, ADDRESS_PATTERNS |
| **6.2** | 🟢 W TOKU | UI, Express Mode, SessionStore bugi |
| **7** | ⏳ po 3c-FIX | Format tokenów TYP_XXX_NNN, taksonomia 9 typów |
| **OCR** | 📋 po 7 | OutputGuard redesign, BUG-GUARD |
| **LT** | 📋 wymaga plików | Rozbudowa LookupTables z Morfeusz2 |

---

## CZĘŚĆ 3 — DECYZJE ARCHITEKTONICZNE (obie platformy)

- **USE_NEW_PIPELINE=True** — domyślne na Desktop od Run H
- **Taksonomia tokenów Mobile (Potok 7):** 9 typów: OSOBA, ADRES, NUMER, ORGANIZACJA, EMAIL, TELEFON, SYGNATURA, DATA, KWOTA. DOKUMENT usunięty z planu.
- **Format tokenu Mobile:** TYP_XXX_NNN gdzie XXX = 3-znakowy suffix sesji
- **Trzy tryby obsługi jakości obrazu** (Mobile i Desktop, do implementacji w UI):
  - qs ≥ 80: przetwarza bez komunikatu
  - qs 60-79: przetwarza z ostrzeżeniem "obraz niskiej jakości"
  - qs < 60: odrzuca z komunikatem + opcja "Spróbuj mimo to"
- **Dataset benchmark Mobile:** lvl 0-3 (68 dok.) jako podstawowy miernik silnika. Lvl 4-5 to śmieci — OCR ich nie widzi.
- **Test przez DOCX vs benchmark:** DOCX testuje silnik w izolacji, benchmark testuje cały pipeline z OCR. Naprawiaj przez DOCX, weryfikuj przez benchmark.
- **BUG-ARCH Mobile:** hasło NIE wiązane kryptograficznie z kluczem SQLCipher — Android Keystore + AES-256-GCM wystarczające (Art. 32 RODO)
- **verbal_amounts Desktop:** usunięte świadomie z nowego pipeline (19 FP → 0)

---

## CZĘŚĆ 4 — BACKLOG STRATEGICZNY

| Temat | Opis | Priorytet |
|---|---|---|
| OutputGuard redesign | Flagowanie podejrzanych wzorców które przeżyły pipeline | Wysoki |
| Samouczenie encji | Program nie uczy się na zaznaczonych encjach w obu platformach | Wysoki |
| Express Mode | Desktop + Mobile — tryb free bez logowania, max 3 kliknięcia | Przed dystrybucją |
| Aktualizacje | Tauri updater przez GitHub Releases (Desktop), Play Store (Mobile) | Przed dystrybucją |
| Wersje językowe | Profile wzorców PII per kraj (CZ, SK, DE) | Po stabilizacji PL |
| Anonimizacja wizualna | Blur twarzy, tablice rejestracyjne, EXIF/DOCX metadata | Po OCR-Enhancement |
| PBKDF2 migracja Desktop | AUD-13/14 — zmiana iteracji/soli unieważnia mapy.enc | Przed dystrybucją |
| Synchronizacja Mobile↔Desktop | Format .lynxdict, przenoszenie słownika | Po potoku LT |

---

## AKTUALIZACJA — sesja wieczorna 16.06.2026

### Zmiany drugiego Soneta (potok UI Mobile)
Następujące pliki zostały zmienione w sesji wieczornej:
- `OutputGuard.kt` v1.7 — nowa `data class GuardHit`, `runOutputGuard()` zwraca `List<GuardHit>` z RED/YELLOW
- `PseudonymEngine.kt` — `guardHits` dodane do `PseudonymResult` z wartością domyślną `emptyList()`
- `PseudonymResultPanel.kt` — `GuardHitsSection` z klikalnymi hitami, baner RED, "Wyślij" zablokowany przy RED
- `ShareTargetActivity.kt` — `FLAG_SECURE` tylko w release, `onSaveDescription` podpięty
- `OutputGuardTest.kt` — 7 nowych testów

Cofnięte w tej samej sesji:
- Wzorzec sygnatury komorniczej — przywrócony do `[A-Z]{1,3}` (zbyt szeroki `[A-Z][A-Za-z]{0,2}` łapał skróty)
- Rozszerzone sprawdzenie streetForms w NameEngine — cofnięte (zepsuło recall)

### Ważne odkrycie — UserDictionary w benchmarku
Benchmark używa `UserDictionary.load(context)` — czyli ładuje prawdziwy słownik z telefonu.
Ręczne testy przez aplikację dodały śmieciowe encje do słownika:
- `NUMER_005@wp.plOCR`
- `PL61 1090 1014 0000 0712 1981 2874IBAN`
- i inne

To spowodowało 890 FP w benchmarku (precision 25%).

**Rozwiązanie:** dodano `UserDictionary.clear(context)` na początku `runBenchmark()`.
To czyści słownik przed każdym benchmarkiem — gwarantuje powtarzalność wyników.

### Wynik końcowy po sesji wieczornej
| Metryka | Rano (moja sesja) | Wieczór (po naprawie) |
|---|---|---|
| Recall | 70.2% | 67.7% |
| Precision | 67.5% | 67.2% |
| FP | 147 | 144 |
| CLR | 15 | 15 |

Niewielka regresja recall (70.2% → 67.7%) — przyczyna niewyjaśniona, prawdopodobnie różnica w datasecie lub LookupTables.

### Ważna zasada na przyszłość
Po każdym ręcznym teście przez aplikację — wyczyść UserDictionary przed benchmarkiem.
Albo po prostu polegaj na `UserDictionary.clear(context)` które jest teraz w `runBenchmark()`.

### Rozbieżność formatu tokenów
OutputGuard v1.7 szuka tokenów w formacie `OSOBA_ABC_001` (z sufixem sesji).
Silnik generuje tokeny w formacie `OSOBA_001` (bez sufiksu).
OutputGuard nie rozpoznaje istniejących tokenów — skanuje tekst z gołymi tokenami.
To może powodować fałszywe GuardHit ale NIE wpływa na benchmark (guard jest read-only).
Do naprawy w potoku OCR gdy będzie implementowany nowy OutputGuard.
