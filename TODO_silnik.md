# TODO — silnik detekcji LynxMask Mobile

## Następna sesja (priorytet)

1. ~~**BUG-PESEL1**~~ — ✅ NAPRAWIONE 19.06 (OCR_PESEL_WORD)

2. **BUG-EMAIL-TLD1** — `@wp p1` — TLD z cyfrą nie naprawiane
   - Fix: zmienić `([a-zA-Z]{2,4})\b` → `([a-zA-Z0-9]{2,4})\b` w OCR_EMAIL_TLDSPACE (OcrNormalizer.kt linia 159)
   - Test: dodać przypadek `bartosz@prawnik.p1`

3. **BUG-NIP-SPLIT** — NIP naprawiony częściowo → silnik łapie fragmenty osobno jako dwa NUMER
   - Zbadać w bugs.txt sekcja NUMER które NIPy są rozbite
   - Sprawdzić trace dla dokumentów z krytycznym brakiem NUMER

4. **ADRES recall ~68%** — przejrzeć bugs.txt sekcja [ADRES POMINIĘTE]

5. **Nowy dataset testowy** — wygenerować dokumenty z wariantami OCR które naprawiliśmy (PESE1, PE5EL, UI.Nazwa, NlP, emaile ze spacją) — benchmark nie mierzy nowych reguł

6. **Zdjęcia z aparatu** — zebrać realne artefakty OCR z różnych typów dokumentów → analiza → nowe reguły OcrNormalizera

7. **Dataset overfitting** — obecny benchmark mierzy tylko znane dokumenty, nie wykrywa nowych luk w silniku

---

## EMAIL — edge case

- "email zleceniobiorcy: joanna.grabowska@interia.pl" → silnik maskuje "joanna" jako OSOBA zamiast całego emaila
- Wzorzec z kontekstem "e-mail:" (pozycja 1) może wchodzić w konflikt z NameEngine gdy lokalną część emaila stanowi imię
- Do zbadania: czy wzorzec pozycji 1 matchuje fragment przed @ i oddaje go NameEngine

## Diagnostyka benchmark — EMAIL fałszywy BRAK_W_OCR

- doc_00072: krzysztof.nowakowski@onet.pl — OCR daje "krzysztof nowakowski@onet pl"
- OcrNormalizer naprawia → krzysztof_nowakowski@onet.pl (podkreślnik zamiast kropki)
- Silnik maskuje email poprawnie, ale benchmark nie liczy bo szuka kropki nie podkreślnika
- Rozwiązanie: normalize() w analyze() powinien ignorować różnicę . vs _ w local-part emaila
- EMAIL recall w rzeczywistości prawdopodobnie 100% (6/6), benchmark pokazuje 83,3%
- CZĘŚCIOWO NAPRAWIONE: analyze() teraz używa normalizedText → BRAK_W_OCR poprawne; problem . vs _ pozostaje

## OcrNormalizer — otwarte bugi

### ~~BUG-PESEL1~~ — ✅ NAPRAWIONE 19.06
- OCR_PESEL_WORD zastąpił OCR_PESEL_DIGITS — obsługuje PESE1, PE5EL, P E S E L, PEB3L
- 9 testów jednostkowych, 0 FAILED

### BUG-NIP-SPLIT
- Wejście: `NIP: I42-I99-O6-38` (OCR artefakty)
- OCR_NIP_DIGITS naprawia częściowo → `142-199-O6-38` lub podobnie
- StructuralEngine łapie `142-199` jako NUMER_007 i `O6-38` jako NUMER_008
- Przyczyna: NIP naprawiony tylko do połowy, reszta nie przechodzi wzorca NIP w silniku
- Rozwiązanie: zbadać dokładnie który fragment nie jest naprawiany

### BUG-EMAIL-TLD1
- Wejście: `bartosz.jablowski@prawnik.p1` (OCR: l→1 w TLD)
- OCR_EMAIL_TLDSPACE szuka `([a-zA-Z]{2,4})\b` — nie matchuje `p1` bo zawiera cyfrę
- Rozwiązanie: `([a-zA-Z0-9]{2,4})\b`

## ŚRODOWISKO — wymagane przed każdą sesją w CMD

Przed uruchomieniem gradlew w CMD ustaw JAVA_HOME:
```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
```
Bez tego: "ERROR: JAVA_HOME is not set"

**Przed benchmarkiem po zmianie kodu — wymagany reinstall:**
```
.\gradlew :app:installDebug
.\gradlew :app:installDebugAndroidTest
run_benchmark.bat
```

---

## Otwarte bugi OcrNormalizer — z analizy zewnętrznej (19.06)

### OCR_EMAIL_LOCALSPACE — tylko jedna spacja
- Obecna reguła naprawia tylko jedną spację w local-part emaila
- "jan adam kowalski@wp.pl" → naprawiane tylko częściowo ("jan_adam kowalski@wp.pl")
- Rozwiązanie: replace w pętli aż brak zmian, lub wzorzec na wiele segmentów

### IBAN rozbity przez newline
- Obecna reguła obsługuje spacje w IBAN ale nie przejście do nowej linii
- Przykład: "PL61 1020 1026\n0000 0422 7020\n1111" nie jest sklejane
- Rozwiązanie: reguła OCR_IBAN_NEWLINE w OcrNormalizerze — skleja IBAN rozbity przez \n

### Wersja w nagłówku OcrNormalizer.kt nieaktualna
- Nagłówek mówi v1.3, rzeczywista wersja to v1.6
- Zaktualizować nagłówek i listę zmian

### KNOWN_CITY_FORMS nie obsługuje miast bez ogonków
- "Bialystok", "Lodz", "Krakow" nie trafią w listę miast
- Rozwiązanie: przy sprawdzaniu w KNOWN_CITY_FORMS stosować fold() (usunięcie ogonków) dla kandydata

---

## Kolejka do wdrożenia (stan 2026-06-20)

### Z research agenta 19.06 (rozwiązania z internetu — zweryfikowane)

**RESEARCH-1 — Walidacja sumy kontrolnej PESEL/NIP** 🔲
- Plik: StructuralEngine.kt
- PESEL wagi: 1,3,7,9,1,3,7,9,1,3 — wynik mod 10 == ostatnia cyfra
- NIP wagi: 6,5,7,2,3,4,5,6,7 — wynik mod 11 == ostatnia cyfra
- Po korekcie l→1 sprawdź sumę — jeśli OK, korekta pewna w ~100%

**RESEARCH-2 — De-leet dla imion/nazwisk** 🔲
- Plik: OcrNormalizer.kt (nowy krok przed NameEngine)
- Mapowanie: 3→e, 4→a, 5→s, 0→o, 1→i — TYLKO tokeny zaczynające się wielką literą
- Jaro-Winkler fuzzy matching: biblioteka string-similarity-kotlin (Kotlin Multiplatform, MIT)
- Wymaga decyzji: dodać zależność czy zaimplementować samodzielnie

**RESEARCH-3 — ML Kit confidence + dwupoziomowe progi** 🔲
- Plik: ShareTargetActivity.kt (podłączyć) + OcrNormalizer.kt (assessQuality)
- getConfidence() per Symbol/Element/Line dostępne w ML Kit
- Progi: avg < 0.7 → YELLOW, avg < 0.5 → RED
- Caveat: GPS < 22.30 zwraca 0.0f — sprawdzać > 0 przed użyciem
- Aktualnie mlKitConfidence zawsze null (linie 287 i 434 ShareTargetActivity)

### Z analizy kodu (bugi zidentyfikowane)

**BUG-EMAIL-TOKEN** 🔲 — StructuralEngine.kt
- EMAIL wykrywany jako NUMER zamiast EMAIL

**BUG-DATE-PARTIAL** 🔲 — StructuralEngine.kt
- 2026-06-20 → NUMER_062-20 (zamaskowane rok-miesiąc, zostaje -20)

**BUG-FP-REFNUM** 🔲 — StructuralEngine.kt
- UZ/2026/0088, FV/2026/000088, I C 234/26 → NUMER (false positive)

**BUG-TEL-PREFIX** 🔲 — StructuralEngine.kt
- (22) 765-43-21 → (22) NUMER (prefix pominięty)

**Fixed dataset** 🔲 — run_benchmark.bat
- Cel: porównywalne wyniki między runami (teraz fresh dataset)
