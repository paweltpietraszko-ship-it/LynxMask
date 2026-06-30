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

**Cursor do diagnozy:** Gdy problem rozsiana po wielu plikach — zaproponuj użycie Cursora. Cursor dostarcza diagnozę → Claude wdraża i commituje.

**Nowe pliki/moduły:** Nie twórz nowego pliku ani nie dodawaj zależności bez konieczności. Jeśli masz wybór między rozszerzeniem istniejącego pliku a nowym — wybierz istniejący. Nowy plik tylko gdy: (a) przekracza granicę odpowiedzialności (patrz "Pliki molochów"), (b) byłby importowany z wielu innych plików, lub (c) właściciel wprost prosił.

**JEDEN WŁAŚCICIEL NA ENCJĘ:** Przed dodaniem wzorca dla encji (KWOTA/OSOBA/ADRES/NUMER) — sprawdź mapę architektury (`memory/project_architecture.md`). Jeśli wzorzec już istnieje w innej warstwie — napraw go zamiast dodawać nowy. Sesja 30.06: Claude dodawał kolejne wzorce KWOTA i OSOBA do kolejnych warstw zamiast naprawić istniejące → warstwy zaczęły zawłaszczać tokeny nawzajem → tokeny fragmentowane, ogony, zagnieżdżenia. Paweł musiał testować każdy problem osobno na telefonie bez jasnej drogi powrotu.

**Zmiana architektury = pytanie właściciela NAJPIERW.** Nie "naprawiam po cichu" rozgraniczenia warstw. To jest decyzja właściciela.

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
