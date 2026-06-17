package com.lynxmask.app

// StructuralEngine.kt — Wzorce regex warstwy strukturalnej
// Wersja: 1.8
//
// Zmiany v1.8 (sesja benchmark):
//   - IBAN-FIX: dodano dwa wzorce IBAN:
//       • PL IBAN strukturalny: \bPL[-\s]?\d{2}(?:[-\s]?\d{4}){6}\b
//         (obsługuje format ciągły, z grupami spacji i z myślnikami)
//       • IBAN z kontekstem "IBAN:": dla polskich i zagranicznych numerów UE
//     Wcześniej: wzorzec VAT EU \b[A-Z]{2}\d{8,12}\b nie łapał IBAN
//     (PL + 26 cyfr = 28 znaków, przekracza limit 12 cyfr wzorca VAT).
//     Benchmark: IBAN 0% recall → oczekiwany wzrost po naprawie.
//   - EMAIL-FIX: dodano wzorzec kontekstowy "e-mail: ..." jako fallback
//     gdy OCR gubi znak @. Wzorzec strukturalny zachowany bez zmian.
//
// Zmiany v1.7 (Potok 3c — refaktor systemowy):
//   - KONTEKSTOWE-FIX: zastąpiono 3 wzorce PESEL OCR (v1.6) jednym wzorcem
//     kontekstowym. Podejście systemowe: słowo kluczowe + cyfry do ostatniej
//     jest odporniejsze na dowolny format OCR niż enumerowanie formatów spacji.
//   - NOWY BLOK 0 — 9 wzorców kontekstowych dokumentów tożsamości i uprawnień:
//       • PESEL: pe[s5][e3]l + \d[\d \t\-]{8,16}\d
//         (obsługuje OCR S→5, E→3; spacje/myślniki między cyframi)
//       • Dowód osobisty: dow[oó]d + 3 litery + 6 cyfr (OCR-tolerant)
//         dow[oó]d obsługuje OCR bez znaku ó; nie łapie "DO" (przyimek)
//       • Paszport: paszport + 2 litery + 7 cyfr (OCR-tolerant)
//       • Prawo jazdy: prawo/prawa jazdy + ciąg alfanumeryczny
//       • Karta pobytu: karta/kartę pobytu + ciąg alfanumeryczny
//       • Legitymacja służbowa: każdy rodzaj (policja, wojsko, sejm, straż)
//       • Książeczka żeglarska
//       • Uprawnienia budowlane (format z ukośnikami np. SWK/0031/PWOK/12)
//       • "Seria i numer": ogólny fallback gdy dokument nie jest wymieniony wprost
//   - Wzorce kontekstowe umieszczone NA POCZĄTKU listy (przed strukturalnymi)
//     bo kontekst słowny jest silniejszym sygnałem niż sama struktura cyfr.
//   - Wzorce strukturalne PESEL (pozycje 9-10: ciągłe 11 cyfr + spacja po 6)
//     ZACHOWANE — działają gdy numer PESEL wystąpi bez słowa kluczowego (tabele).
//   - Wariant O→0 / l→1 nadal pominięty — wymaga próbek logcatu.
//
// Zmiany v1.6 (Potok 3c):
//
// Zmiany v1.5 (Potok 3b):
//   - BUG-06-FIX: sygnatura akt (pozycja 31) — usunięto RegexOption.IGNORE_CASE.
//     Z flagą IGNORE_CASE wzorzec matchował "tak/nie/jest", "a/b/test" itp.
//     (3 człony po ukośniku ≤8 znaków każdy). Sygnatury akt w PL używają
//     uppercase — flaga niepotrzebna, powodowała fałszywe NUMER.
//   - BUG-KOD-POCZTOWY-FIX: wzorce 32 i 33 (ADRES z kodem pocztowym).
//     Dwie zmiany: (a) \d{3} → \d{3,4} obsługuje OCR artifact "65-5110"
//     (skaner dodaje cyfrę do kodu pocztowego); (b) lookahead
//     (?!\s*(?:19|20)\d{2}\b) umieszczony PRZED grupą cyfr blokuje rok
//     (np. "15-2024") jako fałszywy kod. Lookahead po \d{3,4} nie działa —
//     rok jest częścią samego dopasowania, nie tym co po nim.
//
// Zmiany v1.1:
//   - NIP: dodany format 3-2-2-3 (np. 521-33-15-332) obok istniejącego 3-3-2-2
//   - Telefon stacjonarny: dodany format kierunkowy (81 123-45-67)
//   - PWZ: rozszerzony kontekst — łapie też "nr 1234567" i "nr. lekarza 1234567"
//
// Zmiany v1.2 (sesja 7):
//   - ADDR-FIX: adres ul./al. — \s → [^\S\n] w nazwie ulicy; poprzednio regex
//               mógł zszywać fragmenty z różnych akapitów przez newline
//   - SYG-FIX: sygnatura sądowa — usunięto (?i); [IVXLCDM]+ z IGNORE_CASE
//               łapało polskie spójniki "i","v","l" jako cyfry rzymskie
//
// Zmiany v1.3 (sesja 10):
//   - BUG-OCR-1-FIX: wzorzec numeru budynku/lokalu: część po ukośniku zmieniona
//     z \d{1,4} na \d{1,2}. Rationale: numery lokali/apartamentów mają ≤2 cyfry.
//     Rok (2014, 1999) i grosze (100) mają 3-4 cyfry — nie mogą być numerem lokalu.
//     Wcześniej "651/2014" (rozporządzenie UE) i "00/100" (grosze) były wykrywane
//     jako TOKEN_ADRES, zaburzając wynik dla paragrafów prawnych i kwot.
//   - TODO-7 (komentarz): ochrona dat przez lookahead w CATCHALL — patrz sekcja CATCHALL
//
// Zmiany v1.4 (Potok 3):
//   - BUG-AL-FIX: wzorzec adresu z kodem pocztowym (pozycja 33) — dodany opcjonalny
//     prefiks (?:(?i:ul\.|al\.|pl\.|os\.)[^\S\n]+)? przed \b[A-ZŁŚŹĆŃĄĘÓŻ].
//     Problem: wzorzec wymagał wielkiej litery na starcie; "al. Niepodległości 12,
//     60-001 Poznań" zaczynało się od małego "a" (prefiks) → zero matchów w wzorcu 33.
//     Fix: prefiks opcjonalny z inline-flag (?i:...) ograniczonym do grupy prefiksu.
//     Bezpieczne: dla adresów bez prefiksu zachowanie identyczne z v1.3 (\b nadal
//     wymagane przed nazwą ulicy); wzorzec 37 (ul./al. bez kodu) zostaje niezmieniony.
//   - BUG-05-FIX: tablice rejestracyjne — zmieniono \d{2,5} → \d{4,5} (Opcja A).
//     Problem: wzorzec był za szeroki — łapał kody produktów z 2–3 cyframi.
//     Polskie tablice mają ≥4 cyfr w członie numerycznym; zmiana eliminuje krótkie
//     fałszywe dopasowania bez ryzyka regresji na testach z realnymi tablicami.
//     UWAGA: wzorzec nadal może matchować kody z ≥4 cyframi (np. "WZ12345").
//     Pełna eliminacja wymagałaby słowa kontekstowego (Opcja B) — następny potok.

// ============================================================
// Warstwa 2 — Regex strukturalne
// Kolejność KRYTYCZNA — bardziej specyficzne przed ogólnymi
// ============================================================
internal val STRUCTURAL_PATTERNS: List<Pair<String, Regex>> = listOf(

    // --- Email --- (przeniesiony na pozycję 0 — musi być przed CATCHALL \d{9} i VAT EU [A-Z]{2}\d{8,12})
    // Wzorzec strukturalny — wymaga @
    TOKEN_NUMER to Regex("""\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b"""),
    // Wzorzec z kontekstem "e-mail:" / "email:" — OCR-tolerant, łapie gdy @ zgubiony
    TOKEN_NUMER to Regex("""(?i)\be[- ]?mail\s*[:–\-]\s*[a-zA-Z0-9._%+\-@]+\.[a-zA-Z]{2,}\b"""),

    // ============================================================
    // Blok 0 — Kontekstowe wzorce dokumentów tożsamości i uprawnień
    // Zasada systemowa: słowo kluczowe + ciąg alfanumeryczny do ostatniego znaku
    // OCR-tolerant: separator między słowem kluczowym a numerem może być dowolny
    //   ([^\S\n]*[:–\-]?[^\S\n]*), a wewnątrz numeru spacje/myślniki są dozwolone.
    // Umieszczone PRZED wzorcami strukturalnymi — kontekst jest silniejszym sygnałem.
    // Wzorce strukturalne (pozycje niżej) łapią numery BEZ słów kluczowych (tabele).
    // ============================================================

    // PESEL z kontekstem
    // pe[s5][e3]l — obsługuje OCR: E→3 ("PES3L" ✓), S→5 ("PE5EL" ✓)
    // (?:[^\S\n]+\w+)? — opcjonalne jedno słowo między PESEL a cyframi:
    //   "PESEL: 6505..." ✓, "PESEL pacjenta: 6505..." ✓, "PESEL nr 6505..." ✓
    // \d[\d \t\-]{8,16}\d — cyfry z opcjonalnymi separatorami OCR
    TOKEN_NUMER to Regex("""(?i)\bpe[s5][e3]l\b(?:[^\S\n]+\w+)?[^\S\n]*[:–\-]?[^\S\n]*\d[\d \t\-]{8,16}\d"""),

    // Dowód osobisty z kontekstem
    // dow[oó]d — obsługuje OCR bez znaku ó ("dowod osobisty" ✓, "dowód" ✓)
    // Nie matchuje samego "DO" (przyimek) — wymaga dow+[oó]+d
    // d\.?[^\S\n]*o\. — skrót "D.O." / "D. O." / "d.o."
    // Format numeru: 2–3 litery + 5–11 znaków + ostatnia cyfra
    TOKEN_NUMER to Regex("""(?i)(?:dow[oó]d\b(?:[^\S\n]+os\w{0,7})?|d\.?[^\S\n]*o\.)[^\S\n]*[:–\-]?[^\S\n]*[A-Z]{2,3}[\w \t\-]{5,11}\d"""),

    // Paszport z kontekstem
    // paszport\w{0,2} — "paszport", "paszportu", "paszportem"
    // Format PL: 2 litery + 7 cyfr; OCR-tolerant: [\d \t\-]{5,14}\d
    TOKEN_NUMER to Regex("""(?i)\bpaszport\w{0,2}\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z]{1,2}[\d \t\-]{5,14}\d"""),

    // Prawo jazdy z kontekstem
    // praw[ao] jazdy — "prawo jazdy" i "prawa jazdy" (dopełniacz)
    // Format PL (nowy wzór od 2013): ciąg alfanumeryczny ~15 znaków
    TOKEN_NUMER to Regex("""(?i)\bpraw[ao][^\S\n]+jazdy\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z0-9][\w \t\-]{8,20}\d"""),

    // Karta pobytu z kontekstem — format alfanumeryczny, zmienny historycznie
    // kart[aę] — "karta" i "kartę" (biernik, forma w dokumentach)
    TOKEN_NUMER to Regex("""(?i)\bkart[aę][^\S\n]+pobyt\w{0,2}\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z0-9][\w \t\-]{5,18}[A-Z0-9]"""),

    // Legitymacja służbowa z kontekstem
    // Nie ograniczamy rodzaju: policja, wojsko, straż, sejm, ratownictwo itd.
    // Każda legitymacja służbowa zawiera dane osobowe — maskujemy numer
    TOKEN_NUMER to Regex("""(?i)\blegitymacj\w{1,3}\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z0-9][\w \t\-]{3,18}[A-Z0-9\d]"""),

    // Książeczka żeglarska z kontekstem
    // (?:książeczk|ks[i1][aą][zżź]eczk) — obsługuje brak diakrytyków OCR:
    //   "ksiazeczka" ✓, "ks1azeczka" (OCR i→1) ✓, "książeczka" ✓
    // (?:żegl|zegl) — "żeglarska" i "zeglarska" (OCR ż→z)
    TOKEN_NUMER to Regex("""(?i)(?:książeczk|ks[i1][aą][zżź]eczk)\w{1,3}[^\S\n]+(?:żegl|zegl)\w{0,6}\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z0-9][\w \t\-]{3,18}[A-Z0-9\d]"""),

    // Uprawnienia budowlane z kontekstem
    // Format zawiera ukośniki (np. SWK/0031/PWOK/12) — dodano / do klasy znaków numeru
    // UWAGA: wzorzec sygnatury akt (poz. niżej) też łapie ukośniki — tu kontekst rozstrzyga
    TOKEN_NUMER to Regex("""(?i)\buprawni\w{0,8}[^\S\n]+bud\w{0,8}\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z0-9][\w/ \t\-]{4,25}[A-Z0-9\d]"""),

    // "Seria i numer" — ogólny kontekst gdy dokument nie jest wymieniony wprost
    // Łapie: "seria i numer dokumentu: ABC123456", "seria i nr: AB1234567"
    // Może też złapać numer faktury/zamówienia — to celowe (dane wrażliwe w kontekście)
    TOKEN_NUMER to Regex("""(?i)\bseria[^\S\n]+i[^\S\n]+(?:numer|nr)\b[^\S\n]*[:–\-]?[^\S\n]*[A-Z]{1,3}[\w \t\-]{4,18}[A-Z0-9\d]"""),

    // ============================================================
    // Warstwa 2 — Regex strukturalne
    // Kolejność KRYTYCZNA — bardziej specyficzne przed ogólnymi
    // ============================================================
    TOKEN_NUMER to Regex("""\b\d{24}\b"""),

    // --- IBAN ---
    // v1.8: stare wzorce zastąpione — były zduplikowane i niekompletne.
    // Polski IBAN: PL + \s? (spacja opcjonalna po PL) + 2 cyfry kontrolne + 6 grup po 4 cyfry
    // Obsługuje: PL41169010149375012387120644 i PL41 1690 1014 9375 0123 8712 0644
    TOKEN_NUMER to Regex("""\bPL[-\s]?\d{2}(?:[-\s]?\d{4}){6}\b"""),
    // IBAN z kontekstem "IBAN:" — dla polskich i zagranicznych numerów UE
    TOKEN_NUMER to Regex("""(?i)\bIBAN\s*:?\s*[A-Z]{2}\d{2}(?:\s?\d{4}){3,7}\b"""),
    // IBAN z kontekstem "konto" — fallback gdy OCR wstawia spacje w nieregularnych miejscach
    // Łapie: "konto komornika: PL41 169010 14937..." niezależnie od podziału na grupy
    TOKEN_NUMER to Regex("""(?i)\bkont\w{0,3}\s+\S{0,20}\s*[:–\-]\s*(PL[\d\s]{24,34})\b"""),
    // Konto bez prefiksu PL ze spacjami grupującymi (61 1090 1014 0000 0712 1981 2874)
    TOKEN_NUMER to Regex("""\b\d{2}(?:\s\d{4}){5,6}\b"""),
    // Konto bez prefiksu PL z myślnikami (61-1090-1014-0000-0712-1981-2874)
    TOKEN_NUMER to Regex("""\b\d{2}-\d[\d\-]{20,28}\d\b"""),

    // --- Numery rejestrowe firm ---
    TOKEN_NUMER to Regex("""\bKRS\s*\d{10}\b""", RegexOption.IGNORE_CASE),
    TOKEN_NUMER to Regex("""\bHRB\s*\d{4,8}\b""", RegexOption.IGNORE_CASE), // Niemcy
    TOKEN_NUMER to Regex("""\bBDO\s*\d{6,9}\b""", RegexOption.IGNORE_CASE),

    // --- PESEL przed REGON ---
    TOKEN_NUMER to Regex("""(?<!\d)\d{11}(?!\d)"""),
    TOKEN_NUMER to Regex("""\b\d{6} \d{5}\b"""),  // PESEL ze spacją (błąd OCR: 650511 12345)

    // --- REGON 14-cyfrowy ---
    TOKEN_NUMER to Regex("""\b\d{14}\b"""),

    // --- NIP warianty ---
    // Format 3-3-2-2 (np. 521-334-15-33) — pierwotny; [-\s.] obejmuje też OCR-artefakt kropki
    TOKEN_NUMER to Regex("""(?<!\d)\d{3}[-\s.]?\d{3}[-\s.]?\d{2}[-\s.]?\d{2}(?!\d)"""),
    // Format 3-2-2-3 (np. 521-33-15-332) — dodany v1.1, musi być przed kodem pocztowym
    TOKEN_NUMER to Regex("""\b\d{3}[-\s]?\d{2}[-\s]?\d{2}[-\s]?\d{3}\b"""),
    TOKEN_NUMER to Regex("""\bPL\d{3}[-\s]?\d{3}[-\s]?\d{2}[-\s]?\d{2}\b""", RegexOption.IGNORE_CASE),
    TOKEN_NUMER to Regex("""\b[A-Z]{2}\d{8,12}\b"""),                       // VAT EU ogólny

    // --- IBAN ---
    // Polski IBAN: PL + 26 cyfr = 28 znaków łącznie.
    // Spacje grupujące (format bankowy): PL05 3250 0003 ... — obsługiwane przez \s?
    // OCR może dodawać lub pomijać spacje między grupami cyfr.
    TOKEN_NUMER to Regex("""\bPL[-\s]?\d{2}(?:[-\s]?\d{4}){6}\b"""),
    // IBAN z kontekstem "IBAN:" — dla polskich i zagranicznych numerów UE.
    TOKEN_NUMER to Regex("""(?i)\bIBAN\s*:?\s*[A-Z]{2}\d{2}(?:\s?\d{4}){3,7}\b"""),

    // --- REGON 9-cyfrowy ---
    TOKEN_NUMER to Regex("""\b\d{9}\b"""),

    // --- Telefony ---
    TOKEN_NUMER to Regex("""\b(?:\+?48[-\s]?)?\d{3}[-\s]?\d{3}[-\s]?\d{3}\b"""),  // PL komórkowy/miejski 9 cyfr
    // Telefon stacjonarny z kierunkowym: "81 123-45-67", "12 345 67 89" — dodany v1.1
    TOKEN_NUMER to Regex("""\b\d{2}[\s\-]\d{3}[\s\-]?\d{2}[\s\-]?\d{2}\b"""),
    TOKEN_NUMER to Regex("""\+\d{1,3}[\s\-]?\(?\d{1,4}\)?[\s\-]?\d{3,15}"""),      // Międzynarodowy

    // --- Kwoty z walutami (format PL i EU) ---
    // (?!00\s) wyklucza "00 PLN" — artifact OCR gdy "350,00 PLN" łamane przez linię
    TOKEN_KWOTA to Regex(
        """\b(?!00\s)\d{1,6}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?\s*(?:zł|PLN|EUR|USD|GBP|CHF|DKK|NOK|CZK|HUF|RON)\b""",
        RegexOption.IGNORE_CASE
    ),

    // --- Dokumenty tożsamości ---
    // BUG-DOWOD-FIX v2: OCR z telefonu (lvl3) daje małe litery serii ("foh614892")
    // oraz/lub spację w środku cyfr ("FOH 614 892").
    // (?i): case-insensitive — OCR nie gwarantuje wielkich liter w serii.
    // \d{3}[^\S\n]?\d{3}: cyfry 3+3 z opcjonalną spacją między grupami.
    TOKEN_NUMER to Regex("""(?i)\b[A-Z]{3}[^\S\n]?\d{3}[^\S\n]?\d{3}\b"""),  // Dowód osobisty PL
    TOKEN_NUMER to Regex("""\b[A-Z]{3}\s+nr\s+\d{6}\b""", RegexOption.IGNORE_CASE), // Dowód "seria XXX nr NNNNNN"
    TOKEN_NUMER to Regex("""\b[A-Z]{2}\s?\d{7}\b"""),   // Paszport PL
    // PWZ lekarza — rozszerzony v1.1: "PWZ: 1234567", "nr 1234567", "nr. lekarza 1234567"
    // Kontekst wymagany żeby uniknąć false positive na każde 7 cyfr
    TOKEN_NUMER to Regex(
        """(?i)(?:PWZ[:\s]+|nr\.?\s+(?:lekarza|prawa\s+wyk[^\s]{0,20})\s+)\d{7}\b"""
    ),
    TOKEN_NUMER to Regex("""\bPWZ:\s?\d{7}\b"""),        // Nr lekarza (format jawny)

    // --- Numery rejestracyjne pojazdów ---
    // BUG-05-FIX v1.4: zmieniono \d{2,5} → \d{4,5}.
    // Poprzedni wzorzec matchował kody alfanumeryczne z 2–3 cyframi ("WZ12", "ISO90").
    // Polskie tablice mają co najmniej 4 cyfry — zawężenie jest bezpieczne dla testów.
    TOKEN_NUMER to Regex("""\b[A-Z]{2,3}\s?\d{4,5}[A-Z]{0,2}\b"""),

    // --- VIN ---
    TOKEN_NUMER to Regex("""\b[A-HJ-NPR-Z0-9]{17}\b"""),

    // --- Adres IP ---
    TOKEN_NUMER to Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""),

    // --- Kod ICD-10 ---
    TOKEN_NUMER to Regex("""\b[A-Z]\d{2}(?:\.\d{1,2})?\b"""),

    // --- BIC/SWIFT (kontekstowy) ---
    TOKEN_NUMER to Regex("""(?i)(?:BIC|SWIFT)[\s:]+([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\b"""),

    // --- Numery działek geodezyjnych ---
    TOKEN_NUMER to Regex("""(?i)(?:działki?|nr działki)\s+\d+(?:/\d+)?"""),

    // --- Identyfikatory alfanumeryczne (ID-UZ-77412, CERT-8841, ZW-PS-0336) ---
    TOKEN_NUMER to Regex("""\b[A-Z]{2,6}[-:/][A-Z0-9]{2,10}(?:[-:/][A-Z0-9]{2,10})?\b"""),

    // --- Sygnatura akt ---
    // BUG-06-FIX v1.5: usunięto RegexOption.IGNORE_CASE.
    // Z IGNORE_CASE: "tak/nie/jest" (3 człony po ukośniku, każdy ≤8 znaków)
    // pasowało do wzorca → fałszywy NUMER. Sygnatury akt w dokumentach PL
    // mają człony uppercase i/lub cyfry — IGNORE_CASE było zbędne.
    // Test regresji: "tak/nie/jest" → brak maskowania.
    TOKEN_NUMER to Regex(
        """(?<!\w)(?=[0-9A-Za-z/]*[A-Za-z])[A-Z0-9]{1,8}(?:/[A-Z0-9]{1,8}){2,}(?!\w)"""
    ),

    // --- Sygnatura administracyjna (np. PT.070433.2020, OW.085394.2025) ---
    TOKEN_NUMER to Regex("""\b[A-Z]{2}\.\d{6}\.\d{4}\b"""),

    // --- Numer wewnętrzny 3-2-2 ---
    TOKEN_NUMER to Regex("""\b\d{3}[\s\-]\d{2}[\s\-]\d{2}\b"""),

    // --- Sygnatura sądowa/notarialna z odstępem (np. II K 123/25, I C 456/26, A 4567/2026) ---
    // Musi być PRZED wzorcem budynku, żeby nie była brana za adres
    // SYG-FIX v1.2: usunięto (?i) — z IGNORE_CASE polskie "i","v","l" (spójniki)
    // pasowały do [IVXLCDM]+ jako cyfry rzymskie → false positive "i C 456/26"
    // Sygnatury w dokumentach PL są zawsze wielką literą — case sensitivity poprawna
    TOKEN_NUMER to Regex("""\b(?:[IVXLCDM]+\s+)?[A-Z]{1,3}\s+\d{1,6}/\d{2,4}\b"""),

    // --- CATCHALL: ciągi cyfr 8+ (przepisany z negatywnym lookahead) ---
    // TODO-7 (sesja 10): Daty NIE mają osobnej jawnej reguły ochrony — są chronione
    // "przez przypadek" przez tę regułę i przez interpunkcję:
    //   • "15.01.2024" — kropki przerywają ciąg cyfr → \d{8,} nie pasuje → data bezpieczna
    //   • "20240115" (YYYYMMDD, 8 cyfr) — NIE jest chroniona lookaheadem
    //     (lookahead sprawdza tylko (?:19|20)\d{2}\b — wymaga \b po 4 cyfrach,
    //      a "20240115" nie ma \b w środku). To jest znane ograniczenie.
    //   • Rok samodzielny (1999, 2024) — (?!(?:19|20)\d{2}\b) wyklucza go z CATCHALL.
    // Jeśli kiedykolwiek zmienisz tę regułę lub CATCHALL, dodaj testy dla wszystkich
    // formatów dat (patrz PseudonymEngineTest.kt TODO-7).
    // Nie łapie: lat 1900-2099, wartości z jednostkami, pozycji < 8 cyfr
    TOKEN_NUMER to Regex("""\b(?!(?:19|20)\d{2}\b)\d{8,}\b""")
)

// ============================================================
// Warstwa adresowa — uruchamiana PO NameEngine (applyContextualBlacklist),
// żeby NameEngine widział pełne adresy jako kontekst dla rozpoznania imion.
// Przeniesione z STRUCTURAL_PATTERNS — zachowane wszystkie komentarze i fixy.
// ============================================================
internal val ADDRESS_PATTERNS: List<Pair<String, Regex>> = listOf(

    // --- Adresy z kodem pocztowym PL ---
    // BUG-KOD-POCZTOWY-FIX v1.5:
    // \d{2}-\d{3} → \d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4}
    // Lookahead przed cyframi kodu: "15-2024" (rok) nie przejdzie, bo po "-"
    // lookahead widzi "20" + "\d{2}" + \b → blokuje.
    // "65-5110" przejdzie: "51" nie pasuje do (?:19|20) → lookahead nic nie blokuje.
    // "60-001" przejdzie: "00" nie pasuje do (?:19|20) → OK.
    TOKEN_ADRES to Regex(
        """(?:(?i:ul\.|al\.|pl\.|os\.)[^\S\n]+)?\b[A-ZŁŚŹĆŃĄĘÓŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{1,29}(?:\s+[A-ZŁŚŹĆŃĄĘÓŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{1,29})?\s+\d{1,4}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?[,\s]+\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4}[,\s]+[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ ,]{2,40}\b"""
    ),
    // BUG-KOD-POCZTOWY-FIX v1.5: analogicznie — wzorzec 33 (kod + miejscowość).
    TOKEN_ADRES to Regex("""\b\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4}[,\s]+[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ ]{2,40}\b"""),

    // --- Adres z ul./al./pl./os. bez kodu pocztowego ---
    // ul. Długa 7, al. Róż 12A, ul. Kazimierza Wielkiego 14/3
    // ADDR-FIX v1.2: [^\S\n] zamiast \s w nazwie ulicy — zapobiega dopasowaniu
    // przez newline (np. łączeniu "ul. Długa" z akapitu 1 z "14/3" z akapitu 2)
    TOKEN_ADRES to Regex(
        """(?i)(?:ul\.|al\.|pl\.|os\.)[^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z[^\S\n]\-]{1,50}[^\S\n]+\d{1,4}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?(?!/[\d])"""
    ),

    // --- Numer budynku/lokalu (np. 4/6, 12A/3B, 47/2) ---
    // BUG-OCR-1-FIX v1.3: zmieniono \d{1,4} → \d{1,2} po ukośniku.
    // Numer lokalu/apartamentu ma co najwyżej 2 cyfry (lokal 99 to już bardzo duże).
    // Rok (2014, 1999) i grosze (100) mają 3-4 cyfry → nie mogą być numerem lokalu.
    // Poprzednio: "651/2014" (rozporządzenie UE nr 651/2014) → fałszywy TOKEN_ADRES.
    // Poprzednio: "00/100 złotych" (grosze w kwocie słownej) → fałszywy TOKEN_ADRES.
    // (?!/[\d]) wyklucza daty złożone: 30/05/2026 (po 05 następuje /2026)
    TOKEN_ADRES to Regex("""\b\d{1,4}[A-Za-z]?/\d{1,2}[A-Za-z]?(?!/[\d])\b"""),
)
