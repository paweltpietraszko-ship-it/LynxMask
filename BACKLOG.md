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
| **1** | Ręczny prostokąt: podpis / pieczątka; opcjonalnie „zostaw widoczne" na zaznaczonym | **Cursor** | 🔲 |
| **2** | Kolejność: redakcja obrazu → OCR tekstu (do ustalenia w spike) | **Cursor** | 🔲 |

**Pliki docelowe:** `ShareTargetActivity.kt`, `ImageRedactionPipeline.kt` (roboczo).

**Out of scope F0:** auto-podpis; PDF/DOCX jako raster.

**Kryterium sukcesu F0:** twarz zblurowana domyślnie; użytkownik może cofnąć blur na wybranym regionie i świadomie wysłać.

**Stan F0:** zaimplementowane (Claude Code, sesja 23.06.2026). TODO-10 onboarding do usunięcia w Fazie 1.

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

| # | Bug | Skutek |
|---|---|---|
| BUG-FLAG-LIMIT | NameEngine: max 5 flag algorytmicznych | 6+ encji nieznanych → eksport odblokowany mimo niezamaskowanych fragmentów |
| BUG-SCAN-P1 | ShareTargetActivity: OCR tylko str. 1 | PII na str. 2+ niewidoczne, użytkownik myśli że dokument czysty |
| BUG-AUTH-RESET | LoginScreen: "Zapomniałem hasła" | Dostęp do biblioteki bez uwierzytelnienia |
| AUDIT-01 | OcrQuality.kt: martwy kod | Bramka jakości OCR nigdy nie odpala — zły skan przechodzi bez ostrzeżenia |
| BUG-DELETE-DICT | "Usuń wszystkie dane" | Nie czyści UserDictionary ani GuardAllowlist — PII zostaje |

---

## NASTĘPNA SESJA — UL (UI Library)

Biblioteka dokumentów — pełny audyt flow:
- **BUG-LIB-EDIT**: „Edytuj dokument" nie działa
- Pozostałe bugi nieznane — odkryć podczas testowania

Pliki: SessionStore.kt + composable biblioteki w MainActivity

---

## KOLEJKA SILNIKA

| # | Zadanie | Plik |
|---|---|---|
| N2 | BUG-NIP-SPLIT: NIP naprawiony do połowy, silnik łapie fragmenty jako dwa NUMER | OcrNormalizer |
| N3 | OCR_EMAIL_LOCALSPACE: wiele spacji — tylko jedna naprawiana | OcrNormalizer |
| S4 | Kwoty słownie: „dwadzieścia tysięcy złotych" | StructuralEngine |
| S6 | Sklejanie nazwisk: „Kowal ski" — OCR rozbija spacją | NameEngine |
| S7 | BUG-EMAIL-TOKEN: email wykrywany jako NUMER | StructuralEngine |
| S11 | Email z imieniem w local-part: konflikt wzorca email z NameEngine | StructuralEngine |

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
