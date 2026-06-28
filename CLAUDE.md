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

---

## Stan projektu Mobile (stan: 25.06.2026)

**Projekt:** `C:\Projects\LynxMask\`
**Urządzenie:** Android 16, Samsung SM-A536B

**Wersje kluczowych plików:**
- OcrNormalizer: v2.6 | StructuralEngine: v2.3 | NameEngine: v1.12 | OutputGuard: v2.0
- ShareTargetActivity: v2.7 | SessionStore: v1.5 | LibraryScreen: v2.3
- UserDictionary: v1.4 | ImageRedactionPipeline/Screen: nowe (sesja 25.06)

**Testy:** ~373 testów, 0 FAILED, 3 skipped (stan 23.06)

**Benchmark stały (68 dok., baseline 21.06):** Recall 78,9% | Lvl0 95,2%
**Benchmark v2 (25.06):** Recall ogólny 90,5% | Recall krytyczny 98,0% | 0 BUG_SILNIKA

**Kolejka bugów:**
- 🔲 ImageRedact — cały tekst maskowany zamiast tylko PII (regresja, Cursor pracuje)
- 🔲 ImageRedact — blur twarzy niewidoczny (ramka zamiast pixelate)
- 🔲 Testy 6.2: JUnit SessionStore + Deanonymizer
- 🔲 S10 — TELEFON nie w tokenMap (wyciek PII)
- 🔲 AUD-M06 — security-crypto 1.1.0-alpha06 → 1.0.0
- 🔲 BUG-PESEL-OCR-SILNIK — wymaga OCR z telefonu (niskie)
- 🔲 Audyt RODO/security (Cursor)
- 🔲 Prawnik: ToS, Privacy Policy
- 🔲 Google Play: konto, screenshoty, opis

**Zamknięte w ostatnich sesjach:**
✅ IMAGE-REDACT F0/F1 — face detection + blur + ręczny prostokąt + OCR sugestia
✅ OCR email/adres tolerance, postal code space, NIP split, IBAN, PESEL variants
✅ UserDictionary eksport/import .lynxdict | EXIF rotation fix | benchmark v2

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

- `MASTER_LynxMask_Mobile.md` — biblia projektu Mobile, sekcje 22–24 najświeższy stan
- `MASTER_LynxMask_Desktop.md` — biblia projektu Desktop
- `BACKLOG.md` — aktywna kolejka zadań Mobile
- `TODO_silnik.md` — backup bieżących problemów silnika
- `edge_cases_do_slownika.txt` — edge cases z testów do ręcznego dodania do słownika (nie opłaca się pisać reguły)

**Zasada:** nie tworzyć briefów w katalogu głównym — wszystko idzie do MASTER lub BACKLOG.
