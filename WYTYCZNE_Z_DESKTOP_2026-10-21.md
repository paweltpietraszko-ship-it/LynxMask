# Wytyczne dla LynxMask Mobile z porównania silników (2026-10-21)

Dla: implementator Mobile (CC w repo `LynxMask`). Autor: CC Desktop (na prośbę Pawła). To wnioski i kierunki naprawy, nie gotowy
brief — przed zmianą ocenić merytorycznie i zapytać Pawła o punkty z sekcji 3. Ścieżki `%TEMP%\lynx_cc_review\...` są lokalne na
komputerze Pawła (narzędzie porównawcze nie jest w żadnym repo).

**Źródło i odtworzenie**
- Mobile `450204b` (`PseudonymEngine.pseudonymize`, test JVM, słowniki z `src/test/resources`). Desktop: `cc70822` (przed) oraz stan po LYNX-FORMATS-PACK2 r2 (`25f3b35`; scalone do main Desktop jako `f55230c` z jedną drobną poprawką spacji w formie prawnej). `LYNX_HERBERT` off, pusty profil.
- 693 dokumentów syntetycznych (testy 38, clean 300, pliki 7, stress 300, ocr 48). Liczby = **wystąpienia**, "bez testy/" = bez plików z komentarzami OCZEKIWANE (dają szum).
- Odtworzenie: `%TEMP%\lynx_cc_review\mobile_compare\run.ps1` (wyniki: `mobile_compare\report.md`, `mobile_compare_pack2\po2\{diff,stats}.json`). Pojedyncze zdania: `CompareDumpTest` w `mobile_compare\repo` (`gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --offline --tests "*CompareDump*"`, JAVA_HOME = jbr Android Studio, `LYNX_CMP_LIST`/`LYNX_CMP_OUT`). Próby tego szkicu: `mobile_wytyczne\probe\`.
- Kierunek D>M = Desktop zakrył, Mobile nie. Wszystkie przypadki poniżej potwierdzone próbą na silniku Mobile (nie tylko heurystyką podtypu z raportu).

## 1. Braki Mobile (priorytet wg liczby wystąpień)

**1.1 Imię + rzadkie/obce nazwisko: nazwisko zostaje jawne.** ~433 (401 bez testy/, 186 dok.) + ~20 pokrewnych ("Imię [[Nazwisko]]", gdzie Desktop zakrył samo nazwisko). Największa klasa.
- Próba: `Imię i nazwisko:\nKeith Grochola` -> `OSOBA_001 Grochola` (token = tylko "Keith"); `niżej podpisany Aram Góralewski`; `na działania Anetta Godzwon` (też drugie wystąpienie w tekście); `Pan Ilgar Kiezik`, `Wnioskodawca: Spartak Pronina`. Kontrola: `Jan Kowalski` -> cała OSOBA (nazwisko z top-1000).
- Reguła: `NameEngine.kt:472` `hasSurnameEvidence` (wymóg nazwiska w `surnamesForms`), użyte w `:742`, `:774`, `:804` (NAME_FORWARD/BACKWARD, `:494`/`:511`). Pochodzi z BUG-PROZA-SKLEJANIE (11.07), żeby "Zasada Pawła" nie dawała OSOBY.
- Kierunek: zniesienie wymogu dowodu nazwiska TYLKO w kontekście z silną kotwicą: wartość po etykiecie (`Imię i nazwisko:`, `Wnioskodawca`, `NABYWCA/SPRZEDAWCA:`), `niżej podpisany/a`, `na działania`, `Pan/Pani`, linia zawierająca wyłącznie dwa wyrazy z wielkiej litery, numerowane strony umowy ("1. Imię Nazwisko"). W wolnym tekście zostawić obecny wymóg.
- Ryzyko: nadmaskowanie dwuwyrazowych nazw własnych po etykiecie; zgodne z polityką (wątpliwość = maskuj). Ochrona przed regresją 11.07: test z "Zasada Pawła"/"Teza Pawła" w prozie musi nadal przejść.

**1.2 Kwota słowna: maskowany tylko początek.** 15 + 151 (każdy dokument typu umowa/komornik z `(słownie: ...)`) + 8.
- Próba: `(słownie: 44 tysięcy 813 złotych)` -> `KWOTA_002 813 złotych` (reszta jawna); `(słownie: 504 złotych)` -> bez zmian; `Zapłata 504 złotych` -> bez zmian. Słownie literami (`pięćset złotych 00/100`) maskowane, ale z jawnym ogonem `00/100`. `Kwota 1 200 złotych` maskowane (kotwica "kwota").
- Reguły: A.9 `AnchorEngine.kt:389` (waluta tylko `zł|PLN|...`, "złotych" odrzucone przez `(?![a-ząć...])`), A.9d `:442` (cyfra + mnożnik, kończy się na "tysięcy"), S4 w `StructuralEngine.kt:729-760` (cyfry nie liczą się jako człon słowny bez kotwicy).
- Kierunek: (a) w A.9 dopuścić `złot\p{L}{0,3}|groszy` po cyfrze (lub osobna reguła cyfry + "złotych"/"gr"); (b) po dopasowaniu A.9d doklejać następujące `\d+ złot\p{L}*` oraz opcjonalne `\d{2}/100`.
- Ryzyko: niskie ("N złotych" to pieniądze); sprawdzić "5 złotych drzew" nie występuje w korpusie, ale `złotych` jako przymiotnik ("złotych medali") — wymagać cyfry bezpośrednio przed.

**1.3 Numer działki ewidencyjnej, gdy wartość jest w następnej linii.** 15 (15 dok., clean/stress/ocr). Uwaga: raport nazwał to "KW", ale KW Mobile maskuje poprawnie; luka dotyczy `Nr działki ewidencyjnej:\n711878.0052.5931/5`.
- Próba: ta sama etykieta i wartość w jednej linii -> maskowane (BUG-DZIALKA-ANCHOR); wartość po `\n` -> jawna.
- Reguła: `AnchorEngine.kt:597` `docNumberRe` (separatory `[^\S\n]`, nie przekracza linii).
- Kierunek: dla gałęzi `Nr/Numer ... działk*` dopuścić jeden `\n` po etykiecie przed wartością zawierającą cyfry.
- Ryzyko: niskie (wąska gałąź, etykieta z "działk").

**1.4 Adres: ulica z 3-wyrazową nazwą, numer domu zostaje.** 20 (20 dok.).
- Próba: `al. Jana Pawła II 38, 70-001 Rybnik` -> token `al. Jana Pawła 11` + jawne `38` (OcrNormalizer zmienia "II" na "11" przed regułą).
- Reguła: A.11 `AnchorEngine.kt:489`, prefiks + max 3 tokeny (`{0,2}` po pierwszym).
- Kierunek: gdy ostatni token nazwy to rzymska cyfra (I, II, III, ...), zaakceptować jeszcze jeden token liczbowy (numer domu); alternatywnie nie normalizować rzymskich cyfr po wyrazie z wielkiej litery w kontekście `ul./al.`.
- Ryzyko: niskie; dotyczy tylko numeru domu (nie ulicy), ale numer + kod pocztowy zawężają adres.

**1.5 Drobne, pojedyncze (głównie pliki testowe).** Tablice rejestracyjne (`KR 4AB67`, `GD 99XYZ`), `PWZ 2 345678`, IBAN zagraniczny z opisem (`IBAN zagraniczny: DE89...`; reguła IBAN `StructuralEngine.kt:471` wymaga cyfr tuż po słowie IBAN), firma bez formy prawnej po etykiecie `SPRZEDAWCA:` ("Agencja Reklamowa KREACJA", 2 wyst.; Mobile w próbie nie maskuje, Desktop tak). Łącznie ~10 wyst. Nie pilne; do osobnej decyzji, czy dodawać reguły.

## 2. Fałszywe alarmy Mobile

**2.1 Numeracja listy jako KWOTA.** `1. Os`, `2. Sz`, `4. Sz` (np. `4. Szkolenie` -> `KWOTA_001 kolenie`). 4 wyst. bez testy/ (3 dok., ocr/pliki); tekst zostaje uszkodzony (zjada 2 litery słowa).
- Reguła: A.9c `AnchorEngine.kt:424`: klasa OCR `[0-9OolIiSsBbZz]` pozwala, by dwa litery ("Os", "Sz") udawały dwie cyfry groszy; lookahead sprawdza tylko kolejny znak z tej samej klasy.
- Kierunek: po dwóch znakach groszy dodać `(?!\p{L})` oraz wymagać, by przynajmniej jeden z dwóch znaków był cyfrą albo oba były `O/o`. Test: `4. Szkolenie`, `1. Oświadczenie` bez zmian; `15 OOO,OO zł`, `15OOO,OO`, `15 0OO,00` nadal KWOTA.
- Ryzyko: niskie; zachować uwagę na "15 000,00zł" (łapie A.9, nie A.9c).

**2.2 Data bez roku jako KWOTA.** `08.07`, `22.06`, `12.01` (z `12.01-18.01.2024`) -> KWOTA. 3 wyst. bez testy/ (13 łącznie, reszta komentarze plików testowych). Data jest zakryta, myli się tylko typ (etykieta w widoku/przywracaniu). Zmiana typu kłóci się z decyzją A.9c (Paweł 08.07: "kształt = kwota"). Rekomendacja: nie zmieniać bez decyzji właściciela, odnotować.

## 3. Różnice polityki do decyzji właściciela (nie zalecenia)

- **INSTYTUCJA / nazwy bez formy prawnej:** 153 (141 bez testy/) pełne + ~100 granice FIRMA (110 z 113 i 90 z 99 to sądy, urzędy, komornicy, ZUS, kancelarie: "Sąd Rejonowy", "Urząd Skarbowy", "SR Warszawa-Mokotów"). Typ INSTYTUCJA istnieje tylko w Desktop (taksonomia wspólna; zmienia tylko Paweł). Mobile wymaga formy prawnej (A.1 `AnchorEngine.kt:92`).
- **Sama miejscowość:** Mobile zakrywa (M>D ADRES miejscowość: 250 wyst., 141 dok., np. `w Zabrzu`, `Urząd Skarbowy w Warszawie`), Desktop nie. Polityka: sama miejscowość nie jest daną osobową, więc to nadmaskowanie Mobile, nie cel. Zmiana (A.11c/A.11d `AnchorEngine.kt:501-532`) to decyzja właściciela; nie wymagana do wyrównania wycieków.
- **Nr działki / tablice rejestracyjne:** Desktop maskuje; Mobile częściowo (1.3, 1.5).

## 4. Czego NIE zmieniać / nie naśladować

- **Data ISO bez kotwicy:** Mobile JUŻ maskuje (`spotkanie 2020-11-05`, `Data: 1955-09-19`, `dob: 1990-07-22` -> NUMER; efekt reguły kształtu A.5b `AnchorEngine.kt:257` i kotwic A.7/A.7b). W porównaniu wszystkie rozbieżności ISO były kierunku M>D (Desktop nadrabiał po FORMATS-PACK2). Wniosek "Mobile ma ISO tylko z `ur.`" NIE potwierdzony; nic nie robić (opcjonalnie dodać test regresji).
- **Kolumny tabel faktur (`8178    10059`, `1401     1513`)**: Desktop maskuje jako telefon/NIP/liczba (≈30 wyst., 17+ dok.), to nadmaskowanie Desktop; Mobile ma to zostawić.
- **Szum OCR w Desktop:** pojedyncze zwykłe słowa jako OSOBA/FIRMA w plikach `ocr` ("Dlużnik", "Splata", "Poucze", "FAKTURAvVAT", ~20 wyst.) — błąd Desktop, nie wzorzec dla Mobile.
- Nie osłabiać: nazwisko-rzeczownik (Zając, Wróbel, Kot) maskowane zawsze (`NameEngine.kt:409`); maskowanie dat podobnych do urodzenia bez `ur.`; sygnatury; format tokenu, `TOKEN_RE`, taksonomia (typy FIRMA/OSOBA/NUMER/EMAIL/KWOTA/ADRES).
- Nie dodawać typu INSTYTUCJA ani masowania samej miejscowości jako "wyrównania do Desktop".
- Kolejność proponowanych prac: 1.1 (największy wyciek), 1.2, 1.3, 1.4, 2.1; po każdej: przepuścić korpus `mobile_compare\corpus` przez `CompareDumpTest` i porównać `compare.py` (dbać o oba kierunki, tryb tekst i OCR).
