# Czyszczenie słowników imion i nazwisk (z LynxMask Desktop, 2026-10-21)

Dla: implementator Mobile. Autor: CC Desktop na prośbę Pawła. **Nie podmieniać assetów bez benchmarku i testu na telefonie**
(zasada repo Mobile) — ten katalog to gotowe pliki i skrypt do oceny, nie zmiana aplikacji.

## Problem
`app/src/main/assets/names_inflected.json` (= `app/src/test/resources/names_inflected.json`) ma pod imionami formy innych słów:
Tom → być, jest, jestem, tym, tego, była, będę; Ala → ale; Kim → kto; Nico → nic; Mai → mają, mając; Inna → innym; Rana → rano;
Tania → taniej. Skutek: zwykłe słowa z wielkiej litery (zwłaszcza na początku zdania) zakrywane jako OSOBA przez samotne imię ze
słownika. Pomiar na prawdziwych zdaniach KPWr (CLARIN-PL, 75 dokumentów): Mobile 61 fałszywych OSOBA (Ale 11, Tym 7, Jest 6, Być 3,
Nic 3, Była 2…). Przyczyna w generatorze: formy brane z każdej interpretacji homografu bez sprawdzenia lematu i kwalifikatora
imię/nazwisko (Desktop `backend/generate_lookups_v3.py:145–160`; plik pochodzi z wcześniejszej wersji generatora).
`surnames_top1000.json` Mobile ma dodatkowo wadliwe formy (`kamińsk`, `kamińskiem`, `zielińsko`).

## Zawartość katalogu
- `clean_names.py` — skrypt (Python 3 + `morfeusz2`): `python clean_names.py <wej.json> <wyj.json> [raport.json] [--closed]`. Format pliku
  zachowany 1:1, usuwa tylko elementy list. Reguły w docstringu.
- `names_inflected.json` — wynik z `--closed` dla assetu Mobile (16 788 → 14 666 form, −2 122, 165 kluczy).
- `surnames_top1000.json` — wynik bez `--closed` (9 474 → 8 969 form, −505, 251 kluczy, głównie formy wadliwe).
- `names_inflected.report.json`, `surnames_top1000.report.json` — usunięte formy per klucz.
Oba wyniki odtwarzalne bajt w bajt z assetów `master` z 21.10.

## Kontrola i pomiar (Desktop CC, lokalnie)
- 0 strat prawdziwych form: wszystkie słowa osób gold (KPWr, `dataset_clean`, `pl-pii-synthetic-v1` PERSON; 306 obecnych w słowniku
  Mobile) są po czyszczeniu nadal w słowniku.
- Silnik Mobile (test JVM, `PseudonymEngine.pseudonymize`) na KPWr: fałszywe OSOBA 61 → 27; osoby gold bez zmian (126/359 — luka
  rzadkich nazwisk, patrz `WYTYCZNE_Z_DESKTOP_2026-10-21.md` pkt 1.1); `pl-pii` PERSON 2117/2242 i `dataset_clean` 140/140 bez zmian.
- Zostaje „Ale” (9): `LookupTables.kt:245` dokleja warianty ASCII przy ładowaniu, więc prawdziwy biernik „alę” daje „ale”. Do decyzji:
  nie składać ASCII dla form, które po złożeniu są zwykłym słowem, albo dopisać „ale” do `OSOBA_DENYLIST` (jak Desktop robi z
  potwierdzonymi kolizjami).

## Kroki dla Mobile
1. Skopiować oba JSON do `app/src/main/assets/` i `app/src/test/resources/`.
2. Testy JVM (`gradlew testDebugUnitTest`), benchmark i test na telefonie wg zasad repo.
3. Decyzja w sprawie „ale” (wyżej).
Desktop: ta sama zmiana jako `LYNX-NAMES-DICT-CLEAN` (repo LynxMask-Desktop; plik Desktop różni się od Mobile — 3620 vs 3609 kluczy).
