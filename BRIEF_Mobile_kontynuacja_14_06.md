# BRIEF DLA INSTANCJI WYKONAWCZEJ — LynxMask Mobile, kontynuacja
**Data:** 14.06.2026
**Projekt:** C:\Projects\LynxMask
**Testy:** .\gradlew :app:testDebugUnitTest
**Benchmark:** wymaga telefonu — powiedz Pawłowi żeby uruchomił

---

## 1. STAN NA WEJŚCIU — co zrobiono przed tą instancją

### Baseline benchmarku (przed sesją 14.06)
- Recall: 63.0%, Precision: 68.4%
- Krytyczne braki (CLR): 15, False positives: 96
- NUMER: 67.9%, OSOBA: 48.6%, ADRES: 61.9%, EMAIL: 60.0%
- 2 faile w testach (pre-existing): "konto bez prefiksu PL" i "adres z al."

### Zmiany wprowadzone w sesji 14.06
**StructuralEngine.kt** — EMAIL przeniesiony na pozycję 0 w STRUCTURAL_PATTERNS
(był na pozycji 16, po wzorcach NUMER — powodowało konsumowanie local part emaila przez wzorzec NUMER)

**NameEngine.kt v1.11** — dodano dwa filtry w 4 miejscach (HONORIFIC, NAME_FORWARD, NAME_BACKWARD, TITLE_PATTERN):
- Filtr długości: kandydat < 4 znaki → nie maskuj (eliminuje "SŁU", "pl", "nin")
- OSOBA_DENYLIST: słowa pospolite → nie maskuj
  ```kotlin
  val OSOBA_DENYLIST = setOf(
      "zamieszkania", "zameldowania", "służbowa", "służbowy",
      "wydział", "wydziału", "wydzialu", "wydziatu",
      "informacji", "informacj", "uzyskanych",
      "niejszej", "nin", "icznie",
      "sąd", "sądu", "rejonowy", "okręgowy",
      "urząd", "urzędu", "gminy", "gmina",
      "ulica", "ulicy", "adres", "adresu",
      "imię", "nazwisko", "pesel", "numer",
      "miejscowość", "miejscowości",
  )
  ```

**Wyniki testów po zmianach:** 91 PASS, 2 FAIL (te same pre-existing co przed)

### Ważne odkrycia z diagnostyki
- Wszystkie 15 krytycznych braków to OCR lvl=2/3 — pipeline ich nie widzi bo ML Kit nie wyekstrahował tekstu
- Typ "?" w benchmarku (54 encje, 68.5% recall) — klucze ground truth bez mapowania w typeMap
- benchDir zapisuje do chronionego folderu Android — zmienić na /storage/emulated/0/Documents/LynxMask/bench/ żeby adb pull działało bez problemów

---

## 2. NASTĘPNE ZADANIA — w tej kolejności

### ZADANIE A — napraw typeMap w benchmarku (15 minut)
54 encje klasyfikowane jako "?" bo typeMap nie ma ich kluczy z ground_truth.json.
Przeczytaj BenchmarkInstrumentedTest.kt, znajdź typeMap, porównaj z pełną listą 22 kluczy ground truth.
Dodaj brakujące mappingi. Uruchom benchmark — Recall może wzrosnąć o kilka pp samą tą zmianą.

### ZADANIE B — zmień benchDir na folder publiczny (5 minut)
W BenchmarkInstrumentedTest.kt zmień benchDir z:
`/sdcard/Android/data/com.lynxmask.app/files/bench`
na:
`/storage/emulated/0/Documents/LynxMask/bench`
Dodaj uprawnienie WRITE_EXTERNAL_STORAGE jeśli brakuje w manifeście dla testów.
Weryfikacja: `adb pull /storage/emulated/0/Documents/LynxMask/bench/benchmark_report.txt`

### ZADANIE C — PESEL checksum walidacja
Aktualny wzorzec `\b\d{11}\b` łapie wszystkie 11-cyfrowe ciągi, w tym nie-PESELe.
Dodaj walidację sumy kontrolnej jako osobną funkcję wywoływaną po dopasowaniu regex:
```kotlin
fun isValidPesel(pesel: String): Boolean {
    if (pesel.length != 11) return false
    val weights = intArrayOf(1, 3, 7, 9, 1, 3, 7, 9, 1, 3)
    val sum = weights.indices.sumOf { weights[it] * pesel[it].digitToInt() }
    val checkDigit = (10 - (sum % 10)) % 10
    return checkDigit == pesel[10].digitToInt()
}
```
Wzorzec PESEL w StructuralEngine powinien używać tej walidacji. Napisz test jednostkowy z 5 prawidłowymi i 3 nieprawidłowymi PESEL-ami.

### ZADANIE D — granice encji osobowych
NameEngine nie łapie:
- Dwa imiona: "Anna Maria Nowak" — po pierwszym imieniu nie sprawdza drugiego
- Myślnik w nazwisku: "Nowak-Kowalska" — rozbija na dwa tokeny
- Tytuły: "dr Marek Zieliński", "mgr Anna Kowalska"

Rozszerz logikę po wykryciu imienia:
- W prawo: sprawdź czy następny token to drugie imię (w LookupTables)
- Obsłuż myślnik: jeśli po nazwisku jest "-" a potem kolejne nazwisko — sklejaj
- Tytuły: lista `setOf("dr", "mgr", "inż", "prof", "lek", "adw", "r.pr", "pan", "pani")` jako kotwica przed imieniem

### ZADANIE E — pre-ekstrakcja NameEngine przed maskowaniem adresów
KRYTYCZNE dla OSOBA recall — identyczny problem jak Desktop przed Coverage-Fix.

Obecna kolejność warstw:
```
Warstwa 2: STRUCTURAL_PATTERNS  ← maskuje adresy
Warstwa 3: NameEngine           ← dostaje tekst BEZ adresów, traci kontekst ulicy
```

Efekt: "Jan Kowalski al. Niepodległości 161/20, 00-001 Warszawa" — po maskowaniu adresu
SpaCy/NameEngine nie widzi "al. Niepodległości" i nie rozpoznaje Jana Kowalskiego jako osoby.

Fix (analogiczny do Desktop pipeline_new.py):
1. Wyciągnij encje OSOBA z tekstu PRZED maskowaniem adresów (pre-ekstrakcja)
2. Zapisz w zmiennej lokalnej
3. Zamaskuj adresy i inne PII strukturalne
4. Zastosuj zapisane encje OSOBA

Uwaga: pre-ekstrakcja powinna działać na tekście PO maskowaniu PESEL/NIP/IBAN (żeby NameEngine nie widział wrażliwych liczb), ale PRZED adresami.

### ZADANIE F — benchmark po wszystkich zmianach
Powiedz Pawłowi żeby uruchomił benchmark przez Android Studio.
Pobierz wyniki: `adb pull /storage/emulated/0/Documents/LynxMask/bench/benchmark_report.txt`
Cel minimalny:
- Recall ogółem ≥ 75%
- OSOBA ≥ 65%
- CLR ≤ 10 (z 15)

---

## 3. CZEGO NIE RUSZAĆ

- BUG-AL-OPEN — adresy z "al." — odłożony, nie dotykać bez nowej strategii
- Logika kryptograficzna (AES-256-GCM, SQLCipher) — działa, nie dotykaj
- UI i nawigacja — poza zakresem tego potoku
- isMinifyEnabled — osobna sesja
- 2 pre-existing faile w testach — nie naprawiaj teraz, są zdiagnozowane

---

## 4. WERSJE PLIKÓW PO SESJI 14.06

| Plik | Wersja | Status |
|---|---|---|
| StructuralEngine.kt | v1.8 (EMAIL na poz. 0) | ✓ zmieniony dziś |
| NameEngine.kt | v1.11 (denylist + filtr długości) | ✓ zmieniony dziś |
| PseudonymEngine.kt | v2.2 | niezmieniony |
| BenchmarkInstrumentedTest.kt | bez wersji | wymaga zmian (Zadania A i B) |
| PseudonymEngineTest.kt | 91 testów, 2 skip | niezmieniony |

---

## 5. KONTEKST ARCHITEKTONICZNY

- Silnik: PseudonymEngine.kt orkiestruje 6 warstw (OcrNormalizer → UserDictionary → StructuralEngine → NameEngine → OutputGuard)
- TokenAllocator: brak centralnego — lokalny assignToken() wewnątrz pseudonymize(), reverseMap dla deduplication
- LookupTables: wymaga initializeForTesting() w testach JUnit (bez tego — degraded mode, 200 imion zamiast 1874)
- OCR: ML Kit Text Recognition v2, on-device
- Desktop jest referencją dla logiki — rozwiązania Desktop przenoś na Mobile

---

## 6. REGUŁY PRACY

- Test po każdym zadaniu przed przejściem dalej
- Benchmark uruchamia Paweł — powiedz mu kiedy gotowe
- Czytaj pliki przez Claude Code, nie proś Pawła o wklejanie
- Zadawaj Pawłowi tylko pytania decyzyjne
