package com.lynxmask.app

/**
 * AddressEngine.kt — v0, silnik równoległy testowy (04.07.2026)
 *
 * Decyzja właściciela po audycie Cursora (`CURSOR_AUDYT_ADRES_2026-07-04.md`,
 * brief `CURSOR_BRIEF_AddressEngine_2026-07-04.md`): 12 nakładających się reguł ADRES
 * rozsianych po 4 plikach (StructuralEngine, NameEngine, AnchorEngine) produkowało
 * niespójne bugi (Zielona Góra→OSOBA, Władysława Stanisława Reymonta rozbite na 4 tokeny,
 * myślnik naprawiony w jednym miejscu a nie w drugim). Zamiast kolejnej punktowej łatki —
 * jeden skonsolidowany silnik, uruchamiany PRZED NameEngine, żeby żadna reguła imion/nazwisk
 * nie zdążyła pociąć adresu zanim ten silnik zobaczy go w całości.
 *
 * v0 = TEST RÓWNOLEGŁY. Stare źródła (StructuralEngine.applyPostalCityPatterns,
 * ADDRESS_PATTERNS, NameEngine.applyStreetLookup, AnchorEngine A.11/A.11c/A.11d/A.11e)
 * CELOWO NIE są usuwane w tej iteracji — nadal działają jako fallback po tym silniku.
 * Duplikaty są tu oczekiwane i informacyjne, nie błąd.
 *
 * WAŻNE — poprawka architektury (Paweł, 04.07, po pierwszej rundzie testów): TYLKO reguły
 * strukturalne/deterministyczne należą tu, na POCZĄTKU potoku. Kotwice luźne (prefix linii
 * "do 60 znaków bez walidacji", numer lokalu z oknem kontekstu ±100 zn.) MUSZĄ zostać
 * WYŁĄCZNIE w AnchorEngine (Warstwa 4b, na końcu, na resztkach) — to jest cały sens briefu v2
 * (AnchorEngine = zbieracz resztek, nie równoległy silnik). Pierwsza wersja tego pliku
 * błędnie przeniosła kopie A.11 (prefix) i A.11e (numer lokalu) na sam początek — luźna
 * kotwica wygrywała z precyzyjnymi regułami (STREET_FULL, POSTAL_K1) zanim те dostały szansę,
 * tworząc urwane tokeny (np. "65-001 Zielona" bez "Góra"), które potem stary A.11c/d na końcu
 * pozornie "naprawiał" — co błędnie wyglądało jak wina starego kodu. Usunięte z tego pliku.
 *
 * BUG-SLOWNIK-KRADL-SLOWA-FIX (05.07, Paweł złapał sam po teście "ul. Długa 7, Gdańsk"):
 * ten sam mechanizm co wyżej ("luźne wygrywa z precyzyjnym") dotyczył też słownika ulic
 * WEWNĄTRZ tego silnika, nie tylko Anchora. STREET_DICT (dawny Blok 1) nie wymaga prefiksu
 * "ul." i biegł PIERWSZY — dla "ul. Długa 7, Gdańsk" łapał samo "Długa 7" (bo "długa" jest w
 * streetForms), zanim STREET_CITY (dawny Blok 3b) zdążył zobaczyć całą frazę z miastem.
 * "Gdańsk" zostawał osierocony. Fix: STREET_CITY (najpełniejszy kontekst: ulica+numer+miasto)
 * biegnie teraz PIERWSZY, STREET_DICT (słownik, walidacja bez wymogu prefiksu) PRZENIESIONY
 * NA SAM KONIEC — działa wyłącznie jako zbieracz resztek (nazwa+numer, których nic innego nie
 * skonsumowało), analogicznie do roli AnchorEngine w całym potoku. Wciąż biegnie PRZED
 * NameEngine (cały ten plik jest Warstwą 0b) — więc ochrona "Zielona Góra" przed rozbiciem na
 * OSOBA jest zachowana niezależnie od pozycji wewnątrz tego pliku.
 *
 * Diagnostyka w DEBUG: token nadany TU = zielony w podglądzie tekstu (LAYER_ADDRESS_ENGINE),
 * token nadany starym kodem = niebieski jak dotychczas. Po potwierdzeniu na telefonie
 * (test_adres_60.txt, test_adresy_50.txt, test_kod_pocztowy_migracja.txt) — osobna sesja
 * usuwa duplikaty ze starych plików i wyłącza Rundę 2 dla ADRES.
 *
 * Kolejność wykonania (od najpełniejszego kontekstu do zbieracza resztek na końcu):
 * 3b. ulica + numer + miasto BEZ kodu pocztowego (np. "ul. Długa 7, Gdańsk") — jeden token
 * 2.  ulica bez kodu pocztowego (wymaga "ul.", fallback gdy 3b nie trafił — brak miasta)
 * 3.  ulica pełna z kodem i miastem jako jeden token
 * 4.  kod pocztowy ↔ miasto (3 kierunki)
 * 1.  słownik ulic (nazwa+numer bez prefiksu "ul.", walidacja przez streetForms) — ZBIERACZ
 *     RESZTEK, tylko to czego bloki 2/3/3b/4 nie skonsumowały
 */

internal const val LAYER_ADDRESS_ENGINE = "ADDRESS_ENGINE"

// BUG-PLN-ADRES-FIX (05.07, diagnoza Cursor): STREET_FULL ma OPCJONALNY prefiks "ul." —
// ten sam mechanizm co wcześniejszy BUG-PLN-NUMER w StructuralEngine (tablica rejestracyjna),
// tylko tu "PLN" jest traktowane jak nazwa ulicy, a "1234, 00-001 Warszawa" dopełnia kształt
// pełnego adresu. Ta sama, świadomie kompletna lista skrótów walutowych co przy TOKEN_NUMER —
// nie enumeracja ad-hoc, jeden ustalony zestaw z brief v2.
internal val CURRENCY_PREFIX_DENY = setOf("pln", "eur", "usd", "gbp", "chf", "dkk", "nok", "czk", "huf", "ron")

internal fun applyAddressEngine(
    text: String,
    assignToken: (value: String, tokenType: String, layer: String, rule: String) -> String
): String {
    var t = text

    fun replaceRangeAsToken(acc: String, range: IntRange, value: String, rule: String): String {
        val token = assignToken(value.trim(), TOKEN_ADRES, LAYER_ADDRESS_ENGINE, rule)
        val before = acc.getOrElse(range.first - 1) { ' ' }
        val after = acc.getOrElse(range.last + 1) { ' ' }
        val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
        val suf = if (after.isLetterOrDigit() || after == '_') " " else ""
        return acc.replaceRange(range, pre + token + suf)
    }

    // ------------------------------------------------------------------
    // Blok 3b — STREET_CITY: ul.+nazwa+numer+miasto BEZ kodu pocztowego między ulicą a
    // miastem (np. "ul. Długa 7, Gdańsk"). Diagnoza Cursor 04.07 (objaw 4): żaden istniejący
    // blok tego nie obsługiwał — Blok 2 kończy się na numerze budynku, Blok 4 (POSTAL) wymaga
    // kodu. Stary Anchor A.11c też nie łapał (wymaga białego znaku zaraz po tokenie, nie
    // przecinka). Reguła STRUKTURALNA (wymaga cityForms), nie kotwica — dlatego tu, nie w
    // AnchorEngine.
    //
    // BUG-SLOWNIK-KRADL-SLOWA-FIX (05.07): przeniesiony na SAM POCZĄTEK (był Blok 3b, po
    // STREET_DICT i STREET_NO_ZIP) — to najpełniejszy kontekst (ulica+numer+miasto), więc
    // musi dostać pierwszą szansę, zanim luźniejsze bloki (STREET_DICT bez wymogu prefiksu,
    // STREET_NO_ZIP bez wymogu miasta) zdążą skonsumować tylko część frazy i osierocić miasto.
    // ------------------------------------------------------------------
    if (LookupTables.initialized && LookupTables.cityForms.isNotEmpty()) {
        val streetCityRe = Regex(
            """(?i)(?:ul[.,]|al\.|pl\.|os\.|u\.)[^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]{1,29}(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]{1,29}){0,2}[^\S\n]+\d{1,4}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?[,]?[^\S\n]+([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z]{1,29}(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z]{1,29})?)"""
        )
        t = streetCityRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) return@fold acc
            if (!LookupTables.cityForms.contains(m.groupValues[1].lowercase())) return@fold acc
            replaceRangeAsToken(acc, m.range, m.value, "STREET_CITY")
        }
    }

    // ------------------------------------------------------------------
    // Blok 2 — ulica bez kodu pocztowego: ul.+nazwa(1-3 słowa)+numer.
    // NAPRAWIONA wersja ADDRESS_PATTERNS drugi wzorzec (~702 w StructuralEngine.kt) —
    // oryginał miał zagnieżdżone [^\S\n] wewnątrz klasy znaków [...], co (diagnoza Cursor
    // 04.07) nie robiło tego co komentarz zakładał — nie pozwalało realnie na wielosłowną
    // nazwę tak jak trzeba. Tu: jawna, powtarzana grupa dla 0-2 DODATKOWYCH słów, więc
    // "Jana Pawła II" (3 człony łącznie) też się mieści.
    //
    // BUG-KOLEJNOSC-FIX (04.07, diagnoza Cursor, runda 3): biegnie PRZED STREET_FULL (Blok 3).
    // STREET_FULL ma OPCJONALNY prefiks "ul." — dla "ul. Jana Pawła II 10/5 20-001 Lublin"
    // łapał urwany match "Pawła II 10/5 20-001 Lublin" (zaczynając od drugiego słowa nazwy,
    // gubiąc "ul. Jana"), bo biegł jako pierwszy. STREET_NO_ZIP wymaga prefiksu "ul."
    // explicite — nie ma tej dwuznaczności, więc idzie przed STREET_FULL i konsumuje poprawny,
    // pełny match zanim STREET_FULL zdąży się pomylić. Biegnie PO STREET_CITY (Blok 3b, wyżej)
    // — jeśli za numerem stoi rozpoznane miasto, 3b już skonsumował całość i tu nie ma nic do
    // roboty (TOKEN_RE guard).
    // ------------------------------------------------------------------
    run {
        val streetNoZipRe = Regex(
            """(?i)(?:ul[.,]|al\.|pl\.|os\.|u\.)[^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]{1,29}(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]{1,29}){0,2}[^\S\n]+\d{1,4}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?(?!/[\d])"""
        )
        t = streetNoZipRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) acc else replaceRangeAsToken(acc, m.range, m.value, "STREET_NO_ZIP")
        }
    }

    // ------------------------------------------------------------------
    // Blok 3 — ulica pełna: ul.+nazwa(1-2 słowa)+numer+kod+miasto = JEDEN token.
    // Kopia ADDRESS_PATTERNS pierwszy wzorzec (StructuralEngine.kt), STREET_NAME_CHARS
    // (wspólna stała, zawiera myślnik). Uruchamiany PO Bloku 2 (patrz komentarz tam) —
    // dociera tylko do linii z kodem pocztowym, których Blok 2 (bez kodu) nie skonsumował.
    //
    // BUG-PLN-ADRES-FIX (05.07, diagnoza Cursor): prefiks "ul." jest OPCJONALNY (potrzebne
    // dla adresów bez prefiksu, np. zdegradowanych przez OCR) — ale to samo pozwalało
    // "PLN 1234, 00-001 Warszawa" dopasować się w całości, traktując "PLN" jak nazwę ulicy
    // (ten sam kształt-bug co przy tablicy rejestracyjnej w StructuralEngine, tylko po
    // stronie adresu, nie NUMER-u). Grupa 1 = prefiks (jeśli był), grupa 2 = pierwsze słowo
    // nazwy. Gdy prefiks NIE wystąpił, pierwsze słowo nie może być skrótem waluty.
    // ------------------------------------------------------------------
    run {
        // BUG-CROSS-NEWLINE-FIX (04.07): oryginał (StructuralEngine ~685) używał \s / [,\s]
        // jako separatorów — \s obejmuje \n, więc kod pocztowy na końcu linii mógł się
        // skleić z tekstem z NASTĘPNEJ linii (np. etykietą testu). [^\S\n] = spacja/tab,
        // nigdy nowa linia. Ten sam błąd istnieje w oryginale (nie naprawiany teraz — poza
        // scope v0), tu naprawiony dla nowego silnika.
        val streetFullRe = Regex(
            """((?i:ul[.,]|al\.|pl\.|os\.|u\.)[^\S\n]+)?\b([A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]{1,29})(?:[^\S\n]+[A-ZŁŚŹĆŃĄĘÓŻ][$STREET_NAME_CHARS]{1,29})?[^\S\n]+\d{1,4}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?[,]?[^\S\n]+\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4}[,]?[^\S\n]+[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ ,]{2,40}\b"""
        )
        t = streetFullRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) return@fold acc
            val hasPrefix = m.groupValues[1].isNotEmpty()
            val firstWord = m.groupValues[2].lowercase()
            if (!hasPrefix && firstWord in CURRENCY_PREFIX_DENY) return@fold acc
            replaceRangeAsToken(acc, m.range, m.value, "STREET_FULL")
        }
    }

    // ------------------------------------------------------------------
    // Blok 4 — kod pocztowy ↔ miasto (3 kierunki). Kopia
    // StructuralEngine.applyPostalCityPatterns — ten sam kształt i te same guardy
    // (NIP-fragment fix 01.07).
    // ------------------------------------------------------------------
    run {
        // BUG-CROSS-NEWLINE-FIX (04.07): [,\s]+ → [,]?[^\S\n]+ — [,\s] łapało nową linię,
        // więc goły kod na końcu linii ("...00-001\n") sklejał się z pierwszym słowem
        // NASTĘPNEJ linii (np. "OCZEKIWANE:" z etykiety testu) jakby to było miasto.
        // Ten sam błąd jest w oryginale StructuralEngine.applyPostalCityPatterns (poza scope v0).
        //
        // BUG-MIASTO-DWUCZŁONOWE-FIX (04.07, diagnoza Cursor): miasto było jednowyrazowe —
        // "65-001 Zielona Góra" maskowało tylko "65-001 Zielona", zostawiając "Góra" osieroconą
        // (którą potem NameEngine łapał jako OSOBA, bo "góra" jest w surnamesForms). Dodano
        // opcjonalne drugie słowo, jak w POSTAL_K2.
        val postalCodeToNameRe = Regex(
            """(?<!\d{2,3}-)(?<!\d)\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4}(?!-\d)[,]?[^\S\n]+[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{2,30}(?:[^\S\n][A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{2,30})?"""
        )
        t = postalCodeToNameRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) acc else replaceRangeAsToken(acc, m.range, m.value, "POSTAL_K1")
        }

        if (LookupTables.initialized && LookupTables.cityForms.isNotEmpty()) {
            val postalNameToCodeRe = Regex(
                """\b([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z]+(?:[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z]+)?)[,]?[^\S\n]+((?<!\d)\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4})"""
            )
            t = postalNameToCodeRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
                if (TOKEN_RE.containsMatchIn(m.value)) acc
                else if (!LookupTables.cityForms.contains(m.groupValues[1].lowercase())) acc
                else replaceRangeAsToken(acc, m.range, m.value, "POSTAL_K2")
            }
        }

        val postalBareCodeRe = Regex(
            """(?<!\d{2,3}-)(?<!\d)\d{2}-(?!\s*(?:19|20)\d{2}\b)\d{3,4}(?!-\d)\b"""
        )
        t = postalBareCodeRe.findAll(t).toList().asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) return@fold acc
            val lineStart = acc.lastIndexOf('\n', m.range.first).let { if (it < 0) 0 else it + 1 }
            val lineEnd = acc.indexOf('\n', m.range.last).let { if (it < 0) acc.length else it }
            val line = acc.substring(lineStart, lineEnd).lowercase()
            val hasCityWord = LookupTables.initialized &&
                line.split(Regex("""\W+""")).any { it.isNotEmpty() && LookupTables.cityForms.contains(it) }
            if (hasCityWord) acc else replaceRangeAsToken(acc, m.range, m.value, "POSTAL_K3")
        }
    }

    // ------------------------------------------------------------------
    // Blok 1 — słownik ulic (np. "Zielona Góra 3" bez prefiksu "ul."). Kopia
    // STREET_CANDIDATE_REGEX (NameEngine.kt) + lookup z applyStreetLookup.
    //
    // BUG-SLOWNIK-KRADL-SLOWA-FIX (05.07, Paweł): PRZENIESIONY NA SAM KONIEC (był Blok 1,
    // pierwszy). Ten blok nie wymaga żadnego prefiksu ("ul.", "al." itd.) — dopasowuje
    // dowolne "Nazwa+numer" i tylko WALIDUJE nazwę przeciwko streetForms. Uruchomiony
    // pierwszy, "kradł" słowa precyzyjniejszym blokom: dla "ul. Długa 7, Gdańsk" łapał samo
    // "Długa 7" (bo "długa" jest w streetForms) zanim STREET_CITY zdążył zobaczyć całą frazę
    // z miastem — "Gdańsk" zostawał osierocony. Teraz działa WYŁĄCZNIE jako zbieracz resztek:
    // tylko to, czego bloki 3b/2/3/4 (pełniejszy kontekst: miasto, kod, prefiks) nie
    // skonsumowały. Dalej biegnie PRZED NameEngine (cały ten plik to Warstwa 0b) — ochrona
    // "Zielona Góra" przed rozbiciem na OSOBA nie zależy od kolejności WEWNĄTRZ tego pliku.
    // ------------------------------------------------------------------
    if (LookupTables.initialized && LookupTables.streetForms.isNotEmpty()) {
        t = STREET_CANDIDATE_REGEX.findAll(t).toList().asReversed().fold(t) { acc, m ->
            if (TOKEN_RE.containsMatchIn(m.value)) return@fold acc
            val streetLower = m.groupValues[1].trim().lowercase()
            // BUG-PLN-ADRES-FIX (05.07): dodatkowa warstwa obronna — nawet gdyby skrót
            // waluty kiedyś trafił do streetForms (błąd danych), nie ma zgody na maskowanie.
            if (streetLower in CURRENCY_PREFIX_DENY) return@fold acc
            val known = LookupTables.streetForms.contains(streetLower) ||
                LookupTables.streetForms.contains("ulica $streetLower") ||
                LookupTables.streetForms.contains("ulicy $streetLower") ||
                LookupTables.streetForms.contains("aleje $streetLower") ||
                LookupTables.streetForms.contains("alei $streetLower") ||
                LookupTables.streetForms.contains("plac $streetLower") ||
                LookupTables.streetForms.contains("placu $streetLower") ||
                LookupTables.streetForms.contains("os. $streetLower") ||
                LookupTables.streetForms.contains("osiedle $streetLower")
            if (!known) acc else replaceRangeAsToken(acc, m.range, m.value, "STREET_DICT")
        }
    }

    return t
}
