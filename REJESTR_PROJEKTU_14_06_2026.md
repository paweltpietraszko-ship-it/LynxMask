# REJESTR PROJEKTU — LynxMask Desktop + Mobile
## Data: 14.06.2026
## Źródła: sesja orchestratora 14.06.2026 (Coverage-Fix, Testy+Słownik, Mobile NER-fix start)

---

# CZĘŚĆ 1 — AKTYWNE BUGI DESKTOP

## WYSOKIE

### BUG-ADDR-FP
**Opis:** ADRES precision 68.3% (FP=20). FP generowane przez duplikaty OCR multilinii — ten sam adres łapany dwukrotnie (raz z prefiksem ul., raz samo kod+miasto z nowej linii). Miasta w duplikatach są poprawne (SIMC potwierdza) — problem strukturalny, nie wzorzec.
**Plik:** layers/address.py
**Fix:** deduplication po kodzie pocztowym — przed allocate() sprawdź czy reverse_map zawiera już wartość z tym samym kodem. Szkic: `postal = re.search(r'\d{2}-\d{3}', value); if postal and any(postal.group() in v for v in allocator.reverse_map.values()): continue`
**Potok:** Bezpieczeństwo

### BUG-PT-ADMIN-SIG
**Opis:** Sygnatura administracyjna PT.070433.2020 rozcięta — `PT.` zostaje w tekście jako plaintext, `070433.2020` trafia do NUMER. OCR na lvl=1 zamienia `PT.0` na `PTO` (kropka+zero = litera O) i ostatnią kropkę na spację. Wzorzec `_ADMIN_SIG_RE = r"\b[A-Z]{2}\.\d{6}\.\d{4}\b"` działa poprawnie na czystym wejściu — problem wyłącznie OCR.
**Plik:** layers/legal.py (wzorzec OK), ocr_engine.py (źródło)
**Potok:** OCR-Enhancement

### BUG-UI-TOKEN-SKLEJANIE
**Opis:** Token skleja się z następnym słowem bez spacji w UI — `ADRES_015aświadczenia`. Pipeline lokalnie zwraca poprawny wynik z spacją. Bug w warstwie renderowania wyników w frontend.
**Plik:** Pseudonimizuj.tsx lub pseudominizer_api.py (warstwa budowania tekstu wyjściowego)
**Potok:** UI-2

## ŚREDNIE

### BUG-3 (token injection)
**Opis:** Użytkownik wpisuje FIRMA_001 w tekście wejściowym i przez depseudonimizację odtwarza dane z mapy sesji.
**Plik:** anonymizer.py
**Potok:** Bezpieczeństwo

### BUG-2 (cudzysłowy typograficzne)
**Opis:** Firma w cudzysłowie „Wiśniewski i Wspólnicy" — SpaCy rozbija nazwę.
**Plik:** pipeline.py
**Potok:** Bezpieczeństwo

### BUG-4 (IBAN zagraniczny)
**Opis:** Guard nie wykrywa IBAN zagranicznych (nie-PL).
**Plik:** output_guard.py
**Potok:** Bezpieczeństwo

### BUG-7
**Opis:** check_blacklist_context() zdefiniowana ale nie wywoływana.
**Plik:** pipeline.py + spacy_ner.py
**Potok:** Bezpieczeństwo

### BUG-NEW-3
**Opis:** /archive nie weryfikuje guard_blocked przed zapisem.
**Plik:** pseudominizer_api.py
**Potok:** Bezpieczeństwo

### BUG-NEW-4
**Opis:** Walidacja profilu biura nie odrzuca słów pospolitych.
**Plik:** pseudominizer_api.py
**Potok:** Bezpieczeństwo

## NISKIE

### BUG-10 ("Dodaj i zakryj")
**Opis:** POST /profile/add-entity HTTP 401 — core feature niedziałający.
**Plik:** pseudominizer_api.py + Pseudonimizuj.tsx
**Potok:** UI-2

### BUG-5, BUG-6, BUG-8, BUG-12-PSE-REGISTRY
**Potok:** Bezpieczeństwo (niski priorytet) — bez zmian względem rejestru 13.06

---

# CZĘŚĆ 2 — PLANOWANE POTOKI DESKTOP

## Kolejka

| # | Status | Zakres | Zależy od |
|---|--------|--------|-----------|
| NER-fix | ✅ ZAMKNIĘTY | guard injection, wzorce PII, word boundary | — |
| Coverage-Fix | ✅ ZAMKNIĘTY | nowy pipeline, TokenAllocator, warstwy, SIMC | NER-fix |
| Testy+Słownik | ✅ ZAMKNIĘTY | testy przepisane, cities_forms.json, benchmark fix | Coverage-Fix |
| **UI-2** | ⏳ czeka | BUG-10, BUG-UI-TOKEN-SKLEJANIE | — |
| **Bezpieczeństwo** | ⏳ czeka | BUG-3, AUD-*, token injection, PBKDF2 | mapa architektury aktualna |
| **OCR-Enhancement** | 📋 planowany | preprocessing obrazu przed Tesseract, lvl=2/3 recall | Bezpieczeństwo |
| **Express-Mode** | 📋 planowany | tryb bez logowania, bez biblioteki, dane w RAM | UI-2 |
| **Anonimizacja-wizualna** | 📋 planowany | blur twarzy, tablice rej., EXIF/DOCX metadata | OCR-Enhancement |
| **Instalator+Dokumentacja** | 📋 na końcu | — | wszystkie potoki |

## Przed Bezpieczeństwem — WYMAGANE
Mapa architektury musi być zaktualizowana do stanu po Coverage-Fix przed startem Bezpieczeństwo. Obecna mapa (v2.1) odzwierciedla pre-Krytyczne wersje plików.

---

# CZĘŚĆ 3 — WERSJE PLIKÓW DESKTOP (stan 14.06.2026)

| Plik | Wersja | Uwagi |
|---|---|---|
| pipeline.py | v1.21 | USE_NEW_PIPELINE=True domyślnie |
| pipeline_core.py | v0.2+ | TokenAllocator, PipelineState, ConflictResolver |
| pipeline_new.py | v0.4 | pre-ekstrakcja NER przed address layer |
| layers/identity.py | v1.1 | NIP z kropką OCR ([-\s.]) |
| layers/numeric.py | v1.0 | NOWY — KL-, FV-/VAT/, UMW/ |
| layers/address.py | v1.5 | _CITY_FORMS z SIMC, _match_city() |
| layers/contact.py | v1.0 | email, telefon |
| layers/financial.py | v1.0 | IBAN, konta |
| layers/legal.py | v1.0 | sygnatury, KW |
| layers/ner_adapter.py | v1.1 | extract_ner_results() pre-ekstrakcja |
| layers/fallback.py | v1.0 | siatka 8+ cyfr |
| layers/validation.py | v1.0 | stdnum + phonenumbers |
| anonymizer.py | v4.25 | skip_guard parametr |
| ner_blocklist.py | v1.3 | 263 wpisów (+24 skróty urzędowe) |
| pseudominizer_api.py | v1.30-TAURI | guard [AUD-01] na poziomie API |
| benchmark.py | v1.2+ | fix HTTP 400 w accepted filter |
| cities.json | — | NOWY — 31117 miast z SIMC GUS |
| cities_forms.json | — | NOWY — 179370 form morfologicznych (Morfeusz2) |
| output_guard.py | v3.7 | bez zmian |
| ocr_engine.py | v1.4.5 | bez zmian |
| main.rs | v1.5 | bez zmian |
| Pseudonimizuj.tsx | v1.7 | bez zmian |

## Wyniki benchmarku (Run H — stan końcowy)
| Metryka | Stary pipeline (Run A) | Nowy pipeline (Run H) |
|---|---|---|
| CLR | 5.3% | **3.2%** |
| Recall | 90.8% | **91.4%** |
| Precision | 58.6% | **70.4%** |
| F1 | 71.2% | **78.9%** |
| OSOBA recall | 96.3% | **96.3%** |
| ADRES recall | 93.5% | **93.5%** |

---

# CZĘŚĆ 4 — AKTYWNE BUGI MOBILE

## KRYTYCZNE

### BUG-GUARD
**Opis:** OutputGuard nie zna tokenMap sesji — przepuszcza PII.
**Plik:** OutputGuard.kt
**Potok:** OCR

### Luka reset hasła
**Opis:** "Zapomniałem hasła" daje dostęp do biblioteki bez uwierzytelnienia.
**Plik:** LoginScreen.kt
**Potok:** 6.2

## WYSOKIE

### BUG-OSOBA-49%
**Opis:** OSOBA recall 48.6% (34/70). Główna przyczyna: NameEngine działa PO maskowaniu adresów — traci kontekst ulicy. Pre-ekstrakcja NameEngine przed apply_address (jak Desktop) powinna dać +10-15pp.
**Plik:** PseudonymEngine.kt (kolejność warstw)
**Fix:** wyciągnij encje OSOBA przed maskowaniem adresów, zapisz w cache, zastosuj po
**Potok:** 3c-FIX / kontynuacja sesji 14.06

### BUG-ADRES-62%
**Opis:** ADRES recall 61.9%. Adresy bez prefiksu ul./al., OCR łamie linie, kody z błędem OCR.
**Plik:** StructuralEngine.kt
**Potok:** 3c-FIX

### BUG-typeMap
**Opis:** 54 encje klasyfikowane jako "?" — klucze ground truth bez mapowania w typeMap benchmarku. Zaniża rzeczywisty recall.
**Plik:** BenchmarkInstrumentedTest.kt
**Potok:** natychmiast (15 minut)

## ŚREDNIE

### FP-FRAGMENTY-OCR
**Opis:** 96 false positives — fragmenty słów i złamane linie jako OSOBA. Częściowo naprawione przez denylist+filtr długości w v1.11, pełna weryfikacja po benchmarku.
**Plik:** NameEngine.kt v1.11
**Potok:** po benchmarku weryfikacja

## NISKIE

### BUG-20
**Opis:** android.hardware.camera required="true" po usunięciu kamery.
**Plik:** AndroidManifest.xml
**Potok:** 6.2

---

# CZĘŚĆ 5 — WERSJE PLIKÓW MOBILE (stan 14.06.2026)

| Plik | Wersja | Uwagi |
|---|---|---|
| StructuralEngine.kt | v1.8 | EMAIL na pozycji 0 (zmiana 14.06) |
| NameEngine.kt | v1.11 | denylist + filtr długości 4 znaki (zmiana 14.06) |
| PseudonymEngine.kt | v2.2 | bez zmian |
| OutputGuard.kt | v1.6 | bez zmian |
| LookupTables.kt | v1.2 | bez zmian |
| UserDictionary.kt | v1.3 | bez zmian |
| SessionStore.kt | v1.4 | bez zmian |

## Wyniki benchmarku Mobile (baseline przed sesją 14.06)
| Metryka | Wartość |
|---|---|
| Recall | 63.0% |
| Precision | 68.4% |
| CLR | 15 |
| FP | 96 |
| OSOBA | 48.6% |
| ADRES | 61.9% |
| EMAIL | 60.0% |

---

# CZĘŚĆ 6 — KOLEJKA POTOKÓW MOBILE

| # | Status | Zakres | Zależy od |
|---|--------|--------|-----------|
| 3b | ✅ ZAMKNIĘTY | StructuralEngine v1.5, NameEngine v1.9 | — |
| RODO | ✅ ZAMKNIĘTY | LoginScreen v2.0, SessionStore v1.4 | — |
| BUG-DICT | ✅ ZAMKNIĘTY | UserDictionary v1.3 | — |
| 3c | ✅ ZAMKNIĘTY | StructuralEngine v1.7, NameEngine v1.10 | — |
| **3c-FIX** | 🟡 W TOKU (sesja 14.06) | EMAIL pozycja 0, denylist, pre-ekstrakcja NER | 3c ✅ |
| **6.2** | 🟢 W TOKU | UI, Express Mode, SessionStore bugi | RODO ✅ |
| **7** | ⏳ po 3c-FIX | Format tokenów TYP_XXX_NNN, taksonomia 9 typów | 3c-FIX |
| **OCR** | 📋 po 7 | OutputGuard, BUG-GUARD | 7 |
| **.LYNXDICT** | 📋 po OCR | Sync desktop→mobile | OCR |
| **LT** | 📋 wymaga plików | Rozbudowa LookupTables z Morfeusz2 | pliki od Pawła |

---

# CZĘŚĆ 7 — DECYZJE ARCHITEKTONICZNE (obie platformy)

- **USE_NEW_PIPELINE=True** — domyślne od Run H, nieodwracalne
- **Taksonomia tokenów Mobile (Potok 7):** 9 typów: OSOBA, ADRES, NUMER, ORGANIZACJA, EMAIL, TELEFON, SYGNATURA, DATA, KWOTA. DOKUMENT usunięty z planu.
- **Format tokenu Mobile:** TYP_XXX_NNN gdzie XXX = 3-znakowy suffix sesji
- **BUG-ARCH Mobile:** hasło NIE jest wiązane kryptograficznie z kluczem SQLCipher — Android Keystore + AES-256-GCM wystarczające (Art. 32 RODO)
- **Próg OCR Desktop:** 70% confidence = HTTP 422. Dokumenty lvl=2/3 z qs>70 przetwarzane z ostrzeżeniem
- **verbal_amounts:** usunięte z nowego pipeline Desktop — świadoma decyzja (19 FP → 0)
- **cities_forms.json:** 179370 form miast (SIMC GUS + Morfeusz2), ładowane przy starcie jako frozenset

---

# CZĘŚĆ 8 — BACKLOG STRATEGICZNY

| Temat | Opis | Priorytet |
|---|---|---|
| Aktualizacje aplikacji | Tauri updater przez GitHub Releases dla Desktop, Play Store dla Mobile | Przed dystrybucją |
| Express Mode | Desktop + Mobile — tryb free bez logowania, max 3 kliknięcia | Po UI-2 |
| Wersje językowe | Osobne profile wzorców PII per kraj (CZ, SK, DE) | Po stabilizacji PL |
| Anonimizacja wizualna | Blur twarzy, tablice rejestracyjne, EXIF/DOCX metadata | Po OCR-Enhancement |
| PBKDF2 migracja | AUD-13/14 — zmiana iteracji/soli unieważnia mapy.enc | Przed dystrybucją |
| Zmiana nazwy katalogu | Desktop\pseudominizer\ → pełny przegląd hardkodowanych ścieżek | Średni |
| Synchronizacja Mobile↔Desktop | Przenoszenie dokumentów i map | Niski |
| Podpisy odręczne | Wymaga własnego modelu — po fine-tuningu | Backlog |
| Fine-tuning NER | Model pomocniczy na danych z Biblioteki | Po stabilizacji |
