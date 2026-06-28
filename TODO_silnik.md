# TODO — silnik detekcji LynxMask Mobile (stan: 28.06.2026)

## Następna sesja (priorytet po release)

1. ~~**BUG-PESEL1**~~ — ✅ NAPRAWIONE 19.06 (OCR_PESEL_WORD)

2. ~~**BUG-EMAIL-TLD1**~~ — ✅ NAPRAWIONE — `([a-zA-Z0-9]{2,4})\b` już w OCR_EMAIL_TLDSPACE (OcrNormalizer.kt linia 251)

3. ~~**OCR_EMAIL_LOCALSPACE — tylko jedna spacja**~~ — ✅ NAPRAWIONE — pętla do-while w replace (OcrNormalizer.kt ~linia 688)

4. ~~**S11 — email z imieniem w local-part**~~ — ✅ ZAMKNIĘTE strukturalnie — EMAIL wyprzedza NameEngine, testy w PseudonymEngineTest.kt

5. **BUG-NIP-SPLIT** — NIP naprawiony częściowo → silnik łapie fragmenty osobno jako dwa NUMER
   - Zbadać w bugs.txt sekcja NUMER które NIPy są rozbite
   - Sprawdzić trace dla dokumentów z krytycznym brakiem NUMER

6. **ADRES recall ~68%** — przejrzeć bugs.txt sekcja [ADRES POMINIĘTE]

7. **Zdjęcia z aparatu** — zebrać realne artefakty OCR z różnych typów dokumentów → analiza → nowe reguły OcrNormalizera

---

## Otwarte bugi OcrNormalizer

### BUG-NIP-SPLIT
- Wejście: `NIP: I42-I99-O6-38` (OCR artefakty)
- OCR_NIP_DIGITS naprawia częściowo → `142-199-O6-38` lub podobnie
- StructuralEngine łapie `142-199` jako NUMER_007 i `O6-38` jako NUMER_008
- Rozwiązanie: zbadać dokładnie który fragment nie jest naprawiany

### IBAN rozbity przez newline
- Obecna reguła obsługuje spacje w IBAN ale nie przejście do nowej linii
- Przykład: "PL61 1020 1026\n0000 0422 7020\n1111" nie jest sklejane
- Rozwiązanie: reguła OCR_IBAN_NEWLINE w OcrNormalizerze

### KNOWN_CITY_FORMS nie obsługuje miast bez ogonków
- "Bialystok", "Lodz", "Krakow" nie trafią w listę miast
- Rozwiązanie: fold() przy sprawdzaniu kandydata w KNOWN_CITY_FORMS

### Diagnostyka benchmark — EMAIL fałszywy BRAK_W_OCR
- doc_00072: `krzysztof nowakowski@onet pl` → normalizer daje podkreślnik zamiast kropki
- Benchmark nie liczy bo szuka kropki — recall EMAIL w rzeczywistości prawdopodobnie wyższy niż mierzy bat

---

## Kolejka do wdrożenia (stan 2026-06-20, nie weryfikowane od tego czasu)

### Z research agenta 19.06 (rozwiązania z internetu — zweryfikowane)

**RESEARCH-1 — Walidacja sumy kontrolnej PESEL/NIP** 🔲
- Plik: StructuralEngine.kt
- PESEL wagi: 1,3,7,9,1,3,7,9,1,3 — wynik mod 10 == ostatnia cyfra
- NIP wagi: 6,5,7,2,3,4,5,6,7 — wynik mod 11 == ostatnia cyfra

**RESEARCH-2 — De-leet dla imion/nazwisk** 🔲
- Plik: OcrNormalizer.kt (nowy krok przed NameEngine)
- Mapowanie: 3→e, 4→a, 5→s, 0→o, 1→i — TYLKO tokeny zaczynające się wielką literą
- Wymaga decyzji: dodać zależność czy zaimplementować samodzielnie

**RESEARCH-3 — ML Kit confidence + dwupoziomowe progi** 🔲
- Plik: ShareTargetActivity.kt + OcrNormalizer.kt (assessQuality)
- getConfidence() per Symbol/Element/Line dostępne w ML Kit
- Progi: avg < 0.7 → YELLOW, avg < 0.5 → RED

### Z analizy kodu (bugi zidentyfikowane)

**BUG-DATE-PARTIAL** 🔲 — StructuralEngine.kt
- 2026-06-20 → NUMER_062-20 (zamaskowane rok-miesiąc, zostaje -20)

**BUG-FP-REFNUM** 🔲 — StructuralEngine.kt
- UZ/2026/0088, FV/2026/000088, I C 234/26 → NUMER (false positive)

---

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
