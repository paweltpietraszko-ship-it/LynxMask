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

internal fun applyAnchorEngine(
    text: String,
    assignToken: (value: String, tokenType: String) -> String
): String {
    var t = text

    fun applyAll(re: Regex, tokenType: String) {
        val hits = re.findAll(t).toList().ifEmpty { return }
        t = hits.asReversed().fold(t) { acc, m ->
            if (matchOverlapsToken(acc, m.range)) acc
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
    // BUG-FIRMA-PRZECINEK-FIX (01.07, test ręczny): [.,] zamiast \. — OCR często myli
    // kropkę z przecinkiem ("Sp. z o.o," / "S,A," / "sp,j,"). Bez tego legalForm w ogóle
    // nie rozpoznawał zdegradowanej formy prawnej, więc A.1 nie odpalał się wcale —
    // NameEngine łapał tylko nazwisko (Kowalski/Nowak/Wiśniewski) jako OSOBA, a reszta
    // nazwy firmy (włącznie z formą prawną) zostawała jawna. Ten sam Nowak z czystą
    // kropką ("S.A.") maskował się poprawnie jako cała nazwa firmy — kontrast pokazał bug.
    // ------------------------------------------------------------------
    val legalForm = """(?:Sp[.,]\s*z\s*o[.,]o[.,]\s*(?:S[.,]K[.,]A[.,])?|S[.,]A[.,]|sp[.,]j[.,]|s[.,]c[.,]|Sp[.,]k[.,]|S[.,]K[.,]A[.,])"""
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
    // A.3  TELEFON — kotwica: warianty jakie ludzie faktycznie piszą
    // (BUG-MIGRACJA 01.07 — konsoliduje dawne A.3+A.3b, usuwa A.3c)
    // Ogólna zasada (brief sekcja 6): kotwica → maskuj ciąg cyfropodobny z przerwami
    // ≤1 znak (spacja/kreska/kropka/nawias — nawias dla numerów kierunkowych typu
    // "(22) 765-43-21"), zatrzymaj się na pierwszym znaku który nie jest cyfropodobny
    // ani separatorem. Bez limitu długości — ten sam wzorzec łapie numer z kierunkowym
    // i bez (gdy pismo dotyczy jednej lokalizacji, kierunkowy bywa pominięty).
    // Kształt bez kotwicy (dawne A.3c, "\b[5-8]\d{2}...") USUNIĘTY — audyt wykazał że
    // to był martwy duplikat StructuralEngine.kt:366 ("\b\d{3}[-\s.]?\d{3}[-\s.]?\d{3}\b"),
    // które jest SZERSZE (bez ograniczenia pierwszej cyfry do 5-8). A.3c nigdy nie dodawało
    // realnego pokrycia. Brief i tak zakazuje kształtu bez kotwicy w AnchorEngine (jedyny
    // wyjątek: suma kontrolna PESEL) — więc nawet gdyby A.3c coś dodawał, nie tu miejsce.
    //
    // Kotwice świadomie POMINIĘTE — lawina FP:
    //   "numer" (samo) — koliduje z "numer sprawy/domu/pozycji/zamówienia" (codzienność
    //     w dokumentach urzędowych, prawie zawsze NIE telefon)
    //   "48" (samo, bez +/00) — koliduje z kwotami "48 000 zł", wiekiem, ilościami
    //   "t" (samo, bez dwukropka) — koliduje z oznaczeniami tabel/pomiarów ("T 25°C")
    //
    // \w*+ (possessive, nie \w*) na "tel"/"kom[oó]rk" — BEZ TEGO regex silnik cofa się
    // (backtracking) W GŁĄB samego słowa-kotwicy szukając litery z klasy D (o/l/i/s/b/z),
    // bo "telefon" zawiera "o" które jest D-klasą (OCR-zamiennik zera). Efekt: "telefon:
    // 501..." dawało dopasowanie "telefo" (silnik cofnął \w* do "ef", potem wziął "o" z
    // "telefon" jako pierwszą cyfrę telefonu!). Possessive blokuje to cofanie.
    //
    // (?![a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ]) na końcu — ten sam problem może wystąpić gdy po
    // kotwicy jest dodatkowe słowo opisowe zamiast cyfr od razu, np. "tel. biurowy: ..."
    // ("biurowy" zaczyna się od 'b'/'i' — D-klasa) → bez guardu dałoby "tel. bi" śmieć.
    // StructuralEngine R1 (linia 359, plain \d) już obsługuje ten wariant z jednym
    // dodatkowym słowem — jeśli R1 nie złapie, wolimy brak matcha niż śmieć.
    // ------------------------------------------------------------------
    applyAll(
        Regex(
            """(?i)(?:\+4[8Bb]|0048|tel\w*+\.?|kom[oó]rk\w*+|kom\.?|fax\.?""" +
            """|mobile\s*:?|gsm\s*:?|wew\.?|\bt\s*:)[^\S\n]*:?[^\S\n]*\(?$D(?:[\s\-.()]?$D)*""" +
            """(?![a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ])"""
        ),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.4  PESEL — kotwica: keyword pe[s5][e3][lL1]
    // Wymagam min. 9 D-znaków po keywordzie — blokuje FP na słowach jak "PESEL kształt"
    // gdzie 's','z' w "kształt" są w D-klasie ale to tylko 2 D-znaki, nie 9.
    // BUG-PESEL-SKLEJENIE-FIX (test ręczny 01.07): {9,13} → {9,11} — górna granica 13
    // pozwalała dopasowaniu ciągnąć się 2 znaki ZA prawdziwy 11-cyfrowy PESEL, jeśli
    // zaraz po nim (nawet po spacji) był inny ciąg cyfropodobny — np. "PESEL 90051512340
    // 00-001 Warszawa" doklejało "00-" z kodu pocztowego do tokenu PESEL. Prawdziwy PESEL
    // ma zawsze dokładnie 11 cyfr; górna granica 11 (zamiast 13) nadal toleruje OCR gubiący
    // cyfry (dolna granica 9) ale nie ciągnie się w sąsiedni, niepowiązany ciąg.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)pe[s5][e3][lL1]\b[^0-9OolIiSsBbZz\n]{0,15}(?:$D[\s\-]?){9,11}"""),
        TOKEN_NUMER
    )

    // A.4c PESEL standalone — PRZENIESIONE do StructuralEngine.kt (BUG-MIGRACJA 01.07,
    // feature/entity-migration). Wywoływane z PseudonymEngine.kt Runda 1, zaraz po
    // STRUCTURAL_PATTERNS. Zachowanie identyczne — patrz applyPeselShapeChecksum().

    // ------------------------------------------------------------------
    // A.5  NIP — kotwica: keyword N[IL1]P
    // Widzę "NIP" → maskuję ciąg cyfropodobny w kształcie NIP (10 cyfr). Bez walidacji sumy.
    // BUG-NIP-KOD-SKLEJENIE-FIX (Cursor 01.07): $D\S* (bez ograniczenia) połykało
    // WSZYSTKO do następnej spacji, w tym sklejony bez separatora kod pocztowy
    // ("NIP 526-021-15-8100-001" → cały ciąg jako jeden token, "Warszawa" jawne).
    // Fix: ograniczenie do dokładnie 10 cyfropodobnych (prawdziwy kształt NIP) zamiast
    // dowolnego \S* — zatrzymuje się na 10. cyfrze niezależnie od tego co następuje.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)N[IiLl1]P\b[^0-9OolIiSsBbZz\n]{0,15}$D(?:[\s\-]?$D){9}"""),
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
    // BUG-DATA-SLOWNA-FIX (01.07): (?:[^\S\n]+\S+){0,2} na końcu — bez tego jeden token
    // po kotwicy (\S*, stop na spacji) urywał datę słowną "8 kwietnia 1963" na samym "8",
    // zostawiając "kwietnia 1963" jawne; tak samo urywał "12.O3 .1985" (spacja od OCR
    // przed końcówką roku) na "12.O3". Limit {0,2} dodatkowych słów zapobiega zjadaniu
    // dalszego zdania (np. "...1963 roku zamieszkały w Krakowie" — stop po "1963").
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:\bur\b\.?|u[nr]\.|dob\s*:?|d\.o\.b\.?|date\s+of\s+birth\s*:?|urodzon\w{0,5}\b(?:[^\S\n]+(?:dnia|w[^\S\n]+dniu))?|data\s+urodzenia\s*:?)[^\S\n]*$D\S*(?:[^\S\n]+\S+){0,2}"""),
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
    // BUG-KW-KWOTA-FIX (Cursor 01.07): \bKW\b zamiast gołego KW — bez granicy słowa,
    // (?i) sprawiał że "kw" wewnątrz zwykłego słowa "kwota" pasował do (?:KRS|KW),
    // zjadając kotwicę "kwota:" zanim A.9b (KWOTA prefix) dostał szansę jej użyć.
    applyAll(
        Regex("""(?i)(?:\bKRS\b|\bKW\b)\s*:?\s*\S+"""),
        TOKEN_NUMER
    )

    // ------------------------------------------------------------------
    // A.9b KWOTA — kotwica: prefix kwota: / suma: / wartość: / wynagrodzenie:
    // Idzie PRZED A.9 — keyword-anchor jest precyzyjniejszy.
    // [^\S\n] zamiast \s — nie crossuje linii, nie wchodzi w cyfry tokenów.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:kwot[aęą][^\S\n]*:?|sum[aą][^\S\n]*:?|wartości?[^\S\n]*:?|wynagrodzeni\w{0,4}[^\S\n]*:?)[^\S\n]*(?<![A-Z0-9_])[0-9][$D,.]*(?:[^\S\n]$D{1,3})*(?:[,.]$D{1,2})?[^\S\n]*(?:zł|z[1l]|PLN|EUR|USD|GBP|CHF)?(?![a-ząćęłńóśźż])"""),
        TOKEN_KWOTA
    )

    // ------------------------------------------------------------------
    // A.9  KWOTA — kotwica: suffix walutowy zł/PLN/EUR/USD
    // Widzę walutę → co przed nią wygląda jak liczba → maskuję.
    // [^\S\n] zamiast \s — nie crossuje linii, nie zjada cyfr z sąsiednich tokenów.
    // z[1l] — OCR: "z1"/"zl" zamiast "zł".
    // [0-9] na początku — wymaga prawdziwej cyfry (D-klasa FP: "o" z "sto złotych").
    // D-klasa w grupach: "15 OOO,OO zł" — OCR zamienia 000→OOO w środku liczby.
    // (?![a-ząćęłńóśźż]) — BUG-ZLECENIE-FIX: bez tego "z[1l]" łapał "zl" jako początek
    // słowa "zlecenie", zjadając cyfrę przed nim jako kwotę i obcinając "zl" ze słowa.
    // (?<![A-Z0-9_]) — BUG-TOKEN-AMPUTACJA-FIX (Cursor 01.07): bez tego [0-9] na
    // początku matchował cyfry WEWNĄTRZ istniejącego tokenu (np. "039" z "NUMER_039"),
    // gdy token stał bezpośrednio przed liczbą wyglądającą na kwotę ("NUMER_039 49
    // 999,99 z1") — replaceRange wycinał środek tokenu, zostawiając kalekie "NUMER_"
    // (bez cyfr, nierozpoznawalne przez UI) obok nowego KWOTA_xxx.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?<![A-Z0-9_])[0-9][$D,.]*(?:[^\S\n]$D{1,3})*(?:[,.]$D{1,2})?[^\S\n]*(?:zł|z[1l]|PLN|EUR|USD|GBP|CHF)(?![a-ząćęłńóśźż])"""),
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
            """(?!(?:OSOBA|FIRMA|NUMER|EMAIL|KWOTA|ADRES)_)""" +
            """[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]{1,30}""" +
            """(?:\s+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ\-]{1,40})?"""
        ),
        TOKEN_OSOBA
    )

    // ------------------------------------------------------------------
    // A.11 ADRES — kotwica: prefix ul. / al. / os. / pl.
    // Widzę prefiks adresowy → co po nim (nazwa + numer) → maskuję.
    //
    // COFNIĘTE 04.07 (diagnoza Cursor): próba kształtu ogólnego `\b[uaop].[.,]` zamiast
    // enumeracji — zbędna i ryzykowna (FP na "Op."/"Ap."/"Up." itd.), skoro OCR_ADDR_PREFIX
    // w OcrNormalizer.kt już normalizuje zdegradowany prefiks PRZED tym jak AnchorEngine
    // w ogóle zobaczy tekst. Wraca dosłowna enumeracja — degradacje obsłużone wcześniej w potoku.
    // ------------------------------------------------------------------
    applyAll(
        Regex("""(?i)(?:ul[.,]|al\.|os\.|pl\.)[^\S\n][^\n]{2,60}"""),
        TOKEN_ADRES
    )

    // A.11b (samotny kod pocztowy + opcjonalne miasto) usunięty 04.07 (migracja ADRES krok 5) —
    // duplikat StructuralEngine.applyPostalCityPatterns kierunek 1/3 (Warstwa 1b), które działa
    // wcześniej w potoku. Jeśli test ręczny ujawni regresję (miasto spoza słownika obok kodu) —
    // krok 6 planu (guard A.11c/A.11d na osierocony kod) ma to pokryć.

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
    // A.12 NUMER dokumentu (faktura/umowa) — kotwica: "Nr"/"nr" + sygnał dokumentu wstecz
    // StructuralEngine wymaga dokładnej frazy "nr faktury"/"numer umowy" (fraza-kotwica).
    // Realne dokumenty piszą tytuł osobno od numeru: "FAKTURA VAT" \n "Nr FV-08217/08/2023"
    // albo "UMOWA O ŚWIADCZENIE USŁUG" \n "nr U-00284/2023" — słowo "faktury"/"umowy"
    // nigdy nie styka się z samym numerem, więc fraza-kotwica nigdy nie trafia (BUG_SILNIKA).
    // Tu: widzę "Nr X" → sprawdzam WSTECZ (60 zn.) czy jest słowo-sygnał dokumentu → maskuję.
    // Nie waliduje formatu numeru — kształt dokumentu bywa dowolny (cyfry/litery/ukośniki).
    // ------------------------------------------------------------------
    val docNumberRe = Regex("""(?<![a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ])[Nn]r\.?[^\S\n]*:?[^\S\n]*\S+""")
    val docSignalRe = Regex("""(?i)faktur|umow|zlecen|kontrahent|\bvat\b|uproszczon""")
    t = docNumberRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
        if (matchOverlapsToken(acc, m.range)) acc
        else {
            val lookback = acc.substring(maxOf(0, m.range.first - 60), m.range.first)
            if (!docSignalRe.containsMatchIn(lookback)) acc
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
