# TODO — LynxMask Mobile (stan: 28.06.2026)
# Źródła: BACKLOG.md + CLAUDE.md + sesja 28.06 (cd.)
# Ocena: ważne vs bombka. Kolejność = priorytet release.

---

## BLOKERY RELEASE — to musi być przed sklepem

| # | Zadanie | Dlaczego blokuje | Agent |
|---|---------|-----------------|-------|
| ~~R1~~ | ~~**S10 — TELEFON nie w tokenMap**~~ | ✅ fix ee69719 — separator kropkowy dodany do 4 wzorców | ~~Claude Code~~ |
| ~~R2~~ | ~~**AUD-M06 — security-crypto alpha → 1.0.0**~~ | ✅ fix 28435ca — MasterKeys API 1.0.0 stable | ~~Claude Code~~ |
| ~~R3~~ | ~~**IMAGE-REDACT UI**~~ | ✅ Cursor — nawigacja, LynxScreenHeader, LynxNavExtras, redesign (sesja 28.06) | ~~Cursor~~ |
| R4 | **Testy kamerą (E2E)** — checklist z BACKLOG §Strategia | Użytkownik sam zgłasza: "wymaga intensywnych testów przy użyciu aparatu" | Paweł |
| R5 | **ToS + Privacy Policy** — treść prawnicza | Wymagane przez Google Play. Zapytanie do prawnika wysłane 28.06. Ekran wbudowany gotowy — podmienić tekst w PrivacyPolicyDialog (MainActivity.kt). | Prawnik |

---

## GOOGLE PLAY — zadania przed publikacją

| # | Zadanie | Kto | Status |
|---|---------|-----|--------|
| GP1 | Keystore — wygenerować klucz podpisujący | Paweł + Claude Code | 🔲 gdy Paweł gotowy |
| GP2 | Konto Play Console ($25, jednorazowo) | Paweł | 🔲 |
| GP3 | Firma testerów (12 lub 16 kont Google) | Paweł | 🔲 |
| GP4 | Build podpisanego .aab | Claude Code | 🔲 po GP1 |
| GP5 | Wgranie .aab + screenshotów + opisu | Paweł w Play Console | 🔲 po GP4 |
| GP6 | Closed Testing 14 dni z 12+ testerami | Paweł | 🔲 |

Screenshoty gotowe: `Google_Play/01_HUB.jpg` … `06_ZABEZPIECZENIA.jpg`
Szczegóły: `Google_Play/RELEASE_CHECKLIST.md`

---

## WAŻNE — zrobić przed lub tuż po release

| # | Zadanie | Dlaczego ważne | Agent |
|---|---------|---------------|-------|
| ~~W1~~ | ~~**Testy 6.2 — JUnit SessionStore + Deanonymizer**~~ | ✅ 23 testy (DeanonymizerTest 13 + SessionStoreSerializationTest 10) | ~~Claude Code~~ |
| ~~W2~~ | ~~**S7 — email wykrywany jako NUMER**~~ | ✅ fix a3d4cf1 (22.06) | ~~Claude Code~~ |
| ~~W3~~ | ~~**IMAGE-REDACT F2**~~ | ✅ Cursor — kolejność redakcja obrazu → OCR tekstu (sesja 28.06) | ~~Cursor~~ |
| ~~W4~~ | ~~**Audyt RODO/security**~~ | ✅ AUDIT-05 (Log.d guard x3) + AUDIT-05b (debug button) + przegląd 10 punktów | ~~Claude Code~~ |
| ~~W5~~ | ~~**Smoke E2E automated**~~ | ✅ SmokeE2ETest.kt — 3 testy instrumented | ~~Claude Code~~ |
| ~~W6~~ | ~~**BUG-SS-1/SS-3**~~ | ✅ b65d560 (UPSERT), b9eae1c (IO thread) | ~~Claude Code~~ |

---

## NISKIE — po release lub okazjonalnie

| # | Zadanie | Ocena |
|---|---------|-------|
| N1 | BUG-PESEL-OCR-SILNIK (dok. doc_00006) | Wymaga konkretnego skanu. Nie blokuje. |
| N2 | AUDIT-04 — duplikat wzorców IBAN | Kosmetyka silnika, zero wpływu na wynik |
| N3 | BUG-DOCX-PARTIAL — brak nagłówków/stopek | Edge case, większość PII jest w body |
| N4 | BUG-PDF-LIMIT — PDF >10 stron bez blokady | Można wyświetlić ostrzeżenie zamiast fixować |

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

## KAMPANIA TESTÓW KAMERĄ (checklist dla Pawła — R4)

Zgodnie z BACKLOG §Strategia — E2E C ręczne (~15 min):

1. **Zdjęcie dokumentu** → share do LynxMask → twarz zblurowana domyślnie → zapis do biblioteki → podgląd
2. **Ręczny prostokąt** → zaznacz podpis → blur → usuń blur → świadome odkrycie → share JPEG
3. **OCR z aparatu** → tekst z PESEL/NIP → Review → pseudonimizuj → brak gołego numeru w podglądzie → depseudo przywraca
4. **Realny dokument** (nie syntetyczny) — faktura, umowa — jedno zdjęcie telefonem → krytyczne PII zamaskowane
5. **Schowek** → skopiuj tekst z danymi → kafelek LynxMask → ostrzeżenie → zapis
6. **Odzyskiwanie hasła** → zapomniałem hasła → klucz odzysk. → nowe hasło → biblioteka OK

Zablokuj release do momentu: pkt 1–4 bez regresji na Samsung SM-A536B (Android 16).

---

## Co zamknięto w sesji 28.06 (cd.)

✅ BUG-SCAN-ROUTE — looksLikeIdentityDocument() false positive z PESEL (fix 932b20a)
✅ BUG-DEPSEUDO-AUTO — auto-wybór najnowszej sesji gdy brak SESJA_ w tekście AI (fix 932b20a)
✅ AUDIT-05 — Log.d bez BuildConfig.DEBUG guard (GuardAllowlist, LookupTables, UserDictionary) (fix 7044b4d)
✅ AUDIT-05b — "Kopiuj logi diagnostyczne" tylko w debug build (fix ce6f9be)
✅ Privacy Policy — dialog wbudowany w apce, dostępny z LoginScreen i SecurityModal (fix 1640c0f)
✅ Cursor UI — nawigacja, LynxScreenHeader, LynxNavExtras, ML Kit skalowanie (fix a71b477)
✅ Google Play — screenshoty 6 szt. w Google_Play/, RELEASE_CHECKLIST.md
✅ Zweryfikowano: S10, AUD-M06, BUG-EMAIL-TLD1, N3(loop), S11 — wszystkie już zamknięte w poprzednich sesjach
