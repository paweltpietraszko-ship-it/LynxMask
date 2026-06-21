# MASTER — LynxMask Mobile

**Wersja:** 1.0 (20.06.2026, po scaleniu sesji porannej i popołudniowej)
**Funkcja:** jedno źródło prawdy dla platformy Mobile (Android / Kotlin). Z tego pliku wycinasz pojedynczy brief naraz dla Claude Code.
**Data konsolidacji:** 20.06.2026
**Źródła:** BRIEF\_Sonet\_18\_06\_kompletny.md (18–19.06, najnowszy stan silnika + benchmark), TODO\_silnik.md (20.06, OCR + silnik), TODO\_LynxMask\_mobile\_12\_06 (13.06, UI/bezpieczeństwo/decyzje — recall NIEAKTUALNY), MAPA\_ARCHITEKTURY\_mobile\_v2 (09.06, szkielet OK, wersje martwe), raport sesji 18–19.06, odpowiedzi Claude Code z 20.06.

## Zasada nadrzędna przy aktualizacji

Nie pisz od nowa. Odznaczaj DONE, dopisuj nowe. Najnowszy dokument wygrywa przy konflikcie. Ground truth z git/Claude Code wygrywa nad dokumentem.

**Wersjonowanie (od 20.06):** jeden numer wersji w nagłówku, nie osobne pliki per wersja — to wciąż jeden, nadpisywany plik. Numer rośnie przy każdym większym scaleniu/checkpoincie (koniec sesji, większa decyzja). Bump wersji = jedna linia w nagłówku, nie osobny rejestr.

\---

## DLA INSTANCJI DOCELOWEJ — PRZECZYTAJ NAJPIERW

**Ten master jest źródłem STANU, nie kolejności.** Numeracja sekcji to porządek dokumentu, nie ranking. Masz prawo i obowiązek przeustawić potoki pod to, co akurat ważne — nie idź ślepo po kolei.

**Dwie decyzje należą do Pawła i blokują pracę — NIE podejmuj ich sam, dopytaj:** los Potoku LT (sekcja 8), sposób de-leet (sekcja 8). Format tokenu ZAMKNIĘTY 20.06 (sekcja 9). Jeśli czegoś nie ma w masterze, a wygląda na ustalone — możesz poprosić o przeszukanie wcześniejszych sesji, ale traktuj stare zapisy jako „do weryfikacji na świeżo", nie jako pewnik.

**Ważne — czytanie „Potoków" (zmiana 20.06):** numeracja Potoków to relikt starego workflow. Czytać sekcje jako rozdziały tematyczne, nie bramki blokujące się sztywno. Realna kolejność wynika z zależności plikowych i oceny orchestratora.

**Proces przekazania między instancjami:** orchestrator aktualizuje ten plik przez polecenie do Claude Code, nigdy sam. Claude Code może prowadzić osobny TODO\_silnik\_live.md na nowe, niepilne odkrycia.

**Reguła sztafety wewnątrz Mobile:** normalizer (OcrNormalizer), silnik (NameEngine/StructuralEngine) i UI dzielą trzy pliki na szwach — PseudonymEngine.kt, PseudonymEngineTest.kt, ShareTargetActivity.kt — oraz wspólny benchmark. Idą JEDEN PO DRUGIM, nie równolegle. Mobile vs Desktop mogą iść równolegle (zero wspólnych plików).

**Rzeczy współdzielone z Desktop (jeden właściciel = Paweł):** format tokenu + spójność TOKEN\_RE, samouczenie (sekcja 19), sync .lynxdict, taksonomia 9 typów. Nie zmieniać jednostronnie. **Format tokenu zamknięty tu 20.06 (sekcja 9) — Paweł musi przekazać tę decyzję do instancji Desktop, orchestrator Mobile tego nie synchronizuje.**

**Zasady nienaruszalne / miny:** sekcja 18. Przeczytaj przed dotknięciem silnika lub OCR.

**Środowisko i benchmark:** sekcja 11 — pełna obowiązkowa sekwencja reinstalla + run\_benchmark\_fresh.bat.

### Jak pisać polecenia do Claude Code

Jedno zadanie na raz. Format polecenia:

```
Przeczytaj plik: C:\\\\Projects\\\\LynxMask\\\\app\\\\src\\\\main\\\\java\\\\com\\\\lynxmask\\\\app\\\\<plik>.kt
Znajdź fragment <X>. Pokaż z numerami linii.
Nic nie zmieniaj. Czekaj na dalsze instrukcje.
```

Po diagnozie wydajesz pojedynczą zmianę. Po każdej zmianie: test (`.\\\\gradlew :app:testDebugUnitTest`), dopiero potem commit. Małe zadanie → test → wynik → następne. Gdy Claude Code idzie w złą stronę: „Zatrzymaj się. Zrób tylko to, co napisałem." Kompletne pliki, nie fragmenty; ta sama nazwa = nadpisanie; czytaj realny kod przed pisaniem; nie dotykaj plików skończonych.

\---

## 0\. CO ZMIENIŁO SIĘ PRZY KONSOLIDACJI (przeczytaj raz)

* **Stare liczby recall z 13.06 (OSOBA 57,8%, EMAIL 0%) — WYRZUCONE.** Decyzja właściciela: dzisiejsze dane wiążące, stare do kosza.
* **EMAIL nie jest zepsuty.** Benchmark zaniża, bo myli kropkę z podkreślnikiem w części lokalnej (krzysztof\_nowakowski vs krzysztof.nowakowski). Realny recall ≈ 100% (6/6). To błąd pomiaru, nie luka silnika. NIE planować „naprawy EMAIL recall".
* **ADRES nie jest zepsuty.** Potwierdzone trace'em (Claude Code 20.06): wszystkie pominięcia to BRAK\_W\_OCR. Silnik nie ma buga adresowego. 87,8% to sufit jakości OCR na LVL2/3. NIE optymalizować pod ADRES benchmark.
* **Potok 3c-FIX NIE istnieje w Mobile.** Nazwy BUG-IBAN-PL / BUG-EMAIL-ZERO / BUG-WHITELIST-OCR / BUG-SLU-FP to projekt Desktop. Sekcja 3c-FIX ze starego TODO usunięta jako zanieczyszczenie.
* **Potok LT (Morfeusz) — premisa nieaktualna.** Generatora fleksji imion/nazwisk NIE MA. Dane JSON już są i są załadowane. Historyczny problem OSOBA był logiką (surnamesForms nieużywane do detekcji), już naprawioną w sesji 17–18.06. LT przeniesiony do „decyzje otwarte" — prawdopodobnie nie ma czego realizować.
* **Mapa architektury 09.06 — wersje martwe**, szkielet (funkcje per plik, zależności, diagnostyka) nadal użyteczny.

\---

## 1\. STAN AKTUALNY — TRZY OSOBNE LICZBY (nie zlepiać)

|Pomiar|Wynik|Co realnie mówi|
|-|-|-|
|Czysty dokument, ręcznie przez apkę, LVL0–1|100% maskowania|silnik na czystym tekście działa; mówi o przypadkach, które przetestowano|
|Benchmark z OCR, LVL03, 68 dok.|ogólny 74,3%|OSOBA 81,5%, EMAIL 83,3% (real ≈100%, błąd pomiaru), ADRES 87,8% (sufit OCR), NUMER \~73%, FP 91|
|Zachowanie na NIEZNANYM dokumencie (dok. #101)|**NIEZBADANE**|ani testy ręczne, ani benchmark tego nie pokazują — patrz sekcja 7|

**Wariancja benchmarku:** świeży dataset = inne dokumenty co run, recall waha się ±5%. To nie regresja. Regresja = ten sam dataset gorszy wynik.
**Lvl2 generatora ma błąd kalibracji** (blur po szumie, kontrast 70% gorszy niż lvl3). To problem generatora, nie silnika. Nie walczyć z lvl2.

\---

## 2\. OTWARTE ZADANIA SILNIKA (priorytet z konsultacji ChatGPT + Opus, potwierdzony)

Zasada wszędzie: **nie modyfikuj tekstu źródłowego — normalizuj tylko do lookupu, maskuj oryginalny span.**

|#|Zadanie|Plik|Opis|Status|
|-|-|-|-|-|
|S1|CAPS LOCK w nazwiskach|NameEngine|KOWALSKI JAN, PIETRASZKO PAWEL niewykrywane. toLookupForm() → lookup, bramka tylko gdy trafia w słownik (eliminuje UMOWA/RODO/REGON)|🔲|
|S2|ASCII imiona|NameEngine|Stanislaw, Lukasz niewykrywane — słownik ma tylko formy z ogonkami. fold() przy starcie, foldedNames map|🔲|
|S3|Inicjały przy nazwiskach|NameEngine|K. Kowalski → maskować całość. INITIALS\_RE, rozszerz span w lewo. Niskie ryzyko FP (tylko przy potwierdzonym nazwisku)|🔲|
|S4|Kwoty słownie|StructuralEngine|„dwadzieścia tysięcy złotych" niewykrywane. Wymagać kotwicy: złotych/zł/groszy|🔲|
|S5|Walidacja sumy kontrolnej PESEL (RESEARCH-1)|StructuralEngine|PESEL wagi 1,3,7,9,1,3,7,9,1,3 mod 10. NIP wagi 6,5,7,2,3,4,5,6,7 mod 11. Zmniejsza FP + po korekcie l→1 daje pewność \~100%|🔲|
|S6|Sklejanie nazwisk|NameEngine|„Kowal ski" — OCR rozbija spacją. Sprawdź left+right w surnamesForms|🔲|
|S7|BUG-EMAIL-TOKEN|StructuralEngine|EMAIL wykrywany jako NUMER zamiast EMAIL|🔲|
|S8|BUG-DATE-PARTIAL|StructuralEngine|2026-06-20 → maskuje rok-miesiąc, zostaje „-20"|🔲|
|S9|BUG-FP-REFNUM|StructuralEngine|UZ/2026/0088, I C 234/26 maskowane jako NUMER (false positive)|🔲|
|S10|BUG-TEL-PREFIX|StructuralEngine|(22) 765-43-21 → prefiks (22) pomijany|🔲|
|S11|Email z imieniem w local-part|StructuralEngine + NameEngine|„email: joanna.grabowska@interia.pl" → imię maskowane jako OSOBA, wzorzec kontekstowy „e-mail:" wchodzi w konflikt z NameEngine. Zbadać kolejność|🔲|

\---

## 3\. OTWARTE ZADANIA NORMALIZERA (OcrNormalizer.kt — osobny pas, NIE silnik)

|#|Zadanie|Opis|Status|
|-|-|-|-|
|N1|BUG-EMAIL-TLD1|@wp p1 — TLD z cyfrą. Fix: `(\\\[a-zA-Z]{2,4})\\\\b` → `(\\\[a-zA-Z0-9]{2,4})\\\\b` (linia 159)|✅ 19.06|
|N2|BUG-NIP-SPLIT|NIP naprawiony tylko do połowy, silnik łapie fragmenty jako dwa NUMER. Zbadać który fragment nie jest naprawiany|🔲|
|N3|OCR\_EMAIL\_LOCALSPACE wiele spacji|naprawia tylko jedną spację. Pętla aż brak zmian lub wzorzec na wiele segmentów|🔲|
|N4|IBAN przez newline|„PL61 1020...\\n0000..." nie sklejany. Reguła OCR\_IBAN\_NEWLINE|🔲|
|N5|KNOWN\_CITY\_FORMS bez ogonków|Bialystok, Lodz, Krakow — stosować fold() dla kandydata|🔲|
|N6|De-leet imion/nazwisk|✅ 21.06 — własna impl. w OcrNormalizer krok 15 (bez zewnętrznych bibliotek)|✅|
|N7|Nagłówek wersji nieaktualny|nagłówek mówi v1.3, realnie v1.6. Zaktualizować|✅ 20.06 (v2.0)|

\---

## 4\. UI — POTOK 6.2 (W TOKU) i powiązane

|Zadanie / Bug|Plik|Status|
|-|-|-|
|Express Mode — pełny przepływ (priorytet 1)|MainActivity / UI / Rust-brak (mobile RAM)|🟢 w toku|
|RESEARCH-3 — ML Kit confidence + progi (avg<0.7 YELLOW, <0.5 RED)|ShareTargetActivity + OcrNormalizer assessQuality|🔲 (mlKitConfidence teraz null, linie 287/434)|
|assessQuality shouldReject dla confidence<0.5 + obsługa odrzucenia w UI + override|OcrNormalizer + UI|🔲|
|Luka reset hasła — „Zapomniałem hasła" daje dostęp bez uwierzytelnienia|LoginScreen.kt|🔲|
|BUG-SS-1 INSERT OR REPLACE nadpisuje NULLami|SessionStore.kt|🔲|
|BUG-SS-3 init() na Main thread — ANR|SessionStore.kt|🔲|
|AUD-M05 silent failure AES-GCM/SQLCipher|SessionStore.kt|🔲|
|AUD-M06 security-crypto 1.1.0-alpha06 → 1.0.0|build.gradle.kts|🔲|
|BUG-16 „Zamaskuj i zapamiętaj" hardcoded OSOBA — selektor typu|PseudonymResultPanel.kt|🔲|
|BUG-17 ManualTokenSection brak TOKEN\_KWOTA|PseudonymResultPanel.kt|🔲|
|BUG-DESCRIPTION-01 pole „Opis dokumentu" bez zapisu|PseudonymResultPanel.kt|🔲|
|BUG-20 camera required="true" po usunięciu kamery|AndroidManifest.xml|🔲 sprawdzić|
|Testy JUnit 6.2: SessionStore (load/save/list/delete/deleteAll/TTL), Deanonymizer (detectSessionId/restore)|—|🔲|

\---

## 5\. KOLEJKA POTOKÓW

|#|Status|Zakres|Zależy od|
|-|-|-|-|
|6.2|🟢 W TOKU|UI, Express Mode, SessionStore, LoginScreen|RODO ✅|
|UI-2|⏳ czeka|nowy układ PseudonymResultPanel — patrz sekcja 17|6.2 (wspólny PseudonymResultPanel.kt)|
|Silnik (S1–S11)|🔲 GOTOWY|luki silnika z sekcji 2|— (różne pliki niż 6.2, ale wspólny PseudonymEngineTest — sztafeta)|
|Normalizer (N1–N7)|🔲 GOTOWY|OcrNormalizer|— (osobny plik)|
|7 — taksonomia 9 typów + format tokenu|✅ taksonomia i format zamknięte (sekcja 9)|OSOBA/ADRES/NUMER/ORGANIZACJA/EMAIL/TELEFON/SYGNATURA/DATA/KWOTA|—|
|OCR (potok) — Guard|✅ Krok 1 zamknięty (sekcja 21)|BUG-GUARD (FP/szum), v2.0|—|
|OCR (potok) — BUG-DICT engine + GuardAllowlist|🔲 w toku (Krok 2, sekcja 21)|UserDictionary, GuardAllowlist|— (nie czeka na „7", patrz nota wyżej o rozdziałach)|
|.LYNXDICT|📋 po Kroku 2|sync słownika desktop→mobile|Krok 2|

**Uwaga 20.06:** kolumna „Zależy od" w tej tabeli to porządek historyczny z czasu, gdy Potoki blokowały się sztywno jeden za drugim — patrz nota na początku dokumentu.

**Reguła sztafety wewnątrz Mobile:** normalizer, silnik i UI dzielą trzy pliki na szwach — PseudonymEngine.kt, PseudonymEngineTest.kt, ShareTargetActivity.kt — oraz wspólny benchmark. Dlatego idą JEDEN PO DRUGIM, nie równolegle. Mobile vs Desktop mogą iść równolegle (zero wspólnych plików).

\---

## 6\. BUGI ZAMROŻONE / BEZPIECZEŃSTWO SILNIKA

|Bug|Plik|Opis|Status|
|-|-|-|-|
|BUG-AL-OPEN|PseudonymEngine.kt|adresy „al." niemaskowane — wielokrotne nieudane próby. **NIE dotykać bez nowej strategii**|❄️ zamrożony|
|BUG-GUARD|OutputGuard.kt|Guard nie zna tokenMap sesji — przepuszcza PII|🔲 potok OCR|
|BUG-DICT (engine)|PseudonymEngine.kt|silnik nie konsultuje UserDictionary przed maskowaniem|🔲 potok OCR|
|BUG-MOBILE-TEXTINPUT|ShareTargetActivity|pole „wklej tekst" nie uruchamia pseudonimizacji|🔲 osobna sesja|
|TOKEN\_RE przed Potokiem 7|NameEngine.kt|format z sufiksem zepsuje TOKEN\_RE — naprawić PRZED Potokiem 7|🔲 zależne od decyzji 8|

\---

## 7\. OBRONA PRZED „DOKUMENTEM #101" (najważniejsze, niezbadane)

100% na ręcznie wybranych czystych dokumentach mówi tylko, że silnik radzi sobie z przypadkami, o których pomyślano. Benchmark ze świeżym datasetem łapie regresję, ale ma sufit: generator produkuje tylko takie dokumenty, jakie zaprogramował autor — „świeży" nie znaczy „nowy". Klasy błędu, której generator nigdy nie tworzy, benchmark nie pokaże (to overfitting z TODO\_silnik pkt 7). Liczbą wartą pilnowania nie jest „jak wysoki recall", tylko „jaki najprostszy dokument umiem ręcznie zbudować, żeby go jeszcze zepsuć".

Dwie rzeczy realnie testują dok. #101:

* **Zdjęcia z aparatu** — realne artefakty OCR z różnych typów dokumentów (TODO\_silnik pkt 6). Brudniejsze niż cokolwiek z generatora.
* **Dokumenty adwersaryjne** — celowo budowane pod znane słabe miejsca (CAPS LOCK, ASCII imiona, kwoty słownie, sklejone nazwiska, inicjały).
* **Fixed dataset** — porównywalność między runami (run\_benchmark.bat), obok fresh.

\---

## 8\. DECYZJE OTWARTE

1. **Potok LT.** Premisa nieaktualna (dane są, logika naprawiona). Decyzja: skasować czy zostawić jako odległy backlog?
2. **RESEARCH-2 de-leet.** Dodać zależność string-similarity-kotlin czy implementować samodzielnie?

\---

## 9\. DECYZJE ZAMKNIĘTE (nie wracać)

* BUG-ARCH: hasło NIE wiązane kryptograficznie z kluczem SQLCipher — Android Keystore + AES-256-GCM wystarczające (Art. 32 RODO). Nie implementować key wrapping.
* Słownik bazowy user\_dictionary\_default.json — w paczce instalatora (bundle), nie OTA.
* Taksonomia 9 typów (Potok 7) POTWIERDZONA. FIRMA+INSTYTUCJA→ORGANIZACJA. DOKUMENT usunięty (NIP/PESEL/IBAN itd. zostają NUMER). DATA nowy typ kontekstowy. (Raport 3c wymieniał 10 typów z DOKUMENT — NIEAKTUALNE, użyć 9.)
* Testy JUnit type-agnostic — utrzymane.
* PESEL odczyt z OCR (OCR\_PESEL\_WORD) — ✅ NAPRAWIONE 19.06. (Uwaga: to inna warstwa niż S5 walidacja sumy kontrolnej — nie mylić.)
* **Format tokenu — ZAMKNIĘTE 20.06.** Decyzja orchestratora na zlecenie właściciela („wybierz bardziej bezpieczny"). Format: `TYP\\\_XXXXXX\\\_NNN` — TYP jeden z 9 typów; XXXXXX 6 znaków heks, wspólne dla wszystkich tokenów w jednej sesji maskowania (blokuje kolizję między sesjami); NNN 3-cyfrowy licznik per typ. Przykład: `OSOBA\\\_D0ECAF\\\_001`. TOKEN\_RE nadal `(?!\\\\d)`, nie `\\\\b`. Identyczny Mobile i Desktop — Paweł przekazuje tę decyzję do instancji Desktop. Status: decyzja zamknięta, format JESZCZE NIE wdrożony w kodzie — dziś realny format to nadal TYP\_NNN (sekcja 12). Migracja to osobne zadanie.

\---

## 10\. NAPRAWIONE W SESJACH 17–18.06 (informacyjnie)

NameEngine: LOOKUP-FIX TITLE\_PATTERN, surnamesForms do pozytywnej detekcji (to była realna przyczyna niskiego OSOBA), HONORIFIC\_NAME\_ONLY, namesForms standalone, zdrobnienia 174→197 imion, kolejność bloków wg specyficzności.
Benchmark/diagnostyka: DetectionTrace, ścieżki Android 16, sekcje EMAIL/ADRES POMINIĘTE, norm()/normalizeForCompare() z ASCII, run\_benchmark\_fresh.bat.
OcrNormalizer v1.3→v1.6: 9 reguł OCR, emaile ze spacjami, prefiks ul., cyfry w PESEL/NIP/IBAN/REGON, OCR\_UL\_PREFIX, OCR\_PESEL\_WORD, PESEL\_SPLIT, NIP\_SPLIT.
OcrNormalizer v1.6→v2.0 (20.06): OCR\_PESEL\_SPLIT rozszerzony na klasy \[TIlOo0-9] (obsługuje l/O/o jako cyfry ze spacją), OCR\_IBAN\_SPLIT analogicznie, OCR\_NUMERIC\_CHAR\_MAP+'o'→'0' (zależność z OCR\_ZERO\_AS\_O krok 3), nowy krok 14 OCR\_DIGIT\_IN\_CONTEXT (l/O/I między cyframi → cyfra). 214 testów, 0 FAILED.
OutputGuard v1.7→v2.0 (20.06): Pełne przepisanie. Nowe RED: PESEL\_SPACE (`\\\\b(?:\\\\d\\\[ \\\\-]?){10}\\\\d\\\\b` — artefakty OCR), TELEFON\_PELNY (z kropką/0048/nawiasami, mandatory separator blokuje REGON compact). YELLOW SYGNATURA/LICZBA zawężone kotwicą słowną w oknie 35 znaków (eliminuje FP „1/1 etatu", „734/1 KC"). Nowe YELLOW: URODZENIE, MIEJSCE\_UR, EMAIL\_FRAGMENT (`\\\\S+@\\\\S+` — zniekształcone maile). Usunięte: REGON, PL\_PREFIX, TELEFON. Naprawiony token exclusion. 3 pliki testów: OutputGuardTest.kt (7), OutputGuardRedesignFpTpTest.kt (45), OutputGuardDiagnosticTest.kt (2 metody, 68 dok.). 54 testy, 0 FAILED.

\---

## 11\. ŚRODOWISKO

```
set JAVA\\\_HOME=C:\\\\Program Files\\\\Android\\\\Android Studio\\\\jbr
set PATH=%JAVA\\\_HOME%\\\\bin;%PATH%
cd C:\\\\Projects\\\\LynxMask
```

Pełny reinstall przed benchmarkiem po zmianie kodu aplikacji (OBOWIĄZKOWY):

```
.\\\\gradlew :app:uninstallDebug
.\\\\gradlew :app:installDebug
.\\\\gradlew :app:uninstallDebugAndroidTest
.\\\\gradlew :app:installDebugAndroidTest
run\\\_benchmark\\\_fresh.bat
```

Bez installDebug zmiany w silniku nie trafiają na telefon. Telefon podłączony i odblokowany.
Testy jednostkowe: `.\\\\gradlew :app:testDebugUnitTest`
Po każdej zmianie silnika: test DOCX na telefonie (silnik w izolacji) PRZED benchmarkiem obrazowym (OCR+silnik razem).

\---

## 12\. WERSJE PLIKÓW — DO WERYFIKACJI

Najnowsze udokumentowane: OcrNormalizer v2.0, NameEngine v1.10, StructuralEngine v1.7, OutputGuard v2.0. Mapa 09.06 jest za tym (v1.3/v1.7/v1.4). **Przed startem każdego potoku zapytaj Claude Code o aktualny nagłówek dotykanego pliku** — dokumenty mogą być za realnym repo.

\---

## 13\. SILNIK — NIŻSZY PRIORYTET / DO WERYFIKACJI

Nie kwalifikują się do „szybkich napraw" — wymagają diagnozy lub mają status nieznany po ostatnich potokach. Weryfikować przy najbliższej okazji dotykania danego pliku.

|Bug|Plik|Opis|Status|
|-|-|-|-|
|FP-FRAGMENTY-OCR|NameEngine|fragmenty słów / złamane linie jako OSOBA („nicznie", „mail\\njoanna"). Propozycja: OSOBA musi zawierać tylko litery, bez cyfr/znaków spec.|🔲 niski|
|BUG-PESEL-OCR-SILNIK|StructuralEngine|doc\_00006: PESEL w OCR ale niezamaskowany, \\b\\d{11}\\b nie złapał (prawd. błąd OCR w cyfrach). Wymaga tekstu OCR do diagnozy|🔲 do zbadania|
|BUG-28|NameEngine|lazy NAME\_FORWARD/BACKWARD/HONORIFIC\_REGEX kompilowany z fallback 200 imion PRZED LookupTables.init()|🔲 po OCR|
|BUG-08|PseudonymEngine|propagacja nazwisk nie łapie członu po tokenie („dr OSOBA\_003 Lewandowska-Karpowicz")|🔲 TBD|
|BUG-07|LookupTables|generateFeminineVariants niepełne (tylko -ski/-cki/-dzki)|🔲 TBD|
|BUG-29|NameEngine|priorytet flag: niekontekstowe wypychają kontekstowe — może być by design|🔲 TBD|
|BUG-01|PseudonymEngine|`\\\\s+` w regexie emaili łączy przez `\\\\n` — fix: `\\\[^\\\\S\\\\n]+`. Niskie ryzyko|🔲 przy pracy na PseudonymEngine (Potok 7?)|
|BUG-02|OutputGuard|cicha degradacja gdy LookupTables niezainicjowane — v1.6 częściowo (warning), stan po załadowaniu nieznany. Oryginalnie wysokie ryzyko|🔲 weryfikacja w Potoku OCR|
|BUG-03|OutputGuard / StructuralEngine|niezgodność wzorca EMAIL — Guard nie alarmuje dla domen od cyfry (user@1und1.de)|🔲 niski|
|BUG-04|UserDictionary|`\\\_loaded` nie resetowane po clear() — fix: `\\\_loaded=false` w clear()|🔲 Potok OCR|
|BUG-05|StructuralEngine|tablice rejestracyjne bez kontekstu — FP na kodach produktów/paragrafach. v1.4 częściowy fix, stan po v1.5/v1.7 nieznany|🔲 weryfikacja przy pracy na StructuralEngine|
|TOKEN\_RE w NameEngine.kt:691 nieznany stan|NameEngine.kt|„Niezidentyfikowana nazwa własna" (PseudonymFlag) ma WŁASNY TOKEN\_RE, osobny od OutputGuard (sekcja 17, 20.06). OutputGuard miał stary/martwy exclusion regex — ten w NameEngine może mieć ten sam problem, niezweryfikowane.|🔲 do sprawdzenia, analogiczne ryzyko jak naprawiony bug w OutputGuard|

\---

## 14\. UI — DROBNE I POLISH (Potok 6.2 lub TBD)

|Zadanie / Bug|Plik|Status|
|-|-|-|
|„Zapisz w bibliotece" usunąć z DepseudonymizationScreen Ekran 2|DepseudonymizationScreen|🔲 6.2|
|Edytuj dokument w LibraryScreen — podpiąć PseudonymResultPanel z istniejącą sesją|LibraryScreen|🔲 6.2|
|Styl BottomNavBar „cegły": odsunięcie od krawędzi, aktywny = podświetlenie + pionowa kreska|UI|🔲 6.2|
|„PDF · DOCX · TXT · Obraz" pod przyciskiem Wybierz plik w Hub|UI|🔲 6.2|
|SecurityModal — suwak TTL|UI|🔲 6.2|
|Logo PSE w nagłówku LibraryScreen|LibraryScreen|🔲 6.2|
|TODO-6: separator fragmentów „==="|ShareTargetActivity|🔲 6.2|
|AndroidManifest — sprawdzić lynxmask\_login SharedPreferences vs allowBackup=false|AndroidManifest.xml|🔲 6.2|
|BUG-LOG-DBL podwójna normalizacja w logOcrAnalysis()|DebugLogBuffer.kt|🔲 6.2 opcjonalnie|
|clearOnExit — metoda istnieje, niepodpięta w MainActivity.onStop()|DebugLogBuffer.kt|🔲 6.2|
|BUG-21 ClipboardCheckActivity/TileService — weryfikacja po załadowaniu manifestu|AndroidManifest.xml|🔲 TBD|
|BUG-13 wyciek TextRecognizer przy wyjątku — brak try-finally wokół recognizer.close()|ShareTargetActivity.kt|🔲 TBD nieprzypisany|
|BUG-18 „Pomiń" tylko na pierwszej karcie — może być by design|OnboardingScreen.kt|🔲 TBD|

\---

## 15\. ODŁOŻONE BEZ TERMINU / DECYZJE OTWARTE (uzupełnienie)

* **Potok 9 — eksport `.lynx` przez WiFi/LAN.** Brak briefu, wymaga decyzji o architekturze. Brak terminu.
* **BUG-PIPELINE-DUPLICATE** — pseudonymize+save+audit zdublowane w dwóch ścieżkach ShareTargetActivity. Nieaktywny, do obserwacji.
* **AUD-M04 — isMinifyEnabled=false** — brak obfuskacji w release. Wymaga reguł ProGuard (SQLCipher+Compose). Osobna sesja po stabilizacji silnika.

\---

## 16\. POTOKI ZAMKNIĘTE (historia — nie wracać bez nowych dowodów)

* **3b** ✅ StructuralEngine v1.5, NameEngine v1.9
* **RODO** ✅ LoginScreen v2.0, SessionStore v1.4, MainActivity v2.1
* **BUG-DICT** ✅ UserDictionary v1.3, ShareTargetActivity v2.8-DICT
* **3c** ✅ (częściowy sukces) StructuralEngine v1.7, NameEngine v1.10, 77 testów, benchmark infra
* Naprawione bugi: BUG-10/11/12/15/19/23/24/25 (kamera usunięta Potok 1), BUG-22 (NameEngine v1.7), BUG-31 (Potok 2), BUG-26/27 (RODO/3b).
* **Uwaga:** „3c-FIX" figurujący w starym TODO mobile NIE jest potokiem Mobile (nazwy bugów = Desktop). Nie wskrzeszać.

\---

## 17\. POTOK UI-2 — UKŁAD EKRANU WYNIKOWEGO (PseudonymResultPanel.kt)

Uzgodniony z właścicielem (sesja 17.06, BRIEF\_Sonet\_kontynuacja\_17\_06). Powód przebudowy: stary układ otwierał ManualDialog jako osobne okno (skakanie między dwoma ekranami), a pole „wklej / Tekst do maskowania" zajmowało miejsce i nic nie robiło. Nowy układ maskuje wbudowanie i odzyskuje to miejsce.

**Kolejność od góry:**

1. **Baner RED** — znika automatycznie gdy wszystkie RED hity obsłużone.
2. **RED hity** — klikalne, każdy znika po zamaskowaniu.
3. **YELLOW hity** — klikalne, znikają po zamaskowaniu lub „ignoruj".
4. **Przycisk „Podgląd tekstu"** → modal z pełnym tekstem po maskowaniu. ✅ ZROBIONE
5. **Wbudowany formularz maskowania** — przeniesiony z ManualDialog BEZ osobnego okna: pole tekstowe + chipy typów + „Maskuj". ❌ NIEZROBIONE — to jest sedno potoku.
6. **„👁 ukrytych"** + przyciski akcji.
7. **„Wyślij"** zablokowany gdy są aktywne RED hity. ✅ ZROBIONE

**Bugi powiązane z UI-2 (status do weryfikacji — z sesji 17.06):**

|Bug|Opis|Status|
|-|-|-|
|Baner RED nie znika|nie znika po obsłużeniu wszystkich RED — powinien zniknąć gdy lista RED pusta|🔲|
|YELLOW hit nie znika|po zamaskowaniu hit zostaje na ekranie — GuardHitsSection nie wie że manualMasks się zmieniło|🔲|
|Wklejenie do pola maskowania nic nie robi|zaznaczony niezamaskowany numer wklejony w pole „Tekst do maskowania" nie maskuje|🔲|
|BUG-UI-TOKEN-SKLEJANIE|token przylega do sąsiedniego słowa bez spacji (FIRMA\_001Firma:) — występuje też na Desktop|🔲 weryfikacja|
|~~Names guard flaguje własne tokeny~~|Token exclusion w OutputGuard v2.0 naprawiony — regex `\\\\b(?:OSOBA\|ADRES\|NUMER\|...)\\\_\\\\d{3}\\\\b` zastąpił martwy `\\\\b\\\[A-Z]{2,}\\\_\\\[A-Z]{3}\\\_\\\\d{3}\\\\b`. „Niezidentyfikowana nazwa własna" to osobny mechanizm NameEngine.kt:691 (PseudonymFlag, nie GuardHit) — ma własny TOKEN\_RE.|✅ 20.06|

**Reguła OutputGuard RED/YELLOW (uzgodniona z właścicielem):**

* **RED (blokujący)** — wzorce wysokiego zaufania, użytkownik musi zdecydować przed wysłaniem: 11 cyfr (PESEL), PL+26 cyfr (IBAN), format NIP (XXX-XXX-XX-XX), format dowodu (litery+cyfry, np. ABC123456 / ciągły bez spacji jak FOH614892).
* **YELLOW (informacyjny)** — wzorce, które mogą być PII albo czymś innym; użytkownik widzi zaznaczenie, ale może wysłać bez klikania.

**Status silnika OutputGuard (sesja 20.06):** v2.0 — przepisany w całości. 54 testy (0 FAILED). Nowe reguły RED: PESEL\_SPACE, TELEFON\_PELNY (szerszy — kropka/0048/nawiasy). YELLOW SYGNATURA i LICZBA zawężone kotwicą słowną (okno 35 znaków). Nowe YELLOW: URODZENIE, MIEJSCE\_UR, EMAIL\_FRAGMENT. Usunięte: REGON, PL\_PREFIX (silnik maskuje). Naprawiony token exclusion regex — aktualny format TYPE\_NNN (`\\\\b(?:OSOBA|ADRES|...)\\\_\\\\d{3}\\\\b`). Diagnostyka na 68 dok.: 46 YELLOW (wyłącznie SYGNATURA — sygnatury komornicze/sądowe/umów niezamaskowane przez silnik), 0 RED, 0 token w matchedText.

**Uwaga o platformach:** UI-2 istnieje też po stronie Desktop (Pseudonimizuj.tsx / PseudonymResultPanel) z tym samym konceptem układu — to potok bliźniaczy, nie ten sam plik. Trzymać spójność wyglądu między platformami; kod osobny.

\---

## 18\. ZASADY NIENARUSZALNE / MINY (nie cofać bez wyraźnego uzasadnienia)

Decyzje, które instancja łatwo cofnie „naprawiając" coś — a cofnięcie psuje projekt. Każda była już raz przemyślana lub okupiona błędem.

* **TOKEN\_RE używa `(?!\\\\d)`, NIE `\\\\b`.** Zapobiega bugowi token-w-tokenie. Nie zmieniać na `\\\\b`. Dotyczy też decyzji o formacie tokenu (sekcja 8) — jakikolwiek format wybierzesz, zachowaj kotwiczenie przez `(?!\\\\d)`.
* **MIDWORD\_SPACE\_RE zostało CELOWO usunięte.** Globalne sklejanie spacji w środku słów niszczyło tekst. **NIE wprowadzać ponownie** globalnego sklejania jako „naprawy OCR" — to już próbowano i zepsuło wynik. Naprawy spacji robić punktowo w OcrNormalizerze (jak N3/N4), nie globalnie.
* **FLAG\_SECURE zawsze włączone** (blokada zrzutów ekranu w release; w debug odblokowane osobno).
* **isDebug = BuildConfig.DEBUG**, nigdy zahardkodowane `true`.
* **OSOBA\_DENYLIST** istnieje i filtruje fałszywe trafienia w detekcji nazwisk — uwzględniać przy zmianach NameEngine.
* **OutputGuard NIE może kopiować/importować wzorców detekcji z NameEngine.kt / StructuralEngine.kt** (zasada właściciela, 20.06). Guard jest ostatnią linią obrony — dzielenie wzorców z silnikiem oznacza dziedziczenie tych samych martwych punktów. Dotyczy też DANYCH, nie tylko wzorców — patrz GuardAllowlist vs UserDictionary, sekcja 19.
* **Zasady pracy z Claude Code:** kompletne pliki (nigdy fragmenty), ta sama nazwa pliku = nadpisanie, czytaj realny kod przed pisaniem, nie dotykaj plików oznaczonych jako skończone.

\---

## 19\. SAMOUCZENIE — BUG-SAMOUCZENIE (fundament, do zbudowania na obu platformach)

To nie jest pojedynczy bug, to fundament całego systemu (potwierdzone 20.06). Pętla:

1. Silnik maskuje np. „Nowaka" jako OSOBA\_001.
2. Użytkownik widzi, że to część adresu — **odkrywa token**.
3. Korekta trafia do słownika profilu: „Nowaka = ADRES, nie OSOBA".
4. Przy następnym dokumencie silnik już wie.

Bez możliwości odkrycia tokenu silnik nie ma jak się korygować — dlatego część luk silnika (np. mylne OSOBA) rozwiąże się sama, gdy ten mechanizm powstanie. Decyzja właściciela: robić silnik dalej ze świadomością, że odkrywanie tokenów część problemów wchłonie. Mobile: zależne od BUG-DICT engine (silnik konsultuje UserDictionary) + BUG-GUARD. **Współdzielone z Desktop — patrz desktop master sekcja 4.**

**Rewizja 20.06:** ten mechanizm budować dopiero PO sprzątnięciu OutputGuard (✅ zamknięty, sekcja 21).

**DWA słowniki, nie jeden (propozycja właściciela, 20.06).**

1. UserDictionary (istniejący) — korekty TYPU dla silnika, konsultowany przez PseudonymEngine.kt.
2. GuardAllowlist (nowy) — wartości które Guard flagował a użytkownik potwierdził jako nie-PII, konsultowany przez OutputGuard.kt.

Powód rozdzielenia: rozszerzenie zasady z sekcji 18 na poziom danych — scalenie tworzyłoby ryzyko, że odrzucenie fałszywego alarmu Guard wyłączy maskowanie w silniku gdzie indziej.

Zdecydowane: dopasowanie jako para (wartość, typ\_reguły) nie goła wartość — niskoentropijne wartości mają ryzyko kolizji z cudzym prawdziwym PII. Magazyn: reużyć szyfrowanie z UserDictionary v1.3.

Otwarte — czeka na właściciela: czy YELLOW „ignoruj" wystarcza do zapisu w GuardAllowlist, czy potrzebny osobny przycisk „to nie PII, zapamiętaj" (rekomendacja: osobny przycisk)? TTL / przegląd wpisów?

Powiązany detal silnika (wdrażany 20.06): blok „samo nazwisko z surnamesForms" w NameEngine — niski priorytet, po warstwach adresowych i firmowych, z bramką TOKEN\_RE.containsMatchIn + OSOBA\_DENYLIST.

**Sync .lynxdict / GuardAllowlist — kierunek potwierdzony (właściciel, 20.06):** Mobile prowadzi projekt tego słownika, Desktop (Python) będzie z niego czerpał, nie odwrotnie. Wymóg: schemat danych musi być przenośny między Kotlin i Python — prosty JSON / proste typy w warstwie wymiany (.lynxdict), bez Kotlin-specific serializacji. Lokalne szyfrowanie-at-rest po stronie Mobile (EncryptedFile/Keystore) zostaje osobne od formatu wymiany — nie mieszać tych dwóch warstw.

\---

## 20\. PODZIAŁ LUK SILNIKA: POWTARZALNE vs JEDNORAZOWE

**Założenie produktu:** reguła dowozi \~80+%, samouczenie dobija do 95% na encjach POWTARZALNYCH. To dzieli luki silnika na dwie klasy o różnym priorytecie. Instancja docelowa **NIE łata ręcznie luk z klasy A**, dopóki samouczenie nie działa — bo je przykryje.

### Klasa A — wartości POWTARZALNE (oddane samouczeniu, niski priorytet ręcznej naprawy)

OSOBA, ORGANIZACJA/instytucje, miasta, ulice, adresy. Te same wartości wracają w kolejnych dokumentach tego samego użytkownika, więc korekta raz wpisana do słownika profilu działa na przyszłość. Tu silnik może zostać „na osiemdziesiąt parę procent" — samouczenie domknie różnicę.

Przykłady z realnego dokumentu 19.06: „Góra" brane za OSOBA, „Bolesławiec" pominięte jako miejsce urodzenia, miasto w adresie.

### Klasa B — identyfikatory JEDNORAZOWE (muszą osiągnąć 95% samą REGUŁĄ, teraz)

PESEL, NIP, IBAN, nr dowodu/paszportu, e-mail, telefon, data i miejsce urodzenia, sygnatury. Samouczenie ich **NIE podniesie** — każdy pojawia się raz i nie wraca, nie ma czego zapamiętać. PESEL tego pacjenta nie powtórzy się w następnym dokumencie. Dla klasy B nie ma drugiej szansy z pamięci, więc to jedyne luki detekcji warte naprawy przed zbudowaniem samouczenia.

### Próg per typ, nie uśredniony

Klasa B celuje w bardzo wysoką wykrywalność (każde pominięcie = wyciek), akceptuje trochę FP. Klasa A trzyma 95% i tnie FP (nadgorliwość psuje tekst, pominięcie mniej groźne). Uśredniony recall ukryłby wyciek pojedynczego PESEL w morzu poprawnych trafień — pilnować per typ.

### Kolejność wynikowa

1. Zbuduj samouczenie wcześnie — zdejmuje pracę nad klasą A.
2. Z luk detekcji rusz teraz tylko klasę B.
3. Resztę klasy A oceń dopiero po włączeniu pętli korekt — zobaczysz, ile w ogóle zostało.

\---

## 21\. SPRZĄTANIE OutputGuard — POTOK (20.06)

**Cel:** Guard jako siatka bezpieczeństwa — łapie to, czego silnik nie zdążył lub nie potrafi. Zasada niezależności (sekcja 18): Guard NIE zna tokenMap sesji, działa wyłącznie na wzorcach w tekście wyjściowym.

### Rozważone reguły znakowe (podczas projektowania v2.0)

* REGON (9/14 cyfr) — **usunięty**: silnik maskuje REGON jako NUMER; Guard nie powinien duplikować.
* PL\_PREFIX (PL + cyfry) — **usunięty**: obsłużony przez IBAN RED.
* TELEFON (stary, tylko `\\\\d{9}`) — **zastąpiony** przez TELEFON\_PELNY (szerszy, RED).
* LICZBA bez kotwicy — **zawężona**: kotwica słowna w oknie 35 znaków eliminuje FP „1/1 etatu", „734/1 KC".
* SYGNATURA bez kotwicy — **zawężona**: j.w.

### Rozważone reguły znakowe — propozycja właściciela, decyzja (20.06)

Propozycja: @ / \_ \& $ % jako sygnał alertu.

* **@ — ZATWIERDZONE**, wdrożone jako EMAIL\_FRAGMENT (`\\\\S+@\\\\S+`, YELLOW). Łapie zniekształcone/częściowo zamaskowane resztki maila, których nie złapie strukturalny EMAIL (RED).
* **/ — ODRZUCONE.** Zbyt częsty w normalnym polskim tekście (daty, ułamki, i/lub, km/h, URL). SYGNATURA z kotwicą już to pokrywa węziej.
* **\_ — ODRZUCONE, mocno.** Własny format tokenu UŻYWA podkreślnika (`TYP\\\_NNN` dziś, `TYP\\\_XXXXXX\\\_NNN` docelowo) — goła reguła na „\_" flagowałaby własne tokeny systemu w każdym dokumencie. Nie wprowadzać tej reguły, nawet jeśli ktoś zaproponuje ją ponownie w przyszłości.
* **\&, $, % — pominięte.** Brak dowodu realnej luki w tych typach dokumentów (\& ma teoretyczną wartość dla nazw firm „X \& Y" — klasa A, wrócić przy konkretnym przykładzie wycieku).

### Krok 1 — WYNIK FINALNY, ZATWIERDZONY (20.06)

Testy: 54/54 zielone (7 OutputGuardTest + 45 OutputGuardRedesignFpTpTest + 2 diagnostyczne). Jedna iteracja:
TELEFON\_PELNY mylił NIP z telefonem (kierunkowy \\d{2,3} łapał początek NIP) — naprawione na \\d{2} (polskie
kierunkowe zawsze 2-cyfrowe).

Walidacja na pełnym benchmarku (68 dok.): RED=0, YELLOW=46, FP=0, własne tokeny w matchedText=0. Wszystkie
46 hitów to SYGNATURA — TP w 100% przypadków (sygnatury komornicze, sądowe, numery umów, fragmenty faktur).
Stary FP (2026-31) wyeliminowany. 10 dokumentów „czysty formularz" (PESEL+NIP+IBAN+email+telefon) = 0 hitów.

URODZENIE/MIEJSCE\_UR nie odpaliły na 68 dok. — benchmark używa etykiety „Data urodzenia: XX.XX.XXXX" (już
maskowanej przez silnik jako DATA), nie skróconej „ur. XX.XX.XXXX". Reguły potwierdzone działające na
korpusie 7-dok (sanity check). Nie traktować braku odpaleń na 68-dok jako defektu reguły.

**KROK 1 ZAMKNIĘTY.**

### Nowo potwierdzona luka silnika — kandydat na S12 (zanotowane, nie ruszane dziś)

46 hitów SYGNATURA na 68 dok. to systemowa luka: StructuralEngine nie wykrywa sygnatur spraw/umów/faktur.
Inna strona S9 (który dotyczy NADMIAROWEGO maskowania podobnych numerów jako NUMER) — tu chodzi o BRAK
maskowania jako SYGNATURA. Kandydat na nowe zadanie silnika, część zakresu „sygnatury" z Klasy B (sekcja 20),
świadomie odłożona z dzisiejszej sesji. Guard pełni rolę siatki bezpieczeństwa do czasu naprawy silnika.

### KONIEC SESJI 20.06 — START NASTĘPNEJ INSTANCJI TUTAJ

Zrobione dziś: format tokenu zamknięty, zasada niezależności Guard (sekcja 18), Guard przeprojektowany i
zwalidowany empirycznie na 68 dok. (Krok 1 zamknięty), propozycja GuardAllowlist z otwartymi pytaniami
(sekcja 19), kierunek sync z Desktop potwierdzony (Mobile prowadzi, Python-przenośny schemat).

Następny krok Przed Krokiem 2 — szybkie sprzątanie: EngineGoldenTest (aktualizacja golden PO weryfikacji diffu), @Ignore na

OcrLvl1IntegrationTest (BUG-PESEL-10, czeka na S5) i OcrDegradationTest 4x (N6 de-leet, czeka na decyzję

RESEARCH-2, sekcja 8). (Krok 2, sekcja 21): mechanizm odkrywania tokenu + BUG-DICT engine + GuardAllowlist. Czeka na
potwierdzenie właściciela co do przycisku „to nie PII" vs samo „ignoruj" (sekcja 19).

---

### SESJA 21.06 — ZROBIONE

**Audyt katalogu głównego (commit 5e110c5):** usunięto 8 briefów/raportów, 12 katalogów datasetów,
2 duże logi (12 MB), 2 kopie Kotlin w roota. Zasada: nie tworzyć briefów w katalogu — nowe problemy
do TODO_silnik.md, stan projektu do MASTER.

**StructuralEngine — wzorzec kontekstowy PESEL (commit d8d7df3):** minimum cyfr po słowie "PESEL:"
obniżone z 10 do 6 (`{8,16}` → `{4,16}`). Powód: 6 cyfr = data urodzenia = wyciek mimo skróconego
PESELa przez OCR. Przy kontekście słownym ryzyko FP = 0. +5 testów (PESEL 6/9 cyfr, dowód -2 cyfry,
paszport -2 cyfry, KRS 8 cyfr) — pozostałe encje obsługiwane przez istniejące wzorce.

**OcrLvl1IntegrationTest (commit 902ff72):** naprawiona błędna asercja `startsWith("PESEL")` →
`startsWith("NUMER")` (taksonomia używa NUMER dla wszystkich numerów strukturalnych). BUG-PESEL-10
ZAMKNIĘTY — 10-cyfrowy PESEL z OCR jest maskowany przez wzorzec kontekstowy.

**Stan testów po sesji 21.06: 266 testów, 5 FAILED (pre-existing):**

| FAIL | Powód | Co zrobić |
|---|---|---|
| `EngineGoldenTest` | golden file nieaktualny | zaktualizować plik expected |
| 4× `OcrDegradationTest` | de-leet (Be4ta, Krzy5zt0f) + tel OCR | @Ignore + "czeka na N6 RESEARCH-2" |

**EngineGoldenTest (commit 49b1969):** ZAMKNIĘTY. ADRES tag poprawiony (odmiana), EMAIL x2 zakomentowane (BUG-EMAIL-TOKEN/S7). 41/41 PASS.

**Stan testów końcowy sesji 21.06: 266 testów, 4 FAILED — wyłącznie OcrDegradationTest (de-leet + tel OCR), czeka na RESEARCH-2.**

**N6 de-leet (commit dc6c86f):** ZAMKNIĘTY. OcrNormalizer krok 15 — własna implementacja (4→a, 3→e, 5→s, 0→o, 1→i), weryfikacja przez LookupTables. Decyzja: bez zewnętrznej biblioteki.

**Stan testów końcowy: 266 testów, 0 FAILED, 1 @Ignore** (BUG-TEL-PREFIX — telefon bez + nie trafia do tokenMap, tylko do OutputGuard; czeka na S10).

**Następny krok: Krok 2** — mechanizm odkrywania tokenu + BUG-DICT engine + GuardAllowlist. Czeka na decyzję Pawła: osobny przycisk „to nie PII, zapamiętaj" czy wystarczy samo „ignoruj" przy YELLOW hicie (sekcja 19).

