package com.lynxmask.app

/**
 * AnchorEngine.kt — Warstwa 4b: zbieracz resztek kotwicowy (v2)
 *
 * Filozofia: silnik widzi kotwicę → jedna myśl → maskuje zakres. Koniec.
 * Działa na tekście PO rundzie 1 — widzi tylko resztki między istniejącymi tokenami.
 *
 * Klasa cyfropodobnych D = [0-9OolIiSsBbZz] (OCR myli te znaki z cyframi).
 * Kotwica wymagana — sam kształt bez kontekstu nie jest PII po zniszczeniu OCR.
 * Wyjątek: standalone PESEL z poprawną sumą kontrolną (suma = kotwica).
 */

private const val D = """[0-9OolIiSsBbZz]"""


// Sprawdza sumę kontrolną PESEL (11 cyfropodobnych). Separator (spacja/kreska) jest pomijany.
private fun isPeselChecksumValid(s: CharSequence): Boolean {
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

internal fun applyAnchorEngine(
    text: String,
    assignToken: (value: String, tokenType: String) -> String
): String {
    var t = text

    fun applyAll(re: Regex, tokenType: String) {
        val hits = re.findAll(t).toList().ifEmpty { return }
        t = hits.asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) acc
            else {
                val token = assignToken(m.value.trim(), tokenType)
                val before = acc.getOrElse(m.range.first - 1) { ' ' }
                val after = acc.getOrElse(m.range.last + 1) { ' ' }
                val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
                val suf = if (after.isLetterOrDigit() || after == '_') " " else ""
                acc.replaceRange(m.range, pre + token + suf)
            }
        }
    }

    // ------------------------------------------------------------------
    // A.1  FIRMA — kotwica: forma prawna (suffix)
    // Widzę "Sp. z o.o." → co przed tym na tej linii zaczyna się od wielkiej litery
    // to nazwa firmy → maskuję. Nie liczę słów, nie sprawdzam nazwy.
    // ------------------------------------------------------------------
    val legalForm = """(?:Sp\.\s*z\s*o\.o\.\s*(?:S\.K\.A\.)?|S\.A\.|sp\.j\.|s\.c\.|Sp\.k\.|S\.K\.A\.)"""
    applyAll(
        Regex(
            """[A-ZŁŚŹĆŃĄĘÓŻ][A-Za-ząćęłńóśźżŁŚŹĆŃĄĘÓŻ0-9\-"„]{0,40}""" +
            """(?:[^\S\n]+(?:[A-ZŁŚŹĆŃĄĘÓŻ][A-Za-ząćęłńóśźżŁŚŹĆŃĄĘÓŻ0-9\-"„]{0,40}|[iz&])){0,6}""" +
            """[^\S\n]+$legalForm"""
        ),
        TOKEN_FIRMA
    )

    // ------------------------------------------------------------------
    // A.2  EMAIL — kotwica: znak @
    // Rozszerzam lewo (do spacji) + prawo (do spacji, z opcjonalnym " .domena").
    // Obsługa OCR: "piotr @ firma.pl" (spacje wokół @), "firma .pl" (spacja w domenie).
    // Nie waliduje formatu. @ w dokumencie = email.
    // ------------------------------------------------------------------
    applyAll(Regex("""[^\s\n@]*\s*@\s*[^\s\n]+(?:\s*\.[^\s\n]+)*"""), TOKEN_EMAIL)

    // A.2b EMAIL — osierocona domena po istniejącym tokenie
    // EMAIL_001@nfz.gov.pl → A.2 skipped (TOKEN_RE), tu łapiemy @nfz.gov.pl
    // Wymaga minimum jednej kropki w domenie — nie matchuje @TOKEN_001 bez rozszerzenia.
    applyAll(Regex("""@[^\s\n@]+(?:\.[^\s\n@]+)+"""), TOKEN_EMAIL)

    // ------------------------------------------------------------------
    // A.3  TELEFON — kotwica twarda: +48 / 0048
    // Widzę +48 → co po nim wygląda jak cyfry (z separatorami) → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?:\+4[8Bb]|0048)[0-9OolIiSsBbZz \t\-\.]{6,18}"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.3b TELEFON — kotwica kontekstowa: tel. / kom. / fax / mobile / gsm
    // StructuralEngine ma tel./fax. bez kom./mobile/gsm.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:tel\.?|kom\.?|fax\.?|mobile\s*:|gsm\s*:)\s*\+?[0-9OolIiSsBbZz\s\-\.\(\)]{7,20}"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.3c TELEFON — kształt PL komórkowy bez kotwicy (druga linia)
    // Prefiksy 5xx/6xx/7xx/8xx = polskie komórkowe. StructuralEngine już
    // zabrał numery z kotwicą — tu łapiemy resztki bez kontekstu.
    // TOKEN_RE chroni istniejące tokeny przed re-processingiem.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""\b[5-8]\d{2}[-\s.]?\d{3}[-\s.]?\d{3}\b"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.4  PESEL — kotwica: keyword pe[s5][e3][lL1]
    // Wymagam min. 9 D-znaków po keywordzie — blokuje FP na słowach jak "PESEL kształt"
    // gdzie 's','z' w "kształt" są w D-klasie ale to tylko 2 D-znaki, nie 9.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)pe[s5][e3][lL1]\b[^0-9OolIiSsBbZz\n]{0,15}(?:$D[\s\-]?){9,13}"""),
        TOKEN_NUMER
    )

    // A.4c PESEL — standalone: suma kontrolna jest kotwicą (wyjątek od wymogu kotwicy).
    // Ciąg 9-13 D-znaków (z opcjonalnym pojedynczym separatorem) → maskuję tylko gdy
    // suma kontrolna PESEL dokładnie się zgadza (prawdopodobieństwo ~10% → wymagane).
    val peselShapeRe = Regex("""(?<![a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ0-9OolIiSsBbZz])(?:$D[\s\-]?){9,13}(?![a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ0-9OolIiSsBbZz])""")
    val peselHits = peselShapeRe.findAll(t).toList()
    t = peselHits.asReversed().fold(t) { acc, m ->
        if (TOKEN_RE.containsMatchIn(m.value)) acc
        else if (isPeselChecksumValid(m.value)) {
            val token = assignToken(m.value.trim(), TOKEN_NUMER)
            val before = acc.getOrElse(m.range.first - 1) { ' ' }
            val after = acc.getOrElse(m.range.last + 1) { ' ' }
            val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
            val suf = if (after.isLetterOrDigit() || after == '_') " " else ""
            acc.replaceRange(m.range, pre + token + suf)
        }
        else acc
    }

    // ------------------------------------------------------------------
    // A.5  NIP — kotwica: keyword N[IL1]P
    // Widzę "NIP" → maskuję wszystko do następnej spacji/newline. Bez walidacji formatu.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)N[IiLl1]P\b[^0-9OolIiSsBbZz\n]{0,15}$D\S*"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.5b NIP — kotwica: kształt z kreskami (bez keywordu)
    // Rząd D-znaków z co najmniej dwoma myślnikami = sygnał strukturalny NIP.
    // Kreski W UKŁADZIE to kotwica — bez walidacji formatu.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?<![0-9OolIiSsBbZz_])$D{2,4}-$D{2,4}(?:-$D{2,4})+(?![0-9OolIiSsBbZz_])"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.6  IBAN — kotwica: prefix PL
    // Widzę PL + ciąg cyfropodobnych → maskuję. PL w polskim dokumencie = IBAN.
    // Nie sprawdzam 26 cyfr, nie liczę grup, nie sumuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?<![A-ZŁŚŹĆŃĄĘÓŻ])PL[$D \t\-]{20,42}"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.7  DATA urodzenia — kotwica: keyword ur. / dob: / urodzon / urodzony dnia / date of birth
    // Widzę kotwicę → co po niej wygląda jak ciąg cyfr z separatorami → maskuję.
    // Nie waliduje formatu daty. "21O5.l979" po "ur." = data urodzenia.
    // Warianty OCR: u[nr]. (r→n), d.o.b., urodzony dnia XX / w dniu XX (forma prawna/notarialna).
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:\bur\b\.?|u[nr]\.|dob\s*:?|d\.o\.b\.?|date\s+of\s+birth\s*:?|urodzon\w{0,5}\b(?:[^\S\n]+(?:dnia|w[^\S\n]+dniu))?|data\s+urodzenia\s*:?)[^\S\n]*$D\S*"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.7b  DATA urodzenia — podejście odwrotne: widzę datę → sprawdzam WSTECZ kotwicę
    // A.7 nie łapie gdy OCR wstawia spację w "ur." → "u r." albo "u r" bez kropki.
    // Tu: znajdź kształt daty → okno 60 znaków wstecz → jest kotwica? → maskuj.
    // Kotwice zdegenerowane przez OCR:
    //   "u r."  — spacja między u i r (user case)
    //   "u r"   — bez kropki
    //   "un."   — r→n (OCR)
    //   "u.r."  — kropka zamiast spacji
    //   "ur"    — samo ur bez separatora
    //   + standardowe: ur. dob: d.o.b. date of birth urodzon data urodzenia
    // ------------------------------------------------------------------
    val datePat7b = Regex("""(?<!\d)$D{1,2}[./\-]$D{1,2}[./\-]$D{2,4}(?!\d)""")
    val birthAnchorRe = Regex("""(?i)(?:\bu\s*[rn]\s*\.?(?:\s|${'$'})|\bu\.r\.?|\bdob\s*:?|\bd\.o\.b\.?|date\s+of\s+birth|urodzon\w{0,5}|data\s+urodzeni)""")
    t = datePat7b.findAll(t).toList().asReversed().fold(t) { acc, m ->
        if (TOKEN_RE.containsMatchIn(m.value)) acc
        else {
            val lookback = acc.substring(maxOf(0, m.range.first - 60), m.range.first)
            if (!birthAnchorRe.containsMatchIn(lookback)) acc
            else {
                val token = assignToken(m.value.trim(), TOKEN_NUMER)
                val before = acc.getOrElse(m.range.first - 1) { ' ' }
                val after  = acc.getOrElse(m.range.last + 1) { ' ' }
                val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
                val suf = if (after.isLetterOrDigit()  || after  == '_') " " else ""
                acc.replaceRange(m.range, pre + token + suf)
            }
        }
    }

    // ------------------------------------------------------------------
    // A.8  SYGNATURA — kotwica: keyword sygn. / KRS / KW
    // Widzę kotwicę → co po niej do końca linii (lub pierwszej spacji dla KRS/KW) → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)sygn\.?\s*(?:akt\.?)?\s*:?\s*[^\n]+"""),
        TOKEN_NUMER
    )
    applyAll(
        Regex("""(?i)(?:KRS|KW)\s*:?\s*\S+"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.9  KWOTA — kotwica: suffix walutowy zł/PLN/EUR/USD
    // Widzę walutę → co przed nią wygląda jak liczba → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)[0-9][\d\s,\.]*\s*(?:zł|PLN|EUR|USD|GBP|CHF)"""),
        TOKEN_KWOTA
    )

    // ------------------------------------------------------------------
    // A.9b KWOTA — kotwica: prefix kwota: / suma: / wartość: / wynagrodzenie:
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:kwot[aęą]\s*:?|sum[aą]\s*:?|wartości?\s*:?|wynagrodzeni\w{0,4}\s*:?)\s*[0-9][\d\s,\.]*"""),
        TOKEN_KWOTA
    )

    // ------------------------------------------------------------------
    // A.10 OSOBA — kotwica: tytuł dr / mgr / adw. / mec. / lek. / prof.
    // Widzę tytuł → 1–2 tokeny z wielkiej litery po nim → maskuję.
    // NameEngine obsługuje pary imię+nazwisko. AnchorEngine łapie resztki po OCR.
    // ------------------------------------------------------------------
    applyAll(
        Regex(
            """(?i)(?:dr\s+(?:hab\.?\s+)?|prof\.?\s+|mgr\s+(?:inż\.?\s+)?|inż\.?\s+""" +
            """|adw\.?\s+|mec\.?\s+|lek\.?\s+(?:med\.?\s+)?)""" +
            """[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]{1,30}""" +
            """(?:\s+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]{1,40})?"""
        ),
        TOKEN_OSOBA
    )

    // ------------------------------------------------------------------
    // A.11 ADRES — kotwica: prefix ul. / al. / os. / pl.
    // Widzę prefiks adresowy → co po nim (nazwa + numer) → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:ul[.,]|al\.|os\.|pl\.)[^\S\n][^\n]{2,60}"""),
        TOKEN_ADRES
    )

    // ------------------------------------------------------------------
    // A.11b ADRES — kotwica: samotny kod pocztowy XX-XXX + opcjonalna nazwa miasta
    // ADDRESS_PATTERNS wymaga nazwy miasta. Tu łapiemy sam kod + miasto.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""\b\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3}\b(?:[^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]+(?:[^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]+)?)?"""),
        TOKEN_ADRES
    )

    // ------------------------------------------------------------------
    // A.11c ADRES — miasto po istniejącym tokenie ADRES
    // Runda 1 maskuje ulicę ale zostawia miasto (np. "ADRES_003 Warszawa").
    // Słownik sąsiedztwa: cityForms + streetForms z assets (tysiące form z odmianami).
    // ------------------------------------------------------------------
    // A.11c  Miasto/ulica PO tokenie ADRES (słownik, lookbehind 9 znaków fixed)
    // "ADRES_001 Warszawa" → maskuje "Warszawa" jeśli w cityForms lub streetForms.
    // Łapie też wariant "-NNN Miasto" (resztka kodu pocztowego po tokenie).
    // ------------------------------------------------------------------
    val wordPl = """[A-Za-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ][A-Za-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]{2,}"""
    fun maskIfDict(acc: String, m: MatchResult): String {
        if (TOKEN_RE.containsMatchIn(m.value)) return acc
        val words = m.value.trim().lowercase().split(Regex("""\s+"""))
        if (words.none { it in LookupTables.cityForms || it in LookupTables.streetForms }) return acc
        val token = assignToken(m.value.trim(), TOKEN_ADRES)
        val before = acc.getOrElse(m.range.first - 1) { ' ' }
        val after  = acc.getOrElse(m.range.last + 1) { ' ' }
        val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
        val suf = if (after.isLetterOrDigit()  || after  == '_') " " else ""
        return acc.replaceRange(m.range, pre + token + suf)
    }

    // po tokenie (z opcjonalnym "-NNN" jeśli resztka kodu pocztowego)
    val afterAdresRe = Regex("""(?<=ADRES_\d{3})(?:-\d+)?[^\S\n]+$wordPl(?:[^\S\n]+$wordPl)?""")
    t = afterAdresRe.findAll(t).toList().asReversed().fold(t) { acc, m -> maskIfDict(acc, m) }

    // ------------------------------------------------------------------
    // A.11d  Miasto/ulica PRZED tokenem ADRES (słownik, lookahead)
    // "Gdańsk ADRES_001" → maskuje "Gdańsk".
    // ------------------------------------------------------------------
    val beforeAdresRe = Regex("""$wordPl(?:[^\S\n]+$wordPl)?[^\S\n]+(?=ADRES_\d{3})""")
    t = beforeAdresRe.findAll(t).toList().asReversed().fold(t) { acc, m -> maskIfDict(acc, m) }

    // ------------------------------------------------------------------
    // A.11e  Numer budynku/lokalu z weryfikacją kontekstu (fallback)
    // Kotwice: kod pocztowy w pobliżu LUB token ADRES w pobliżu
    //          LUB poprzednie słowo w słowniku POLISH_CITIES.
    // Bez kontekstu nie maskujemy — samo "4/6" to nie PII.
    // ------------------------------------------------------------------
    val buildingNumRe2 = Regex("""\b\d{1,4}[A-Za-z]?/\d{1,2}[A-Za-z]?\b""")
    t = buildingNumRe2.findAll(t).toList().asReversed().fold(t) { acc, m ->
        if (TOKEN_RE.containsMatchIn(m.value)) acc
        else {
            val winBefore = acc.substring(maxOf(0, m.range.first - 100), m.range.first)
            val winAfter  = acc.substring(m.range.last + 1, minOf(acc.length, m.range.last + 100))
            val hasPostal = Regex("""\b\d{2}-\d{3}\b""").containsMatchIn(winBefore + winAfter)
            val hasAdres  = Regex("""ADRES_\d{3}""").containsMatchIn(winBefore + winAfter)
            val prevWord  = winBefore.trimEnd().split(Regex("""\s+""")).lastOrNull()?.lowercase() ?: ""
            val hasCity   = prevWord in LookupTables.cityForms || prevWord in LookupTables.streetForms
            if (hasPostal || hasAdres || hasCity) {
                val token = assignToken(m.value.trim(), TOKEN_ADRES)
                val bef = acc.getOrElse(m.range.first - 1) { ' ' }
                val aft = acc.getOrElse(m.range.last + 1) { ' ' }
                val pre = if (bef.isLetterOrDigit() || bef == '_') " " else ""
                val suf = if (aft.isLetterOrDigit() || aft == '_') " " else ""
                acc.replaceRange(m.range, pre + token + suf)
            } else acc
        }
    }

    // ------------------------------------------------------------------
    // CLEANUP: sierocące prefiksy telefoniczne przed istniejącymi tokenami
    // Runda 1 (StructuralEngine) może zostawić: "+", "+4B", "tel.", "kom." przed tokenem.
    // Usuwamy je — sama cyfra jest już zamaskowana, prefix nie jest PII.
    // ------------------------------------------------------------------
    val tok = """(?:FIRMA|OSOBA|NUMER|EMAIL|KWOTA|ADRES)_\d{3}"""
    t = Regex("""\+4[8Bb]\s*(?=$tok)""").replace(t, "")   // "+4B NUMER_016" → "NUMER_016"
    t = Regex("""\+\s*(?=$tok)""").replace(t, "")          // "+NUMER_013"    → "NUMER_013"
    t = Regex("""(?i)(?:tel\.?|kom\.?|fax\.?)\s*(?=$tok)""").replace(t, "")  // "tel.NUMER_017" → "NUMER_017"
    // "ul. ADRES_003" → "ADRES_003", "adres: ul. ADRES_003" → "ADRES_003"
    t = Regex("""(?i)(?:adres\s*:?\s*)?(?:ul[.,]|al\.|os\.|pl\.|u\.)[^\S\n]*(?=ADRES_\d{3})""").replace(t, "")

    return t
}
