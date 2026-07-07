package com.lynxmask.app

// StructuralEngine.kt — Wzorce regex warstwy strukturalnej
// Wersja: 2.3
//
// Zmiany v2.2 (sesja 23.06 — BUG-NIP-CTX-3223):
//   - Dodano drugi wzorzec kontekstowy NIP dla formatu 3-2-2-3 (XXX-XX-XX-XXX).
//     Poprzednio wzorzec kontekstowy obsługiwał tylko 3-3-2-2 → NIP w formacie
//     3-2-2-3 był maskowany przez wzorzec strukturalny, ale "NIP:" zostawało w tekście.
//     Fix: nowy wzorzec tuż po 3-3-2-2, celowo poza NIP_PATTERN_STRINGS (S5 bypass).
//
// Zmiany v2.1 (sesja 22.06 — NIP context pattern no-S5):
//   - Dodano wzorzec kontekstowy NIP (L200): \bNIP\b + cyfry, S5 pominięte.
//     OCR może przekręcić jedną cyfrę NIPu → suma błędna → bez tego wzorca prawidłowe
//     NIPy z keywordem "NIP:" nie byłyby maskowane. Zasada S5 bypass opisana w komentarzu
//     przy NIP_PATTERN_STRINGS (L500+).
//
// Zmiany v2.0 (sesja 21.06 — S5 + BUG-PESEL-10 + BUG-FP-REFNUM):
//   - S5: Dodano isValidPesel() i isValidNip() — walidacja sum kontrolnych.
//     Filtr wbudowany w Warstwę 2 PseudonymEngine.kt dla wzorców PESEL i NIP.
//     Niepoprawna suma kontrolna → token nie jest przypisywany.
//     Uwaga: istniejące testy NIP używały NIPów z błędną sumą — zaktualizowane.
//   - BUG-PESEL-10: Rozszerzono wzorzec kontekstowy PESEL (z keywordem "pesel")
//     na minimum 5 cyfr (\d[\d \t\-]{3,16}\d zamiast {4,16}).
//     Wzorzec strukturalny \d{11} pozostaje bez zmian (ryzyko FP bez pełnego checksumu).
//   - BUG-FP-REFNUM: Wzorzec alfanumerycznych ID pozostawiony bez zmian.
//     Analiza benchmark_trace.txt: >80% dopasowań to prawdziwe PII
//     (numery faktur FV-*, umów KT/*, KL-*, VAT/*). Próg 50% FP nie osiągnięty.
//
// Zmiany v1.9b (sesja 21.06 — kontekstowe wzorce identyfikatorów):
//   - BLOK-0c: 4 nowe wzorce kontekstowe (format-agnostic, słowo kluczowe → numer):
//       • sygnatura: "sygn. akt I Co 3704/2018", "sygn. akt Km 808382024"
//         Nie wylicza kodów wydziałów — reaguje na etykietę "sygn." / "sygnatura akt".
//         Działa dla: akt, komornicze (Km), administracyjne, notarialne, urzędy.
//       • numer umowy: "nr umowy UMW/2022/966", "nr umowy U-00615/2024"
//         Format po słowie kluczowym: dowolny alfanumeryczny z ukośnikami.
//       • numer faktury: "nr faktury FV-01079/04/2024", "faktura nr 4704/12/2020"
//       • numer kw: "nr KW PO1P/00424625/8", "księgi wieczystej nr M/00787548/4"
//     Uzasadnienie: doraźne patche strukturalne (regex na format) nie skalują się —
//     każde pole ma dziesiątki formatów, każdy urząd/sąd inaczej je zapisuje.
//     Podejście kontekstowe (jedno słowo kluczowe → format-agnostic capture) jest
//     odporne na OCR i pokrywa wszystkie instytucje bez listy kodów.
//
// Zmiany v1.9 (sesja 21.06 — NUMER recall):
//   - SYGNATURA-FIX: sygnatura sądowa/komornicza — [A-Z]{1,3} → [A-Z][a-zA-Z]{0,2}.
//     Poprzednio: "I Co 3704/2018" ("Co"), "I Ns 6475/2022" ("Ns"), "Km 4917/2018" ("Km")
//     były pominięte, bo [A-Z]{1,3} nie pasuje do małych liter w kodach wydziałów.
//     Teraz: pierwszy znak musi być uppercase, następne 0–2 znaki mogą być dowolnej
//     wielkości. Pokrywa też Km bez prefiksu rzymskiego.
//   - DATA-UR-FIX: dodano wzorzec kontekstowy "data urodzenia: DD.MM.YYYY".
//     doc_00009: OCR "Data urodzenía: 17.09.1985" — encja JEST w tekście, ale silnik
//     nie miał żadnego wzorca na datę urodzenia. Wzorzec obsługuje: dat[aą] ur(odzenia)?
//     + DD.MM.YYYY (lub / lub -). Dopuszcza OCR akcent na "í".
//   - DOWOD-FIX: wzorzec strukturalny dowodu — \d{3}[^\S\n]?\d{3} → \d{2,3}[^\S\n]?\d{3,4}.
//     doc_00033: OCR "AWY57 1380" (spacja po 2 cyfrach zamiast 3) → poprzedni wzorzec
//     nie pasował bo oczekiwał dokładnie 3 cyfry w pierwszej grupie.
//   - DOWOD-CTX-FIX: separator w wzorcu kontekstowym dowodu — dodano \n? po [:–\-]?.
//     OCR "Nr dowodu osobistego:\nAWY57 1380" — keyword i numer w różnych liniach OCR.
//   - SYG-ADM-FIX: sygnatura administracyjna — \b[A-Z]{2}\.\d{6}\.\d{4}\b →
//     \b[A-Z]{2,3}[.\s]\d{6}[.\s]\d{4}\b. Obsługuje separator jako kropkę LUB spację
//     (OCR zamienia PT.075012.2018 → PT 075012 2018).
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
// S5 — Walidacja sum kontrolnych PESEL i NIP
// Obie funkcje przyjmują wyłącznie cyfry (bez separatorów).
// ============================================================

/**
 * Sprawdza sumę kontrolną PESEL (11 cyfr).
 * Wagi: 1,3,7,9,1,3,7,9,1,3 dla cyfr d0–d9; d10 = cyfra kontrolna.
 * Kontrolna = (10 - (suma % 10)) % 10.
 */
internal fun isValidPesel(digits: String): Boolean {
    if (digits.length != 11) return false
    val wagi = intArrayOf(1, 3, 7, 9, 1, 3, 7, 9, 1, 3)
    val suma = digits.take(10).mapIndexed { i, c ->
        c.digitToIntOrNull()?.times(wagi[i]) ?: return false
    }.sum() % 10
    val kontrolna = (10 - suma) % 10
    return digits[10].digitToIntOrNull() == kontrolna
}

/**
 * Sprawdza sumę kontrolną NIP (10 cyfr, bez separatorów).
 * Wagi: 6,5,7,2,3,4,5,6,7 dla cyfr d0–d8; d9 = cyfra kontrolna.
 * Jeśli suma % 11 == 10 → NIP strukturalnie nieprawidłowy.
 */
internal fun isValidNip(digits: String): Boolean {
    if (digits.length != 10) return false
    val wagi = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
    val suma = digits.take(9).mapIndexed { i, c ->
        c.digitToIntOrNull()?.times(wagi[i]) ?: return false
    }.sum() % 11
    if (suma == 10) return false
    return digits[9].digitToIntOrNull() == suma
}

// ============================================================
// PESEL standalone (D-class tolerant) — PRZENIESIONE z AnchorEngine.kt A.4c
// (BUG-MIGRACJA 01.07, feature/entity-migration). Wywoływane z PseudonymEngine.kt
// Runda 1, zaraz po STRUCTURAL_PATTERNS. Zachowanie identyczne co przed migracją —
// suma kontrolna jest jedynym wyjątkiem od wymogu kotwicy (brief AnchorEngine v2 §6.3).
// ============================================================
private const val PESEL_D = """[0-9OolIiSsBbZz]"""

// Sprawdza sumę kontrolną PESEL (11 cyfropodobnych). Separator (spacja/kreska) jest pomijany.
// Tolerancyjna na OCR (D-klasa: O→0, l/i→1, z→2, s→5, b→8) — inna niż isValidPesel()
// powyżej (ta wymaga gołych cyfr, używana przez S5 bypass dla STRUCTURAL_PATTERNS).
private fun isPeselChecksumValidTolerant(s: CharSequence): Boolean {
    val weights = intArrayOf(1, 3, 7, 9, 1, 3, 7, 9, 1, 3, 1)
    var n = 0; var sum = 0
    for (c in s) {
        val d = when (c.lowercaseChar()) {
            'o' -> 0; 'l', 'i' -> 1; 'z' -> 2; 's' -> 5; 'b' -> 8
            in '0'..'9' -> c - '0'
            else -> continue
        }
        if (n >= 11) return false
        sum += d * weights[n++]
    }
    return n == 11 && sum % 10 == 0
}

// BUG-PESEL-MOST-TOKEN-FIX (diagnoza Cursor 07.07, traceMode): [\s\-]? obejmował \n, więc
// regex mógł "przeskoczyć" przez nową linię z ogona wcześniej utworzonego tokenu (np. cyfry
// "001" z "NUMER_001") w prawdziwy PESEL na następnej linii, tworząc zanieczyszczone
// dopasowanie ("001\n5508107") które nie przechodzi sumy kontrolnej — a findAll i tak
// skonsumowało ten zakres, więc prawdziwy PESEL na tej linii nigdy nie dostawał osobnej
// szansy. PESEL nigdy nie rozciąga się na dwie linie, więc [ \t\-]? (bez \n) jest bezpieczne
// i ogólne — nie tylko łata ten jeden dokument.
private val peselShapeRe = Regex(
    """(?<![a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ0-9OolIiSsBbZz])(?:$PESEL_D[ \t\-]?){9,13}(?![a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ0-9OolIiSsBbZz])"""
)

// Ciąg 9-13 D-znaków (z opcjonalnym pojedynczym separatorem) → maskuje TYLKO gdy
// suma kontrolna PESEL dokładnie się zgadza (prawdopodobieństwo przypadku ~10% → wymagane).
internal fun applyPeselShapeChecksum(
    text: String,
    assignToken: (value: String, tokenType: String) -> String
): String {
    var t = text
    val peselHits = peselShapeRe.findAll(t).toList()
    t = peselHits.asReversed().fold(t) { acc, m ->
        if (TOKEN_RE.containsMatchIn(m.value)) acc
        else if (isPeselChecksumValidTolerant(m.value)) {
            val token = assignToken(m.value.trim(), TOKEN_NUMER)
            val before = acc.getOrElse(m.range.first - 1) { ' ' }
            val after = acc.getOrElse(m.range.last + 1) { ' ' }
            val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
            val suf = if (after.isLetterOrDigit() || after == '_') " " else ""
            acc.replaceRange(m.range, pre + token + suf)
        }
        else acc
    }
    return t
}

// CTX_STRAY (BUG-PESEL-OBCA-LITERA, 07.07): pojedyncza obca litera tolerowana w środku
// ciągu cyfr kontekstowych, gdy zaraz po niej jest znowu prawdziwa cyfra — patrz komentarz
// przy wzorcu PESEL niżej.
private const val CTX_STRAY = """[A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż]"""

// BUG-IBAN-OGON-KRADZIONY (07.07, znalezione ręcznym testem na telefonie — Paweł: "raz
// maskowany w całości, raz tylko ostatnie cyfry"; POPRAWIONE PO RAZ DRUGI po jawnej instrukcji
// Pawła "nigdy nie proponuj rozproszonych miejsc" — pierwsza próba dopisywała ten sam guard do
// N osobnych gołych wzorców kształtu z osobna, znajdując kolejne ofiary jedna po drugiej.
// PRAWDZIWY fix jest w JEDNYM miejscu: IBAN_EARLY niżej, pierwszy wzorzec w STRUCTURAL_PATTERNS,
// zabiera swoje terytorium ZANIM jakikolwiek goły wzorzec (REGON, telefon, CATCHALL) dostanie
// szansę zobaczyć fragment nierozpoznanego jeszcze "PL"+cyfry. Ten sam kształt co AnchorEngine
// A.6 (D-klasa + spacja/tab/myślnik, 20-42 znaki) — kotwica "PL" jest silniejszym sygnałem niż
// jakikolwiek goły wzorzec liczący same cyfry, więc powinna wygrywać pierwsza, nie bronić się
// na końcu. Jako efekt uboczny naprawia też "zawłaszczanie" etykiety (np. "konto komornika:")
// przez wzorzec kontekstowy niżej — IBAN_EARLY zabiera sam numer, zanim kontekstowy wzorzec
// zdąży dokleić etykietę.
private const val IBAN_EARLY = """(?<![A-ZŁŚŹĆŃĄĘÓŻa-z])PL[0-9OolIiSsBbZz \t\-]{20,42}"""

// ============================================================
// Warstwa 2 — Regex strukturalne
// Kolejność KRYTYCZNA — bardziej specyficzne przed ogólnymi
// ============================================================
internal val STRUCTURAL_PATTERNS: List<Pair<String, Regex>> = listOf(

    // --- IBAN_EARLY (BUG-IBAN-OGON-KRADZIONY, 07.07) --- musi być PIERWSZY wzorzec w całej
    // liście — kotwica "PL" + elastyczny ciąg cyfropodobny zabiera swoje terytorium zanim
    // jakikolwiek goły wzorzec kształtu (REGON, telefon, CATCHALL) dostanie szansę. Patrz
    // komentarz przy definicji stałej IBAN_EARLY wyżej.
    TOKEN_NUMER to Regex(IBAN_EARLY),

    // --- Email --- (przeniesiony na pozycję 0 — musi być przed CATCHALL \d{9} i VAT EU [A-Z]{2}\d{8,12})
    // TLD: [a-zA-Z][a-zA-Z0-9]{1,} — zaczyna się literą, może zawierać cyfry (OCR: "p1"→"pl", "c0m"→"com")
    TOKEN_EMAIL to Regex("""\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z][a-zA-Z0-9]{1,}\b"""),
    // Wzorzec z kontekstem — toleruje OCR: "e-nnail", "e-maii" (podwójne n/i)
    TOKEN_EMAIL to Regex("""(?i)\be[- ]?m[na]{1,2}i{1,2}l\s*[:–\-]\s*[a-zA-Z0-9._%+\-@]+\.[a-zA-Z][a-zA-Z0-9]{1,}\b"""),

    // ============================================================
    // Blok 0 — Kontekstowe wzorce dokumentów tożsamości i uprawnień
    // Zasada systemowa: słowo kluczowe + ciąg alfanumeryczny do ostatniego znaku
    // OCR-tolerant: separator między słowem kluczowym a numerem może być dowolny
    //   ([^\S\n]*[:–\-]?[^\S\n]*), a wewnątrz numeru spacje/myślniki są dozwolone.
    // Umieszczone PRZED wzorcami strukturalnymi — kontekst jest silniejszym sygnałem.
    // Wzorce strukturalne (pozycje niżej) łapią numery BEZ słów kluczowych (tabele).
    // ============================================================

    // PESEL z kontekstem
    // pe[s5][e3][lL1] — obsługuje OCR: E→3 ("PES3L" ✓), S→5 ("PE5EL" ✓), L→1 ("PESE1" ✓)
    // (?:[^\S\n]+\w+)? — opcjonalne jedno słowo między PESEL a cyframi:
    //   "PESEL: 6505..." ✓, "PESEL pacjenta: 6505..." ✓, "PESEL nr 6505..." ✓
    // \d[\d \t\-]{3,16}\d — minimum 5 cyfr (BUG-PESEL-10: OCR może zgubić 1 cyfrę;
    //   kontekst słowny "pesel" eliminuje FP przy tak krótkim ciągu cyfr)
    //   Poprzednio {4,16} = min 6 cyfr; teraz {3,16} = min 5 cyfr.
    // BUG-PESEL-KOD-SKLEJENIE-FIX (Cursor 01.07, pas bezpieczeństwa #3): usunięto `\-`
    // z klasy znaków — PESEL to ciągłe cyfry (max spacje/taby jako separator OCR), a
    // myślnik w tej klasie pozwalał dopasowaniu ciągnąć się w kod pocztowy/NIP (kształt
    // z myślnikami) sąsiadujący z PESEL-em. Prawdziwy fix jest w OcrNormalizer (Warstwa 0)
    // i StructuralEngine.applyPostalCityPatterns (Warstwa 1b) — to dodatkowy pas bezpieczeństwa.
    // BUG-PESEL-OBCA-LITERA (benchmark 500 dok. 07.07, doc_00025/doc_00355 — potwierdzone
    // ręcznym testem na telefonie): prawdziwy OCR na zaszumionym obrazie potrafi pomylić
    // POJEDYNCZĄ cyfrę z DOWOLNĄ literą, nie tylko znaną D-klasą ("4"→"A", "7"→"r" w dwóch
    // różnych dokumentach — nie da się tego enumerować literą po literze). Bez tolerancji ten
    // wzorzec (elastyczna klasa środkowa) potrafi dopasować się CZĘŚCIOWO — urwać tuż przed
    // obcą literą — co jest gorsze niż brak dopasowania: token PESEL powstaje, ale zjada tylko
    // część cyfr, a "ogon" (np. "A0") zostaje jawny TUŻ ZA tokenem, i słowo-kotwica "PESEL" jest
    // już skonsumowane, więc żadna kolejna warstwa nie dostanie już szansy go dokończyć. Fix:
    // pojedyncza obca litera w środku jest tolerowana TYLKO gdy zaraz po niej (z opcjonalnym
    // separatorem spacja/tab) jest znowu prawdziwa cyfra — odróżnia to "przerwę w cyfrach" od
    // "koniec numeru, zaczyna się inny tekst", więc nie wraca BUG-PESEL-SKLEJENIE-FIX powyżej.
    TOKEN_NUMER to Regex(
        """(?i)\bpe[s5][e3][lL1]\b(?:[^\S\n]+\w+)?[^\S\n]*[:–\-]?[^\S\n]*""" +
        """\d(?:[\d \t]|$CTX_STRAY(?=[ \t]?\d)){3,16}\d"""
    ),

    // NIP z kontekstem — analogicznie do PESEL: słowo kluczowe wystarczy, S5 pominięte.
    // OCR może przekręcić jedną cyfrę → suma błędna → bez tego wzorca prawidłowy NIP nie byłby maskowany.
    // Wzorce NIE są w NIP_PATTERN_STRINGS → S5 celowo nie stosowane.
    // (?:[^\S\n]+\w+)? — opcjonalny modyfikator: "NIP nabywcy:", "NIP świadka:", "NIP sprzedawcy:"
    TOKEN_NUMER to Regex("""(?i)\bN[IL1]P\b(?:[^\S\n]+\w+)?[^\S\n]*[:–\-]?[^\S\n]*\d{3}[-\s.]?\d{3}[-\s.]?\d{2}[-\s.]?\d{2}\b"""),
    // BUG-NIP-CTX-3223: format 3-2-2-3 (XXX-XX-XX-XXX)
    TOKEN_NUMER to Regex("""(?i)\bN[IL1]P\b(?:[^\S\n]+\w+)?[^\S\n]*[:–\-]?[^\S\n]*\d{3}[-\s.]?\d{2}[-\s.]?\d{2}[-\s.]?\d{3}\b"""),

    // Data urodzenia z kontekstem
    // dat[aą] ur(odzenia)? — obsługuje warianty:
    //   "data urodzenia: 21.05.1979"  ← pełne słowo
    //   "data ur. 21.05.1979"         ← skrót z kropką (\.? po \b)
    //   "Data urodzenía: 17.09.1985"  ← OCR í zamiast i ([ií] w klasie)
    //   "Data urodzenia:\n21.05.1979" ← newline po dwukropku (\n? w separatorze)
    // \b po ur(odzeni...)? blokuje "data urzędu" — 'z' po \b nie jest word boundary
    // (po ur w środku słowa nie ma \b bo następny znak też jest \w)
    TOKEN_NUMER to Regex("""(?i)\bdat[aą]\s+ur(?:odzen[ií][^\s:–\-\d]{0,2})?\b\.?[^\S\n]*[:–\-]?\n?[^\S\n]*\d{1,2}[./\-]\d{1,2}[./\-]\d{2,4}\b"""),

    // S-DATE-PL: data DD.MM.YYYY bez kontekstu — rok musi być 19xx lub 20xx
    // Separatory: kropka / ukośnik / przecinek (kreska jest lapana przez A.5b NIP-shape).
    // Rok poza zakresem (np. 1234) → brak masowania (test: "parametr 10.12.1234" → skip).
    TOKEN_NUMER to Regex("""\b\d{1,2}[./,]\d{1,2}[./,](?:19|20)\d{2}\b"""),

    // S-DATE-CTX: data po słowie kluczowym Dnia/Data — akceptuje 2-cyfrowy rok
    // Kontekst słowny ("Dnia", "Data:") jest dowodem że to data, nie losowe liczby.
    // Separator: kropka / ukośnik / przecinek / kreska; rok 2–4 cyfry.
    TOKEN_NUMER to Regex("""(?i)\b(?:dnia|dat[aą]\s*:?)[^\S\n]+\d{1,2}[./,\-]\d{1,2}[./,\-]\d{2,4}\b"""),

    // Dowód osobisty z kontekstem
    // dow[oó]d — obsługuje OCR bez znaku ó ("dowod osobisty" ✓, "dowód" ✓)
    // Nie matchuje samego "DO" (przyimek) — wymaga dow+[oó]+d
    // d\.?[^\S\n]*o\. — skrót "D.O." / "D. O." / "d.o."
    // Format numeru: 2–3 litery + 5–11 znaków + ostatnia cyfra
    // DOWOD-CTX-FIX v1.9: dodano \n? po [:–\-]? — OCR może mieć newline między
    // "Nr dowodu osobistego:" a samym numerem (np. w formularzu z polem na osobnej linii)
    TOKEN_NUMER to Regex("""(?i)(?:dow[oó]d\w{0,4}\b(?:\s+os\w{0,10})?|d\.?[^\S\n]*o\.)[^\S\n]*[:–\-]?\n?[^\S\n]*[A-Z0-9]{2,3}[\w \t\-]{5,11}\d"""),

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
    // Blok 0c — Kontekstowe wzorce identyfikatorów dokumentowych
    // Zasada: słowo kluczowe → cokolwiek za nim wygląda jak numer.
    // OCR-tolerant i format-agnostic: nie wyliczamy formatów numerów,
    // tylko reagujemy na etykiety (sygnatura, nr umowy, nr faktury, nr kw).
    // ============================================================

    // Sygnatura akt/komornicza z kontekstem "sygn." / "sygnatura akt"
    // Łapie: I Co 3704/2018, Km 4917/2018, Km 808382024 (OCR bez ukośnika)
    // Format po słowie kluczowym: 1-3 grupy liter + cyfry (z opcjonalnymi ukośnikami)
    // BUG-SYGNATURA-SPACJA-FIX (01.07, sam Claude — piąty wariant tego samego wzorca
    // bugu z dzisiejszej sesji): (?:[^\S\n]?/[^\S\n]?[\d/\-]{1,20})? na końcu — bez tego
    // OCR-owa spacja przed ukośnikiem ("234 /24") ucinała match na "234", zostawiając
    // " /24" jawne. AnchorEngine A.8 nie naprawiał bo matchOverlapsToken widział token.
    TOKEN_NUMER to Regex("""(?i)\bsygn(?:atura)?\.?(?:[^\S\n]+akt)?\b[^\S\n]*[:–\-]?[^\S\n]*(?:[A-Za-z]{1,4}[^\S\n]+){1,3}\d[\d/\-]{1,20}(?:[^\S\n]?/[^\S\n]?[\d/\-]{1,20})?\b"""),

    // Numer umowy z kontekstem "nr umowy" / "numer umowy"
    // Łapie: UMW/2022/966, U-00615/2024, KT/0001/2022
    // \n? dopuszcza newline między etykietą a numerem (OCR: label + enter + wartość)
    // BUG-NUMER-FAKTURA-VAT-FIX (diagnoza Cursor 07.07, uogólnione na 441/449): lookahead
    // (?=[A-Za-z0-9/\-]*\d) — capture identyfikatora musi zawierać choć jedną cyfrę.
    // Prawdziwy numer umowy/faktury/KW zawsze ma cyfrę; bez tego wymogu capture może złapać
    // zwykłe słowo opisowe zamiast prawdziwego identyfikatora (patrz uzasadnienie przy 445).
    TOKEN_NUMER to Regex("""(?i)\b(?:nr|numer)\.?[^\S\n]+umow[ya]\b[^\S\n]*[:–\-]?\n?[^\S\n]*(?=[A-Za-z0-9/\-]*\d)[A-Za-z0-9][A-Za-z0-9/\-]{3,22}\b"""),

    // Numer faktury z kontekstem "nr faktury" / "numer faktury" / "faktura nr"
    // Łapie: FV-01079/04/2024, 4704/12/2020, FV/2022/12
    // BUG-NUMER-FAKTURA-VAT-FIX (diagnoza Cursor 07.07): trzecia alternatywa — "faktura"/
    // "faktury" z do 5 dowolnymi słowami pośrednimi (np. "VAT", "VAT Nr", "uproszczona")
    // zamiast wymogu DOKŁADNIE sąsiadującego "nr"/"numer". Bez tego "Faktura VAT 26/06/006"
    // (bez słowa "nr" wcale) nie miał żadnego właściciela kontekstowego — łapał go dopiero
    // wzorzec sygnatury sądowej (linia ~650) tylko częściowo, zostawiając ostatni segment jawny.
    // Lookahead (?=[A-Za-z0-9/\-]*\d) — bez tego, gdy prawdziwy numer jest oddzielony
    // nową linią ("FAKTURA VAT\nNr FV-08217/08/2023"), ALT3 z zerem słów pośrednich fałszywie
    // łapała samo słowo "VAT" jako "identyfikator" (znalezione testem Pythona przed commitem).
    // TEST 07.07 (Paweł): wyłączone celowo — sprawdzamy czy AnchorEngine A.12 (kotwica
    // "Nr"/"FV"/"fv"/"f.v."/"f-ra" + sygnał wstecz "faktur|vat|uproszczon") sam wystarcza
    // jako jedyne miejsce zakrywające NUMER faktury, bez duplikatu w StructuralEngine.
    // Jeśli test wypadnie źle — odkomentować tę linię (patrz TODO.md).
    // TOKEN_NUMER to Regex("""(?i)\b(?:(?:nr|numer)\.?[^\S\n]+faktur[ay]|faktura[^\S\n]+(?:nr|numer)|faktur[ay](?:[^\S\n]+\w+){0,5})\b[^\S\n]*[:–\-]?\n?[^\S\n]*(?=[A-Za-z0-9/\-]*\d)[A-Za-z0-9][A-Za-z0-9/\-]{2,22}\b"""),

    // Numer KW (księgi wieczystej) z kontekstem
    // Łapie: PO1P/00424625/8, M/00787548/4
    // BUG-NUMER-FAKTURA-VAT-FIX (diagnoza Cursor 07.07, uogólnione): patrz uzasadnienie przy 441.
    TOKEN_NUMER to Regex("""(?i)\b(?:(?:nr|numer)\.?[^\S\n]+kw|ksi[eę]g[ia][^\S\n]+wieczyst\w{0,3}(?:[^\S\n]+(?:nr|numer))?)\b[^\S\n]*[:–\-]?\n?[^\S\n]*(?=[A-Za-z0-9/\-]*\d)[A-Za-z0-9][A-Za-z0-9/\-]{3,20}\b"""),

    // ============================================================
    // Warstwa 2 — Regex strukturalne
    // Kolejność KRYTYCZNA — bardziej specyficzne przed ogólnymi
    // ============================================================
    TOKEN_NUMER to Regex("""\b\d{24}\b"""),

    // --- IBAN ---
    // v1.8: stare wzorce zastąpione — były zduplikowane i niekompletne.
    // Polski IBAN: PL + \s? (spacja opcjonalna po PL) + 2 cyfry kontrolne + 6 grup po 4 cyfry
    // Obsługuje: PL41169010149375012387120644 i PL41 1690 1014 9375 0123 8712 0644
    // \b na końcu usunięte: gdy IBAN przylega bezpośrednio do następnego tokenu (sklejone),
    // \b failuje (cyfra→cyfra). {6} jest precyzyjne więc regex nie przejada sąsiednich tokenów.
    TOKEN_NUMER to Regex("""\bPL[-\s]?\d{2}(?:[-\s]?\d{4}){6}"""),
    // IBAN z kontekstem "IBAN:" — dla polskich i zagranicznych numerów UE
    // BUG-IBAN-ZAGRANICZNY-FIX (Paweł 07.07): sztywne grupy WYŁĄCZNIE po 4 cyfry zakładały że
    // (całkowita długość - 2 cyfry kontrolne) dzieli się przez 4 bez reszty — prawda dla PL (26),
    // fałsz dla wielu innych krajów UE (np. DE: 20 cyfr po kodzie kraju, 18 BBAN nie dzieli się
    // przez 4 → "00" na końcu zostawało jawne; podobnie FR). Opcjonalna końcowa grupa 1-3 cyfr
    // obsługuje resztę z dzielenia, niezależnie od konkretnego kraju/długości.
    TOKEN_NUMER to Regex("""(?i)\bIBAN\s*:?\s*[A-Z]{2}\d{2}(?:\s?\d{4}){2,7}(?:\s?\d{1,3})?"""),
    // IBAN z kontekstem "konto" — fallback gdy OCR wstawia spacje w nieregularnych miejscach
    // Łapie: "konto komornika: PL41 169010 14937..." niezależnie od podziału na grupy
    TOKEN_NUMER to Regex("""(?i)\bkont\w{0,3}\s+\S{0,20}\s*[:–\-]\s*(PL[\d\s]{24,34})\b"""),
    // IBAN garbled OCR — PL + 20–40 alfanum (bez lookahead — poprzedni wzorzec ReDoS na długim OCR)
    TOKEN_NUMER to Regex("""\bPL[A-Z0-9]{20,40}\b""", RegexOption.IGNORE_CASE),
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

    // --- REGON 9-cyfrowy ---
    TOKEN_NUMER to Regex("""\b\d{9}\b"""),

    // --- Telefony ---
    // KOLEJNOŚĆ KRYTYCZNA: kontekstowe (tel./kom./fax) PRZED strukturalnymi.
    // Bez tego linia \b\d{3}...\d{3}\b kradnie cyfry, zostawiając "tel." / "kom." na widoku.
    //
    // Rozszerzone słowa kluczowe: komórka, wew, gsm, nr tel
    TOKEN_NUMER to Regex("""(?i)\b(?:tel(?:efon)?|kom(?:órka)?|fax|faks|wew(?:nętrzny)?|gsm|nr[\s.]?tel)\.?(?:[^\S\n]+\w+)?[^\S\n]*[:–\-]?[^\S\n]*\+?\(?\d[\d\s\-\.\(\)]{5,20}\d\b"""),
    // +48 / +4B (OCR: 8→B) z prefiksem
    TOKEN_NUMER to Regex("""\+4[8Bb][-\s.]?\d{3}[-\s.]?\d{3}[-\s.]?\d{3}(?!\d)"""),
    // samo 48 jako prefix (bez +) — np. "48 601 234 567" w OCR bez znaku plusa
    TOKEN_NUMER to Regex("""(?<!\+)(?<!\d)\b48[-\s.]?\d{3}[-\s.]?\d{3}[-\s.]?\d{3}\b"""),
    // PL komórkowy/miejski bez prefiksu: "600 123 456", "22.765.43.21"
    // S10-FIX: separator [-\s.] zamiast [-\s]
    TOKEN_NUMER to Regex("""\b\d{3}[-\s.]?\d{3}[-\s.]?\d{3}\b"""),
    // Telefon stacjonarny z kierunkowym: "81 123-45-67", "12 345 67 89"
    TOKEN_NUMER to Regex("""\b\d{2}[\s\-.]?\d{3}[\s\-.]?\d{2}[\s\-.]?\d{2}\b"""),
    // S10: kierunkowy w nawiasach "(22) 765-43-21", "(12)345-67-89"
    TOKEN_NUMER to Regex("""\(\d{2}\)[^\S\n]?\d{3}[-\s.]?\d{2}[-\s.]?\d{2}\b"""),
    // BUG-MIEDZYNARODOWY-FIX (01.07): stary wzorzec kończył się \d{3,15} (wymaga
    // 3+ CIĄGŁYCH cyfr) — dla numeru z nierównym grupowaniem, np. "+48 501 23 567"
    // (3-2-3 zamiast 3-3-3), silnik cofał się i rozbijał "48" na "4"+"8" żeby
    // dopasować "501" jako trzycyfrową końcówkę, produkując ucięty match "+48 501"
    // i zostawiając "23 567" jawne. Fix: powtarzalna grupa (?:sep?\d{1,4}){1,4}
    // zamiast jednego sztywnego \d{3,15} — obsługuje dowolne grupowanie cyfr.
    TOKEN_NUMER to Regex("""\+\d{1,3}[\s\-.]?\(?\d{1,4}\)?(?:[\s\-.]?\d{1,4}){1,4}"""),      // Międzynarodowy

    // --- Kwoty z walutami (format PL i EU) ---
    // (?!00\s) wyklucza "00 PLN" — artifact OCR gdy "350,00 PLN" łamane przez linię
    TOKEN_KWOTA to Regex(
        """\b(?!00\s)\d{1,6}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?\s*(?:zł|PLN|EUR|USD|GBP|CHF|DKK|NOK|CZK|HUF|RON)\b""",
        RegexOption.IGNORE_CASE
    ),
    // BUG-PLN-KWOTA-SYMETRIA (05.07, decyzja Pawła): szyk waluta+liczba ("PLN 1234") był
    // dotąd tylko WYKLUCZANY z NUMER/ADRES (zostawał jawny) — niespójne, skoro "1234 PLN"
    // (odwrotny szyk) jest KWOTĄ. Kwota sama nie identyfikuje osoby (nie jest to jak
    // PESEL/adres/telefon), ale skoro engine i tak maskuje jeden szyk, drugi powinien być
    // spójny. Ten sam kształt liczby co wyżej, (?!00\b) analogicznie wyklucza artefakt OCR
    // "PLN 00" z rozbitego "PLN 350,00".
    TOKEN_KWOTA to Regex(
        """\b(?:zł|PLN|EUR|USD|GBP|CHF|DKK|NOK|CZK|HUF|RON)\s*(?!00\b)\d{1,6}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?\b""",
        RegexOption.IGNORE_CASE
    ),

    // --- Dokumenty tożsamości ---
    // REVERT (?i): case-insensitive zjadał 3-literowe imiona (Jan, Piotr) + cyfry jako dowód.
    // Wzorzec celowo case-sensitive — seria dowodu to zawsze uppercase w oryginalnym dokumencie.
    // DOWOD-FIX v1.9: \d{3}[^\S\n]?\d{3} → \d{2,3}[^\S\n]?\d{3,4}.
    // OCR lvl3 (doc_00033) produkuje "AWY57 1380" — spacja po 2 cyfrach zamiast 3.
    // Nowy wzorzec: 2-3 cyfry + opcjonalna spacja + 3-4 cyfry = razem 5-7 cyfr (oczekiwane 6).
    // BUG-PLN-DOWOD-FIX (05.07, diagnoza trace po zgłoszeniu "PLN 12345" jako NUMER):
    // seria dowodu to 3 wielkie litery + 5-7 cyfr — ten sam kształt co skrót waluty + kwota
    // bez separatora ("PLN 12345" = "PLN" jako seria + "12"+"345" jako numer). Ten sam,
    // świadomie zamknięty zestaw kodów walut co przy wcześniejszym fixie tablicy
    // rejestracyjnej (StructuralEngine.kt:558) — nie długość nazwy (seria dowodu ma zawsze
    // dokładnie 3 litery, więc próg długości złamałby prawdziwe serie).
    TOKEN_NUMER to Regex("""\b(?!(?:PLN|EUR|USD|GBP|CHF|DKK|NOK|CZK|HUF|RON)\b)[A-Z]{3}[^\S\n]?\d{2,3}[^\S\n]?\d{3,4}\b"""),  // Dowód osobisty PL ze spacją (AWY57 1380)
    TOKEN_NUMER to Regex("""\b(?!(?:PLN|EUR|USD|GBP|CHF|DKK|NOK|CZK|HUF|RON)\d)[A-Z0-9]{3}\d{6}\b"""),  // Dowód compact — seria może mieć cyfrę OCR (2TS935950)
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
    // BUG-PLN-NUMER-FIX (04.07, sesja AddressEngine v0): kształt [A-Z]{2,3}\s?\d{4,5}
    // pokrywa się z "PLN 1234"/"USD 5678" (skrót waluty + kwota) — bez "zł"/kwoty jako
    // liczby z separatorami, kwota poprzedzona etykietą waluty wygląda identycznie jak
    // tablica rejestracyjna. Lookahead wyklucza znane skróty walutowe (ta sama lista co
    // TOKEN_KWOTA wyżej) — realne polskie tablice nie kolidują z tymi skrótami.
    // BUG-NUMER-FAKTURA-TABLICA-FIX (diagnoza Cursor 07.07, traceMode): ten wzorzec jest
    // wcześniej na liście niż wzorzec sygnatury/faktury z ukośnikami (linia ~605) i łapał
    // prefiks "FVI2025" numeru faktury "FVI2025/12/1828", zanim szerszy wzorzec dostał
    // szansę objąć całość — "/12/1828" zostawał jawny. Prawdziwe tablice rejestracyjne
    // nie są kontynuowane ukośnikiem, więc (?!\s*/) bezpiecznie wyklucza ten przypadek
    // i oddaje go wzorcowi z ukośnikami.
    TOKEN_NUMER to Regex(
        """\b(?!(?:PLN|EUR|USD|GBP|CHF|DKK|NOK|CZK|HUF|RON)\b)[A-Z]{2,3}\s?\d{4,5}[A-Z]{0,2}(?!\s*/)\b"""
    ),

    // --- VIN ---
    TOKEN_NUMER to Regex("""\b[A-HJ-NPR-Z0-9]{17}\b"""),

    // --- Adres IP ---
    TOKEN_NUMER to Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""),

    // --- Kod ICD-10 ---
    TOKEN_NUMER to Regex("""\b[A-Z]\d{2}(?:\.\d{1,2})?\b"""),

    // --- BIC/SWIFT (kontekstowy) ---
    TOKEN_NUMER to Regex("""(?i)(?:BIC|SWIFT)[\s:]+([A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\b"""),

    // --- Numery działek geodezyjnych ---
    // BUG-DZIALKA-FIX (Paweł 07.07): WYŁĄCZONE — kolejny wariant tego samego problemu co
    // numer_faktury (07.07, patrz feedback_anchor_vs_structural_faktura_experiment.md):
    // "Numer dziatki" (pełne słowo "Numer", nie "Nr"; "ł"→"t" zamiast "l") ujawniło że
    // enumerowanie kolejnych degradacji/synonimów w StructuralEngine nie kończy się.
    // Migracja do AnchorEngine A.12 (docNumberRe: "Nr"/"Numer" + do 2 słów pośrednich +
    // sygnał "dzia.k" jako wildcard na literze "ł") — Anchor jako jedyny właściciel,
    // zgodnie z decyzją z eksperymentu faktury. Skomentowane, nie usunięte — łatwy powrót.
    // TOKEN_NUMER to Regex(
    //     """(?i)(?:dzia[łl]k\w*|nr[^\S\n]+dzia[łl]k\w*)\b(?:[^\S\n]+\w+){0,2}[^\S\n]*[:–\-]?[^\S\n]*""" +
    //     """(?=[A-Za-z0-9./\-,]*\d)[A-Za-z0-9]+(?:[^\S\n]?[/\-.,][^\S\n]?[A-Za-z0-9]+)*"""
    // ),

    // --- Identyfikatory alfanumeryczne (ID-UZ-77412, CERT-8841, ZW-PS-0336) ---
    // BUG-NR-SIEROTA-FIX (Cursor 01.07): trzeci opcjonalny segment — bez niego
    // "FV-08217/08/2023" ucinał się na "FV-08217/08", zostawiając "/2023" jawne.
    // A.12 (AnchorEngine) nie mógł tego naprawić — matchOverlapsToken blokował
    // cały jego match bo widział już utworzony token NUMER_xxx w oknie.
    TOKEN_NUMER to Regex("""\b[A-Z]{2,6}[-:/][A-Z0-9]{2,10}(?:[-:/][A-Z0-9]{2,10}){0,2}\b"""),

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
    // SYG-ADM-FIX v1.9: [A-Z]{2}\.\d{6}\.\d{4} → [A-Z]{2,3}[.\s]\d{6}[.\s]\d{4}.
    // OCR (doc_00023 lvl0): "PT.075012.2018" → "PT 075012 2018" (OCR zamienia . na spację).
    // [.\s] w klasie znaku: . jest dosłownym znakiem (nie metaznakiem wewnątrz []).
    // {2,3}: obsługuje też 3-literowe prefixsy (np. "IW.", "SA.").
    TOKEN_NUMER to Regex("""\b[A-Z]{2,3}[.\s]\d{6}[.\s]\d{4}\b"""),

    // --- Numer wewnętrzny 3-2-2 ---
    // (?<!\d{3}[\s\-]): nie matchuj jeśli poprzedza 3 cyfry + separator — to ogon odrzuconego NIPu.
    // Przypadek: 526-000-13-20 (zła suma S5) → wzorzec 3-2-2-3 odrzuca cały NIP,
    // ale bez lookbehind wzorzec 3-2-2 złapałby ogon "000-13-20" jako oddzielny token.
    // BUG-NIP-3-2-2-FIX (Cursor 01.07): (?![\s\-]\d) na końcu — bez tego "722-30-32-34"
    // (4 segmenty, nie NIP w formacie 3-3-2-2 ani 3-2-2-3) dopasowywał tylko "722-30-32",
    // zostawiając "-34" jawne. A.5b (AnchorEngine, kształt NIP bez keywordu) bierze całość
    // poprawnie, ale matchOverlapsToken blokował go bo token już istniał. Trzeci wariant
    // tego samego wzorca bugu co StructuralEngine.kt:419 i :460 (fakturę/sygnatura).
    TOKEN_NUMER to Regex("""(?<!\d{3}[\s\-])\b\d{3}[\s\-]\d{2}[\s\-]\d{2}(?![\s\-]\d)\b"""),

    // --- Sygnatura sądowa/notarialna z odstępem (np. II K 123/25, I C 456/26, A 4567/2026) ---
    // Musi być PRZED wzorcem budynku, żeby nie była brana za adres
    // SYG-FIX v1.2: usunięto (?i) — z IGNORE_CASE polskie "i","v","l" (spójniki)
    // pasowały do [IVXLCDM]+ jako cyfry rzymskie → false positive "i C 456/26"
    // Sygnatury w dokumentach PL są zawsze wielką literą — case sensitivity poprawna
    // SYGNATURA-FIX v1.9: [A-Z]{1,3} → [A-Z][a-zA-Z]{0,2}.
    // Kody wydziałów sądowych mają mieszaną wielkość: "Co" (cywilne odwoławcze), "Ns"
    // (niesporne), "Ka" (karne apelacyjne). Poprzednio: "I Co 3704/2018" pominięte
    // bo [A-Z]{1,3} odrzuca 'o' i 's'. Obejmuje teraz też "Km" (sygnatura komornicza)
    // bez prefiksu cyfr rzymskich.
    // Zabezpieczenie przed FP: wymaga uppercase pierwszej litery, tj. "do 5/2020"
    // (przyimek) nie pasuje bo 'd' jest małe.
    // BUG-NR-SYGNATURA-FIX (Cursor 01.07): (?![Nn]r\b) wyklucza "Nr" jako fałszywy kod
    // wydziału. Bez tego "Nr 8678/02/2023" matchował "Nr 8678/02" (Nr jak "Co"/"Ns"),
    // zostawiając "/2023" jawne — A.12 (AnchorEngine) nie mógł naprawić bo widział
    // już utworzony token w oknie matchOverlapsToken. "Nr" + numer faktury/umowy
    // obsługuje teraz A.12 w całości.
    // BUG-NUMER-FAKTURA-VAT-FIX (diagnoza Cursor 07.07): (?!\s*/) — ten wzorzec (2 segmenty,
    // np. "VAT 26/06") konsumował prefiks dłuższego identyfikatora ("Faktura VAT 26/06/006"),
    // zostawiając "/006" jawne, zanim kontekstowy wzorzec faktury (linia ~445) mógł objąć
    // całość. Ten sam mechanizm bugu co tablica rejestracyjna (linia ~579).
    TOKEN_NUMER to Regex("""\b(?:[IVXLCDM]+\s+)?(?![Nn]r\b)[A-Z][a-zA-Z]{0,2}\s+\d{1,6}/\d{2,4}(?!\s*/)\b"""),

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
    // BUG-KW-CATCHALL-FIX (Cursor 01.07): (?<![A-Z0-9/]) + (?![/\d]) — bez tego CATCHALL
    // łapał sam środkowy ciąg cyfr osadzony w identyfikatorze z ukośnikami (np.
    // "KW GD1M/00234567/8" → CATCHALL brał tylko "00234567"), zostawiając prefiks
    // literowy i sufiks jawne. AnchorEngine A.8 (kotwica KW) nie naprawiał bo
    // matchOverlapsToken widział już utworzony token w oknie. Czwarty wariant tego
    // samego wzorca bugu co StructuralEngine.kt:419/:452/:460 (faktura/sygnatura/NIP).
    TOKEN_NUMER to Regex("""\b(?!(?:19|20)\d{2}\b)(?<![A-Z0-9/])\d{8,}\b(?![/\d])"""),

    // --- S4: Kwoty słowne ---
    //
    // Wzorzec A — kontekst finansowy (wysoka precyzja):
    // Słowo kluczowe (kwota/suma/wynagrodzenie...) + słowa + kotwica walutowa.
    // Pokrywa: "kwota pięćset złotych", "wynagrodzenie w wysokości tysiąc zł".
    TOKEN_KWOTA to Regex(
        """(?i)\b(?:kwot\p{L}{0,5}|kwoci\p{L}{0,3}|sum[aęąy]\p{L}{0,3}|wysoko(?:ść|ści)\p{L}{0,2}|wartości\p{L}{0,4}|wynagrodzeni\p{L}{0,4}|honorari\p{L}{0,4}|odszkodowani\p{L}{0,4}|należno\p{L}{0,5}|pożyczk\p{L}{0,4})[^\S\n]+(?:\p{L}+[^\S\n]+){0,9}(?:złotych|złote|złoty|zł|groszy|grosze|grosz)\b"""
    ),

    // Wzorzec B — liczebnik + skrót walutowy (bezpieczny):
    // "dwadzieścia tysięcy zł", "pięćset groszy" — skróty walutowe nie są przymiotnikami.
    // (?!\p{L}) zamiast \b bo "zł" kończy się na "ł" (non-\w) — \b by nie zadziałał.
    TOKEN_KWOTA to Regex(
        """(?i)\b(?:tysi\p{L}{0,5}|milion\p{L}{0,4}|miliard\p{L}{0,4}|sto|stu|dwieście|dwustu|trzysta|trzystu|czterysta|czterystu|pięćset\p{L}{0,4}|sześćset\p{L}{0,4}|siedemset\p{L}{0,4}|osiemset\p{L}{0,4}|dziewięćset\p{L}{0,4}|dwadzieścia\p{L}{0,3}|dwudziestu|trzydzieści\p{L}{0,2}|trzydziestu|czterdzieści\p{L}{0,2}|czterdziestu|pięćdziesiąt|sześćdziesiąt|siedemdziesiąt|osiemdziesiąt|dziewięćdziesiąt|jedenaście|dwanaście|trzynaście|czternaście|piętnaście|szesnaście|siedemnaście|osiemnaście|dziewiętnaście|zero|jeden\p{L}{0,5}|dwa|dwie|dwóch?|trzy\p{L}{0,3}|cztery|czterech|pięć\p{L}{0,3}|sześć\p{L}{0,3}|siedem\p{L}{0,3}|osiem\p{L}{0,3}|dziewięć\p{L}{0,3}|dziesięć\p{L}{0,3})(?:[^\S\n]+\p{L}+){0,7}[^\S\n]+(?:zł|groszy|grosze|grosz)(?!\p{L})"""
    ),

    // Wzorzec C — liczebnik + pełne słowo walutowe:
    // "dwadzieścia tysięcy złotych" — pokrywa główny case z backlogu S4.
    // Lookahead: złot... musi być terminatorem — po nim interpunkcja/newline/cyfra
    // lub słowa brutto/netto/słownie. Blokuje FP: "sto złotych monet" (monet = rzeczownik).
    TOKEN_KWOTA to Regex(
        """(?i)\b(?:tysi\p{L}{0,5}|milion\p{L}{0,4}|miliard\p{L}{0,4}|sto|stu|dwieście|dwustu|trzysta|trzystu|czterysta|czterystu|pięćset\p{L}{0,4}|sześćset\p{L}{0,4}|siedemset\p{L}{0,4}|osiemset\p{L}{0,4}|dziewięćset\p{L}{0,4}|dwadzieścia\p{L}{0,3}|dwudziestu|trzydzieści\p{L}{0,2}|trzydziestu|czterdzieści\p{L}{0,2}|czterdziestu|pięćdziesiąt|sześćdziesiąt|siedemdziesiąt|osiemdziesiąt|dziewięćdziesiąt|jedenaście|dwanaście|trzynaście|czternaście|piętnaście|szesnaście|siedemnaście|osiemnaście|dziewiętnaście|zero|jeden\p{L}{0,5}|dwa|dwie|dwóch?|trzy\p{L}{0,3}|cztery|czterech|pięć\p{L}{0,3}|sześć\p{L}{0,3}|siedem\p{L}{0,3}|osiem\p{L}{0,3}|dziewięć\p{L}{0,3}|dziesięć\p{L}{0,3})(?:[^\S\n]+\p{L}+){0,7}[^\S\n]+złot\p{L}{0,3}(?=[^\S\n]*(?:[,.\n\);:\d]|\z|\b(?:brutto|netto|słownie|groszy|grosze|grosz|PLN)\b))"""
    )
)

// ============================================================
// Wspólny zestaw znaków nazwy ulicy — JEDNO źródło prawdy, używane przez AddressEngine.kt
// (jedyny silnik ADRES od 07.07) i NameEngine.STREET_CANDIDATE_REGEX. Dodanie nowego znaku
// (apostrof, kolejny diakrytyk) — jedno miejsce, nie trzeba pamiętać o kopiach.
internal const val STREET_NAME_CHARS = "A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ\\-"

// ============================================================
// S5 — Zestawy pattern stringów PESEL i NIP do walidacji checksumów
// Używane w PseudonymEngine.kt przez regex.pattern in PESEL_PATTERN_STRINGS.
// Kotlin Regex nie ma equals() opartego na treści — porównujemy po .pattern (String).
// Wzorce muszą być identyczne ze stringami użytymi w STRUCTURAL_PATTERNS powyżej.
//
// ⚠️  ZASADA NIENARUSZALNA — S5 bypass przez wzorzec kontekstowy:
//
//   Wzorce KONTEKSTOWE (z keywordem "PESEL/NIP") CELOWO POMINIĘTE w *_PATTERN_STRINGS.
//   Efekt: gdy keyword jest w tekście → silnik maskuje BEZ sprawdzania sumy kontrolnej.
//   Powód: OCR może przekręcić jedną cyfrę → błędna suma → prawidłowy PESEL/NIP utracony.
//
//   NIE dodawaj wzorca kontekstowego do *_PATTERN_STRINGS — zepsuje to masowanie
//   prawidłowych dokumentów z OCR-artefaktem. Ta pułapka była naprawiana kilkukrotnie.
//
//   Wzorce kontekstowe w STRUCTURAL_PATTERNS (nie w *_PATTERN_STRINGS):
//     PESEL: pe[s5][e3]l + cyfry  →  brak w PESEL_PATTERN_STRINGS ← CELOWE
//     NIP:   NIP + cyfry          →  brak w NIP_PATTERN_STRINGS   ← CELOWE
//
//   Wzorce strukturalne (GOŁA liczba, bez keywordu) → S5 obowiązkowe.
// ============================================================
internal val PESEL_PATTERN_STRINGS: Set<String> = setOf(
    """(?<!\d)\d{11}(?!\d)""",
    """\b\d{6} \d{5}\b""",
    // Wzorzec kontekstowy pe[s5][e3]l celowo POMINIĘTY:
    // słowo "PESEL:" jest wystarczającym dowodem → maskuj bez sprawdzania sumy.
    // OCR może pomylić jedną cyfrę → suma błędna → S5 blokowałby prawidłowe PESELe.
    // CATCHALL celowo POMINIĘTY (AUDIT-03):
    // S5 sprawdza tylko wzorce (?<!\d)\d{11}(?!\d) i \d{6} \d{5} — goły 11-cyfrowy.
    // CATCHALL bez wzorca → maskuje 8+ cyfr jako NUMER bez walidacji sumy — prawidłowe.
    // Przed naprawą: CATCHALL ∈ set → 11-cyfrowy z błędną sumą PESEL NIE był maskowany (FN).
)

internal val NIP_PATTERN_STRINGS: Set<String> = setOf(
    """(?<!\d)\d{3}[-\s.]?\d{3}[-\s.]?\d{2}[-\s.]?\d{2}(?!\d)""",
    """\b\d{3}[-\s]?\d{2}[-\s]?\d{2}[-\s]?\d{3}\b""",
    """\bPL\d{3}[-\s]?\d{3}[-\s]?\d{2}[-\s]?\d{2}\b""",
    // Wzorzec kontekstowy NIP (z keywordem "NIP") celowo POMINIĘTY:
    // słowo "NIP" jest wystarczającym dowodem → maskuj bez sprawdzania sumy.
    // OCR może pomylić jedną cyfrę → suma błędna → S5 blokowałby prawidłowe NIPy.
    // CATCHALL celowo POMINIĘTY (AUDIT-03):
    // S5 sprawdza tylko wzorce NIP o formacie z separatorami — 10-cyfrowy goły NIP.
    // CATCHALL bez wpisu → maskuje 10-cyfrowe bez separatorów jako NUMER — prawidłowe.
    // Przed naprawą: CATCHALL ∈ set → 10-cyfrowy z błędną sumą NIP NIE był maskowany (FN).
)
