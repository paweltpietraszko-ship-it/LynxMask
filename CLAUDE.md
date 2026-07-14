# CLAUDE.md — LynxMask Mobile
# Czytaj ten plik na początku każdej sesji. To zastępuje pamięć między sesjami i kontami.

## Kim jest właściciel projektu

Właściciel projektu nie jest programistą — pisze kod przez Claude Code w terminalu.
- Rozumie projekt na poziomie koncepcji i wyników (metryki, bugi, flow), nie składni kodu
- Nie zna angielskiego — działaj autonomicznie, nie pytaj o każdy krok
- Nie lubi pochlebstw i nadmiernej struktury — odpowiadaj rzeczowo i zwięźle
- Preferuje małe kroki: jedno zadanie → test → wynik → następne

**Terminologia:**
- "Claude" = Claude Code w terminalu
- "Sonet" = osobna sesja na claude.ai w przeglądarce
- "Mapa" = mapa architektury programu (.md), nie struktura danych
- "Cursor" = AI IDE używane do złożonej diagnozy wieloplikowej

**Przerywaj i pytaj TYLKO gdy potrzebna jest decyzja właściciela:** zmiana architektury, kwestie prawne, Google Play, coś co usuwa dane nieodwracalnie. Resztę rób sam.

---

## Zasady pracy

**Brief:** Zawsze czytaj brief na początku sesji jeśli właściciel o to prosi — to główne źródło prawdy. Gdy daje plik do przeczytania — przeczytaj go w całości, potem raportuj: ile testów, recall/precision, pierwsze zadanie.

**Testy:** Uruchamia właściciel, nie Claude. Po każdej zmianie kodu powiedz: "Odpal [NazwaTestu] — powinny być zielone." ZAKAZ uruchamiania gradlew samodzielnie.

**Benchmark:** Podział pracy — Claude robi build i install, właściciel uruchamia bat i wkleja wyniki. NIE wywołuj `run_benchmark.bat` przez terminal. Commit po benchmarku jest automatyczny (hook).

**Build:** `.\gradlew :app:installDebug :app:installDebugAndroidTest` (JAVA_HOME: `C:\Program Files\Android\Android Studio\jbr`)

**Commitowanie:** Po każdej naprawie zamkniętym krokiem. Commit przed zmianą tylko przy dużych reorganizacjach strukturalnych. Tylko Claude Code commituje — zawsze po sprawdzeniu poprawek Cursora.

**Testy najpierw failują:** Fail na nowym teście dowodzi że bug istnieje. Nie pisz reguły i testów jednocześnie.

**Oszczędzaj kontekst:** Duże pliki czytaj przez Grep, nie Read w całości. Przed sekwencją Read na wielu plikach — puść agenta zamiast czytać wszystko sam.

**Pliki molochów:** Powyżej ~600 linii zaproponuj wydzielenie. Granica: osobna odpowiedzialność.

**NLP/text-processing:** Przed własnym rozwiązaniem — wyślij agenta do researchu. Ktoś już to rozwiązał.

**Zasada projektu:** Silnik MASKUJE encje, nie POPRAWIA dokumentu. Nie proponuj fuzzy masking ani near-match.

**Cursor do diagnozy — playbook (zwalidowany 08.07):** Podział sprawdza się w praktyce, trzymaj się go bez pytania za każdym razem.
- **Sięgaj po Cursora sam, bez pytania**, gdy: problem jest rozsiany po wielu plikach (np. "coś nie działa, ale regex sam w sobie jest poprawny"), potrzeba historii gita/porównania commitów żeby znaleźć winowajcę regresji, albo utknąłeś w martwym punkcie technicznym (np. próba bezpośredniego uruchomienia skompilowanych klas zamiast czekać na test właściciela).
- **Pchaj dalej sam, bez Cursora**, gdy: problem jest punktowy (jeden plik, jeden regex), masz szybki cykl weryfikacji (Java przed Kotlinem), albo to prosta naprawa/rozszerzenie istniejącej reguły.
- Cursor dostarcza diagnozę → Claude wdraża i commituje. Zadania dla Cursora pisz wprost w czacie (kopiowalny blok), nie tylko w TODO.md.
- Właściciel konsultuje/koordynuje między Claude i Cursor — to on decyduje które info od kogo ma wagę, nie zgaduj tego za niego.

**Nowe pliki/moduły:** Nie twórz nowego pliku ani nie dodawaj zależności bez konieczności. Jeśli masz wybór między rozszerzeniem istniejącego pliku a nowym — wybierz istniejący. Nowy plik tylko gdy: (a) przekracza granicę odpowiedzialności (patrz "Pliki molochów"), (b) byłby importowany z wielu innych plików, lub (c) właściciel wprost prosił.

**JEDEN WŁAŚCICIEL NA ENCJĘ:** Przed dodaniem wzorca dla encji (KWOTA/OSOBA/ADRES/NUMER) — sprawdź mapę architektury (`memory/project_architecture.md`). Jeśli wzorzec już istnieje w innej warstwie — napraw go zamiast dodawać nowy. Sesja 30.06: Claude dodawał kolejne wzorce KWOTA i OSOBA do kolejnych warstw zamiast naprawić istniejące → warstwy zaczęły zawłaszczać tokeny nawzajem → tokeny fragmentowane, ogony, zagnieżdżenia. Paweł musiał testować każdy problem osobno na telefonie bez jasnej drogi powrotu.

**Zmiana architektury = pytanie właściciela NAJPIERW.** Nie "naprawiam po cichu" rozgraniczenia warstw. To jest decyzja właściciela.

**Ogólna reguła, nie przykład:** przy naprawie regexu/listy pod OCR lub kolizję słownika — szukaj wspólnego kształtu (wildcard, klasa znaków, próg), nie dopisuj pojedynczych znaków/słów z bieżącego testu. Wyjątek: raz ustalony, kompletny, udokumentowany zamknięty zbiór (np. D-class cyfropodobnych `[0-9OolISBZ]`) nie jest tym błędem — enumeracja PO KAŻDYM teście jest. Uwaga na kamuflaż: "systemowa" naprawa, która w praktyce wpisuje przykłady ze skargi do listy wyjątków, to ten sam błąd z inną etykietą — sprawdź czy naprawa faktycznie znajduje wszystkie przypadki (np. Morfeusz2 na całym słowniku), nie tylko zgłoszone. Zweryfikuj fix na przykładzie SPOZA zbioru który go motywował, zanim uznasz go za ogólny.

**Jedno miejsce, nie rozproszone łaty:** gdy ta sama klasa buga może wystąpić w wielu regexach/miejscach, nie dopisuj tego samego guardu do każdego z osobna. Znajdź jedno miejsce wyżej w potoku (silniejsza/wcześniejsza reguła), które przechwytuje całość, zanim któreś z N miejsc dostanie szansę. Jeśli łapiesz się w pętli "napraw → wciąż bug → kolejne miejsce" — przerwij i szukaj wspólnego źródła.

**Bug "silnik źle maskuje słowo" = zwykle jakość słownika, nie kod:** przed pisaniem kolejnego filtra sprawdź dane źródłowe (Morfeusz2: czy cała lista odmian danego klucza faktycznie pasuje do właściwej części mowy). Czyszczenie danych u źródła działa trwale; filtry w kodzie tylko przenoszą problem gdzie indziej.

**Nie edytuj testu żeby przeszedł:** zmiana w samym teście uzasadniona TYLKO gdy przyczyna faktycznie leży w teście (zły fixture, zła asercja) — zawsze nazwij tę przyczynę wprost. W każdym innym przypadku napraw kod produkcyjny, nie test.

**Prawdziwy test = telefon, nie benchmark:** ręczna inspekcja `testy/*.txt` na telefonie jest prawdą ostateczną. Benchmark bywa "chorym termometrem" (błędny scoring, zbyt gruboziarniste etykiety FP) — gdy metryka wygląda źle mimo poprawnego kodu, podejrzewaj najpierw sam pomiar, nie silnik. Kolejność: potwierdź bug na telefonie PRZED diagnozowaniem (nie tylko przed fixem) — gdy benchmark/Guard pokazuje coś nowego, zapytaj czy Paweł może sprawdzić, zanim zaczniesz analizować logi i wyciągać wnioski (nawet wstępne, nawet "to na pewno nie bug").

**Pliki `testy/*.txt`:** wyłącznie tekst do zamaskowania, zero nagłówków/komentarzy w środku — appka przetwarza cały plik jako dokument. Kontekst/cel testu przekazuj w czacie, nie w pliku.

**Zadania dla Cursora:** zawsze wklej czytelnym blokiem wprost w czacie, nie tylko zapisz w TODO.md.

**Kolizja instalacja/benchmark:** nie instaluj/reinstaluj APK gdy jest ryzyko że Paweł w tym samym momencie odpala test na telefonie. Symptom: zero wyników (nie częściowych) bez FATAL EXCEPTION w logcacie = kolizja czasowa, nie bug silnika.

**Guard YELLOW to świadomy kompromis:** audit-only warstwa toleruje fałszywe alarmy (recall > precyzja tam gdzie i tak jest ludzka ocena na końcu) — nie proponuj samodzielnie "poprawek czułości" bez sygnału Pawła.

**Drobne bugi znalezione przy okazji — napraw od razu, bez pytania.** Nie dotyczy architektury, nowych silników, ani niejednoznacznych decyzji produktowych (np. "czy to w ogóle powinno się maskować" to decyzja produktowa, nie bug).

**Tempo i zakres sesji ustala Claude, nie Paweł** — nawet gdy ma czas i naciska na kontynuację. Nie pytaj "zamykamy?" bez konkretnego, nazwanego powodu ryzyka (np. "5 zależnych zmian w tym samym pliku, żadna nie testowana osobno na telefonie").

**Kompromisy implementacyjne:** przy uproszczeniu (Twoim albo Pawła) przedstaw konkretny scenariusz awarii, nie samo "tak"/"nie". Decyzja zostaje po jego stronie, ale ma być świadoma kosztu.

**Pomysły poboczne, o które nikt nie prosił:** zgłoś jednym zdaniem, nie pisz sam bez pytania.

**Powtarzalny krok migracji raz zatwierdzony przez właściciela** (np. wyłączanie starego mechanizmu po migracji encji do docelowej warstwy) — stosuj przy każdej kolejnej encji bez pytania osobno za każdym razem.

**StructuralEngine.kt to moloch (>600 linii)** — przy wyborze dopisać-czy-nowy-plik domyślnie wybieraj nowy plik silnika (wzorzec: `AddressEngine.kt`).

**Diagnoza routingu obrazów/klasyfikacji:** proponuj `adb logcat` przed jakimikolwiek zmianami kodu. Domyślny kierunek klasyfikatora binarnego: obciążenie dowodem leży po stronie rzadszego/wyjątkowego przypadku, nie po stronie reguły.

---

## Zasady AnchorEngine (kotwice)

**Kotwica = jawny sygnał (słowo-klucz, znak, sufiks, kontekst), nigdy sam kształt tekstu.** "WIELKIE_SŁOWO+cyfry" bez sygnału nie jest sprawą AnchorEngine — to warstwa strukturalna (tam decyduje się czy kształt wystarcza). Jedyny wyjątek bez kotwicy: PESEL z poprawną sumą kontrolną.

**Zasięg dopasowania po złapaniu kotwicy zatrzymuje się na granicy klasy znaku** (spacja / koniec ciągu liter-cyfr), nigdy na arbitralnym limicie znaków.

**Identyfikatory (PESEL/NIP/FAKTURA/IBAN): zero sumy kontrolnej jako warunku maskowania.** Widząc kotwicę, AnchorEngine zgarnia cały ciąg cyfropodobny w prawo do końca. Suma kontrolna może wpływać na confidence później, nigdy na decyzję maskować/nie.

**Kotwice luźne działają WYŁĄCZNIE na resztkach, po precyzyjnych regułach strukturalnych** — nigdy na początku potoku, niezależnie w jakim pliku mieszkają (cecha reguły, nie pliku).

**Przed patchowaniem OcrNormalizer/StructuralEngine per przykład — sprawdź obliczeniowo (Python) czy AnchorEngine już łapie to jako fallback.** Jeśli tak, problem nie jest tam gdzie się wydawał. Sam test regexu w izolacji nie wystarcza — sprawdź też czy wcześniejsza warstwa (kontekstowy wzorzec z elastyczną klasą) nie skonsumowała kotwicy częściowo, zanim AnchorEngine w ogóle dostał tekst.

**Nie lekceważ przeniesienia CAŁEJ encji ze StructuralEngine (walidacja kształtu) do AnchorEngine (kotwica, zero walidacji) jako "zbyt dużej zmiany"** — to sprawdzony, tani do przetestowania wzorzec (odwracalny przez zakomentowanie starego wzorca), nie jednorazowe ryzyko architektoniczne. Rozważ to, gdy encja dostaje trzecią/czwartą łatę pod rząd w StructuralEngine.

**Techniczne pułapki regexów kotwicowych** (possessive quantifiers, letter-guardy, okna nakładania tokenów) — sprawdź `memory/lessons_anchor_regex_pitfalls.md` przed pisaniem/migracją reguł A.x.

**Pełna specyfikacja kotwic** (matryca reguł A.1–A.12, 7 zakazów) — `AnchorEngine_brief_v2.docx` w repo / `memory/feedback_anchorengine_spec_violation.md`.

---

## Stan projektu Mobile (stan: 30.06.2026 wieczór)

**Projekt:** `C:\Projects\LynxMask\`
**Urządzenie:** Android 16, Samsung SM-A536B

**Testy:** ~377 testów, 0 FAILED (sesja 30.06 wieczór). Odpalić po każdej zmianie silnika.

**Benchmark fresh (30.06 19:38):** Recall 94,1% | Krytyczny 100,0% | ADRES 98,2% | 0 BUG_SILNIKA ✓
**Benchmark stały (30.06 19:39):** Recall 89,6% ⚠ (<90% próg!) | Krytyczny 98,0% | ADRES 87,3% | 0 BUG_SILNIKA
**Regres NUMER stały: -0,9% (3 encje) — pre-existing, był przed tą sesją (po fix sklejań). ADRES bez regresu.**

**Gałąź aktywna:** `feature/anchor-engine` (nie mergować na main bez benchmarku i testów na telefonie)

**Zamknięte w sesji 30.06 wieczór (commity 7c39eff, ec20056, dcdeb36, 970fb07):**
- ✅ BUG-ADRES-OGONY: matchOverlapsToken w UserDictionary (W4a) i applyAll (W4b AnchorEngine)
- ✅ BUG-KWOTA-OOO: D-class w A.9/A.9b (O/0 interchangeable w tysiącach i groszach)
- ✅ BUG-OCR-UL-KROPKA: OcrNormalizer krok 8c ("ul .Nazwa" → "ul.Nazwa")
- ✅ BUG-ADRES-PODWOJON (częściowo): CITY_POSTAL_REGEX → jeden token dla "Miasto, Kod"

**Otwarte bugi silnika:**
- 🔲 BUG-STALY-NUMER: stały recall 89,6% < 90% — 3 brakujące NUMER. Sprawdź czy matchOverlapsToken w applyAll blokuje NIP/telefon sąsiadujące z ADRES/OSOBA. Porównaj bugs stały 30.06 vs 25.06.
- 🔲 BUG-FP-ULICE-IMIENNE: "Jana Pawła II" → OSOBA, "Zielona Góra" → OSOBA w kontekście adresu. NameEngine nie ma wyłączenia dla ulic patronów. Niski priorytet (PII zakryte).
- 🔲 BUG-ADRES-BRAK-PREFIKS: "Marszałkowska 15/3" bez "ul." → nie maskowane (sufit OCR dla większości przypadków)
- 🔲 BUG-ADRES-MYSLNIK: "ul. Gdańska-Sopocka 3/1" → myślnik łamie wzorzec nazwy ulicy

**Start następnej sesji: BUG-STALY-NUMER** — porównaj `benchmark_results/stały/2026-06-30_1939/benchmark_bugs.txt` z `benchmark_results/stały/2026-06-25_1225/benchmark_bugs.txt`. Znajdź 3 encje NUMER które były wykryte 25.06 a nie są 30.06. Sprawdź czy matchOverlapsToken jest za szeroki.

**Plan refaktoru ADRES: patrz memory/refactor_anchor_engine.md — NIE robić przy otwartych bugach.**

**Kolejka bugów → patrz TODO.md (jedyne źródło prawdy)**

Otwarte blokery release:
- 🔲 R4 — testy kamerą (Paweł, checklist 6 pkt w TODO.md)
- 🔲 R5 — treść ToS + Privacy Policy od prawnika (zapytanie wysłane 28.06)
- 🔲 GP1–GP6 — Google Play: keystore + konto + .aab (screenshoty gotowe w Google_Play/)

Inne otwarte bugi techniczne:
- 🔲 BUG-WARMSTART-CLEAR — onNewIntent brak LynxPendingShare.clear() dla ACTION_MAIN (niski priorytet)

---

## Stan projektu Desktop (stan: 25.06.2026)

**Projekt:** `C:\Users\[user]\Desktop\pseudominizer\`
**Stack:** FastAPI (port 8765) + Tauri v2 + React/TypeScript

**Ostatnie wyniki (Run H, 14.06):** CLR 3,2% | Recall 91,4% | Precision 70,4% | OSOBA recall 96,3%

**Otwarte bugi:**
- BUG-10: "Dodaj i zakryj" HTTP 401 — frontend nie wysyła X-Api-Token do /profile/add-entity
- BUG-UI-TOKEN-SKLEJANIE: token skleja się z następnym słowem (estetyczny)
- BUG-ADDR-FP: ADRES precision 68,3%, FP z multilinii OCR
- BUG-3: Token injection — użytkownik może odtworzyć dane

**Wzorzec słowników (z Mobile, 21.06):**
- UserDictionary (słownik A) = wartości do maskowania, permanentne
- GuardAllowlist (słownik B) = wartości Guard ma pomijać, permanentne
- Oba słowniki MUSZĄ być osobne. Desktop przyjmuje wzorzec z Mobile — nie wymyśla od nowa.

---

## Express Mode — koncepcja (do implementacji w przyszłości)

Darmowa wersja bez logowania, biblioteki sesji i depseudonimizacji.
Flow: otwórz plik → pseudonimizuj → kopiuj wynik.
Jeśli brak RED/YELLOW — tylko niezbędne okna.
Cel: pierwsze doświadczenie z LynxMask przed pełną wersją.

Roadmapa desktop: CLI do automatycznego maskowania dużych zasobów + asystent na bazie Bielika.

---

## Źródła prawdy w projekcie

- `TODO.md` — jedyne źródło prawdy co jest otwarte (blokery, GP, niskie)
- `MASTER_LynxMask_Mobile.md` — biblia projektu Mobile, sekcje 22–24 najświeższy stan
- `MASTER_LynxMask_Desktop.md` — biblia projektu Desktop
- `BACKLOG.md` — aktywna kolejka zadań Mobile
- `TODO_silnik.md` — backup bieżących problemów silnika
- `edge_cases_do_slownika.txt` — edge cases z testów do ręcznego dodania do słownika (nie opłaca się pisać reguły)

**Zasada:** nie tworzyć briefów w katalogu głównym — wszystko idzie do MASTER lub BACKLOG.
