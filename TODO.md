# TODO — LynxMask Mobile
# JEDYNY plik z otwartymi bugami i zadaniami. Zamknięte pozycje usuwać stąd od razu, nie przekreślać.
# Historia/decyzje → git log. Nie tworzyć osobnych briefów/PLAN/CURSOR_* w katalogu głównym —
# treść idzie tutaj (zadania) albo do memory Claude (kontekst/diagnoza/decyzje).
# Ostatni remanent: 04.07.2026 — połączono TODO.md+BACKLOG.md+TODO_silnik.md+3×CURSOR_BRIEF.
# Remanent 05.07.2026 — usunięto PLAN_AddressEngine_Claude/CURSOR_AUDYT_ADRES/CURSOR_BRIEF_AddressEngine
# (Faza A+B ADRES zrobione, backlog Fazy C przeniesiony niżej, reszta w pamięci Claude).

---

## ŚREDNI — EMAIL recall, katalog wzorców degradacji OCR (06.07 ZWERYFIKOWANY, 2 poprawki zastosowane)

Benchmark 04-05.07 pokazywał EMAIL jako najsłabszy recall (57-67%). Katalog 5 przykładów z
05.07 zweryfikowany 06.07 Pythonem przeciw dokładnym regexom z kodu (nie zgadywane) —
**4 z 5 okazały się fałszywym alarmem**: AnchorEngine A.2 (kotwica `@`, zero walidacji formatu)
już maskuje całą frazę niezależnie od kształtu wewnątrz domeny (podwójny podkreślnik, brak
kropki w TLD typu "wppl" — nieważne, token i tak zastępuje cały span).

**Fix 1 (AnchorEngine, 06.07)**: prawa granica A.2 tolerowała spację PRZED kropką w domenie
("firma .pl") ale nie PO kropce ("firma. pl") — symetryczny ogon dodany, `AnchorEngine.kt:73`,
aktywny tylko gdy poprzedni fragment urwał się na kropce (lookbehind).

**Fix 2 (OcrNormalizer, 06.07, diagnoza Cursor traceMode)**: test regresji na Fix 1 ujawnił
INNY, wcześniejszy bug w Warstwie 0 — `OCR_EMAIL_TLDSPACE` (krok 7b) doklejał zwykłe słowo po
mailu z JUŻ kompletnym TLD ("jan@wp.pl do jutra" → "jan@wp.pl.do jutra", bo grupa 1 dopuszczała
kropkę w domenie, mylnie traktując kropkę+spację jako artefakt OCR zamiast granicę zdania).
Naprawione usunięciem kropki z klasy znaków grupy 1 (`OcrNormalizer.kt`). Test regresji w
`OcrNormalizerEmailTest.kt` (`BUG-EMAIL-KROPKA-SPACJA`). Zweryfikowane Pythonem że nie psuje
żadnego istniejącego testu TLDSPACE przed wdrożeniem.

Oba fixy razem: Fix 1 w AnchorEngine (kotwica jako ostatnia deska ratunku, ogólna dla całej
klasy degradacji — patrz pamięć Claude `feedback_anchor_signal_not_shape.md`), Fix 2 w
OcrNormalizer (osobny, wcześniejszy bug w Warstwie 0, znaleziony diagnozą Cursor przez
`traceMode=true`, nie zgadywaniem regexów w izolacji — patrz pamięć Claude
`feedback_trust_anchor_before_patching_other_layers.md`).

**Do zrobienia:** odpalić `PseudonymEngineTest` + `OcrNormalizerEmailTest` (nowe testy + reszta
— powinny być zielone), potem świeży benchmark żeby potwierdzić że EMAIL recall faktycznie
wzrósł.

**Dodatkowe odkrycie 06.07 — "chory termometr":** benchmark stały pokazał EMAIL 57,1% (4/7)
IDENTYCZNIE przed i po powyższych fixach silnika — zbadane, przyczyna NIE w silniku tylko w
samym skrypcie benchmarku. `BenchmarkInstrumentedTest.kt` — `fuzzyMatch` (tolerancja 1 znaku
różnicy, min. 9 znaków) był zarezerwowany tylko dla `numericKeys`, email/tekst nie miał żadnej
tolerancji na literówkę OCR (np. "wozniak"→"woziak" łamie `.contains()` mimo że silnik
prawdopodobnie poprawnie zamaskował całość jako token — kotwica nie waliduje kształtu).
**Naprawione**: usunięto ograniczenie do `numericKeys`, fuzzyMatch teraz działa dla wszystkich
pól. Zweryfikowane Pythonem na 3 przypadkach z benchmarku: 2/3 teraz poprawnie liczone jako
trafienie, 1/3 (`doc_00016`, dwa niezależne błędy OCR naraz: zgubiona litera W lokalnej części
+ zgubiona kropka w domenie) nadal słusznie miss — poza bezpiecznym progiem fuzzyMatch, nie
rozluźniać dalej. Pełna diagnoza i zasada ogólna w pamięci Claude
`feedback_validate_the_benchmark_tool_itself.md` — **przy każdej podejrzanej metryce benchmarku
sprawdzić najpierw sam skrypt scoringu, nie tylko silnik**.

**Do zrobienia:** odpalić świeży benchmark stały po tym fixie — EMAIL recall powinien skoczyć
z 57,1% (4/7) na ~85,7% (6/7).

---

## ŚREDNI — 3 blokery release we fresh 06.07 — 2/3 zdiagnozowane, 1/3 wymaga rerun

Fresh benchmark 06-07.07 2026-07-06_2111 pokazał `BUG_SILNIKA=1`, `OCR_ZNIEKSZTAŁCONY=2`,
`Guard RED=4` — wszystkie FAIL/blokujące. Zdiagnozowane:

**OCR_ZNIEKSZTAŁCONY (2× PESEL, doc_00009 + doc_00062) — NAPRAWIONE, to fałszywy alarm
benchmarku, ten sam wzorzec co EMAIL wyżej.** Wzorzec kontekstowy PESEL (`StructuralEngine.kt:348`)
celowo wchłania słowo-kotwicę do tokenu ("PESEL: 12345678901" → jeden token) — to poprawne
zachowanie silnika. Ale benchmark porównywał CAŁY token (z "pesel:" w środku, 17 zn.) z gołą
wartością ground truth (11 zn.) — różnica długości 6 zawsze wywalała próg fuzzyMatch (±1 zn.).
Fix: `BenchmarkInstrumentedTest.kt`, dodano `extractNumericRuns(tok.original)` przed fuzzyMatch
dla pól numerycznych — wyodrębnia goły ciąg cyfr z dopasowania zanim porówna. Zweryfikowane
Pythonem na obu przypadkach.

**BUG_SILNIKA (IBAN, doc_00031) — POTWIERDZONY FAŁSZYWY ALARM (test ręczny 06.07), dokładny
mechanizm jeszcze niejasny.** Paweł ręcznie sprawdził na telefonie — IBAN faktycznie jest
zamaskowany. Sprawdzone Pythonem: gdyby token zawierał tylko kotwicę-słowo + czysty numer
("IBAN: PL08...", "Nr konta: PL08..."), już SAM `.contains()` (sprzed tej sesji) powinien to
złapać — więc to NIE jest ten sam mechanizm co PESEL wyżej, przyczyna inna i nieznana. Fix
narzędzia zastosowany: sekcja BUG_SILNIKA w `benchmark_bugs.txt` teraz wypisuje WSZYSTKIE
tokeny wykryte w dokumencie (`r.tokens`), nie tylko fragment znormalizowanego tekstu — powinno
pokazać dokładnie jaką wartość token faktycznie przechwycił. **Do zrobienia:** rerun benchmark,
porównać wypisany token z ground truth żeby znaleźć różnicę (np. zniekształcenie OCR w środku
IBAN łamiące dokładne `.contains()`, mimo że PII i tak zakryte).

**Guard RED hits: 4** — jeszcze nie sprawdzone które konkretnie, zrobić po powyższych.

---

## ŚREDNI — lvl0/lvl1 nie na 100% recall mimo "OCR nie przeszkadza" (06.07)

Paweł zauważył: skoro lvl0 (perfect scan) i lvl1 (light noise) mają symulować czyste OCR,
dlaczego recall nie jest 100%? Sprawdzone (staly 2026-07-06_2201): 3 misy na lvl0, 4 na lvl1 —
WSZYSTKIE to złożone identyfikatory z ukośnikiem (numer_faktury/umowy/kw/działki, np.
"UMW/2024/291"). Diagnoza: token FAKTYCZNIE powstaje (`"nr UMWI2024/291"` w FP-liście) — ukośnik
odczytany jako litera "I" przez OCR, **realne ograniczenie odczytu glifu, nie symulowany szum**
(stąd może się zdarzyć nawet przy lvl0). Ale prefiks kotwicy ("nr "/"Nr") + ta jedna literowa
zamiana razem dają różnicę 2 znaków — ponad próg fuzzyMatch (±1) — więc mimo poprawnego
maskowania nie liczyło się jako trafienie. **Naprawione**: dodano porównanie KOŃCÓWKI tokenu
(przyciętej do długości GT) — kotwica zawsze jest przed wartością, nigdy po, więc to bezpieczne
uniwersalnie. Zweryfikowane Pythonem na obu potwierdzonych przypadkach (UMW, VAT).

**Jeden przypadek nadal niejasny**: `doc_00010` (numer_faktury=FV/2025/12/1828, lvl0) — żaden
token w ogóle nie zawiera nic zbliżonego do tej wartości (sprawdzone: "1828" nie występuje
nigdzie indziej w logu). Log OCR ucinał się za wcześnie (faktura ma długi wstęp
sprzedawca/nabywca, numer faktury pewnie dalej). **Naprawione narzędzie**: sekcje ADRES/NUMER
w `benchmark_bugs.txt` teraz pokazują okno WOKÓŁ faktycznej pozycji encji (szuka pierwszych 4
znaków wartości w tekście), nie sztywny limit od początku dokumentu. **Do zrobienia:** rerun,
sprawdzić czy nowe okno pokaże czy to realny brak wzorca silnika dla numer_faktury w tym
konkretnym formacie, czy kolejny artefakt liczenia.

---

## NISKIE — KWOTA kradnie cyfrę sąsiedniemu słowu (05.07)

Wzorzec KWOTA liczba+waluta (`\d{1,6}...\s*(?:zł|PLN|...)`, StructuralEngine.kt ~527) nie
sprawdza co jest PRZED liczbą. W tekście typu "PIN 1234 PLN 1234" (kilka różnych 3-literowych
skrótów + 1234 zbite w jednej linii bez przecinków) "1234" należące semantycznie do "PIN" zostaje
skradzione przez dopasowanie "1234 PLN" (przeskakuje granicę dwóch osobnych wzmianek) — "PIN"
zostaje osierocone bez liczby, kolejna liczba osierocona po tokenie. Odtworzone zrzutem z
telefonu 05.07 (stress-test wielu skrótów naraz). Świadomie odłożone: wymaga sąsiedztwa kilku
różnych skrótów+liczby bez separatorów w jednej linii — rzadkie w prawdziwych dokumentach.
Ogólny fix trudny (jak odróżnić "PIN 1234 PLN" od legalnego "kwota: 1234 PLN" samym regexem bez
listy słów kontekstowych). Test na przyszłość: "PIN 1234 PLN 1234, PLN 1234" → oczekiwane
3 osobne encje/tokeny, żadna nie osierocona.

---

## NISKIE — resztki po odmianie miast dwuwyrazowych (05.07)

Po pełnej odmianie (Morfeusz2, `generate_city_forms_full.py`) zostały 2 mniejsze, świadomie
odłożone wątki: (1) ~317 nazw dwuwyrazowych bez rozstrzygnięcia rodzaju/przypadku — zostają tylko
w mianowniku, jak dotąd (bez regresu, po prostu nie zyskały odmiany); (2) ~194 nazwy 3+-wyrazowe
("Grabów nad Pilicą", "Brzezie k. Sulechowa") pominięte w tej rundzie — inny, rzadszy wzorzec.
Osobno: "jeleniogórska" (przymiotnik odmiejscowy) to jeszcze inny mechanizm — nie łączyć.

---

## DO ZBADANIA — audyt pokrycia testów (nie teraz, po zamknięciu migracji ADRES)

591 testów jednostkowych, ale nikt nie sprawdzał czy się nie pokrywają (kilka testów sprawdzających to samo z różnych sesji) i czy realnie obejmują całą pipeline (OcrNormalizer → StructuralEngine → NameEngine → AnchorEngine → Guard) czy są dziury. Zrobić po zamknięciu bieżącej migracji, nie w trakcie.

---

## BLOKERY RELEASE

| # | Zadanie | Kto |
|---|---------|-----|
| R4 | Testy kamerą (E2E) — checklist niżej | Paweł |
| R5 | ToS + Privacy Policy — treść prawnicza (zapytanie wysłane 28.06) — podmienić w PrivacyPolicyDialog (MainActivity.kt) | Prawnik |
| GP1 | Keystore — wygenerować klucz podpisujący | Paweł + Claude |
| GP2 | Konto Play Console ($25) | Paweł |
| GP3 | Firma testerów (12-16 kont Google) | Paweł |
| GP4 | Build podpisanego .aab | Claude, po GP1 |
| GP5 | Wgranie .aab + screenshotów + opisu | Paweł, po GP4 |
| GP6 | Closed Testing 14 dni, 12+ testerów | Paweł |

Screenshoty gotowe: `Google_Play/01_HUB.jpg`…`06_ZABEZPIECZENIA.jpg`. Checklist: `Google_Play/RELEASE_CHECKLIST.md`. Keystore nie istnieje.

---

## W TOKU — migracja ADRES → AddressEngine (gałąź `feature/entity-migration`)

**Stan 04-05.07.2026: Faza A + Faza B ZROBIONE i potwierdzone.** AddressEngine.kt (Warstwa 0b)
jest głównym silnikiem ADRES. Duplikaty structural/name (`ADDRESS_PATTERNS` Warstwa 3d+Runda 2,
`applyStreetLookup`) wyłączone pod `USE_ADDRESS_ENGINE_V0`. `AnchorEngine` A.11* zostaje aktywny
(kotwica-fallback, nie duplikat). Commity: `d50b252`, `ae2881e`, `747f32b`. Tag punktu powrotu:
`checkpoint-adres-faza-b-2026-07-04`. Benchmark: ADRES recall 94,5%/96,6% (stały/fresh),
szczegóły w pamięci `project_benchmark_baseline_2026-07-04.md`.

**Faza C — dalsze doszlifowanie (priorytet malejący, portowane z planu Cursora):**
- C-A8: `ul. Jana Pawła II 10/5 20-001 Lublin` — dziś wychodzi jako 2 tokeny (ulica+numer /
  kod+miasto), zaakceptowane jako OK, nie 1 duży token. Do rozważenia tylko jeśli benchmark
  pokaże to jako realny recall-miss.
- C-STREET-FULL-3: `STREET_FULL` w AddressEngine.kt ma `{0,1}` dodatkowego słowa nazwy (max
  2 słowa), `STREET_NO_ZIP` ma już `{0,2}` (max 3 słowa) — niespójność, sprawdzić czy nazwy
  3-członowe z kodem pocztowym (nie tylko bez) tego potrzebują.
- Reszta pozycji z oryginalnego planu (C-NIP-GLUE, C-OCR-GLUE, C-A11c-COMMA) — **prawdopodobnie
  już rozwiązane** przez dzisiejsze fixy (NIP glue 3-2-2-3, STREET_CITY blok 3b, A.11 zasięg) —
  zweryfikować przy najbliższej okazji zamiast zakładać.

**Jedyny otwarty blocker:** NIP `390-051-86-91` w `doc_00009.png` (benchmark fresh) — OCR
zdegradowany z nietypową spacją, nie wygląda na temat ADRES. Patrz pamięć
`project_benchmark_baseline_2026-07-04.md`.

**Opcjonalnie po stabilizacji (C2 z oryginalnego planu):**
- `USE_ADDRESS_ENGINE_V0` → `true` na stałe w release (usunąć flagę)
- Fizyczne usunięcie martwego kodu (nie tylko guard) z StructuralEngine/NameEngine
- Kolory diagnostyczne w `TextPreviewModal.kt` do usunięcia gdy dojście do jednego silnika potwierdzone

---

## OTWARTE BUGI SILNIKA

| Bug | Opis | Priorytet |
|-----|------|-----------|
| BUG-FP-ULICE-IMIENIE | "Jana Pawła II" (patron ulicy, bez "ul."/numeru) → OSOBA zamiast adres. PII i tak zakryte. | niski |
| BUG-ADRES-BRAK-PREFIKS | Ulica bez "ul."/"al." nie maskowana, chyba że w słowniku (STREET_DICT, bez numeru) — sufit OCR dla większości przypadków | niski |
| AUDIT-03 | CATCHALL `\d{8,}` (StructuralEngine.kt:640) sprawdza sumę kontrolną PESEL/NIP dla wszystkich długich ciągów cyfr, nie tylko PESEL/NIP — **potwierdzone nadal w kodzie** | średni |
| RESEARCH-3 (połowa) | `assessQuality` liczy próg <0.7 (OcrNormalizer.kt:916) ale brak `shouldReject` dla confidence<0.5 + odrzucenie w UI — **potwierdzone brak w kodzie** | niski |

---

## OTWARTE ZADANIA UI

- **ImageRedactionScreen.kt nie używa LynxColors/LynxSpacing/LynxShapes** — potwierdzone: 0 wystąpień. Ekran redakcji obrazu wizualnie odstaje od reszty appki (Material3 gołe przyciski zamiast Lynx design system). Zakres: sticky footer z 2 akcjami, karty/chipy zamiast Switch+label, przenieść na wzorzec z `LibraryScreen.kt`/`PseudonymResultPanel.kt`. Agent: Cursor.
- BUG-WARMSTART-CLEAR (niski) — `onNewIntent` brak `LynxPendingShare.clear()` dla `ACTION_MAIN`.

---

## NISKIE / DO WERYFIKACJI PRZY OKAZJI (nie zweryfikowane w tym remanencie, samo przeniesione)

- FP-FRAGMENTY-OCR (NameEngine) — fragmenty słów / złamane linie jako OSOBA
- BUG-02 (OutputGuard) — cicha degradacja gdy LookupTables niezainicjowane, stan niepewny
- BUG-05 (StructuralEngine) — tablice rejestracyjne FP na kodach produktów, stan po ostatnich wersjach nieznany
- BUG-08 (PseudonymEngine) — propagacja nazwisk nie łapie członu po tokenie ("dr OSOBA_003 Lewandowska-Karpowicz")
- BUG-29 (NameEngine) — priorytet flag: niekontekstowe wypychają kontekstowe — może być by design
- BUG-BENCH-PUBLIC — benchmark zapisuje do `getExternalFilesDir` (app-scoped, nie w pełni "publiczny" jak pierwotnie opisano) — tylko narzędzie deweloperskie
- BUG-EXPORT-DEPSEUDO — depseudonimizowany tekst eksportowany bez ostrzeżenia
- N1 BUG-PESEL-OCR-SILNIK — wymaga konkretnego skanu do diagnozy (doc_00006)

---

## KAMPANIA TESTÓW KAMERĄ (checklist Paweł, R4, ~15 min)

1. **Zdjęcie dokumentu** → share do LynxMask → twarz zblurowana domyślnie → zapis do biblioteki → podgląd
2. **Ręczny prostokąt** → zaznacz podpis → blur → usuń blur → świadome odkrycie → share JPEG
3. **OCR z aparatu** → tekst z PESEL/NIP → Review → pseudonimizuj → brak gołego numeru w podglądzie → depseudo przywraca
4. **Realny dokument** (nie syntetyczny) — faktura, umowa — jedno zdjęcie telefonem → krytyczne PII zamaskowane
5. **Schowek** → skopiuj tekst z danymi → kafelek LynxMask → ostrzeżenie → zapis
6. **Odzyskiwanie hasła** → zapomniałem hasła → klucz odzyskiwania → nowe hasło → biblioteka OK

Zablokuj release do momentu: pkt 1-4 bez regresji na Samsung SM-A536B (Android 16).

---

## BOMBKI — odrzucone świadomie

| Pomysł | Dlaczego nie |
|--------|-------------|
| Folder scan na mobile | Na mobile nikt nie trzyma folderów z dokumentami. Wartość = desktop CLI (już planowane) |
| Regeneracja klucza odzysk. (v2) | Wymaga starego hasła + klucza. Mało użytkowników tego dotknie. v2. |
| BUG-LOG-OCR / BUG-BENCH-PUBLIC jako blokery | Tylko narzędzia deweloperskie, nie dotykają produkcji |
| Folder CLI na mobile | j.w. — to zadanie desktopowe |

---

## Potwierdzone zamknięte w remanencie 04.07.2026 (dla śladu — czemu zniknęły z BACKLOG/TODO_silnik/MASTER/CURSOR_BRIEF)

- P1 AUTH klucz odzyskiwania — `RecoveryKeyManager.kt` istnieje, w pełni podpięty w `LoginScreen.kt` (isKeySet/generateKey/saveKey/verify/clearKey)
- AUD-M06 security-crypto → `1.0.0` stable (build.gradle.kts:89)
- Testy 6.2 SessionStore/Deanonymizer — pliki testów istnieją
- BUG-04 UserDictionary `_loaded` — kod już nie ustawia `_loaded=true` przy błędzie ładowania
- BUG-LOG-OCR — DebugLogBuffer już nie loguje treści OCR, tylko długość/liczbę linii
- BUG-LIB-6 — CreateDocument/MediaStore potwierdzone w kodzie
- CURSOR_BRIEF nip_glued + postal_regres (3 pliki brief usunięte, zawartość zweryfikowana) — fixy potwierdzone w kodzie (niescommitowane, patrz sekcja migracji wyżej)
- BUG-07 generateFeminineVariants — mapowanie końcówek już rozszerzone poza -ski/-cki/-dzki
- UI nawigacja (epik "UL") — zamknięty 29.06, potwierdzone przez właściciela
- Guard UI znikający przycisk "Nie maskuj" — naprawiony 01.07 (Modifier.weight)
- BUG-STALY-NUMER — zamknięty i zmergowany do master (b6f8a00)

---

## ŚRODOWISKO — wymagane przed gradlew w CMD

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
```

Przed benchmarkiem po zmianie kodu — wymagany reinstall:
```
.\gradlew :app:installDebug :app:installDebugAndroidTest
```
`run_benchmark.bat` odpala Paweł, nie Claude.
