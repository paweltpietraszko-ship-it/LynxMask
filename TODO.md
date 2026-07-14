# TODO — LynxMask Mobile
# JEDYNY plik z otwartymi bugami i zadaniami. Zamknięte pozycje usuwać stąd od razu, nie przekreślać.
# Historia/decyzje → git log. Nie tworzyć osobnych briefów/PLAN/CURSOR_* w katalogu głównym —
# treść idzie tutaj (zadania) albo do memory Claude (kontekst/diagnoza/decyzje).
# Ostatni remanent: 04.07.2026 — połączono TODO.md+BACKLOG.md+TODO_silnik.md+3×CURSOR_BRIEF.
# Remanent 05.07.2026 — usunięto PLAN_AddressEngine_Claude/CURSOR_AUDYT_ADRES/CURSOR_BRIEF_AddressEngine
# (Faza A+B ADRES zrobione, backlog Fazy C przeniesiony niżej, reszta w pamięci Claude).

---

## PRIORYTET NASTĘPNEJ SESJI (14.07 wieczór) — wynik benchmarków po dzisiejszym sprzątaniu

**Benchmarki puszczone po całym dniu pracy (14.07): zero potwierdzonego regresu.**
- `stress` (Cursor, 300 dok. stały zestaw): recall 98,8% identyczny co do encji vs 12.07,
  precyzja płaska (73,7 vs 73,8%), te same top FP co przed zmianami.
- `clean` (300 dok., pula ~25 nazwisk — ograniczona różnorodność): recall 99,4% identyczny
  co do encji vs 12.07, precyzja lekko w dół (68,6 vs 69,3%) ale te same top FP co wcześniej
  (Biała/Paweł/Poznaniu) — nie nowy szum.
- `stały`/`fresh`/`v2` (telefon, ground_truth_lvl03.json): 96,5–97,2% recall, powyżej progu
  release (≥90%). `UX_FP=0` (prawdziwy problem czytelności) w obu przebiegach `stały`.

**BUG-CHORY-TERMOMETR (nowy, 14.07) — benchmark `stały` fałszywie oznacza doc_00026 jako
blocker release.** `dowod_osobisty=LKW771657` oznaczone jako "OCR_ZNIEKSZTAŁCONY — bug
silnika" — Paweł sprawdził na telefonie: **jest zamaskowane poprawnie, to fałszywy alarm
narzędzia**. Powtarzalne (dwa identyczne przebiegi `stały`, ta sama liczba). Podejrzenie:
pomieszane pola w WYGENEROWANYM dokumencie testowym (numer dowodu i PESEL zamienione w
treści) — do zweryfikowania w `generator.py`, nie w silniku. Niepilne, nie blokuje niczego
realnego (telefon rozstrzyga).

**DO ZROBIENIA — ścieżka kontekstowa Guard nie w pełni korzysta z dzisiejszej poprawki
Zając/Wróbel/Dudek/Biała.** W `benchmark_results/staly/2026-07-14_1521/benchmark_bugs.txt`,
sekcja `NAME_ENGINE/CONTEXTUAL/OSOBA` — "Zajac", "Dudek", "Biała" nadal pojawiają się jako
`[REVIEW]` (YELLOW, nie cichy wyciek, ale nie auto-maskowanie) w TEJ konkretnej ścieżce
(dwuwyrazowy kontekstowy check w OutputGuard.kt, `OSOBA_NIEZAMASKOWANE` — różny od
`NAZWISKO_NIEZAMASKOWANE`/`NAZWISKO_RZADKIE_NIEZAMASKOWANE` naprawionych dziś). Sprawdzić
czy ten sam mechanizm (`NAMES_GUARD_CITY_SKIP`/`cityForms` early-return) blokuje też tu,
analogicznie do naprawy z dzisiejszej sesji.

**DO ZROBIENIA — 39/77 pozycji REVIEW w `stały` to adresy (ulica bez kodu + kod osobno).**
Wygląda na graniczne dopasowanie tokenów względem ground truth, nie potwierdzony wyciek —
wymaga osobnego, dokładniejszego przeglądu, nie zrobione dziś z braku czasu.

**Stan repo: WSZYSTKO nadal niescommitowane** (patrz sekcje niżej — cały dzień pracy 14.07
+ dziedzictwo sprzed tej sesji). Cel dnia (przenieść na master) nieosiągnięty z braku
czasu, nie z powodu problemów jakościowych — benchmarki i testy czyste. Zacząć następną
sesję od: (1) commit w spójnych krokach, (2) merge do master.

---

## ZAMKNIĘTE 14.07 — plan A/B/C z sesji 12.07 (słownik + strażnik OSOBA skonsolidowany)

Ciąg dalszy sesji 12.07, gdzie znaleziono że fix OCR_ONE_AS_L był łatką dopasowaną do
jednego przykładu (kontrprzykład VIN) zamiast reguły ogólnej. Uzgodniony plan A/B/C wykonany:

- **A (słownik zawężony do top-1000, próg ≥3296)** — zrobione w międzysesyjnym stanie
  roboczym: `surnames_top1000.json` zawężony, `surnames_extended.json` (39k) dodany
  OSOBNO tylko dla `OutputGuard` (YELLOW, nie auto-maskowanie) — `LookupTables.
  surnamesFormsExtended`. Był untracked w gicie do dziś (14.07) — dodany.
- **B (zamrożenie nowych wzorców OSOBA)** — dotrzymane, 14.07 to wyłącznie domykanie
  dziur w ISTNIEJĄCYCH regułach, zero nowych wzorców.
- **C (jeden strażnik `isCommonWordNotSurname`)** — DOKOŃCZONE 14.07. Funkcja była w 4
  z 8 miejsc maskujących OSOBA (samo nazwisko, ALL-CAPS, inicjał+nazwisko, tytuł+słowo).
  Brakowało jej w: parach "Imię Nazwisko"/"Nazwisko Imię"/"Honorifik+Imię+Nazwisko"
  (NameEngine.kt) i w AnchorEngine A.10 (tytuł→OSOBA, miał ZERO strażnika w ogóle).
  Dopisane wszystkie 4 brakujące miejsca + testy regresyjne (`engine_golden_imiona_
  nazwiska.txt`: "Jan Osoba"/"Zapłata Marek"/"Pan Jan Osoba"/"dr Ważne").
- **ZAMKNIĘTE 14.07 wieczór — DECYZJA WŁAŚCICIELA: usunięty filtr Morfologika
  `isCommonWordNotSurname` w całości, zastąpiony precyzyjną listą `OSOBA_DENYLIST`.**
  Historia tej samej doby: dodanie `isCommonWordNotSurname` do par (punkt wyżej) najpierw
  zepsuło maskowanie odmienionych nazwisk w parze ("Jana Kowalskiego" dopełniacz) —
  załatane pełną odmianą przymiotnikową (`DECLINED_SURNAME_SUFFIXES`). Ale realny test
  na plikach `testy/*.txt` (`ManualTestRegressionTest`) ujawnił coś poważniejszego: TEN SAM
  filtr Morfologika (nawet po naprawie odmiany) blokował maskowanie prawdziwych nazwisk
  będących nazwami zwierząt/przedmiotów — Zając, Wróbel, Sowa, Kot, Karaś zostawały jawne
  w kilkunastu miejscach w wielu plikach. Właściciel: **"Maskujemy nazwiska nawet jak grożą
  FP"** — lepiej fałszywie zamaskować "zająca-zwierzę" niż zgubić "Zająca-nazwisko". Decyzja
  zawężona (nie generalna): Osoba/Zapłata/Łączna/Działający (już znane, realne kłopoty w
  fakturach) zostają zablokowane — ale przez PRECYZYJNĄ listę (`OSOBA_DENYLIST`, ten sam
  mechanizm co istniejące "data/dane/danych/nikach"), nie szeroki filtr gramatyczny który nie
  potrafi odróżnić tych dwóch klas słów. Usunięte: `isCommonWordNotSurname` (funkcja + 8
  wywołań w NameEngine.kt/AnchorEngine.kt). `LookupTables.surnameSuffixes`/
  `DECLINED_SURNAME_SUFFIXES` zostają załadowane ale NIEUŻYWANE (koszt pomijalny) — do
  usunięcia jeśli nikt ich nie przywróci. AnchorEngine A.10 wraca do zachłannego zachowania
  (blokuje tylko OSOBA_DENYLIST, jak reszta AnchorEngine).
- **DODATEK — "Kot" (3 znaki) dalej jawny nawet po usunięciu filtra Morfologika.** Osobna
  przyczyna: próg długości "≤3 znaki = skrót/artefakt OCR" (LENGTH-FIX z wcześniejszej sesji,
  chroni przed "Sp"/"Ko"/"pl") blokował je wszędzie, niezależnie od isCommonWordNotSurname.
  Właściciel: dość ogólnych reguł na ten temat, wrzucić do słownika i zamknąć wątek. Dodane:
  `KNOWN_SHORT_SURNAMES` (NameEngine.kt, obecnie tylko "kot") + osobny, węższy regex (dokładnie
  3 znaki) tuż przed blokiem "Samo nazwisko" — jawny wyjątek od progu długości, nie zmiana
  progu globalnie. Dopisywać kolejne krótkie prawdziwe nazwiska tu, jeśli się pojawią.
- **DODATEK 2 — BUG-MUSIAL-CZASOWNIK, znaleziony przez `ManualTestRegressionTest` po
  usunięciu filtra Morfologika.** "MUSI" (zwykłe "musi coś zrobić") zaczęło się maskować
  jako OSOBA w `test_faktura_20_warianty_07_07.txt` (plik wprost mówi "nie powinno się
  zamaskować"). Przyczyna INNA niż Zając/Kot: wpis nazwiska "Musiał" w słowniku ma
  zanieczyszczoną listę odmian formami czasownika "musieć" (błąd generatora danych z
  wcześniejszej sesji, wcześniej niewidoczny bo filtr Morfologika przypadkiem go zasłaniał).
  Doraźnie dopisane do `OSOBA_DENYLIST` (musi/musisz/musimy/musicie/muszę/muszą).

- **ZAMKNIĘTE 14.07 wieczór — audyt agenta: WYCZYSZCZONY słownik, nie kolejna łatka.**
  Właściciel poprosił o wysłanie agenta do wyczyszczenia słownika zamiast dalszego
  domykania listą wyjątków. Agent przeszedł Morfeuszem2 wszystkie ~10 400 form w
  `surnames_top1000.json` i potwierdził: **13 KOLEJNYCH nazwisk ma DOKŁADNIE ten sam
  błąd co "Musiał"** — cała lista "odmian" to w rzeczywistości pełna koniugacja innego,
  przypadkowo współbrzmiącego czasownika (nie kilka złych form, tylko 20-90 form na
  wpis, praktycznie cała zawartość). Lista: `stępień/kalisz/domagała/gwóźdź/kula/chmiel/
  maj/kuś/musiał/mika/bednarz/żyła/przybyła/przybył`. Najgroźniejsze praktycznie —
  "żyła"→żyć, "przybył/przybyła"→przybyć, "domagała"→domagać, "kuś"→kusić — bardzo
  częste słowa w pismach urzędowych/formalnych, ten sam typ ryzyka co oryginalny "musi".
  **Decyzja: dane nie do naprawienia punktowo (prawie cała lista zła), więc usunięte
  CAŁE te 14 wpisów z `surnames_top1000.json`** (main assets + test resources, teraz
  986 zamiast 1000 kluczy) — czyszczenie danych u źródła, nie kolejny wpis w
  `OSOBA_DENYLIST`. Testy dodane w `engine_golden_imiona_nazwiska.txt` (żyła/przybył/
  domagała w typowych zdaniach urzędowych).
  **ZAMKNIĘTE 14.07 (drugi agent, pełna weryfikacja Morfeuszem):** `surnames_extended.json`
  (39 318 kluczy) wyczyszczony tą samą metodą — dwuetapowo: tanie sito końcówek
  czasownikowych (2507 kandydatów), potem Morfeusz2 per-forma (próg >50% form czysto
  czasownikowych, <20% z jakąkolwiek inną interpretacją). **535 kluczy usuniętych**
  (39318→38783, main assets + test resources, zweryfikowane jako identyczne i poprawnie
  zakodowane). Wśród nich realne nazwiska zdominowane przez odmianę homonimicznego
  czasownika: Motyl/Grab/Gal/Grom/Głąb/Rój/Paś (78-100% form to czasownik, nie deklinacja
  nazwiska). Pełna lista z metrykami w scratchpadzie agenta (nie w repo) — do wglądu jeśli
  potrzebne, nie skopiowana do repo bo 535 pozycji.
- **ZAMKNIĘTE 14.07 — BUG-TOMKIEM (diagnoza Cursor, potwierdzona i wdrożona).**
  `EngineGoldenTest` failował na "Ustaliliśmy z Tomkiem termin spotkania" (0 tokenów OSOBA),
  mimo że "Zadzwoniłem do Tomka wczoraj" (ten sam plik) przechodził. Przyczyna: wpis
  "tomek" w `names_inflected.json` miał niepełną/popsutą listę odmian (brak narzędnika
  "tomkiem", za to obce formy żeńskie "tomce"/"tomką"/"tomkę") — porównanie z poprawnymi
  wzorcami "franek"/"bartek" (mają "frankiem"/"bartkiem", "-owi", "-owie") ujawniło
  asymetrię. Naprawione: pełny męski paradygmat (main assets + test resources).
  Migawki `testy/.golden/` dla 5 plików (`test_email_adres_hard/test_imiona_nazwiska_
  100_11_07/test_lvl3_dane_wrazliwe/test_pesel_warianty/test_ui2_encje_silnik`) USUNIĘTE
  celowo — po weryfikacji że różnice to same poprawki (przesunięcia numerków tokenów +
  Kot/Wiśniewską teraz maskowane, zero nowych przecieków) — przy następnym uruchomieniu
  `ManualTestRegressionTest` zapiszą się na nowo jako aktualny punkt odniesienia
  ("NOWA MIGAWKA", nie błąd).
- **Dodatkowo (poza planem, znaleziony przy okazji tego samego audytu):** `OutputGuard.kt`
  wyciszał YELLOW dla realnych nazwisk kolidujących z nazwą miejscowości (Zając/Wróbel/
  Sikora/Dudek — potwierdzone w `cities_forms.json`) bezwarunkowo. Naprawione: miasto/ulica
  bez ŻADNEGO dowodu nazwiska nadal wyciszone (bez zmian), ale gdy słowo JEST znanym
  nazwiskiem (małym lub rzadkim/extended), kolizja z miastem już nie milczy. Testy dodane
  w `OutputGuardRedesignFpTpTest.kt`.

**Fix OCR_ONE_AS_L z nocy 12.07** (przepisany na regułę ogólną — podmiana cyfra↔litera
TYLKO gdy wynik staje się rozpoznawalnym słowem słownikowym) też jest częścią tego samego
niescommitowanego stanu — patrz `OcrNormalizer.kt` (`fixDigitLetterConfusion`,
`fixNameLetterConfusion`). Weryfikowany rozumowaniem + JVM, **wciąż wymaga testu na
telefonie przed commitem** (nigdy nie potwierdzony przez Pawła na żywym urządzeniu).

**ZAMKNIĘTE 14.07 — OcrDegradationTest "Kaminska mimo B3ata/Be4ta" — potwierdzone testem
diagnostycznym, nie był to bug.** Test sprawdzał starą, zepsutą pisownię ("B3ata"/"Kamlnska")
w wyniku — ale silnik POPRAWNIE naprawia ją przed maskowaniem i maskuje całe imię+nazwisko
jako jeden token ("Beata Kaminska"). Potwierdzone realnym uruchomieniem (nie zgadywaniem):
`TOKEN MAP: {OSOBA_001=Beata Kaminska}`, `surnamesForms zawiera 'kaminska': true`. Cursor
dwa razy z rzędu błędnie twierdził "kaminski ∉ top-1000" — sprawdzone bezpośrednio w JSON,
nieprawda. Asercje testu poprawione na sprawdzanie poprawionej pisowni (analogicznie do
istniejącego testu "Nowicki mimo Krzyszt0f"), diagnostyczny test usunięty (spełnił rolę).

**BUG-OGONKI-ADRES (12.07) — hipoteza MOOT, nie potwierdzona ani wykluczona testem.**
Podejrzenie wiązało się z architekturą `wentThroughOcr`, która **nigdy nie została scalona**
(potwierdzone grep-em w całym repo, 14.07) — więc TEN konkretny mechanizm regresji jest
wykluczony z konstrukcji. `AddressEngine.kt`/`AnchorEngine.kt`/`PseudonymEngine.kt`
(gdzie żyje `matchOverlapsToken`, fix oryginalnego BUG-ADRES-OGONY z 30.06) nie mają
ŻADNYCH niescommitowanych zmian. Ale jedyny wiarygodny dowód że ogonki nie wróciły to
**test na telefonie**, nie rozumowanie o kodzie — pierwszy krok przed commitem/mergem.
Telefon obecnie ma STARY build (sprzed dzisiejszych poprawek) — trzeba zainstalować dzisiejszy
stan, żeby test na telefonie w ogóle sprawdzał aktualny kod, nie stary.

**SPOWOLNIENIE TESTÓW (zgłoszone 14.07, trwa "od kilku dni") — podejrzana przyczyna, nie
naprawione.** Testy JVM trwają ~2 min zamiast ~30s. `surnames_extended.json` ma 39 318 kluczy
i ~370 000 odmienionych form (7,8 MB, 35× więcej niż top-1000) — ładowany i przepuszczany przez
kosztowną normalizację Unicode (`withAsciiVariants`) w `@Before` KAŻDEJ z 25 klas testowych,
które wołają `initializeFromClasspath()`. To liczone od nowa 25× w jednym przebiegu, mimo że
większość testów w ogóle nie używa `surnamesFormsExtended`. Kandydat na przyczynę, NIE
zweryfikowany pomiarem (Claude nie odpala gradlew). Możliwy fix: cache przetworzonego słownika
między klasami testowymi zamiast przeliczania za każdym razem. Priorytet do ustalenia z Pawłem.

## Odłożone świadomie 14.07 (NIE blokują merge do master, dopisać jako osobne zadania)

- **`numer_dzialki`** (numer działki geodezyjnej) — zero wzorca w silniku, nowy typ encji.
- **Generator `generator.py`, `build_decyzja()`** — `numer_kw` trafia do ground truth w 25%
  losowań, ale nigdy nie jest wstawiany do treści dokumentu → fałszywy "miss" w
  `CleanBenchmarkTest`/`StressBenchmarkTest`. Naprawić w generatorze, nie w silniku.
- **OcrNormalizer `fixDigitLetterConfusion`/`fixNameLetterConfusion`** nie sprawdzają
  `surnamesFormsExtended` (39k), tylko mały słownik top-1000 — cichy brak, nieudokumentowany
  jako świadoma decyzja (w przeciwieństwie do reszty splitu 1k/39k).
- **Gradle wrapper 9.4→9.6.1** w tym samym niescommitowanym stanie — niezwiązane z silnikiem,
  niejasne czy celowe (IDE?). Sprawdzić z Pawłem przed commitem czy zostaje czy revert.
- **Ablewski** (nazwisko) — jawne, nie zbadane dlaczego.
- **VIN samochodu** — jawny, nowy typ encji poza dotychczasowym scope.
- **"15 tys" bez kotwicy** — kwota bez słowa-kotwicy jawna, spójne z zasadą projektu.
- **Rodzina nazwisk -ak/-uk/-owicz (deklinacja rzeczownikowa) — TEORETYCZNIE ta sama klasa
  buga co -ski/-ska (naprawione 14.07), ale NIEZWERYFIKOWANA żadnym failującym testem.**
  Diagnoza Cursora (14.07) zasugerowała że odmienione formy tej rodziny (np. "Kowalaka"
  dopełniacz) też mogą fałszywie trafiać do Morfologika jako "nie-osoba". Świadomie NIE
  dopisane teraz — polska deklinacja rzeczownikowa ma pułapki (rodzina "-ec" ma ruchome "e":
  "Kowalec"→dopełniacz "Kowalca", nie "Koweleca" — mechaniczne doklejenie końcówek jak przy
  -ski byłoby błędne). Zrobić dopiero z testem-najpierw na konkretnym przykładzie, nie zgadywać.
- **Usunięcie Rundy 2 (`STRUCTURAL_R2`/`NAME_ENGINE_R2`, `PseudonymEngine.kt` ~379-403)** —
  dowód już zebrany 14.07: `StressBenchmarkTest` liczy `trace.layer` na 300 dokumentach,
  wynik **0 tokenów** przez Rundę 2 (STRUCTURAL: 1541, NAME_ENGINE: 591, ADDRESS_ENGINE: 697,
  ANCHOR: 91 — Runda 2: 0). Spełnia kryterium z decyzji 04.07 ("usuń gdy dowód pokaże że nic
  już nie przechodzi"). Świadomie odłożone na spokojniejszą sesję — Paweł: nie chce ryzykować
  że "szybkie sprzątanie" znowu rozciągnie się na dni tuż przed mergem. Diagnostyka w
  StressBenchmarkTest zostaje (tani do ponownego sprawdzenia po każdej zmianie silnika).
- **ADRES ma dwóch właścicieli** (audyt jeden-właściciel-na-encję, 14.07): AddressEngine.kt
  deklaruje się jako jedyny silnik ADRES, ale NameEngine.kt niezależnie przypisuje ten sam
  token w dwóch miejscach (`applyCityLookup`/`CITY_PREP_REGEX` — miasto po przyimku, i blok
  city-surname-overlap — miasto dwuczłonowe typu "Zielona Góra"). Ma uzasadnienie w
  komentarzach kodu, nie powoduje aktywnych bugów (nie "walczą" o token), ale to ten sam
  wzorzec ryzyka co incydent 30.06. Do decyzji: formalnie zatwierdzić jako trwały wyjątek
  w mapie architektury, albo przenieść obie reguły do AddressEngine.kt. Nie blokuje mergu.

---

## DECYZJA ZAKRESU 12.07 — tylko dokumenty z PII w kontekście RODO, nie wolna proza

Paweł: silnik ma być ograniczony do dokumentów formalnych/urzędowych/biznesowych (umowy,
faktury, pisma urzędowe, wnioski) — NIE do wolnej prozy/dokumentów prywatnych (eseje, listy
osobiste, wspomnienia). Powód: AnchorEngine celowo jest zachłanny bez sumy kontrolnej
(kotwica + zgarnia w prawo, patrz sekcja "ZAMKNIĘTE 07.07 — NUMER_FAKTURY" niżej) — w prozie
ten sam mechanizm łapie fałszywe alarmy (np. ukośnik → sygnatura). To świadomy kompromis
projektowy pod dokumenty formalne, nie bug do łatania punktowo. Biznesowo: nikt nie
potrzebuje maskowania prywatnej korespondencji.

**Skutki:**
- Priorytet z 11.07 wieczór ("zmierzyć słowniki 39k/3,6k na korpusie prozy") — ODRZUCONY,
  nieaktualny.
- `generator_clean.py` Etap 2/3 — szablony BIZNESOWE zostają w zakresie, 4 szablony WOLNEJ
  PROZY (esej/list_osobisty/skarga/wspomnienie) ZAMROŻONE, nie rozwijać dalej.
- Sekcja "OTWARTE, DRUGIE W KOLEJCE — bugi maskowania na esejach/długiej prozie" niżej —
  ZDEZAKTUALIZOWANA tą decyzją, nie podejmować nowych zgłoszeń z dokumentów prywatnych.
- Bugi silnika znalezione PRZY OKAZJI prozy (sklejanie imion/nazwisk 11.07) zostają
  naprawione — to ogólne poprawki, nieszkodliwe dla dokumentów formalnych.
- BUG-ZIELONA-GORA-ODMIANA (niżej) zostaje w zakresie — źródło to pismo urzędowe.

---

## ZAMKNIĘTE 11.07 wieczór — imiona/nazwiska: sklejanie, przymiotnik-pułapka, guard

Ciąg dalszy sesji 11.07 (po redesignie UI). Punkt wyjścia: właściciel zgłosił że "Paweł
Tomasz" (dwa imiona obok siebie) sklejają się w jeden token OSOBA. Diagnoza poszła dużo
głębiej niż jeden bug.

**Zamknięte:**
- **Słownik imion 199→3609** — `names_inflected.json` przywrócony z tagu
  `checkpoint-przed-revertem-slownika-2026-07-08` (był tam już wygenerowany 08.07, ale
  nigdy nie przywrócony razem ze słownikiem nazwisk 11.07 rano). Usunięty jeden śmieć
  ("brak danych" jako "imię" — błąd źródłowego rejestru PESEL).
- **BUG-PRZYMIOTNIK-DIAKRYTYK:** `isAdjective()` (NameEngine.kt) miał regex-fallback bez
  kotwicy początku — "iej" jako podciąg (nie tylko koniec) fałszywie wykrywał przymiotnik
  w imionach kończących się na tę sylabę (Bartłomiej, Maciej, Andriej, Sergiej, Diego...).
  Fix: namesForms sprawdzany PRZED regexem (ten sam wzorzec co surnamesForms już miał).
- **BUG-DWA-IMIONA (3 wersje):** regex łączący "Imię Nazwisko" traktował DRUGIE słowo jako
  nazwisko bez dowodu. v1 sprawdzał surnamesForms (za szeroki — "Tomasz"/"Piotr" też są w
  39k jako rzadkie nazwiska, guard się sam wyłączał). v2: tylko POLISH_FIRST_NAMES (za wąski
  — nie łapał zdrobnień, "Tomek Kasia" nadal się sklejało). v3 (final): pełny namesForms
  (3,6k, czyste źródło dla imion).
- **BUG-CASE-IGNORE-FORWARD:** `IGNORE_CASE` na całym regexie łączącym imię+nazwisko
  sprawiał że `[A-Z...]` pasował też do małej litery — "Data wystawienia" (imię "Data" +
  zwykłe słowo małą literą) sklejało się w fałszywy token. Brakująca kontrola wielkości
  liter dodana we wszystkich 4 blokach łączących (wcześniej miał ją tylko jeden).
- **BUG-DIAKRYTYKI-GRANICA (kolejne miejsce):** końcowy `\b` w regexie łączącym obcinał
  ostatnią literę diakrytyczną nazwiska/imienia (Java `\b` nie zna "ł") — "Jan Paweł Nowak"
  dawało token "Jan Pawe". Fix: `$WORD_END_UNICODE` (już używany gdzie indziej, tu brakował).
- **BUG-SKLEJANIE-MIEDZYLINIOWE:** separator w regexie "Nazwisko Imię" kończył się gołym
  `\s+` (przełyka `\n`) zamiast `[^\S\n]` jak wszędzie indziej w pliku — nazwisko z końca
  jednej linii sklejało się z imieniem z POCZĄTKU zupełnie innej, niepowiązanej linii dalej
  w dokumencie. Znalezione dopiero testem na CAŁYM pliku na telefonie (test JVM sprawdza
  linie osobno, nie widzi tej klasy buga).
- **Kolizje słów pospolitych ze słownikiem imion:** "Data"/"Dane"/"Danych"/"Nikach" (krótkie
  imiona "Dana"/"Dato"/"Nika"/"Niko" mają odmianę pokrywającą się ze zwykłymi słowami) —
  dodane do `OSOBA_DENYLIST`. Próbowany filtr semantyczny (Morfologik) okazał się zbyt
  szeroki — blokował też "Tomka" (Morfologik zna zdrobnienie jako zwykły rzeczownik) —
  COFNIĘTY na rzecz precyzyjnej listy.
- **Nowe reguły Guard (OutputGuard.kt):** `NAZWISKO_NIEZAMASKOWANE`/`IMIE_NIEZAMASKOWANE`
  (YELLOW) — gdy silnik świadomie nie maskuje słowa ze słownika (np. "Mazur" — też nazwa
  tańca, blokowane przez Morfologika w warstwie nazwisk), Guard ostrzega zamiast milczeć.
  Zmierzone PRZED wdrożeniem: wersja bez kotwicy dawała 25% słów-śmieci na realnym tekście
  (`cena`/`organ`/`neto`) — realne ryzyko powrotu do fali 30 flag z wcześniejszej sesji.
  Zawężone do tej samej kotwicy etykiety co istniejąca `OSOBA_NIEZAMASKOWANE`.
- **Nowa infrastruktura testowa:** `NameEngineStickingTest.kt` +
  `engine_golden_imiona_nazwiska.txt` (JVM, bez OCR, wielotagowy format `[OSOBA:x][OSOBA:y]`
  wymusza RÓŻNE tokeny — test sklejania) + `testy/test_imiona_nazwiska_100_11_07.txt`
  (102 linie, do ręcznego testu na telefonie na CAŁYM pliku naraz — to on złapał bug
  międzyliniowy, którego JVM nie widzi).

**Otwarte, świadomie odłożone:**
- **BUG-MAZUR-NIEZAMASKOWANY:** "Mazur" (i klasa podobnych — słowo będące jednocześnie
  nazwiskiem i inną częścią mowy) nie maskuje się wprost — strażnik Morfologika w warstwie
  "Samo nazwisko" uznaje je za zbyt pospolite. Zamiast naprawiać wprost (ryzykowne, ten
  sam mechanizm co niżej), mitygowane przez Guard YELLOW — ale TYLKO w pobliżu etykiety
  danych osobowych. Gołe nazwisko w prozie bez etykiety nadal ucieka bez ostrzeżenia.
- **ZAMKNIĘTE 12.07 — Kolizje słownika NAZWISK:** "Osoba"/"Łączna"/"Zapłaty" (subst/adj) i
  "Działający"/"Działając" (imiesłowy pact/pcon) nadal się maskowały mimo istniejącego
  guardu `MorfologikHelper.isDefinitelyNotPerson()` — imiesłowy to osobna klasa gramatyczna,
  nieobjęta listą warunków (rzeczowniki/przymiotniki już były pokryte, stąd Osoba/Łączna
  fixowały się same, tylko imiesłowy przeciekały). Fix (commit `8668da0`): dopisana cała
  rodzina imiesłowów (pact/pcon/pant/ppas) do `isDefinitelyNotPerson`, nie tylko te 2 tagi
  z diagnozy. Test `MorfologikHelperOsobaTest` wzmocniony o realne asercje. Potwierdzone na
  telefonie. `OSOBA_DENYLIST` sprawdzony — te słowa NIE zostały tam dopisane (obawa
  właściciela że poprzednik "ułatwił sobie życie" listą słów — nieuzasadniona, to była
  faktycznie ogólna reguła gramatyczna).
- **Niejednoznaczne prawdziwe nazwiska bez pewnej odpowiedzi:** Marszałkowski, Sądowy,
  Biała — mogą być nazwiskiem LUB częścią nazwy urzędu/miasta, brak silnego sygnału.

**Benchmark "czysty" dla DOCX/XLSX/TXT** — te formaty NIE przechodzą przez OCR (sprawdzone
w kodzie, `IncomingDocumentFlow.kt`), więc degradacja obrazu z benchmarku fresh/stały jest
dla nich bez sensu. **Etap 1 ZROBIONY** (`generator_clean.py`, commit `7df8e8e`): generator
tekstu bez obrazów, reużywa encje+szablony biznesowe z `generator.py`, dokłada 4 NOWE
szablony prozy (esej/list_osobisty/skarga/wspomnienie) z PII w naturalnych odmienionych
zdaniach, ręczne tabele deklinacji (pula imion/nazwisk/miast w generator.py jest mała i
zamknięta — dokładne, nie algorytmiczne). 200 dokumentów w `dataset_clean/`. **Etap 2
(instrumentalny test na telefonie) i Etap 3 (prawdziwe .docx/.xlsx) — jutro.**

**ZNALEZISKO STRATEGICZNE 11.07 wieczór (na realnym dokumencie właściciela, esej techniczny
SETI, nie umowa) — priorytet PRZED dalszym Etapem 2:** rozszerzone słowniki (nazwiska
1000→39k, imiona 199→3,6k) generują w długiej, różnorodnej prozie znacznie więcej kolizji
niż na wąskim, powtarzalnym benchmarku fresh/stały (wyłącznie dokumenty urzędowe) —
"Zasada Pawła", "Formalna Rada", "Rola", "Belt", "Ale", "Jest", "Lata" złapane jako OSOBA
w jednym dokumencie. Każde dodatkowe unikalne słowo z wielkiej litery to kolejny rzut
kostką przeciw 39k-pozycyjnej liście dobranej wyłącznie progiem częstości (≥100 w rejestrze
PESEL), bez kuracji znaczeniowej — **lista kolizji nie jest skończona, nowy gatunek tekstu
zawsze znajdzie nowe.** Dotychczasowe "0 UX_FP" mierzyło tylko wąski gatunek dokumentów
urzędowych, nie ogólną precyzję.

Naprawione dziś (zostaje, reguła OGÓLNA nie lista słów): bloki łączące dwa słowa w OSOBA
("Imię Nazwisko" itp.) wymagały dowodu słownikowego TYLKO dla jednej strony — druga mogła
być dowolnym słowem z wielkiej litery. Teraz obie strony wymagają dowodu w `surnamesForms`
(`hasSurnameEvidence()`, NameEngine.kt) — eliminuje całą klasę "Zasada Pawła"/"Formalna Rada"
bez dotykania pojedynczych słów.

**NIE zrobione, celowo odrzucone jako złe podejście:** dopisywanie pojedynczych słów
("ale"/"jest"/"kamo"/"rada"/"lata"/"rola"/"belt"/"zasada") do `OSOBA_DENYLIST` — to
dokładnie ten sam wzorzec whack-a-mole co "rodo"/"data"/"dane" wcześniej, tylko z nową
etykietą "systematyczne". Właściciel to złapał i jednoznacznie odrzucił — patrz
`feedback_general_rules_not_examples.md` w pamięci Claude.

**ODRZUCONE 12.07 (decyzja zakresu, patrz sekcja na górze pliku):** pomiar słowników na
korpusie prozy — nieaktualny, silnik nie celuje już w wolną prozę.

---

## Niskopriorytetowe, znalezione przy diagnozie PDF (10.07, doz_zamaskowany.pdf, 21 stron)

- **BUG-ULICA-IMIENNA-JAKO-OSOBA:** "ul. Jana Pawła II" (i podobne ulice nazwane od osób) —
  AddressEngine nie łapie takiej nazwy ulicy, więc trafia do NameEngine i dostaje token
  OSOBA zamiast ADRES. Nie wyciek (dana zamaskowana, zły typ tokenu), znany od 30.06,
  świadomie niski priorytet — Paweł potwierdził 10.07 że zostaje kosmetyką na później.
- **BUG-KOD-POCZTOWY-BEZ-MYSLNIKA:** "94102 Łódź" (OCR zgubił myślnik z "94-102") zostaje
  jawne — wzorzec kodu pocztowego wymaga myślnika, nie toleruje jego braku. To realna luka
  w maskowaniu (nie kosmetyka jak reszta tej sekcji), ale znaleziona przy okazji, nie
  potwierdzona jak częsta w praktyce.
- **Kosmetyka OCR:** "wŁodzi" zamiast "w Łodzi" (sklejona spacja) — nie dotyczy maskowania,
  tylko czytelności.
- **Znane ograniczenie OCR, NIE do naprawienia bezpiecznie:** pomieszana kolejność słów w
  ~5 miejscach na 780 liniach tego dokumentu (np. "tego, / zdarzenie / wcześniej. / które"
  zamiast "tego, które zdarzenie nastąpi wcześniej") — zawsze przy liście punktowanej
  (a/b/c/d/e) sąsiadującej z akapitem. Potwierdzone: to NIE błąd `PdfWriter.kt` (kod nigdy
  nie zmienia kolejności słów/linii, tylko przekazuje dalej) — to ograniczenie odczytu ML
  Kit na tym układzie. Bez bezpiecznego automatycznego fixu (rekonstrukcja kolejności słów
  w dokumencie prawnym = zgadywanie treści, sprzeczne z zasadą "silnik maskuje, nie
  poprawia dokumentu"). Jedyna droga: ręczna korekta na ekranie Review przed maskowaniem.

---

## BUG-ZIELONA-GORA-ODMIANA — niekonsekwentne maskowanie "Zielona Góra" w odmienionych formach

Znalezione przy okazji diagnozy PDF (10.07, `zamaskowany.pdf` realnego pisma urzędowego z
Prezydenta Miasta Zielona Góra) — WAŻNIEJSZE niż formatowanie, prawdziwa luka w silniku,
nie tylko kosmetyka:

- "PREZYDENT MIASTA ZIELONA **OSOBA_004**" — "Góra" (nominativ, osobno od "Zielona" w
  layoucie) trafiło w zwykłe wykrywanie nazwiska zamiast reguły city_surname_overlap dla
  ADRES (ta reguła zadziałała poprawnie gdzie indziej w tym samym dokumencie — "ADRES_006"
  pojawia się kilka razy poprawnie).
- "w **Zielonej Górze**" (locativus, odmieniona forma) — w DWÓCH miejscach w ogóle NIE
  zamaskowane, całkowicie jawne. Ani reguła ADRES, ani żadna inna nie złapała odmienionej
  formy miasta.

Nie diagnozowane głębiej (późna pora, inny obszar niż dzisiejsza praca nad eksportem) —
prawdopodobnie luka w `cities_forms.json`/`city_surname_overlap.json` dla tej konkretnej
odmiany, albo w regule NameEngine która ich używa. Sprawdzić na starcie: czy "Górze"/"Górą"
itd. w ogóle są w `cities_forms.json` (patrz `project_city_declension_task.md` w pamięci —
powinny być, plik ma pełną odmianę Morfeusz2 z 05.07).

---

## ZAMKNIĘTE 11.07 — redesign UI całej apki (Hub → Login → Biblioteka → wynik → Zabezpieczenia)

Gałąź `feature/document-export`, commity `61f6c6c` + `d979d70`. Zaczęte od krytyki Pawła
("Hub wygląda jak lata 2000") → makieta HTML zaakceptowana → ten sam wzorzec rozniesiony
mechanicznie na resztę apki, ekran po ekranie, z testem na telefonie po każdym.

**Ustalony wzorzec (jeden na całą apkę):**
- Jedna wypełniona akcja główna (niebieska, `LynxFilledButton`) zamiast kilku jednakowo
  obramowanych przycisków. Reszta opcji jako płaskie wiersze bez ramek (`LynxFlatRow`,
  ikona + etykieta). Nowe wspólne komponenty w `ui/components/LynxButtons.kt` — stare
  `LynxPrimaryButton`/`LynxSecondaryButton`/`LynxTonalButton` usunięte tam gdzie już
  niepotrzebne (`LynxTonalButton` skasowany całkowicie, był tylko aliasem starego stylu).
- Jeden akcent niebieski wszędzie (spójny z logo PSE) — **nie** zielony na CTA (odrzucone
  po uwadze Pawła, że gryzie się z niebieskim logo). Zielony **tylko** dla "Zabezpieczenia"
  w nawigacji — świadomie inny kolor (nie ostrzegawczy jak żółty/czerwony) żeby zaciekawić,
  bo to brama do wrażliwych akcji (import słownika, wymazanie danych).
- Jawna `fontFamily = LynxTypography.Sans` na KAŻDYM Text — `Type.kt` (Material3 theme)
  miesza `FontFamily.SansSerif` (część nadpisanych stylów) z domyślnym Compose (reszta),
  bez jawnego ustawienia różne teksty na tym samym ekranie renderowały się różną czcionką.
  Naprawione mechanicznie w 132 miejscach (agent, 7 plików) + osobno wcześniej ręcznie.
- Jawny `shape = RoundedCornerShape(...)` na każdym Card/OutlinedTextField — bez tego pole
  ma kwadratowe rogi obok zaokrąglonych sąsiadów (naprawione w 6 miejscach).
- Karty informacyjne (StatusBanner, MaskedSummaryCard, nowy ActionSummaryCard) mają spójny
  odstęp ikony od krawędzi (14dp) — inaczej "schodki" między ikonami w pionie.

**Kolejność ekranów (każdy zatwierdzony na telefonie przed przejściem dalej):** Hub →
ekran logowania (jeden wypełniony przycisk "Odblokuj aplikację" z ikoną odcisku palca
zamiast powtórzonego tekstu + osobnego przycisku; animowana ikona ładowania — token krąży
po pierścieniu logo yin/yang, pętla ~2,4s, zastępuje natywny splash Androida który i tak
nie działał poprawnie przy starcie ze ścieżki Share) → Biblioteka (lista akcji sesji jako
płaskie wiersze, "Usuń dokument" na czerwono za linią) → ekran wyniku pseudonimizacji
(`PseudonymResultPanel`/`ResultPanelUi`) → podgląd tekstu z ręcznym maskowaniem
(`TextPreviewModal`, dodany przycisk "Wklej zaznaczenie" — prawdziwe auto-wypełnianie po
zaznaczeniu nie jest bezpiecznie osiągalne w Compose, patrz `feedback` niżej) → ekran
"Odp. AI" spoza biblioteki (`DepseudonymizationScreen`) → "Zabezpieczenia" (`SecurityModal`
w `MainActivity.kt`).

**Przy okazji naprawione realne bugi (nie tylko wygląd):**
- Czarny ekran przy starcie ze ścieżki Share (`ShareTargetActivity`) — cztery podejścia
  (temat koloru tła, `installSplashScreen()`, ręczny Compose overlay) zanim znaleziono
  prawdziwą przyczynę: oficjalny wzorzec Android "RoutingActivity" (`setKeepOnScreenCondition`)
  zakłada trampolinę w TYM SAMYM tasku — `ShareTargetActivity` ma świadomie inny
  `taskAffinity` od początku projektu, więc to nie zadziałało. Docelowe rozwiązanie:
  animowany ekran ładowania jako zwykła treść Compose w `MainActivity`, niezależny od
  natywnego splasha i przenoszenia się między taskami.
- Biały pasek nad/pod treścią na ekranie review/wyniku — `.statusBarsPadding()` przyklejony
  bezpośrednio do `Surface` kurczył też jego TŁO do obszaru bezpiecznego (nie tylko treść),
  więc pasek statusu/nawigacji wypadał poza Surface i pokazywał surowe jasne tło systemowe.
- Wstecz (systemowy przycisk) z ekranu wyniku wracał do edycji oryginalnego tekstu zamiast
  do Hub — nielogiczne, bo ręczna korekta dzieje się przed pierwszą pseudonimizacją.
- Picker plików ("Wybierz plik") nie pokazywał źródłowych XLSX — brakujący MIME w filtrze
  + fallback `application/octet-stream` (menedżery plików czasem zgłaszają zły typ, ten sam
  problem i fix co wcześniej dla DOCX na ścieżce Share).
- `stripDiacritics()` w `LookupTables.kt` kompilował ten sam regex przy KAŻDYM wywołaniu
  (setki tysięcy razy dla 39k słownika nazwisk) — start apki 22s → 9s. Próba zrównoleglenia
  ładowania słowników była WOLNIEJSZA na telefonie (23,9s) niż sekwencyjnie — presja GC przy
  budowaniu kilku dużych zbiorów naraz na ograniczonej liczbie rdzeni; nie próbować ponownie
  bez nowego pomiaru na urządzeniu.

**Świadomie NIE zrobione:** prawdziwe auto-wypełnianie pola "Tekst do zamaskowania" po
zaznaczeniu tekstu w podglądzie — Compose nie daje dostępu do aktywnego zaznaczenia w
`SelectionContainer`, a ciche czytanie schowka w tle Android 10+ traktuje jako zagrożenie
prywatności (systemowy komunikat). Zastąpione jednym świadomym dotykiem (przycisk "Wklej
zaznaczenie" czytający schowek TYLKO na żądanie użytkownika).

Testy zielone po każdym kroku. Reszta apki (`AlertListSection.kt` i inne niewymienione
wyżej pliki) świadomie NIE ruszona — poza zakresem dzisiejszej sesji.

---

## PRIORYTET NASTĘPNEJ SESJI (10.07 wieczór) — faktury: tokeny na obrazie zamiast czarnych pasków

Paweł: 90% dokumentów w jego poczcie to faktury. Płaski tekst (dzisiejsze DOCX/PDF/Excel)
niszczy strukturę tabeli faktury — kolumny/pozycje/kwoty stają się chaotycznym ciągiem
krótkich linii. Ustalony z Pawłem kierunek: zamiast płaskiego tekstu, dla faktur/skanów
(obraz) — **czarny prostokąt z BIAŁYM TEKSTEM TOKENU na nim** (np. "OSOBA_001"), w miejscu
gdzie dziś jest tylko lity czarny pasek. Zachowuje realny układ faktury (bo to nadal obraz,
nie tekst), a jednocześnie widać CO zostało zamaskowane, nie tylko że coś zamaskowano.

**Ważna decyzja Pawła w trakcie ustalania zakresu:** ODRZUCONE uproszczone parowanie
"pierwszy prostokąt → pierwszy token po kolei" (pozycyjne, bez sprawdzania treści) — Paweł
świadomie zrezygnował mimo że prostsze, bo myli przy powtarzających się wartościach (ten
sam NIP w nagłówku i stopce faktury dostaje jeden token w mapie silnika, ale DWA osobne
prostokąty na obrazie — pozycyjne parowanie przesuwa etykiety od tego miejsca w dół).
**Token musi być dopasowany do prostokąta PO TREŚCI (wartości), nie po kolejności.**
Nie jest to ryzyko wycieku (czarny prostokąt i tak w 100% zakrywa wartość niezależnie od
napisu) — tylko ryzyko mylącej etykiety — ale Paweł uznał że skoro robimy tokeny zamiast
gołych pasków, mają być wiarygodne.

**Co już istnieje (nie budować od zera) — `ImageRedactionPipeline.kt`:**
- OCR (ML Kit) z bounding boxami per linia/element (`boundingBox: Rect`) — X/Y już liczone.
- `RedactionRegion(rect: RectF, type, label)` — regiony do zamalowania już wyliczane.
- `fillBlackRegion(canvas, rect)` — dziś tylko lity czarny prostokąt.
- `detectRedactionRegions()` — już woła silnik maskujący per linia OCR, żeby zdecydować
  czy dana linia ma być zamaskowana (`shouldMaskLine`) — ale dziś zna tylko KATEGORIĘ
  (np. "PESEL"), nie konkretny token z `tokenMap`.

**Co trzeba dopisać:**
1. Dopasowanie regionu do KONKRETNEGO tokenu przez wartość (nie kategorię) — użyć
   `PseudonymEngine.pseudonymize(..., traceMode = true)`, dopasować `DetectionTrace.matchedText`
   (lub `canonicalValue`, ten sam mechanizm co `assignToken` w `PseudonymEngine.kt`) do
   tekstu OCR danej linii/elementu, żeby wiedzieć KTÓRY token (`OSOBA_001` itd.) rysować.
2. Nowa funkcja rysująca: czarny prostokąt (bez zmian) + biały tekst tokenu WYŚRODKOWANY
   na nim, ze zmniejszaną czcionką aż się zmieści w `rect` (ten sam rodzaj logiki co
   `justifiedPositions`/pomiar w `PdfWriter.kt` dziś — mierzyć `Paint.measureText`, zmniejszać
   rozmiar w pętli aż zmieści się w szerokości i wysokości pola).
3. **Faktury jako PDF** (nie tylko zdjęcie/PNG): dziś PDF idzie ścieżką czysto tekstową
   (`ocrFromPdfUri` → płaski tekst → `PdfWriter`). Dla tego trybu trzeba PRZEŁĄCZYĆ na
   ścieżkę obrazową — renderować stronę PDF jako bitmapę (już mamy `PdfRenderer`, używany
   w `ocrFromPdfUri`, DocumentExtractor.kt), przepuścić przez ten sam pipeline co zdjęcie/PNG
   (regiony + tokeny na obrazie), potem złożyć strony z powrotem w PDF (Android `PdfDocument`,
   tak jak już robi `PdfWriter.writePdfFromText` dla tekstu — tu zamiast tekstu strona to
   `Canvas.drawBitmap` zamaskowanego obrazu).
4. Rozstrzygnąć: to nowy, trzeci tryb eksportu (obok płaskiego tekstu i istniejącego
   czarnego paska) — czy PODSTAWIĆ go jako domyślny dla faktur/skanów, czy dać wybór? Nie
   ustalone z Pawłem, dopytać na starcie.

**Kiedy sięgnąć po Cursora:** to dotyka kilku plików naraz (`ImageRedactionPipeline.kt`,
`PdfWriter.kt`/nowy plik, `DocumentExtractor.kt`, ewentualnie `IncomingDocumentFlow.kt`) —
jeśli po rozpoczęciu coś nie gra (np. dopasowanie tekst→token zawodzi na realnym skanie),
skonsultować zamiast łatać punktowo — ten sam playbook co reszta tygodnia.

---

## ZDEZAKTUALIZOWANE 12.07 (decyzja zakresu) — bugi maskowania na esejach/długiej prozie

Ten wątek dotyczył swobodnej prozy/dokumentów prywatnych — poza zakresem po decyzji 12.07
(patrz góra pliku). Nie zbierać nowych zgłoszeń z esejów/listów osobistych. Zostawione dla
historii, nie kontynuować.

---

## ZAMKNIĘTE 10.07 — Document Rebuilder DOCX: zmiana architektury, uproszczone

Gałąź `feature/document-export`. **Decyzja właściciela (10.07):** zrezygnowano z
odwzorowania oryginalnego formatowania (tabele/pogrubienia/obrazki) — to poziom "chmurowej
konkurencji", zbędny dla lokalnej appki mobile. Zamiast tego: eksport zapisuje gotowy,
już zamaskowany tekst (ten sam co w podglądzie) jako świeży, minimalny docx — jeden akapit
na linię, bez szukania czegokolwiek w oryginalnym pliku.

To CAŁKOWICIE eliminuje problem z sesji 09.07 (`MissingTokens` przez normalizację OCR
psującą dopasowanie do surowego tekstu) — nie ma już czego dopasowywać, więc alignment
raw↔normalized (plan (b), diff, `tokenRawRanges`) **stał się zbędny i NIE został
zaimplementowany, celowo**. `DocxWriter.kt` przepisany od zera (`writeDocxFromText`),
`DocumentArtifact.kt`/`DocxExtractor.kt` uproszczone (bez segmentów/XmlRange/zipEntries),
`DocumentExportCoordinator.kt` i UI (`IncomingDocumentFlow.kt`, `PseudonymResultPanel.kt`)
zaktualizowane. Testy zielone, potwierdzone na telefonie (Word otwiera, edytowalny po
zapisie — standardowe Protected View, nie bug).

Pełny stan: `memory/project_document_rebuilder_state.md`.

## ZAMKNIĘTE 10.07 — PDF i Excel, ten sam minimalistyczny wzorzec co DOCX

`PdfWriter.kt`/`XlsxWriter.kt`/`XlsxExtractor.kt` (nowe pliki) + `AndroidManifest.xml`
(brakujący intent-filter dla XLSX — appka w ogóle nie pojawiała się jako opcja udostępniania
dla .xlsx, naprawione). PDF idzie przez istniejący OCR (`ocrFromPdfUri`, PDF zawsze
traktowany jak skan) — bez potrzeby nowego parsera warstwy tekstu PDF.

**PDF dostał dodatkowo kilka rund dopracowania typografii (10.07, zgłoszenia Pawła na
żywym dokumencie — umowa najmu + regulamin sklepu):**
- czcionka sans-serif + łamanie po rzeczywistej szerokości (`Paint.measureText`), nie po
  stałej liczbie znaków — usunęło "wygląd wydruku z lat 70",
- paginacja licząca rzeczywistą wysokość (nie linie sztukami) — eliminuje przedwczesne
  puste miejsce na dole strony,
- usunięta własna stopka "Strona X z Y" — kolidowała z istniejącym znacznikiem stron
  źródłowych z OCR (`── Strona N ──`, DocumentExtractor.kt:140); ten znacznik teraz
  wymusza PRAWDZIWY podział strony w wyniku zamiast być zwykłym tekstem,
- wyśrodkowane nagłówki: `§N` (z opcjonalnym krótkim tytułem, np. "§3 Definicje") oraz
  zamknięta lista słów-kotwic dokumentów (regulamin/umowa/zaświadczenie/itd.) gdy stoją
  SAMOTNIE w linii — reguła OGÓLNA (pierwsze słowo + limit długości + brak interpunkcji
  zdania na końcu), nie lista konkretnych fraz,
- prawdziwe justowanie (wyrównanie do obu marginesów) zwykłych linii akapitu — ostatnia
  linia akapitu zostaje do lewej (standard typograficzny).

Wszystko w `document/PdfWriter.kt`, testowalne w JVM (`PdfWriterTest.kt`) poza samym
rysowaniem Canvas/PdfDocument (Android-only, sprawdzane na telefonie).

**Świadomie NIE naprawiane w tej rundzie:** dokumenty czysto tabelaryczne/formularzowe
(zaświadczenia, faktury) nadal wyglądają jak chaotyczny ciąg krótkich linii — płaski tekst
niszczy strukturę tabeli. To osobny, większy temat — patrz priorytet na górze tego pliku
("faktury: tokeny na obrazie zamiast czarnych pasków").

Bug przy okazji: obraz mniejszy niż 32×32 px dawał surowy angielski wyjątek ML Kit wprost
w UI — naprawione (`IncomingDocumentFlow.kt`, `tooSmallForMlKitMessage`), teraz czytelny
polski komunikat z rzeczywistymi wymiarami.

Desktop (osobny projekt) może pójść dalej w stronę pełnego odwzorowania formatowania —
tam biblioteki i budżet czasu na to pozwalają.

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
