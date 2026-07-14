package com.lynxmask.app

// OutputGuard.kt — Warstwa 6: Output Guard
// Wersja: 2.1
//
// Zwraca List<GuardHit> z poziomami RED (pewne PII) i YELLOW (podejrzane z kontekstem).
// Tokeny własne (format TYP_NNN) zastępowane przez ⟦TOKEN⟧ przed skanowaniem.
//
// v2.0: Nowe wzorce RED: PESEL_SPACE, TELEFON_PELNY (szerszy — kropka, 0048, nawiasy).
//       YELLOW SYGNATURA i LICZBA zawężone kotwicą słowną (okno 35 znaków przed hitem).
// v2.1: SYGNATURA — wzorzec liczby wymaga >=2 cyfr przed separatorem i (4-cyfrowy rok
//       lub >=2 cyfry po); eliminuje FP art. 734/1 i ust. 3/4 przy zachowanej kotwicy.
//       Nowe YELLOW: URODZENIE, MIEJSCE_UR, EMAIL_FRAGMENT.
//       Usunięte: REGON, PL_PREFIX (silnik maskuje), TELEFON (superseded przez TELEFON_PELNY RED).
//       Naprawiony token exclusion regex — aktualny format TYPE_NNN.

// BUG-GUARD-DCLASS-FIX (01.07, wniosek właściciela): Guard tylko OSTRZEGA, nie zamienia
// tekstu — więc powinien być SZERSZY niż silnik, nie tak samo wąski. Silnik maskuje
// precyzyjnie (musi unikać FP bo faktycznie podmienia tekst), ale to co Guard skanuje
// to już RESZTKI po silniku — jeśli tam jest 9-13-znakowy ciąg cyfr, w prawdziwym
// dokumencie to prawie zawsze zniekształcona przez OCR prawdziwa liczba (PESEL/NIP/
// telefon), nie przypadkowy szum. Guard z gołym \d był ślepy na dokładnie te przypadki
// gdzie OCR podmienił cyfrę na literę (O/l/I/i/S/s/B/b/Z/z) — bez D-klasy nie widział
// "9OO4O512345" ani "O328l512367" wcale, mimo że to oczywiste PII po degradacji.
private const val D = """[0-9OolIiSsBbZz]"""

data class GuardHit(
    val label: String,
    val level: String,
    val matchedText: String,
    val startIndex: Int,
    val endIndex: Int
)

// ============================================================
// NAMES-GUARD-CITY-FIX: formy wyrazowe nazw miast — nie flaguj jako imię/nazwisko
// Lista minimalna: tylko formy powodujące konkretne false positives.
// "Góra" jest polskim nazwiskiem → Zielona Góra/Górze fałszywie flagowane.
// ============================================================
private val NAMES_GUARD_CITY_SKIP: Set<String> = setOf(
    // Zielona Góra — formy fleksyjne obu członów
    "góra", "górze", "góry", "górą",
    "zielona", "zielonej",
    // Człony miast będące też pospolitymi przymiotnikami (Nowa Sól, Biała Podlaska itd.)
    "nowa", "nowej", "stara", "starej",
    "biała", "białej",
    "wielka", "wielkiej", "mała", "małej"
).toHashSet()

// ============================================================
// Warstwa 6 — Output Guard
// ============================================================
internal fun runOutputGuard(
    pseudonymizedText: String,
    tokenMap: Map<String, String>
): List<GuardHit> {

    val hits = mutableListOf<GuardHit>()

    val tokenRe = Regex("""\b(?:FIRMA|OSOBA|NUMER|EMAIL|KWOTA|ADRES)_\d{3}\b""")
    val text = tokenRe.replace(pseudonymizedText, "⟦TOKEN⟧")

    fun hit(label: String, level: String, m: MatchResult) =
        GuardHit(label, level, m.value, m.range.first, m.range.last + 1)

    // ── RED — każde dopasowanie to wyciek ────────────────────────────────────
    val redPatterns = listOf(
        "NIP"           to Regex("""(?i)\b$D{3}[-\s]?$D{3}[-\s]?$D{2}[-\s]?$D{2}\b"""),
        "IBAN"          to Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}\b"""),
        "EMAIL"         to Regex("""\b[a-zA-Z0-9._%+\-]+@[a-zA-Z][a-zA-Z0-9\-]*\.[a-zA-Z]{2,}\b"""),
        "DOWOD"         to Regex("""\b[A-Z]{3}\s?\d{6}\b"""),
        // Telefon: kropka/myślnik/spacja jako separator, prefiks +48/0048/48 opcjonalny.
        // Dwie gałęzie — obszarowe (kierunkowy 2-cyfrowy + local 7-cyfrowy) lub mobilna (9 cyfr bez kierunkowego).
        // Polski kierunkowy to zawsze 2 cyfry (nie 3) — zapobiega interpretacji NIP XXX jako kierunkowy.
        // Mandatory separator blokuje 9-cyfrowy compact (REGON).
        "TELEFON_PELNY" to Regex("""(?i)\b(?:(?:\+4[8Bb]|0048|48)[ \-.]?)?(?:(?:\(?$D{2}\)?[ \-.])$D{3}[ \-.](?:$D{3}[ \-.]?$D{3}|$D{2}[ \-.]?$D{2})|$D{3}[ \-.](?:$D{3}[ \-.]?$D{3}))\b"""),
    )
    for ((label, re) in redPatterns)
        re.findAll(text).forEach { hits += hit(label, "RED", it) }

    // RED tolerancyjny — OCR-artefakty które silnik mógł pominąć (IBAN garbled, dowód 2TS…)
    Regex("""\bPL[A-Z0-9\s]{14,40}\b""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
        if (m.value.count { it.isDigit() } >= 10)
            hits += hit("IBAN", "RED", m)
    }
    Regex("""\b[A-Z0-9]{3}\s?\d{6}\b""").findAll(text).forEach { m ->
        val prefix = m.value.take(3)
        if (prefix.count { it.isLetter() } >= 2 && m.value.filter { it.isDigit() }.length >= 6)
            hits += hit("DOWOD", "RED", m)
    }

    // ── YELLOW z kotwicą słowną (okno 35 znaków przed hitem) ────────────────
    fun before(pos: Int) = text.substring(maxOf(0, pos - 35), pos)

    // SYGNATURA: wymaga kontekstu nr/numer/sygn/akt/sprawa/repertorium/poz w pobliżu
    val CTX_SYGN = Regex("""(?i)\b(?:nr|numer|sygn(?:atura)?|akt[auy]?|spraw[ayi]|repertorium|poz)\b""")
    Regex("""(?i)\b$D{2,6}[/\-](?:$D{4}|$D{2,6})\b""").findAll(text).forEach { m ->
        if (CTX_SYGN.containsMatchIn(before(m.range.first)))
            hits += hit("SYGNATURA", "YELLOW", m)
    }

    // LICZBA: wymaga kontekstu nr/numer/poz/pwz/karta/id w pobliżu
    val CTX_LICZBA = Regex("""(?i)\b(?:nr|numer|poz|pwz|karta|id)\b""")
    Regex("""(?i)\b(?!(?:19|20)\d{2}\b)$D{7,10}\b""").findAll(text).forEach { m ->
        if (CTX_LICZBA.containsMatchIn(before(m.range.first)))
            hits += hit("LICZBA", "YELLOW", m)
    }

    // ── YELLOW: niezamaskowane imię+nazwisko po etykiecie danych osobowych ─────
    // Wykrywa "Słowo Słowo" (każde 3+ znaków) gdy w pobliżu jest etykieta osobowa.
    // Okno 80 znaków — obejmuje "ZLECENIOBIORCA:\nImię i nazwisko: Monka Nowakosa".
    val CTX_OSOBA_LABEL = Regex("""(?i)(?:imię[^\S\n]+i[^\S\n]+nazwisko|zlecenio(?:biorca|dawca)|podpisano\s*:|pracownik|wykonawca|zamawiaj[aą]c\w|pełnomocnik|uprawnion\w|pesel\s*:)""")
    fun before80(pos: Int) = text.substring(maxOf(0, pos - 80), pos)
    Regex("""\b[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{2,}\s+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ]{2,}\b""").findAll(text).forEach { m ->
        val words = m.value.lowercase().split(Regex("""\s+"""))
        if (words.none { it in NAMES_GUARD_CITY_SKIP } && CTX_OSOBA_LABEL.containsMatchIn(before80(m.range.first)))
            hits += hit("OSOBA_NIEZAMASKOWANE", "YELLOW", m)
    }

    // ── YELLOW: POJEDYNCZE niezamaskowane imię/nazwisko ze słownika po etykiecie ────
    // BUG-MAZUR-NIEZAMASKOWANY (11.07): silnik czasem ŚWIADOMIE nie maskuje słowa ze
    // słownika nazwisk — strażnik Morfologika w NameEngine ("Samo nazwisko") uznaje np.
    // "Mazur" za zbyt pospolite (nazwa tańca ludowego), mimo że to też częste prawdziwe
    // nazwisko. Reguła OSOBA_NIEZAMASKOWANE wyżej wymaga PARY słów — nie złapie samego
    // "Mazur" bez sąsiadującego imienia. Pomiar na realnym tekście dokumentów (11.07):
    // wersja BEZ kotwicy etykiety dawała 25% słów z wielkiej litery jako trafienia w
    // 39k-słownik (m.in. "cena"/"organ"/"neto" — realny powrót do fali 30 flag z
    // wcześniejszej sesji). Ta sama kotwica CTX_OSOBA_LABEL co wyżej ogranicza skan do
    // miejsc gdzie kontekst już mocno sugeruje dane osobowe — miasta/ulice odfiltrowane
    // (mają własną regułę MIASTO_NIEZAMASKOWANE niżej), znane kolizje pospolite pomijane
    // przez OSOBA_DENYLIST (NameEngine.kt).
    // BUG-KADRY-SILENT-MISS (12.07, decyzja właściciela): surnamesForms (aktywne maskowanie)
    // zawężony do top-1000 (usuwa kolizje typu Osoba/Zapłata/Łączna) — ale to samo zawężenie
    // zostawiałoby CISZĄ rzadkie, prawdziwe nazwiska spoza tej listy (niebezpieczne dla
    // dokumentów kadrowych/HR, PII wycieka bez ostrzeżenia). surnamesFormsExtended (39k) łata
    // to TU — nie maskuje (ryzyko kolizji zbyt wysokie dla auto-akcji), tylko ostrzega.
    // Ta sama kotwica CTX_OSOBA_LABEL i te same listy pomijania co reguła wyżej — celowo,
    // to już zmierzone jako bezpieczne (bez kotwicy 25% szumu, patrz komentarz wyżej).
    if (LookupTables.initialized && (LookupTables.namesForms.isNotEmpty() || LookupTables.surnamesForms.isNotEmpty() || LookupTables.surnamesFormsExtended.isNotEmpty())) {
        Regex("""\b([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,})\b""").findAll(text).forEach { m ->
            val word = m.groupValues[1]
            val lower = word.lowercase()
            if (lower in NAMES_GUARD_CITY_SKIP) return@forEach
            if (lower in OSOBA_DENYLIST) return@forEach
            val isSurname = LookupTables.surnamesForms.contains(lower)
            val isFirstName = LookupTables.namesForms.contains(lower)
            val isRareSurname = !isSurname && LookupTables.surnamesFormsExtended.contains(lower)
            // BUG-GUARD-CITY-SILENCES-SURNAME-FIX (14.07): wcześniej cityForms/streetForms
            // wyciszało OSTRZEŻENIE bezwarunkowo — realne nazwiska pokrywające się z nazwą
            // miejscowości (Zając, Wróbel, Sikora, Dudek — potwierdzone w cities_forms.json)
            // nie dostawały nawet YELLOW. Miasto/ulica bez ŻADNEGO dowodu nazwiska zostaje
            // wyciszone jak dotąd (unika podwójnego ostrzeżenia z MIASTO_NIEZAMASKOWANE
            // niżej) — ale gdy słowo JEST znanym nazwiskiem, kolizja z miastem już nie milczy.
            if ((LookupTables.cityForms.contains(lower) || LookupTables.streetForms.contains(lower)) &&
                !isSurname && !isRareSurname) return@forEach
            if (!isSurname && !isFirstName && !isRareSurname) return@forEach
            if (!CTX_OSOBA_LABEL.containsMatchIn(before80(m.range.first))) return@forEach
            val label = when {
                isRareSurname -> "NAZWISKO_RZADKIE_NIEZAMASKOWANE"
                isSurname -> "NAZWISKO_NIEZAMASKOWANE"
                else -> "IMIE_NIEZAMASKOWANE"
            }
            hits += GuardHit(label, "YELLOW", word, m.range.first, m.range.first + word.length)
        }
    }

    // ── YELLOW: miasto ze słownika zostało jawne (bez auto-maskowania) ──────
    // Decyzja właściciela (08.07, brief Cursor): NIE auto-maskować gołych miast bez
    // kontekstu strukturalnego (kod pocztowy/ul./przyimek — to już robi AddressEngine/
    // CITY_PREP) — zbyt duży FP na wieloznacznych słowach ("warszawski", "Gdański port",
    // nazwy instytucji). Guard tylko OSTRZEGA, nie maskuje.
    // Działa na pseudonymizedText (RAW, nie na `text` z podmienionymi ⟦TOKEN⟧) — potrzebuje
    // widzieć prawdziwe tokeny ADRES_NNN żeby wykryć "ta sama linia ma już adres" i uniknąć
    // podwójnego flagowania tego samego fragmentu. Wiodący/końcowy \b zastąpiony przez
    // WORD_START_UNICODE/WORD_END_UNICODE (StructuralEngine.kt) — nazwa miasta może zaczynać
    // się lub kończyć na polską literę diakrytyczną (Łódź, Żywiec, Ostrów).
    if (LookupTables.initialized && LookupTables.cityForms.isNotEmpty()) {
        val cityWordRe = Regex("""$WORD_START_UNICODE([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,})$WORD_END_UNICODE""")
        val adresTokenRe = Regex("""\bADRES_\d{3}\b""")
        // BUG-MIASTO-ZACHLANNA-PARA-FIX (ultrareview): stara wersja łapała OD RAZU parę
        // "słowo1 słowo2" jednym matchem (opcjonalna druga grupa) — gdy para nie była
        // dwuczłonowym miastem ("Wielka Warszawa"), findAll i tak konsumował oba słowa
        // naraz i NIGDY nie sprawdzał drugiego słowa ("Warszawa") osobno jako miasto.
        // Fix: dopasuj pojedyncze słowa, dla każdego opcjonalnie sprawdź sąsiada jako
        // parę — jeśli para nie jest miastem, słowo-sąsiad zostaje sprawdzone osobno
        // w kolejnej iteracji zamiast być bezpowrotnie "zjedzone".
        val words = cityWordRe.findAll(pseudonymizedText).toList()
        var idx = 0
        while (idx < words.size) {
            val m = words[idx]
            val next = words.getOrNull(idx + 1)
            val sep = pseudonymizedText.getOrNull(m.range.last + 1)
            val adjacent = next != null && next.range.first == m.range.last + 2 &&
                sep != null && sep != '\n' && sep.isWhitespace()
            val first = m.groupValues[1]
            val second = if (adjacent) next!!.groupValues[1] else null
            val (matchedCity, consumedNext) = when {
                second != null && LookupTables.cityForms.contains("${first.lowercase()} ${second.lowercase()}") ->
                    pseudonymizedText.substring(m.range.first, next!!.range.last + 1) to true
                LookupTables.cityForms.contains(first.lowercase()) -> first to false
                else -> null to false
            }
            idx += if (consumedNext) 2 else 1
            if (matchedCity == null) continue
            // Nazwa jest jednocześnie ulicą (np. "Gdańska") — zostaw ocenę kontekstu Guardowi ADRES/ulicy, nie duplikuj.
            if (LookupTables.streetForms.contains(matchedCity.lowercase())) continue
            val start = m.range.first
            val matchEnd = start + matchedCity.length - 1
            val lineStart = pseudonymizedText.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
            val lineEnd = pseudonymizedText.indexOf('\n', matchEnd).let { if (it < 0) pseudonymizedText.length else it }
            val line = pseudonymizedText.substring(lineStart, lineEnd)
            if (adresTokenRe.containsMatchIn(line)) continue
            hits += GuardHit("MIASTO_NIEZAMASKOWANE", "YELLOW", matchedCity, start, start + matchedCity.length)
        }
    }


    // ── YELLOW bezwarunkowe (kontekst wbudowany w regex) ─────────────────────
    val yellowPatterns = listOf(
        // PESEL — silnik z S5 waliduje sumę kontrolną; co zostaje w tekście to albo błędna suma
        // albo nieznany format. Nie blokujemy eksportu RED-em — użytkownik decyduje.
        "PESEL"          to Regex("""(?i)\b$D{11}\b"""),
        "PESEL_SPACE"    to Regex("""(?i)\b(?:$D[ \-]?){10}$D\b"""),
        // Data urodzenia po słowie kluczowym ur./urodzony/urodzona
        "URODZENIE"      to Regex("""(?i)\bur(?:odzony|odzona|odzeni|\.)\s+\d{1,2}[.\-/]\d{1,2}[.\-/]\d{4}\b"""),
        // Miejscowość urodzenia po ur./urodzony w
        "MIEJSCE_UR"     to Regex("""(?i)\bur(?:odzony|odzona|odzeni)?\.?\s+w\s+[A-ZĄĆĘŁŃÓŚŹŻ][a-ząćęłńóśźż]+"""),
        // Dowolny fragment niebędący białym znakiem zawierający @ — zniekształcony/częściowy email
        "EMAIL_FRAGMENT" to Regex("""\S+@\S+"""),
    )
    for ((label, re) in yellowPatterns)
        re.findAll(text).forEach { hits += hit(label, "YELLOW", it) }

    return hits
}
