# TODO — LynxMask Mobile (stan: 28.06.2026)
# Źródła: BACKLOG.md + CLAUDE.md + memory + sesja 28.06
# Ocena: ważne vs bombka. Kolejność = priorytet release.

---

## BLOKERY RELEASE — to musi być przed sklepem

| # | Zadanie | Dlaczego blokuje | Agent |
|---|---------|-----------------|-------|
| ~~R1~~ | ~~**S10 — TELEFON nie w tokenMap**~~ | ✅ fix ee69719 — separator kropkowy dodany do 4 wzorców | ~~Claude Code~~ |
| ~~R2~~ | ~~**AUD-M06 — security-crypto alpha → 1.0.0**~~ | ✅ fix 28435ca — MasterKeys API 1.0.0 stable | ~~Claude Code~~ |
| R3 | **IMAGE-REDACT UI** — przepięcie na Lynx design system | Ekran redakcji wygląda jak inna aplikacja. Ocena oka użytkownika = brak zaufania | Cursor |
| R4 | **Testy kamerą (E2E)** — checklist z BACKLOG §Strategia | Użytkownik sam zgłasza: "wymaga intensywnych testów przy użyciu aparatu" | Paweł |
| R5 | **ToS + Privacy Policy** | Wymagane przez Google Play | Prawnik |

---

## WAŻNE — zrobić przed lub tuż po release

| # | Zadanie | Dlaczego ważne | Agent |
|---|---------|---------------|-------|
| ~~W1~~ | ~~**Testy 6.2 — JUnit SessionStore + Deanonymizer**~~ | ✅ fix 22564b8 — 23 testy (DeanonymizerTest 13 + SessionStoreSerializationTest 10) | ~~Claude Code~~ |
| ~~W2~~ | ~~**S7 — email wykrywany jako NUMER**~~ | ✅ fix a3d4cf1 (22.06) — TOKEN_EMAIL w obu wzorcach, już zamknięte | ~~Claude Code~~ |
| W3 | **IMAGE-REDACT F2** — kolejność obraz → OCR tekstu | F2 dopiero po tym jak UI jest gotowe (R3 najpierw) | Cursor |
| W4 | **Audyt RODO/security** (10 punktów z BRIEF_Cursor) | S10 (TELEFON), logi PII, SessionStore crypto — Cursor ma brief | Cursor |
| ~~W5~~ | ~~**Smoke E2E automated**~~ | ✅ SmokeE2ETest.kt — 3 testy instrumented (roundtrip, izolacja sesji, polskie znaki) | ~~Claude Code~~ |
| W6 | **BUG-SS-1/SS-3** — SessionStore init na Main thread | Potencjalny ANR na starszych telefonach | Claude Code |

---

## NISKIE — po release lub okazjonalnie

| # | Zadanie | Ocena |
|---|---------|-------|
| N1 | BUG-PESEL-OCR-SILNIK (dok. doc_00006) | Wymaga konkretnego skanu. Nie blokuje. |
| N2 | AUDIT-04 — duplikat wzorców IBAN | Kosmetyka silnika, zero wpływu na wynik |
| N3 | AUDIT-05/06 — Log.d bez DEBUG guard + clearOnExit | Czystość kodu, nie bezpieczeństwo w prod |
| N4 | BUG-DOCX-PARTIAL — brak nagłówków/stopek | Edge case, większość PII jest w body |
| N5 | BUG-16/17 — ManualTokenSection brak selektora / TOKEN_KWOTA | UX uzupełniony, nie blokuje podstawowego flow |
| N6 | BUG-PDF-LIMIT — PDF >10 stron bez blokady | Można wyświetlić ostrzeżenie zamiast fixować |

---

## BOMBKI — odrzucone świadomie

| Pomysł | Dlaczego nie |
|--------|-------------|
| Folder scan na mobile | Na mobile nikt nie trzyma folderów z dokumentami. Wartość = desktop CLI (już planowane) |
| Regeneracja klucza odzysk. (v2) | Wymaga starego hasła + klucza. Mało użytkowników tego dotknie. v2. |
| BUG-LOG-OCR / BUG-BENCH-PUBLIC | Tylko narzędzia deweloperskie, nie dotykają produkcji |
| BUG-EXPORT-DEPSEUDO ostrzeżenie | Estetyczny brakujący komunikat, nie utrata danych |
| Folder CLI na mobile | j.w. — to zadanie desktopowe |

---

## KAMPANIA TESTÓW KAMERĄ (checklist dla Pawła)

Zgodnie z BACKLOG §Strategia — E2E C ręczne (~15 min, odpalić po R3):

1. **Zdjęcie dokumentu** → share do LynxMask → twarz zblurowana domyślnie → zapis do biblioteki → podgląd
2. **Ręczny prostokąt** → zaznacz podpis → blur → usuń blur → świadome odkrycie → share JPEG
3. **OCR z aparatu** → tekst z PESEL/NIP → Review → pseudonimizuj → brak gołego numeru w podglądzie → depseudo przywraca
4. **Realny dokument** (nie syntetyczny) — faktura, umowa — jedno zdjęcie telefonem → krytyczne PII zamaskowane
5. **Schowek** → skopiuj tekst z danymi → kafelek LynxMask → ostrzeżenie → zapis
6. **Odzyskiwanie hasła** → zapomniałem hasła → klucz odzysk. → nowe hasło → biblioteka OK

Zablokuj release do momentu: pkt 1–4 bez regresji na Samsung SM-A536B (Android 16).

---

## Co zamknięto w sesji 28.06

✅ Recovery Key P1-AUTH (RecoveryKeyManager + LoginScreen + test)
✅ cityForms blocklist regression — EngineGoldenTest 43/43
✅ Dictionary manager UX (przeglądaj/usuwaj/szukaj)
✅ Migration Desktop → Mobile: cities_forms.json (179k), medical_facilities.json
✅ CityLookupTest (10 testów, 0 FAIL)
