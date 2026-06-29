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
            else acc.replaceRange(m.range, assignToken(m.value.trim(), tokenType))
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

    // ------------------------------------------------------------------
    // A.3  TELEFON — kotwica twarda: +48 / 0048
    // Widzę +48 → co po nim wygląda jak cyfry (z separatorami) → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?:\+4[8Bb]|0048)[0-9OolIiSsBbZz\s\-\.]{6,18}"""),
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
        else if (isPeselChecksumValid(m.value)) acc.replaceRange(m.range, assignToken(m.value.trim(), TOKEN_NUMER))
        else acc
    }

    // ------------------------------------------------------------------
    // A.5  NIP — kotwica: keyword N[IL1]P
    // Widzę "NIP" → co po nim (nawet urwany fragment "123-456-") → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)N[IiLl1]P\b[^0-9OolIiSsBbZz\n]{0,15}$D[$D\-\s\.]*"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.5b NIP — kotwica: kształt z kreskami (bez keywordu)
    // Kreski w układzie co-najmniej-dwa-segmenty = sygnał strukturalny NIP.
    // Widzę "123-4S6-78" → to wygląda jak NIP z OCR → maskuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?<!$D)$D{2,4}-$D{2,4}(?:-$D{2,4})+(?!$D)"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.6  IBAN — kotwica: prefix PL
    // Widzę PL + ciąg cyfropodobnych → maskuję. PL w polskim dokumencie = IBAN.
    // Nie sprawdzam 26 cyfr, nie liczę grup, nie sumuję.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?<![A-ZŁŚŹĆŃĄĘÓŻ])PL[$D\s\-]{20,42}"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.7  DATA urodzenia — kotwica: keyword ur. / dob: / urodzon
    // Widzę kotwicę → co po niej wygląda jak ciąg cyfr z separatorami → maskuję.
    // Nie waliduje formatu daty. "21O5.l979" po "ur." = data urodzenia.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:ur\.?|dob\s*:|urodzon\w{0,5}|data\s+urodzenia\s*:?)\s*$D[$D\.\/\-\s]*$D"""),
        TOKEN_NUMER
    )

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
    // A.11b ADRES — kotwica: samotny kod pocztowy XX-XXX
    // ADDRESS_PATTERNS wymaga nazwy miasta. Tu łapiemy sam kod.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""\b\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3}\b"""),
        TOKEN_ADRES
    )

    return t
}
