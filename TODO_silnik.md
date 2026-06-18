# TODO — silnik detekcji OSOBA

## Następna sesja
- ADRES recall 65.4% — kolejny cel po OSOBA
- EMAIL recall 50% — mały dataset (6 sztuk), sprawdzić które przypadki przepadają
- Dodać logowanie per-encja dla ADRES i EMAIL w benchmarku
- Odkrywanie tokenów przez użytkownika — fundament samouczenia (Mobile i Desktop)

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

## ŚRODOWISKO — wymagane przed każdą sesją w CMD
Przed uruchomieniem gradlew w CMD ustaw JAVA_HOME:
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
Bez tego: "ERROR: JAVA_HOME is not set"
