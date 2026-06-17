# BRIEF DLA SONETA — LynxMask Mobile + Desktop
**Data:** 17.06.2026
**Źródła:** sesja poranna 16.06 (Sonet-1) + sesja wieczorna 16.06 (Sonet-2)

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

### Zasady dla Claude
1. Jedno zadanie na raz — nie łącz kroków
2. Zawsze kończ testem: `.\gradlew :app:testDebugUnitTest`
3. "Nic nie zmieniaj" — gdy chcesz tylko zobaczyć kod
4. Podaj dokładne ścieżki, nie "znajdź plik pipeline"
5. Gdy Claude idzie w złą stronę: "Zatrzymaj się. Zrób tylko to co napisałem."

---

## PROJEKTY I ŚCIEŻKI

- **Mobile:** `C:\Projects\LynxMask\`
- **Desktop:** `C:\Users\p_pie\Desktop\pseudominizer\`
- **Testy Mobile:** `.\gradlew :app:testDebugUnitTest`
- **Benchmark Mobile:** `run_benchmark.bat` (w katalogu projektu)

---

## STAN BENCHMARKU MOBILE

Dataset: `ground_truth_lvl03.json` (68 dokumentów, lvl 0-3)

| Metryka | Baseline rano | Stan końcowy wieczór | Stan 17.06 |
|---|---|---|---|
| Recall | 70.2% | 67.7% | 71.1% |
| Precision | 67.5% | 67.2% | 66.7% |
| FP | 147 | 144 | 155 |
| CLR | 15 | 15 | 15 |
| Lvl 0 | 89.6% | 86.8% | 91.5% |
| Lvl 1 | 88.8% | 86.9% | 89.7% |
| OSOBA recall | — | — | 72.8% |

## ZMIANY 17.06

- d8bb003 — LOOKUP-FIX TITLE_PATTERN
- a5e7ed9 — surnamesForms do pozytywnej detekcji
- 6933944 — HONORIFIC_NAME_ONLY
- cc1621a — kolejność bloków detekcji
- 547a5b7 — zdrobnienia Jan + TODO_silnik.md

## ROZWIĄZANE PROBLEMY 17.06

### Benchmark — problem z uruchomieniem
Po każdej większej zmianie plików benchmark może nie zapisać nowych raportów.
Przyczyna: FileAlreadyExistsException — nie może nadpisać plików w Documents/LynxMask.
Rozwiązanie: pełny reinstall APK przed każdym benchmarkiem:
```
.\gradlew :app:uninstallDebugAndroidTest
.\gradlew :app:installDebugAndroidTest
run_benchmark.bat
```
Bez reinstallu benchmark działa na starym APK i zwraca stare wyniki lub 0.0%.

### NameEngine — zmiany 17.06
1. LOOKUP-FIX TITLE_PATTERN — słowo po tytule musi być w namesForms lub surnamesForms
2. surnamesForms do pozytywnej detekcji — samo nazwisko bez imienia
3. HONORIFIC_NAME_ONLY — Pan/Pani + samo imię bez wymaganego nazwiska
4. Kolejność bloków — od najbardziej do najmniej specyficznych
5. namesForms do pozytywnej detekcji — samo imię bez kontekstu
6. Zdrobnienia — Kasia, Magda, Tomek, Jasiu i inne dodane do names_inflected.json

### WAŻNE — jak uruchamiać benchmark
Przed każdym benchmarkiem pełny reinstall APK testowego:
```
.\gradlew :app:uninstallDebugAndroidTest
.\gradlew :app:installDebugAndroidTest
run_benchmark.bat
```
Bez tego benchmark może działać na starym APK.

### WAŻNE — UserDictionary i benchmark
Benchmark używa `UserDictionary.load(context)` — ładuje prawdziwy słownik z telefonu.
Ręczne testy przez aplikację mogą dodać śmieciowe encje do słownika.
Rozwiązanie: `UserDictionary.clear(context)` jest już dodane na początku `runBenchmark()`.
Nie usuwaj tej linii.

---

## WERSJE PLIKÓW MOBILE (stan 16.06.2026 wieczór)

| Plik | Wersja | Co zmieniono |
|---|---|---|
| StructuralEngine.kt | v1.9 | EMAIL na poz. 0, IBAN z myślnikami, PESEL lookaround, NIP z kropką OCR, sygnatura adm., dowód "seria XXX nr NNNNNN", konto bez PL |
| NameEngine.kt | v1.11 | OSOBA_DENYLIST + filtr długości 4 znaków |
| PseudonymEngine.kt | v2.3+ | ADDRESS_PATTERNS jako Warstwa 3d po NameEngine, guardHits w PseudonymResult |
| OutputGuard.kt | v1.7 | data class GuardHit, RED/YELLOW, 7 testów |
| PseudonymResultPanel.kt | — | GuardHitsSection, baner RED, "Wyślij" blokowany, "Podgląd tekstu" |
| ShareTargetActivity.kt | — | onSaveDescription podpięty, FLAG_SECURE tylko release |
| BenchmarkInstrumentedTest.kt | — | typeMap 32 klucze, UserDictionary.clear(), ground_truth_lvl03.json |
| run_benchmark.bat | — | automatyczny build+deploy+benchmark+pull |
| dataset/ground_truth_lvl03.json | — | 68 dokumentów lvl 0-3 |

---

## OTWARTE BUGI MOBILE

### Krytyczne
| Bug | Plik | Opis |
|---|---|---|
| BUG-DOWOD | StructuralEngine.kt | FOH614892 jest w OCR ale nie maskowany — wzorzec wymaga spacji, OCR daje ciągły ciąg |
| BUG-OSOBA-58% | NameEngine.kt | surnamesForms nie uczestniczy w detekcji OSOBA — tylko zabezpieczenie w isAdjective() |

### Średnie
| Bug | Plik | Opis |
|---|---|---|
| BUG-AL-OPEN | PseudonymEngine.kt | Adresy z "al." nie maskowane — nie dotykać bez planu |
| BUG-IBAN-NOSPACES | StructuralEngine.kt | IBAN bez spacji (PL36...) nie łapany |
| BUG-OUTPUTGUARD-FORMAT | OutputGuard.kt | Guard szuka tokenów OSOBA_ABC_001, silnik generuje OSOBA_001 — rozbieżność formatów |
| BUG-SAMOUCZENIE | PseudonymEngine.kt | Program nie uczy się na zaznaczonych encjach — ani Mobile ani Desktop |

### Odłożone
| Bug | Opis |
|---|---|
| BUG-AL-OPEN | Wielokrotne nieudane próby. Nie dotykać. |
| AUD-M04 | isMinifyEnabled=false — osobna sesja |

---

## POTOK UI-2 — NOWY UKŁAD EKRANU WYNIKOWEGO

Uzgodniony z Pawłem układ PseudonymResultPanel:

1. Baner RED — znika gdy wszystkie RED hity obsłużone
2. RED hity — klikalne, znikają po obsłużeniu ✅ zrobione
3. YELLOW hity — klikalne, znikają po obsłużeniu lub "ignoruj" ✅ zrobione  
4. Przycisk "Podgląd tekstu" → modal z pełnym tekstem ✅ zrobione
5. **Inline formularz maskowania** — przeniesiony z ManualDialog BEZ modalu ❌ DO ZROBIENIA
   - pole tekstowe + chipy typów + przycisk "Maskuj"
   - aktualnie "Dodaj" otwiera ManualDialog jako osobne okno
6. "👁 ukrytych" i przyciski akcji
7. "Wyślij" zablokowany gdy aktywne RED hity ✅ zrobione

---

## KLUCZOWE ZNALEZISKO — OSOBA recall 57.6%

`surnamesForms` (1000 nazwisk z Morfeusz2) jest załadowany ale NIE używany do detekcji.
Detekcja działa tylko strukturalnie:
- NAME_FORWARD_REGEX — imię + słowo z wielką literą
- NAME_BACKWARD_REGEX — odwrotna kolejność
- HONORIFIC_REGEX — pan/pani + imię + słowo z wielką literą

Jeśli OCR zgubi imię lub dokument ma samo nazwisko → silnik go nie wykryje.

**Proponowane rozwiązanie:** detekcja przez surnamesForms z kontekstem:
- słowo pasuje do surnamesForms ORAZ
- występuje po "Pan/Pani" LUB przed/po PESEL LUB w nagłówku

**UWAGA:** Duża zmiana, ryzyko FP. Testuj przez DOCX najpierw, nie benchmark.

---

## KOLEJKA POTOKÓW MOBILE

| Potok | Status | Zakres |
|---|---|---|
| 3c-FIX | ✅ ZAMKNIĘTY | — |
| OutputGuard silnik | ✅ ZAMKNIĘTY | GuardHit, RED/YELLOW |
| **6.2** | 🟢 W TOKU | UI, Express Mode, SessionStore |
| **UI-2** | ⏳ czeka | Nowy układ PseudonymResultPanel (punkt 5) |
| **7** | ⏳ czeka | Format tokenów TYP_XXX_NNN, taksonomia 9 typów |
| **OCR** | 📋 planowany | OutputGuard redesign z dwupoziomowym alertem |
| **LT** | 📋 wymaga plików | Rozbudowa LookupTables z Morfeusz2 |

---

## CO ZROBIĆ JAKO PIERWSZE

1. **BUG-DOWOD** — sprawdź wzorzec dowodu w StructuralEngine.kt vs format FOH614892.
   Mały fix, mierzalny efekt na CLR (15→14).

2. **UI-2 punkt 5** — inline formularz maskowania zamiast ManualDialog.
   Paweł czeka na ten konkretny punkt.

3. **BUG-OSOBA-58%** — surnamesForms do detekcji z kontekstem.
   Testuj przez DOCX najpierw.

---

## STAN DESKTOP (dla referencji)

Desktop jest stabilny po sesjach 14.06. Kolejny potok to UI-2 (Desktop).
Szczegóły w `BRIEF_Sonet_kontynuacja_16_06.md` w katalogu Desktop.

Kluczowe: `USE_NEW_PIPELINE=True` domyślnie, CLR 3.2%, Recall 91.4%.

---

## TRZY TRYBY OBSŁUGI JAKOŚCI OBRAZU (do implementacji w UI)

- qs ≥ 80: przetwarza bez komunikatu
- qs 60-79: przetwarza z ostrzeżeniem "obraz niskiej jakości"
- qs < 60: odrzuca z komunikatem + opcja "Spróbuj mimo to"

---

## DECYZJE ARCHITEKTONICZNE

- Taksonomia tokenów (Potok 7): 9 typów: OSOBA, ADRES, NUMER, ORGANIZACJA, EMAIL, TELEFON, SYGNATURA, DATA, KWOTA
- Format tokenu: TYP_XXX_NNN (XXX = suffix sesji, Potok 7)
- BUG-ARCH: hasło NIE wiązane kryptograficznie z SQLCipher — Android Keystore wystarczające
- Dataset benchmark: lvl 0-3 (68 dok.) jako podstawowy miernik silnika
- Test przez DOCX testuje silnik, benchmark testuje OCR+silnik
