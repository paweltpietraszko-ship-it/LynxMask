# TODO — silnik detekcji LynxMask Mobile

## Następna sesja (priorytet)

1. **BUG-PESEL1** — `PESE1` (cyfra 1 zamiast L) — lookbehind nie matchuje
   - Fix: zmienić `(?<=PESEL\s{0,3}:?\s{0,3})` → `(?<=PESE[Ll1]\s{0,3}:?\s{0,3})` w OcrNormalizer.kt linia 189
   - Test: dodać przypadek `PESE1: T2030375656` do OcrNormalizerPeselTest.kt

2. **BUG-EMAIL-TLD1** — `@wp p1` — TLD z cyfrą nie naprawiane
   - Fix: zmienić `([a-zA-Z]{2,4})\b` → `([a-zA-Z0-9]{2,4})\b` w OCR_EMAIL_TLDSPACE (OcrNormalizer.kt linia 159)
   - Test: dodać przypadek `bartosz@prawnik.p1`

3. **BUG-NIP-SPLIT** — NIP naprawiony częściowo → silnik łapie fragmenty osobno jako dwa NUMER
   - Zbadać w bugs.txt sekcja NUMER które NIPy są rozbite
   - Sprawdzić trace dla dokumentów z krytycznym brakiem NUMER

4. **ADRES recall ~68%** — przejrzeć bugs.txt sekcja [ADRES POMINIĘTE]

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

### BUG-PESEL1
- Wejście: `PESE1: T2030375656` (OCR zamienił L na 1)
- Wynik: lookbehind `(?<=PESEL...)` nie matchuje bo szuka "PESEL" nie "PESE1"
- Numer nie naprawiony mimo reguły OCR_PESEL_DIGITS
- Rozwiązanie: `PESE[Ll1]` w lookbehind

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
