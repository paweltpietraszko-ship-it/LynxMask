# RAPORT — sesja wieczór 16.06.2026

## 1. Co zrobiono w tej sesji

- **OutputGuard v1.7** — analiza logiki flagowania słów z wielką literą; potwierdzono że OutputGuard nie wpływa na liczenie FP w benchmarku (działa read-only, nie modyfikuje tokenMap)
- **PseudonymResultPanel** — praca nad komponentem wynikowym
- **ShareTargetActivity** — praca nad obsługą udostępniania dokumentów do aplikacji

## 2. Co cofnięto

- **Wzorzec sygnatury komorniczej** (`StructuralEngine.kt` linia 278) — cofnięto rozszerzenie `[A-Z][A-Za-z]{0,2}` z powrotem do `[A-Z]{1,3}`; zmiana powodowała fałszywe dopasowania dla sygnatury komorniczej
- **streetForms prefix expansion** (`NameEngine.kt`) — cofnięto dodanie 8 wariantów prefiksów do `applyStreetLookup()`; zmiana niepotrzebna, użytkownik zażądał rewertu

## 3. Wynik końcowy benchmarku

| Metryka    | Wynik  |
|------------|--------|
| Recall     | 67.7%  |
| Precision  | 67.2%  |
| FP         | 144    |

## 4. Przyczyna 822 FP — wyjaśnienie i naprawa

**Przyczyna:** `UserDictionary` zawierał śmieciowe encje dodane podczas ręcznych testów aplikacji na telefonie. Benchmark wywoływał `UserDictionary.load(context)` na początku, co wczytywało te dane do silnika. Każda fraza ze słownika pasująca do tekstu OCR generowała token — a tokeny te nie miały odpowiedników w ground truth → fałszywe pozytywy.

**Naprawa:** Dodano `UserDictionary.clear(context)` w `BenchmarkInstrumentedTest.kt` przed pętlą po dokumentach. Gwarantuje to powtarzalność benchmarku niezależnie od stanu słownika na urządzeniu.

```kotlin
UserDictionary.clear(context)
println("[BENCH_DEBUG] UserDictionary wyczyszczony przed benchmarkiem")
```

**Efekt:** Precision wzrosła z 25.0% (890 FP) → 67.2% (144 FP). Recall bez zmian.
