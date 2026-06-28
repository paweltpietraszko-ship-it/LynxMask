# BACKLOG — LynxMask Mobile
# Jedno źródło aktywnych zadań. MASTER = archiwum decyzji. Ten plik = co robić.
# Aktualizować na końcu każdej sesji. Brief sesji = wytnij stąd to co istotne.

---

## P1 PRODUKT — IMAGE-REDACT (decyzja MASTER §9, 22.06.2026)

**Właściciel: Cursor.** Claude Code / Sonet — **nie ten temat** (silnik tekstowy zostaje u Soneta).

Use-case: zdjęcie dowodu / PJ / DR → udostępnienie w sieci. OCR+tekst **nie wystarcza** — twarz i podpis zostają na pikselach.

**Świadome odkrycie:** domyślnie blur/maska, ale użytkownik może **odkryć wybrane dane** przed wysłaniem (tekst: `revealedTokens` w UI-2; obraz: cofnięcie blur / odznaczenie regionu w podglądzie). To feature, nie bug — nie blokować eksportu, sygnalizować ostrzeżeniem.

| Faza | Zakres | Agent | Done? |
|------|--------|-------|-------|
| **0 — spike** | Share `image/*` → ML Kit Face Detection → blur domyślnie → podgląd z możliwością cofnięcia blur → share JPEG | **Claude Code** | ✅ |
| **1** | Ręczny prostokąt: podpis / pieczątka; opcjonalnie „zostaw widoczne" na zaznaczonym | **Cursor** | ✅ |
| **2** | Kolejność: redakcja obrazu → OCR tekstu (do ustalenia w spike) | **Cursor** | 🔲 |

**Pliki docelowe:** `ShareTargetActivity.kt`, `ImageRedactionPipeline.kt` (roboczo).

**Out of scope F0:** auto-podpis; PDF/DOCX jako raster.

**Kryterium sukcesu F0:** twarz zblurowana domyślnie; użytkownik może cofnąć blur na wybranym regionie i świadomie wysłać.

**Stan F0:** zaimplementowane (Claude Code, sesja 23.06.2026).

**Stan F1:** zaimplementowane (Cursor, sesja 23.06.2026) — `ImageRedactionScreen.kt`: ręczny prostokąt, toggle odkrycia, ostrzeżenie przed Share.

**Brief dla Cursor (Faza 0):**
```
MASTER §9 warstwa obrazu + ten wpis BACKLOG.
Spike: po Share/skanerze obrazu — ML Kit Face Detection, blur domyślnie,
podgląd z toggle cofnięcia blur per region (świadome odkrycie przed Share).
Integracja z istniejącym flow ShareTargetActivity. Nie ruszaj silnika tekstowego.
```

**Dla Soneta / Claude Code:** IMAGE-REDACT **poza Twoim scope** — kontynuuj silnik, Guard, benchmark. Nie odkładaj tego zadania „bo trudne"; to robi Cursor.

---

## KRYTYCZNE — przed jakimkolwiek release

| # | Bug | Skutek | Stan |
|---|---|---|---|
| BUG-AUTH-RESET | LoginScreen: "Zapomniałem hasła" | Reset bez kodu = wipe (fix 23.06). UX nadal słaby bez klucza odzyskiwania | ⚠️ częściowo — patrz epik poniżej |

**Stan F1b (26.06.2026):** zapis obrazu do biblioteki (szyfrowany JPEG w SessionStore), share opcjonalny. Logika OK — **UI do odświeżenia** (patrz sekcja poniżej).

---

## P1 AUTH — KLUCZ ODZYSKIWANIA (decyzja 26.06.2026)

**Problem:** Reset hasła = utrata całej biblioteki. Bezpieczne (RODO, zero backdoora), ale fatalny UX. Mail z resetem **odpada** — aplikacja offline, brak kont, brak serwera.

**Rozwiązanie:** Ten sam model co Desktop (MASTER Desktop §Bezpieczeństwo). Klucz generowany **raz** przy pierwszym haśle. Użytkownik **sam decyduje**, czy go zapisze (wydruk / notes / menedżer haseł). Zgubiony klucz = brak odzyskania — **świadoma odpowiedzialność użytkownika**, nie luka producenta.

### Spec (zgodna z Desktop)

| Element | Wartość |
|---------|---------|
| Format | 24 znaki, 4 grupy po 6: `ABCD12-EFGH34-IJKL56-MNOP78` |
| Entropia | CSPRNG (SecureRandom), alfabet bez mylących znaków |
| Pokazanie | **Raz** przy onboardingu — ekran pełnoekranowy, nie dialog |
| Instrukcja | „Zapisz lub wydrukuj. Bez tego kodu zapomniane hasło = utrata biblioteki." |
| Potwierdzenie | Checkbox „Zapisałem kod" + opcjonalnie wpisanie 2. i 4. grupy |
| Przechowywanie | Lokalnie, hash PBKDF2 (osobny salt od hasła UI) — **nie** plaintext w prefs |
| Reset hasła | Ekran logowania: **(A)** wpisz klucz → ustaw nowe hasło, dane zostają **(B)** brak klucza → obecny wipe (SessionStore + słowniki) |
| Sieć | Brak — zero INTERNET, zero maila |

### Flow ekranów

```
Pierwsze hasło → Generuj klucz → Pokaż kod (kopiuj) → Potwierdź zapis → App
Logowanie → [Zapomniałem] → Dialog: „Masz kod odzyskiwania?"
  → TAK: wpisz klucz → nowe hasło → biblioteka OK
  → NIE: obecny dialog wipe (czerwony, nieodwracalny)
Zabezpieczenia → „Pokaż kod odzyskiwania" (wymaga aktualnego hasła) — opcjonalnie v2
```

### Pliki docelowe

| Plik | Zakres |
|------|--------|
| `RecoveryKeyManager.kt` (nowy) | generacja, hash, weryfikacja, format grup |
| `LoginScreen.kt` | onboarding klucza; reset z kodem vs wipe |
| `MainActivity.kt` | SecurityModal: tekst o kluczu obok „Zmień hasło" |
| testy JVM | `RecoveryKeyManagerTest.kt` — format, verify ok/fail, brak plaintext |

### Kryteria sukcesu

1. Użytkownik z zapisanym kluczem odzyskuje hasło **bez utraty** biblioteki i słowników.
2. Użytkownik bez klucza — ten sam wipe co dziś (fix luki 23.06 zachowany).
3. Klucz nie w Logcat, nie w backupie (prefs poza backup — spójnie z RODO).
4. Brak nowych uprawnień sieciowych.

### Out of scope v1

- Regeneracja klucza (wymaga starego hasła + starego klucza — v2).
- Biometria (Potok 6.2 TODO).
- Wiązanie hasła UI z kluczem SQLCipher (BUG-ARCH — nie ruszać).

**Agent:** Claude Code (auth + prefs + testy). Cursor — tylko review flow UI jeśli ekrany odstają od Lynx tokens.

**Priorytet:** przed pierwszym release publicznym (obok AUD-M06 security-crypto).

---

## NASTĘPNA SESJA — UI IMAGE-REDACT (Paweł, 26.06.2026)

**Problem:** Flow obrazu działa, ale **wygląda jak lata 2000** — nie przez brak funkcji, tylko przez **gołe przyciski Material3** doklejone obok reszty aplikacji, która ma design system Lynx.

**Diagnoza (co jest „nowe” i psuje wrażenie):**
- `ImageRedactionScreen.kt` — **nie używa** `LynxColors` / `LynxSpacing` / `LynxShapes` (w przeciwieństwie do `LibraryScreen`, `PseudonymResultPanel`)
- Dolny panel akcji to stos: `OutlinedTextField` + rząd `OutlinedButton` (Anuluj | + Słowo) + pełna szerokość `Button` + pełna szerokość `OutlinedButton` — **4 poziomy przycisków**, zero hierarchii wizualnej
- Lista „Co wysłać”: płaski `Switch` + tekst „Zakryte/Odkryte” — wygląda jak ustawienia Androida 4.x, nie jak reszta LynxMask
- Skróty „Zakryj wszystkie / Odkryj wszystkie” jako `TextButton` — ledwo widoczne
- Nagłówek „Sprawdź i zapisz” + bodySmall — inny ton niż Biblioteka (mono, sidebar, karty)
- Ostrzeżenie odkrytych pól: emoji ⚠ w `Card` — OK funkcjonalnie, stylistycznie obce do reszty

**Wzorzec do skopiowania (już w repo):**
- `LibraryScreen.kt` → `SessionActionButton` (pełna szerokość, `LynxColors.Blue`, `LynxSpacing.TouchTarget`, `LynxShapes.ButtonRadius`)
- `PseudonymResultPanel.kt` → `ActionSection`, zielony „Dodaj do biblioteki”, karty RED/YELLOW
- `DesignTokens.kt` → jedyna paleta; `ButtonRadius = 2.dp` (ostre — świadomy wybór v2.3)

**Zakres następnej sesji (TYLKO UI/UX, bez zmiany logiki):**

| Plik | Co zrobić |
|------|-----------|
| `ImageRedactionScreen.kt` | Przepiąć na Lynx tokens; **jeden** sticky footer z 2 akcjami (primary: Zapisz, secondary: Udostępnij); Anuluj → ikona/back w nagłówku lub TextButton u góry |
| `ImageRedactionScreen.kt` | Lista pól → **karty/chipy** z ikoną typu (VIN, twarz, adres) zamiast Switch+label; bulk „zakryj/odkryj” jako segmented control lub chipy |
| `LibraryScreen.kt` | Sesja obrazu: ten sam styl co tekst; podgląd w `Card` z `LynxColors.Surface` |
| opcjonalnie | Wydzielić `ImageRedactFooter.kt` / `RegionToggleCard.kt` — tylko jeśli czytelniejsze, bez over-engineeringu |

**Nie ruszać:** `ImageRedactionPipeline.kt`, `SessionStore`, `PseudonymEngine`, logika share/zapisu.

**Kryterium sukcesu:** ekran redakcji obrazu **wizualnie należy do tej samej aplikacji** co Biblioteka i panel po OCR tekście; mechanik widzi max 2 oczywiste akcje na dole, reszta w scrollu nad obrazem.

**Szacunek:** ~1 sesja Cursor, same pliki Compose w `app/.../ImageRedactionScreen.kt` (+ ewent. drobne `LibraryScreen.kt`).

---

## NASTĘPNA SESJA — SILNIK / BUGI

Silnik + otwarte bugi z KOLEJKI SILNIKA (N2, N3, S7, S11) + BUG-PESEL-OCR-SILNIK.

---

## KOLEJKA SILNIKA

| # | Zadanie | Plik |
|---|---|---|
| N2 | ~~BUG-NIP-SPLIT~~ | ✅ naprawione 23.06 — `fixOcrNipNumber` w OcrNormalizer krok 11b |
| N3 | OCR_EMAIL_LOCALSPACE: wiele spacji — tylko jedna naprawiana | OcrNormalizer |
| S7 | BUG-EMAIL-TOKEN: email wykrywany jako NUMER | StructuralEngine |
| S11 | Email z imieniem w local-part: konflikt wzorca email z NameEngine | StructuralEngine |

---

## STRATEGIA TESTÓW — Mobile (decyzja 26.06.2026)

**Problem:** Regresja per warstwa ≠ regresja produktu. Zielone JVM + benchmark nie gwarantują, że share → OCR → silnik → biblioteka → depseudo działa razem.

### Trzy poziomy (wszystkie potrzebne)

| Poziom | Co | Kiedy | Kto |
|--------|-----|-------|-----|
| **A — JVM** | `gradlew :app:testDebugUnitTest` — OcrNormalizer, silnik, Guard | Po każdej zmianie warstwy tekstowej | Claude Code |
| **B — Benchmark v2** | `run_benchmark.bat` / instrumented, sekcja **B** (in-scope) | Regresja OCR+silnik na stałym korpusie 68 dok. | Paweł (bat) + Claude (install) |
| **C — Ręczne E2E** | Pełna apka: share, Review, obraz, biblioteka, depseudo | Po większej zmianie flow/UI; przed release | Paweł |

**KPI release (automat):** sekcja B benchmarku v2 — recall in-scope ≥90%, krytyczne ≥95%, 0 BUG_SILNIKA. Sekcje C/D informacyjnie.

**Benchmark ≠ produkcja (świadome różnice):**
- Benchmark **czyści** UserDictionary i GuardAllowlist — produkcja nie.
- Benchmark **nie** testuje ImageRedact, SessionStore roundtrip, UI Review, schowka.
- Recall mierzy **prawdę o datasecie**, nie „każdy dokument świata”.

### Ręczne E2E — checklista (Paweł, ~15 min po większej zmianie)

1. **Tekst ze share** — faktura/umowa DOCX lub PDF → Review → pseudonimizuj → brak gołego PESEL/NIP w podglądzie → zapis do biblioteki → depseudo przywraca sens.
2. **Obraz** — zdjęcie dokumentu → twarz zblurowana domyślnie → zapis JPEG do biblioteki → podgląd w bibliotece.
3. **Schowek** — wklej tekst z emailem → kafelek LynxMask → ostrzeżenie → zapis opcjonalny.
4. **Dokument #101** — jeden **realny** skan spoza generatora (np. zdjęcie telefonem); jeśli odrzucenie OCR — OK; jeśli przyjęty — krytyczne PII zamaskowane.

### Backlog techniczny — smoke E2E automatyczny (P2, przed UL)

Jeden plik instrumented (bez Compose), łączy warstwy które dziś są osobno:

| Test | Ścieżka |
|------|---------|
| `smokeOcrEngineRoundtrip` | PNG lvl0 z `dataset_staly` → OCR → silnik **ze słownikami jak prod** (bez clear) → brak PII w tokenMap |
| `smokeSessionDepseudo` | wynik powyżej → `SessionStore.save` → load → depseudo → roundtrip |
| `smokeRedactedImage` | JPEG po pipeline obrazu → `saveRedactedImage` → odczyt z DB |

Agent: Claude Code (androidTest). Cursor tylko jeśli dotyka ImageRedactionPipeline.

### Czego automat nie zastąpi

Realne zdjęcia z aparatu, edycja w Review, Guard YELLOW + allowlist w UI, Share Target z messengera — tylko ręcznie (MASTER §7).

---

## KNOWN ISSUES — wiadomo, nie palące

- BUG-DOCX-PARTIAL: DOCX — tylko document.xml, brak nagłówków/stopek
- BUG-PDF-LIMIT: PDF >10 stron — częściowe przetwarzanie bez blokady eksportu
- AUDIT-02: GuardAllowlist silent failure przy błędzie ładowania
- AUDIT-03: CATCHALL w PESEL/NIP_PATTERN_STRINGS (częściowo zaadresowane)
- AUDIT-04: Duplikat wzorców IBAN w STRUCTURAL_PATTERNS
- AUDIT-05: Log.d bez BuildConfig.DEBUG guard
- AUDIT-06: DebugLogBuffer.clearOnExit() niepodpięty do lifecycle
- AUDIT-07: SessionStore.save() z pustym maskedText może nadpisać zaszyfrowany tekst
- BUG-LOG-DICT/OCR: logowanie PII do Logcat bez DEBUG guard
- BUG-BENCH-PUBLIC: benchmark zapisuje w publicznym /storage (tool deweloperski)
- BUG-SS-1/SS-3: SessionStore INSERT OR REPLACE + init() na Main thread
- BUG-16/17: ManualTokenSection brak selektor typu / brak TOKEN_KWOTA
- BUG-LIB-6: „Pobierz plik" w DepseudonymizationScreen używa File() — nie działa Android 11+ (fix: MediaStore)
