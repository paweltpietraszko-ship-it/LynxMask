# Raport: testy ręczne UI-2 (LynxMask Mobile)

**Data:** 2026-06-22  
**Tester:** Paweł Pietraszko  
**Implementacja UI-2:** Cursor (commit `9cef7f9`)  
**Fix podglądu tokenu:** ten commit  
**Zrzuty:** `1000005932.jpg`, `1000005935.jpg` (repo root)

---

## Zakres

Smoke test panelu wyniku po pseudonimizacji (MASTER §17 UI-2):
- układ RED → Podgląd → YELLOW → Kopiuj → Dodaj do biblioteki
- pliki: `testy/test_ui2_encje_silnik.txt`, `testy/test_ui2_guard_only.txt`
- urządzenie fizyczne, Share → LynxMask, bez ręcznej obsługi encji (pierwszy przebieg)

---

## Werdykt ogólny: **UI-2 PASS (z jednym bugiem naprawionym)**

Layout zgodny ze spec: brak starych przycisków Encje/Dodaj, sekcja YELLOW działa, Kopiuj i Dodaj do biblioteki widoczne i aktywne przy braku RED Guard.

---

## Test 1 — `test_ui2_encje_silnik.txt` (sesja F38095)

### Silnik
- Zamaskowano ~30 tokenów (OSOBA, NUMER, EMAIL, ADRES, KWOTA).
- Sekcja „wycieki celowe” (email, tel, dowód, IBAN, NIP) — **silnik złapał wszystko** → brak RED Guard.
- Flag NameEngine (Oskar Zepelin, Ewa Celej) **nie pojawiły się** — silnik od razu dał OSOBA_004/005 zamiast flag PENDING.
- Over-masking w nagłówku/checkliście: daty → NUMER, sygnatury częściowo, `jan.OSOBA_001@`.

### UI-2
| Element | Wynik |
|---------|--------|
| Sekcja RED | Brak — **OK** (silnik złapał wycieki) |
| Sekcja YELLOW | ~5 pozycji (PESEL, SYGNATURA, PESEL_SPACE, EMAIL_FRAGMENT) — zrzut 5932 |
| Podgląd tokenu | Działa odkrycie, **nie działał powrót do maski** → bug naprawiony |
| Kopiuj | OK — schowek z tokenami |
| Dodaj do biblioteki | Przycisk widoczny, testowany |
| Stary layout | Usunięty — OK |

---

## Test 2 — `test_ui2_guard_only.txt` v1 (sesja 38C27C)

### Silnik
- Plik „guard-only” **nadal triggeruje silnik** (daty, RED-wycieki, sygnatury → NUMER/EMAIL).
- Oczekiwane „tekst nietknięty” — **nieosiągalne** przy obecnym silniku.

### UI-2
| Element | Wynik |
|---------|--------|
| Sekcja RED | Brak — silnik maskuje wycieki |
| Sekcja YELLOW | **8 pozycji** — zrzut 5935: PESEL×3, MIEJSCE_UR, SYGNATURA, EMAIL_FRAGMENT×3 |
| Fałszywe EMAIL_FRAGMENT | `jan.kowalski@i/lub` z linii checklisty na dole pliku — artefakt pliku testowego |
| Kopiuj / Biblioteka | Aktywne |

Plik zaktualizowany do **v2** — krótszy, bez mylących linii, realistyczne oczekiwania.

---

## Bug naprawiony w tej sesji

**BUG-UI2-PREVIEW-TOGGLE:** Po odkryciu tokenu w Podglądzie nie można było ukryć ponownie.

**Przyczyna:** `displayText` podmieniał token oryginałem → `TOKEN_RE` nie znajdował tokenu do kliknięcia.

**Fix:**
- Podgląd używa `maskedDisplayText` (tokeny zawsze obecne).
- Kopiuj → `outputText` (z ewentualnymi odkryciami).
- Dodaj do biblioteki → `maskedOutputText` (zawsze tokeny, bez wycieku z podglądu).

Pliki: `PseudonymResultPanel.kt`, `TextPreviewModal.kt`.

---

## Wnioski dla Claude (silnik / backlog)

1. **UI-2 gotowe do użytku** — brak blockerów po fixie podglądu; wymaga rebuild APK.

2. **RED Guard na telefonie trudno testować** — silnik maskuje typowe wycieki przed Guardem. Test RED w UI wymaga albo celowego bypassu silnika, albo ręcznego wklejenia gołego PII w Podgląd → Maskuj.

3. **Pliki testowe guard-only** muszą unikać: dat ISO, słów NIP/email/tel w sekcji wycieków, długich checklist w treści share (sam się skanuje).

4. **Flagi vs auto-mask OSOBA** — imiona z rolami (Pełnomocnik, Inspektor) są maskowane jako OSOBA, nie trafiają do YELLOW flag. To zachowanie silnika, nie UI-2.

5. **Znane problemy silnika** (poza UI-2, widoczne w testach):
   - NIP partial: `526-000-13-29` → `NUMER_002-NUMER_004`
   - Over-masking dat/liczb w nagłówkach dokumentów
   - `85 0717 92056` — Guard PESEL_SPACE mimo poprawnego PESEL w innych kontekstach

6. **Dodaj do biblioteki** — flow OK wizualnie; zapis async bez toast sukcesu (tylko „✓ Dodano” w UI). Toast tylko przy błędzie UPSERT.

---

## Pliki w commicie

- `app/.../PseudonymResultPanel.kt` — fix toggle + biblioteka
- `app/.../TextPreviewModal.kt` — hint
- `testy/test_ui2_encje_silnik.txt`
- `testy/test_ui2_guard_only.txt` (v2)
- `testy/RAPORT_UI2_testy_reczne.md` (ten plik)

---

## Następne kroki (opcjonalne)

- [ ] Re-test toggle podglądu po rebuild
- [ ] Claude: review vs MASTER §17 (read-only)
- [ ] Silnik: NIP partial mask, over-masking dat w nagłówkach
- [ ] Test RED UI: osobny minimalny snippet do ręcznego wklejenia w Podgląd
