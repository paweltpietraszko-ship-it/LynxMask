# TODO — LynxMask Mobile
# JEDYNY plik z otwartymi bugami i zadaniami. Zamknięte pozycje usuwać stąd od razu, nie przekreślać.
# Historia/decyzje → git log. Nie tworzyć osobnych briefów/PLAN/CURSOR_* w katalogu głównym —
# treść idzie tutaj (zadania) albo do memory Claude (kontekst/diagnoza/decyzje).
# Ostatni remanent: 04.07.2026 — połączono TODO.md+BACKLOG.md+TODO_silnik.md+3×CURSOR_BRIEF.
# Remanent 05.07.2026 — usunięto PLAN_AddressEngine_Claude/CURSOR_AUDYT_ADRES/CURSOR_BRIEF_AddressEngine
# (Faza A+B ADRES zrobione, backlog Fazy C przeniesiony niżej, reszta w pamięci Claude).

---

## PRIORYTET NASTĘPNEJ SESJI (09.07 wieczór) — Document Rebuilder: alignment raw↔normalized

Gałąź `feature/document-export`. Pełny stan w memory Claude:
`project_document_rebuilder_state.md` (**zacznij tu**), chronologia w
`project_session_wrapup_2026-07-09.md`.

Skrót: DOCX round-trip zbudowany, 3 luki PII zamknięte, 2 bugi silnika naprawione
(AnchorEngine A.8 SYGNATURA na prozie, sklejanie zdań w pre-processingu emaili) — ale
eksport DOCX odmawia zapisu (`MissingTokens`) dla KAŻDEGO tekstu, gdzie `OcrNormalizer`/
pre-processing poprawi choć jeden znak (potwierdzone na 45-znakowym teście: "Be4ta
Kamińska B3ata Woźniak 48 60l 234 567" → 0/4 encji zapisanych). Powód: poprawki dzieją
się WEWNĄTRZ `pseudonymize()`, `DocxWriter` szuka w surowym tekście sprzed poprawek.

**Plan (b) od Cursora, gotowy do implementacji (2-4 dni):**
1. `PseudonymEngine.pseudonymize()` — zapisać `maskingBaseText` (tekst po normalizacji,
   przed podmianą na tokeny).
2. Nowa funkcja alignmentu (diff surowy↔`maskingBaseText`) w `document/`.
3. `PseudonymResult.tokenRawRanges` (opcjonalne, tylko ścieżka DOCX).
4. `DocxWriter.locateTokenRanges` — użyć zakresów gdy dostępne, fallback `indexOf` gdy nie.

Test docelowy: dokument "Be4ta"/"60l" → `Success` zamiast `MissingTokens`.

Odrzucone warianty: (a) pełna mapa pozycji przez każdy krok normalizacji (2-4 tygodnie,
za drogie), (c) sama bramka "wyłącz normalizację dla DOCX" (niewystarczająca — silnik by
w ogóle nie wykrył degradacji jak "Be4ta"/"60l").

---

## PRIORYTET STRATEGICZNY (09.07, decyzja Pawła) — napraw "chory termometr" PRZED wersją angielską

Paweł: dopóki benchmark testuje polskie dokumenty, może sam złapać kłamstwo benchmarku (czyta
wynik, sprawdza na telefonie). Przy wersji angielskiej straci tę możliwość — będzie musiał
wklejać mi wszystko do sprawdzenia, co jest "mega obciążające". Więc: fundament (poprawność
samego narzędzia pomiarowego) trzeba naprawić TERAZ, kiedy jeszcze można to zweryfikować jego
oczami, nie później.

Zamknięte 09.07 w ramach tego priorytetu: **BUG-FP-LABEL-TYPE-ZLEPIONY** — `benchmark_bugs.txt`
grupował FP tylko po `layer/rule` (np. "NAME_ENGINE/CONTEXTUAL"), a ten sam rule-label jest
współdzielony przez różne typy tokenu (OSOBA/FIRMA/ADRES w `applyContextualBlacklist`,
NameEngine.kt). Efekt: "GAMMA Sp. z o.o." i "Warszawie" wyglądały jak firma/miasto błędnie
zamaskowane jako OSOBA — a naprawdę dostały poprawne, osobne tokeny (TOKEN_FIRMA, TOKEN_ADRES),
tylko report tego nie pokazywał. Potwierdzone ręcznie na telefonie (100% poprawne) PRZED
poprawką reportu (patrz `feedback_real_test_methodology.md`/`feedback_validate_the_benchmark_tool_itself.md`
w pamięci Claude — 7. instancja "chorego termometru"). Fix: `fpByLayer` w
`BenchmarkInstrumentedTest.kt` teraz grupuje po `layer/rule/typ_tokenu`, więc rozróżnienie
widać wprost w raporcie bez czytania kodu silnika.

**ZAMKNIĘTE 09.07 (commit `d923a83`) — audyt Cursora, Faza 1+2:** płaski licznik FP mieszał
zamierzone overmasking (KWOTA/NUMER/adres-fragment ponad GT) z realnym problemem czytelności.
`classifyExtraToken()` w `BenchmarkInstrumentedTest.kt` dzieli każdy token spoza GT na
EXTRA_MASK_OK / EXTRA_MASK_POLICY / UX_FP / REVIEW — realny KPI czytelności to tylko UX_FP.
PRECISION/F1 przeniesione do sekcji informacyjnej z etykietą "metryka GT-gap, nie jakość
silnika". Zweryfikowane Pythonem, build+install OK. Usunięte też `benchmark_mobile.py` (v1)
i `run.bat` — martwe artefakty.

**Świadomie odłożone (Faza 3+4 z planu Cursora, "nie teraz"):**
- Faza 3 — GT opcjonalnie bogatsze (`acceptable_extra_types`/`benchmark_policy` per dokument) —
  robić stopniowo, nie blocker.
- Faza 4 — jedna wspólna implementacja comparatora (Kotlin+Python współdzielą regułę) —
  dopiero po ustabilizowaniu reguł z Fazy 2.

**Audyt innych warstw ZAMKNIĘTY 09.07 (bez zmian kodu — fix już wystarczał):** przejrzane
wszystkie wywołania `assignToken` w całym silniku (grep, wszystkie pliki). STRUCTURAL/
STRUCTURAL_R2/ANCHOR mają `rule = tokenType` (rule JEST typem, zero ambiguacji z konstrukcji).
ADDRESS_ENGINE zawsze `TOKEN_ADRES` (jeden typ, rule różnicuje tylko wzorzec). Jedyne dwa
miejsca ze zlepionym rule="CONTEXTUAL" (wiele typów pod jedną etykietą): NAME_ENGINE (już
naprawione 09.07) i **NAME_ENGINE_R2** (Runda 2, ten sam mechanizm — `applyContextualBlacklist`
wołane drugi raz). Fix z dziś (`${tok.type}` dopisany do etykiety FP w
`BenchmarkInstrumentedTest.kt`) jest UNIWERSALNY — działa niezależnie od tego, czy `rule` jest
ambiwalentny czy nie, więc NAME_ENGINE_R2 jest już poprawnie rozróżniane bez dodatkowej zmiany.
DICT/USER_DICTIONARY (słownik użytkownika, dowolny typ) też był potencjalnie ambiwalentny —
też już pokryte tym samym fixem. Wniosek: "chory termometr" (etykiety FP) zamknięty całościowo,
nie tylko punktowo dla NAME_ENGINE.

---

## PRIORYTET NASTĘPNEJ SESJI — przywrócić słownik nazwisk STOPNIOWO, z benchmarkiem po każdym kroku

Kontekst pełny w memory Claude: `project_session_wrapup_2026-07-08_dictionary_revert.md`.
Skrót: 08.07 zbudowany 3-warstwowy system nazwisk (słownik 1000→39k) — benchmark ujawnił
FP NameEngine 37→152 (4×), precyzja -15pp. Cofnięte (`git reset --hard 29c8ba9` + cherry-pick
4 niezależnych fixów: NIP/telefon-konto/adres). Tag `checkpoint-przed-revertem-slownika-2026-07-08`
to punkt odzysku ze STAREGO (39k) stanu, gdyby trzeba było coś stamtąd wziąć.

**Plan na jutro (małe kroki, nie big-bang):**
1. Wyższy próg wystąpień w rejestrze PESEL (np. 500, nie 100) — mniej nazwisk, mniej kolizji.
2. Denylist pospolitych słów PRZED merge do JSON (Morfologik offline, raz, w generatorze) —
   nie łatać post-hoc w NameEngine w runtime jak dziś (za wolne, złapało 2 z ~117 nowych FP).
3. BEZ warstwy rdzeń+sufiks na start — dodać osobno, po ustabilizowaniu słownika.
4. Fix lemma-lowercase z generatora (już naprawiony, dobry) — zastosować od razu.
5. Benchmark PO KAŻDYM kroku, nie na końcu.

## ZAMKNIĘTE 08.07 — `\b` diakrytyki + cross-newline + KWOTA bez waluty (commit 1b0d7b4)

Priorytet z 07.07 wieczór (`\b` nie rozpoznaje polskich liter diakrytycznych) w pełni
zamknięty, plus odkryty i naprawiony osobny, większy bug tego samego dnia (cross-newline
w KWOTA). Pełna lista w treści commita `1b0d7b4` — skrót:
- `WORD_START_UNICODE`/`WORD_END_UNICODE` (StructuralEngine.kt) — wiodący/końcowy `\b`
  ASCII-only zamieniony we wszystkich potwierdzonych miejscach (KWOTA, NameEngine,
  AddressEngine, ID_CARD).
- BUG-KWOTA-CROSS-NEWLINE + audyt ogólny (33 miejsca, 4 pliki): keyword + goły `\s` +
  wartość kradło kotwicę z sąsiedniej, niepowiązanej linii — potwierdzone traceMode.
- A.9c/A.9d (AnchorEngine): KWOTA bez waluty/keywordu — sam kształt (przecinek+2 cyfry,
  albo cyfra+mnożnik słowny tysiąc/milion/miliard) wystarczy, decyzja Pawła.
- OCR_ZERO_RUN_IN_AMOUNT (OcrNormalizer): run 2+ liter O w kwocie → zera, u źródła.
- Ogólny cleanup ogonów bez spacji po tokenie w AnchorEngine (token+"/"+tekst → token).
- Guard `MIASTO_NIEZAMASKOWANE` (OutputGuard) — gołe miasto bez kodu = YELLOW, nie
  auto-mask (decyzja produktowa Pawła).
- Nowa zasada robocza: drobne bugi znalezione przy okazji naprawiać od razu, bez pytania
  (patrz `feedback_fix_small_bugs_without_asking.md` w pamięci Claude).

**EMAIL — wątek NADAL NIEDOKOŃCZONY, nietknięty 08.07:** `p1otr.wisn1ewski @ kance1aria .pl`
(spacje wokół @ ORAZ spacja przed .pl jednocześnie, z testu `test_anchor_full.txt` linia 10)
wyszło niezamaskowane na telefonie, ale wzorzec AnchorEngine A.2 przetestowany w izolacji
(Javą) DOPASOWUJE się poprawnie do tego dokładnego tekstu. Mechanizm awarii nieznany —
prawdopodobnie inna warstwa wcześniej w potoku koliduje. Nie diagnozowane do końca — do
zrobienia z pełnym `test_anchor_full.txt` jako punktem startu. Skoro dziś naprawiono
analogiczny "coś kradnie kotwicę przed AnchorEngine" bug dla KWOTA (cross-newline) — warto
sprawdzić czy EMAIL nie cierpi na TEN SAM defekt (audyt `\s` objął tylko keyword-patterns,
nie sam wzorzec email A.2 — do zweryfikowania).

---

## ZAMKNIĘTE 07.07 — benchmark: fuzzyMatch ślepy na DWIE niezależne degradacje OCR naraz

Email zgłoszony jako `BRAK_W_OCR` w benchmarku stałym — test ręczny na telefonie potwierdził
że silnik maskuje go POPRAWNIE w całości. Przyczyna: `kamil.wozniak@wp.pl` (19 zn., GT) vs
`kamil.woziak@wppl` (17 zn., realna wartość po dwóch NIEZALEŻNYCH degradacjach OCR: zgubione
"n" + zgubiona kropka) — różnica długości 2, ponad stary sztywny próg `fuzzyMatch` (≤1 znak).
Fix: prawdziwa odległość Levenshteina (`BenchmarkInstrumentedTest.kt`) + próg skalowany
długością wartości (1 dla <15 zn., 2 dla 15-23, 3 dla 24+) — długie pola (email/IBAN)
statystycznie częściej mają 2+ niezależne literówki OCR naraz niż krótkie (PESEL). Zweryfikowane
Pythonem: nie osłabia rozróżnialności (dwie różne wartości podobnej długości nadal odległość
9-14, daleko ponad podniesiony próg). Szósta instancja "chorego termometru" — pełny opis w
pamięci `feedback_validate_the_benchmark_tool_itself.md`.

---

## ZAMKNIĘTE 07.07 — IBAN zagraniczny (DE/FR) zostawiał resztę cyfr jawną

Wzorzec kontekstowy "IBAN:" (StructuralEngine:482) zakładał sztywne grupy po 4 cyfry —
działa dla PL (26 cyfr po kodzie kraju, dzieli się równo), zostawiał resztę jawną dla krajów
gdzie się nie dzieli (DE: 20 cyfr → "00" jawne; FR: podobnie). Fix: opcjonalna końcowa grupa
1-3 cyfr. Test dodany (`IBAN niemiecki i francuski bez reszty jawnej`). Znalezione przy
teście `test_iban_20_warianty_07_07.txt` (20 wariantów zapisu IBAN, Paweł 07.07).

**Odłożone jako mało realistyczne:** wariant z KAŻDĄ cyfrą osobno oddzieloną spacją
("Nr rachunku: PL 6 1 1 0 9 0 1..." — celowa skrajna prowokacja w teście) nie maskuje się
w całości, zostaje ogon jawny. Ekstremalna degradacja OCR (realny skan tak nie wygląda) —
nie naprawiane teraz, do rewizji tylko jeśli pojawi się realny przykład z benchmarku.

---

## NISKIE — PseudonymEngineTest.kt to plik molochów (~2000 linii), rozbić na osobną sesję (07.07)

Przekracza próg z CLAUDE.md ("powyżej ~600 linii zaproponuj wydzielenie") kilkukrotnie —
Paweł świadomie odłożył rozbicie na inną sesję zamiast robić to przy okazji dzisiejszej pracy.

**Propozycja podziału wg encji (osobna odpowiedzialność):**
- `NumerFakturaTest.kt` — testy faktury/FV/VAT z sesji 07.07 (~15 testów)
- `NumerDzialkaTest.kt` — testy działki z sesji 07.07
- Reszta zostaje w `PseudonymEngineTest.kt` (ogólny pipeline: PESEL/NIP/sygnatura/email/umowa/KW/stary bagaż)

Nie robić przy okazji innego zadania — świadoma, osobna sesja poświęcona tylko temu.

---

## ZAMKNIĘTE 07.07 — NUMER_FAKTURY / architektura Anchor vs Structural

Cała diagnoza + eksperyment (wyłączenie StructuralEngine 445, konsolidacja w AnchorEngine
A.12/A.12b/A.12c: FV/VAT/Nr jako kotwice, zero walidacji kształtu) — zapisane w pamięci
`feedback_anchor_vs_structural_faktura_experiment.md`. Testy zielone, potwierdzone telefonem
(20 wariantów czystych + degradacja OCR, `testy/test_faktura_20_warianty_07_07.txt`).

**Rozszerzone 07.07 (benchmark) na numer_dzialki — ten sam wzorzec, druga migracja.**
Benchmark ujawnił 2 warianty: "Nr dzialki" (ł→l) i "Numer dziatki" (pełne słowo + ł→t) —
kolejna enumeracja degradacji/synonimów w StructuralEngine 613. Zmigrowane do AnchorEngine
A.12: "Nr"/"Numer" + do 2 słów pośrednich + sygnał "dzia.k" (wildcard na "ł", ta sama technika
co pe[s5][e3][lL1]/N[IL1]P) jako self-signal wewnątrz dopasowania. StructuralEngine 613
wyłączone (skomentowane). Uwaga: tolerancja słów pośrednich TYLKO dla Nr/Numer, nie dla VAT
(inaczej "Kwota VAT wynosi..." wraca jako fałszywy alarm — zweryfikowane Pythonem, regresja
złapana przed commitem). Testy dodane: degradacja ł→t, regresja "Nr strony"/"punkt nr 5.2".

---

## ZAMKNIĘTE 07.07 — benchmark_bugs.txt nie pokazywał kontekstu dla OCR_ZNIEKSZTAŁCONY/Guard RED

Paweł: "na tym etapie benchmark musi pokazywać frazę z bugiem" — zamiast zmuszać do ręcznego
`adb pull benchmark_trace.txt` przy każdej diagnozie. Naprawione w
`BenchmarkInstrumentedTest.kt`:
- `[OCR_ZNIEKSZTAŁCONY]` — dodano fragment OCR + listę tokenów dokumentu (ten sam format co
  `[BUG_SILNIKA]`, wcześniej pokazywał tylko etykietę i wartość GT bez żadnego kontekstu).
- `[GUARD RED]` — nowa sekcja, wcześniej istniała tylko sumaryczna LICZBA w report.txt, zero
  szczegółu który dokument/jaki fragment. Wątek `guardRedDetails: List<String>` (label+
  dopasowany tekst) przeciągnięty przez `analyze()`/`DocResult` do zapisu w bugs.txt.

2× PESEL `OCR_ZNIEKSZTAŁCONY` (doc_00025, doc_00355) zdiagnozowane i zamknięte dzięki temu
kontekstowi — patrz wpis "ZAMKNIĘTE 07.07 — ostatnie 2× PESEL OCR_ZNIEKSZTAŁCONY" niżej.
1× Guard RED (IBAN) — Paweł 09.07: to Guard robi co ma robić (dokument lvl3 tak zdegradowany,
że słusznie łapie RED zamiast fałszywie maskować) — nie traktować automatycznie jako bug do
naprawy w silniku. Jeśli powtórzy się jako wzorzec (nie pojedynczy dokument) — wtedy dopiero
diagnoza.

---

## CZĘŚCIOWO ODPOWIEDZIANE 07.07 — hipoteza Pawła: AnchorEngine ma fundamentalny błąd (06.07)

Diagnoza Cursor (`traceMode`, benchmark 500 dok.) na dwóch konkretnych PESEL/NIP miss z lvl0/
lvl3: **NIE jeden wspólny mechanizm** (to nie "wcześniejszy wzorzec kradnie prefiks + TOKEN_RE
blokuje" jak przy numer_faktury) — PESEL i NIP padały z RÓŻNYCH przyczyn:
- PESEL: `[\s\-]?` w `peselShapeRe` obejmowało `\n`, więc regex "mostkował" z ogona
  wcześniejszego tokenu (np. "001" z "NUMER_001") w prawdziwy PESEL na następnej linii —
  zanieczyszczone dopasowanie nie przechodziło sumy kontrolnej, prawdziwy PESEL nigdy nie
  dostawał osobnej szansy (findAll już skonsumował ten zakres). Naprawione: `[\s\-]?` → `[ \t\-]?`.
- NIP: luka w Anchor A.5 (`[^0-9OolIiSsBbZz\n]{0,15}`) wykluczała `\n` (keyword+wartość na
  osobnych liniach) ORAZ litery D-class (o,l,i,s) które są zwykłymi literami w polskich
  słowach etykiety ("jeśli", "dotyczy") — luka nie mogła nawet dopasować typowej frazy.
  Naprawione: wykluczać tylko prawdziwe cyfry, dopuścić `\n`, limit 15→25.

**Werdykt Cursora na hipotezę**: ⚠️ częściowo potwierdzona — nie jeden wspólny bug, ale
systemowa NIESPÓJNOŚĆ tolerancji OCR (`[\s\-]?` vs `[ \t\-]?`) i layoutu (`\n` w lukach)
między warstwami/regułami. Interakcja tokenów-placeholderów z późniejszymi regexami
(most token→wartość) to osobna, nienazwana dotąd klasa bugów — do obserwacji czy się powtórzy.
Oba fixy zweryfikowane Pythonem + testy w `PseudonymEngineTest.kt`. Czeka na benchmark 500
jeszcze raz żeby potwierdzić że oba BUG_SILNIKA znikły.

---

## ZAMKNIĘTE 07.07 — ostatnie 2× PESEL OCR_ZNIEKSZTAŁCONY (doc_00025, doc_00355)

Trzecia, ostatnia przyczyna w tej samej rodzinie bugów co wpis wyżej. Dzięki nowemu kontekstowi
diagnostycznemu w `benchmark_bugs.txt` (fragment OCR + tokeny z urządzenia) zdiagnozowane
precyzyjnie: prawdziwy OCR na zaszumionym obrazie pomylił pojedynczą cyfrę z literą spoza
D-klasy — "4"→"A" (doc_00025), "7"→"r" (doc_00355). Nie da się tego enumerować literą po
literze (kolejny dokument = kolejna litera).

Pierwsza próba fixu poszła do AnchorEngine.kt (A.4/A.5) — okazała się martwym kodem: kontekstowy
wzorzec PESEL w Rundzie 1 (`StructuralEngine.kt`, wzorzec "PESEL z kontekstem") biegnie PIERWSZY
i dla doc_00025 dopasowywał się CZĘŚCIOWO (elastyczna klasa środkowa `\d[\d \t]{3,16}\d` potrafi
urwać się tuż przed obcą literą i mimo to zwrócić poprawny match) — konsumował słowo-kotwicę
"PESEL", zostawiając ogon ("A0") jawny, bez szansy dla AnchorEngine na dokończenie (kotwica już
zjedzona). Prawdziwy fix: `CTX_STRAY` w `StructuralEngine.kt` — toleruje jedną obcą literę w
środku ciągu cyfr, TYLKO gdy zaraz po niej jest znowu prawdziwa cyfra (lookahead) — nie cofa
BUG-PESEL-SKLEJENIE-FIX (01.07). Zweryfikowane Pythonem, testy w `PseudonymEngineTest.kt`,
potwierdzone ręcznym testem na telefonie (`testy/test_pesel_obca_litera_07_07.txt`) — Paweł:
"bardzo dobrze zamaskowany".

**Lekcja dla kolejnych instancji**: przy diagnozie AnchorEngine-poziomu bugów sprawdzić NAJPIERW
czy Runda 1 (StructuralEngine) w ogóle dopuszcza tekst do AnchorEngine dla danego przypadku —
częściowe dopasowanie w Rundzie 1 blokuje AnchorEngine skuteczniej niż brak dopasowania.

---

## ZAMKNIĘTE 07.07 — AddressEngine POSTAL_K1 wchłaniał dowolne następne słowo (np. "Tel")

Znalezione ręcznym testem na telefonie (`test_pesel_obca_litera_07_07.txt`, zdanie z "PESEL: ...
Adres: ... 44-100 Łódź Tel: +48 ..."): opcjonalne drugie słowo po kodzie pocztowym (dodane
04.07 dla miast dwuwyrazowych, BUG-MIASTO-DWUCZŁONOWE-FIX) nie miało żadnej walidacji — token
ADRES wchłonął "Tel", zjadając kotwicę telefonu. Fix (`AddressEngine.kt`, blok POSTAL_K1): drugie
słowo wchodzi do tokenu tylko gdy (a) cała fraza jest w `cityForms`, LUB (b) drugie słowo jest w
`citySurnameOverlap` (góra/górka/górny/róg/kępa — ten sam słownik z naprawy "Góra jako OSOBA").
Pierwsza wersja fixu (tylko warunek a) złamała 2 istniejące testy w `AddressEngineTest.kt`
("Zielona Góra" bez pełnej kombinacji w testowym `cityForms`) — poprawione dodaniem warunku (b),
zweryfikowane Pythonem, wszystkie testy zielone.

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

## NISKIE — imię "Zdzisław" brak w słowniku 1460 imion (07.07)

Odkryte przy budowie testu sklejania (`test_sklejanie_encji_07_07.txt`): "Zdzisław" faktycznie
nie ma w produkcyjnym `assets/names_inflected.json` (potwierdzone grepem). Nieznana skala (ile
innych rzadszych imion brakuje) — do sprawdzenia jeśli kiedyś wróci temat pokrycia słownika.
Test zamieniony na "Zbigniew" (jest w słowniku), nie blokuje niczego.

**Ważniejsze odkrycie przy tej samej okazji:** zamiana na "Zbigniew" SAMA W SOBIE nie
wystarczyła — `LookupTables.initializeForTesting()` (wołane w `@Before` klasy
`PseudonymEngineTest`) wstrzykuje celowo MINIMALNY, zaszyty na sztywno słownik testowy (tylko
Jan/Anna/Piotr/Maria/Adam/Katarzyna + Kowalski/Nowak/Malinowski/Wiśniewski/Szymański) —
kompletnie inny niż pełny produkcyjny (1460 imion/1000 nazwisk), którego telefon używa. Stąd
rozjazd: test ręczny na telefonie widział "Zbigniew Baranowski" poprawnie, JVM unit test nie
widział ŻADNEGO nazwiska spoza tej piątki, niezależnie które wybiorę. Fix: test wywołuje
`LookupTables.resetForTesting()` + `LookupTables.initializeFromClasspath()` (ładuje pełny
słownik z `src/test/resources/`, identyczny co produkcyjny asset) na początku, `@After`
klasy i tak sprząta przez `resetForTesting()`. **Zapamiętać na przyszłość:** każdy test
używający NIEPOWTÓRZONYCH (świeżych) imion/nazwisk musi jawnie wołać `initializeFromClasspath()`
zamiast polegać na domyślnym `initializeForTesting()` z `@Before`.

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

## ZAMKNIĘTE 07.07 — migracja ADRES → AddressEngine (jedyny silnik)

**Faza A + Faza B (04-05.07) + konsolidacja finalna (07.07, commit `5fcb3c0`).** AddressEngine.kt
jest teraz JEDYNYM silnikiem ADRES, w każdym buildzie (nie tylko debug). Usunięte fizycznie:
`USE_ADDRESS_ENGINE_V0` (był `BuildConfig.DEBUG` — w release wyłączałby AddressEngine, zostawiając
stary kod jako jedyny mechanizm produkcyjny), `StructuralEngine.applyPostalCityPatterns`,
`StructuralEngine.ADDRESS_PATTERNS` (Runda 1+2), `NameEngine.applyStreetLookup`. `AnchorEngine`
A.11/A.11c/d/e zostaje aktywny (kotwica-fallback na resztkach, nie duplikat — potwierdzone
audytem Cursora `CURSOR_AUDYT_ROZPROSZENIE_ENCJI_2026-07-07.md`). Kompilacja zweryfikowana,
czeka na testy jednostkowe + benchmark Pawła po tej zmianie.

Commity: `d50b252`, `ae2881e`, `747f32b`, `5fcb3c0`. Tag punktu powrotu:
`checkpoint-adres-faza-b-2026-07-04`. Merge do master: `4fb3689` (checkpoint przed tym krokiem).

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

**Zrobione 07.07 (C2 z oryginalnego planu):**
- ✅ `USE_ADDRESS_ENGINE_V0` usunięty, AddressEngine na stałe jedynym silnikiem
- ✅ Fizyczne usunięcie martwego kodu ze StructuralEngine/NameEngine

**Wciąż otwarte:**
- Kolory diagnostyczne w `TextPreviewModal.kt` (zielony=AddressEngine/niebieski=stary kod) — do
  usunięcia, rozróżnienie już nie ma sensu skoro stary kod nie istnieje
- AnchorEngine A.11 (prefiks ulicy) — czy da się bezpiecznie usunąć? Nie jest pod flagą, biegnie
  zawsze; wymaga osobnej weryfikacji benchmarkiem czy realnie coś jeszcze łapie (patrz audyt
  Cursora, pytanie 1)

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
