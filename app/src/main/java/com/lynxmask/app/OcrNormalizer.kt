package com.lynxmask.app

// OcrNormalizer.kt — Warstwa 0: Normalizacja tekstu przed pseudonimizacją
// Wersja: 1.3
//
// Zasada: TYLKO deterministyczne, bezpieczne poprawki o zerowym ryzyku fałszywych zmian.
//
// Zmiany v1.2:
//   - OCR_DIGIT_AS_LETTER: cyfry między literami → litera
//   - OCR_STREET_MIDSPACE: sklejanie spacji w środku nazwy ulicy po ul./al./pl./os.
//
// USUNIĘTE w v1.1: MIDWORD_SPACE_RE ("Wars zawa" → "Warszawa")
// Powód: regex (?<=[a-z]) (?=[a-z]) sklejał WSZYSTKIE spacje między małymi literami,
// niszcząc czytelność dokumentu ("Zawarta w" → "Zawartaw").
//
// Zmiany v1.3 (sesja 9):
//   - OCR_CITY_MIDSPACE: sklejanie nazw miast rozbitych przez OCR
//     "Wars zawa" → "Warszawa", "Bia łystok" → "Białystok"
//     Bezpieczne: sprawdza czy złączone części tworzą znane polskie miasto
//     z zamkniętej listy (~100 form). Nie skleja zwykłych wyrazów.
//     Pierwsza część musi zaczynać się WIELKĄ literą — OCR rzadko błędnie
//     kapitalizuje, więc fałszywych pozytywów praktycznie brak.

data class NormalizationResult(
    val normalizedText: String,
    val corrections: Int    // liczba zastosowanych poprawek (do logowania)
)

data class QualityAssessment(
    val showWarning: Boolean,
    val issues: List<String>
)

object OcrNormalizer {

    // ----------------------------------------------------------
    // Naprawa form prawnych spółek
    // "Sp.z o.0." → "Sp. z o.o."
    // ----------------------------------------------------------
    private val LEGAL_ZERO_RE = Regex(
        """(?i)(sp\.?\s*z\s*o\.)0\."""
    )

    // ----------------------------------------------------------
    // OCR: cyfry mylone z literami — TYLKO gdy otoczone literami po obu stronach
    // ----------------------------------------------------------
    private val OCR_ONE_AS_L = Regex(
        """(?<=[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ])1(?=[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ])"""
    )
    private val OCR_ZERO_AS_O = Regex(
        """(?<=[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ])0(?=[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ])"""
    )
    private val OCR_PIPE_AS_L = Regex(
        """(?<=[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ])\|(?=[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ])"""
    )

    // ----------------------------------------------------------
    // OCR: spacja w środku nazwy ulicy po prefiksie
    // "ul. Marszał kowska 100" → "ul. Marszałkowska 100"
    // ----------------------------------------------------------
    private val OCR_STREET_MIDSPACE = Regex(
        """(?i)((?:ul|al|pl|os)\.[^\S\n]*)([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,9})[^\S\n]+([a-ząćęłńóśźż]{2,7})(?=[^\S\n]+\d|[^\S\n]*,|[^\S\n]*\n|[^\S\n]*$)"""
    )

    // ----------------------------------------------------------
    // OCR_CITY_MIDSPACE v1.3: sklejanie nazw miast rozbitych przez OCR
    //
    // Problem: OCR rozbija słowo na dwa tokeny w dowolnym miejscu.
    // "Wars zawa" zamiast "Warszawa", "Byd goszcz" zamiast "Bydgoszcz".
    //
    // Rozwiązanie: jeśli LEWA_CZĘŚĆ + PRAWA_CZĘŚĆ = znane polskie miasto → sklej.
    // Lista jest zamknięta — błędne sklejenie niemożliwe jeśli wyniku nie ma w liście.
    //
    // Wzorzec:
    //   - Lewa część: wielka litera + 2–10 małych (fragment nazwy miasta)
    //   - Spacja OCR (nie newline)
    //   - Prawa część: 2–8 MAŁYCH liter (kontynuacja, nie nowe słowo)
    //   - Konkatenacja sprawdzana w KNOWN_CITY_FORMS
    //
    // NIE skleja:
    //   "Zawarta w" → "Zawartaw"  — "zawartaw" nie ma w liście ✓
    //   "Kato wice" → tylko jeśli "katowice" ∈ listy (jest) i lewa="Kato" (wielka) ✓
    //   "Wars Za wa" → środkowe "Za" ma wielką literę → nie pasuje jako prawa część ✓
    // ----------------------------------------------------------
    private val KNOWN_CITY_FORMS: Set<String> = setOf(
        // Warszawa — mianownik, dopełniacz, miejscownik, biernik, narzędnik
        "warszawa", "warszawy", "warszawie", "warszawę", "warszawą",
        // Kraków
        "kraków", "krakowa", "krakowie", "krakowem",
        // Gdańsk
        "gdańsk", "gdańska", "gdańsku", "gdańskiem",
        // Wrocław
        "wrocław", "wrocławia", "wrocławiu", "wrocławiem",
        // Poznań
        "poznań", "poznania", "poznaniu", "poznaniem",
        // Łódź
        "łódź", "łodzi",
        // Katowice
        "katowice", "katowic", "katowicach", "katowicami",
        // Lublin
        "lublin", "lublina", "lublinie", "lublinem",
        // Białystok
        "białystok", "białegostoku", "białymstoku",
        // Rzeszów
        "rzeszów", "rzeszowa", "rzeszowie",
        // Szczecin
        "szczecin", "szczecina", "szczecinie",
        // Bydgoszcz
        "bydgoszcz", "bydgoszczy",
        // Toruń
        "toruń", "torunia", "toruniu",
        // Kielce
        "kielce", "kielc", "kielcach",
        // Gliwice
        "gliwice", "gliwic", "gliwicach",
        // Zabrze
        "zabrze", "zabrza", "zabrzu",
        // Bytom
        "bytom", "bytomia", "bytomiu",
        // Olsztyn
        "olsztyn", "olsztyna", "olsztynie",
        // Opole
        "opole", "opola", "opolu",
        // Gdynia
        "gdynia", "gdyni", "gdynię",
        // Częstochowa
        "częstochowa", "częstochowy", "częstochowie",
        // Radom
        "radom", "radomia", "radomiu",
        // Sosnowiec
        "sosnowiec", "sosnowca", "sosnowcu",
        // Tychy
        "tychy", "tychów", "tychach",
        // Rybnik
        "rybnik", "rybnika", "rybniku",
        // Zielona Góra — jednoczłonowe odmiany (Góra osobno za krótka, Zielona za ogólna)
        // obsługiwane przez WHITE_LIST_CITIES w NameEngine; tu pomijamy
    ).map { it.lowercase() }.toHashSet()

    private val OCR_CITY_MIDSPACE = Regex(
        """([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,10})[^\S\n]+([a-ząćęłńóśźż]{2,8})"""
    )

    // ----------------------------------------------------------
    // OCR_EMAIL_SPACES v1.4: naprawa artefaktów spacji w adresach email
    //
    // Reguła 1 — spacja przed TLD (uruchamiać PIERWSZA):
    //   "jan@onet pl" → "jan@onet.pl"
    //   Wzorzec: @domena + spacja + 2-4 litery TLD
    //   Bezpieczne: wymaga @ na początku, TLD ≤ 4 litery — nie skleja zdań
    //
    // Reguła 2 — spacja w local-part przed @ (uruchamiać PO regule TLD):
    //   "mariusz kaminski@o2.pl" → "mariusz_kaminski@o2.pl"
    //   Wzorzec: fragment + spacja + fragment + lookahead @domena.tld
    //   Zamiana spacji → podkreślnik (dozwolony w RFC 5321)
    //   Lookahead wymaga pełnej domeny z kropką — dlatego reguła TLD musi być pierwsza
    // ----------------------------------------------------------
    private val OCR_EMAIL_TLDSPACE = Regex(
        """(@[a-zA-Z0-9.\-]{2,30})[^\S\n]([a-zA-Z]{2,4})\b"""
    )
    private val OCR_EMAIL_LOCALSPACE = Regex(
        """([a-zA-Z0-9._%+\-]{2,})[^\S\n]([a-zA-Z0-9._%+\-]{1,})(?=@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,4}\b)"""
    )

    // ----------------------------------------------------------
    // OCR_UL_PREFIX v1.4: naprawa skrótu "ul." rozbitego przez OCR
    //
    // "u. Nazwa" → "ul. Nazwa"  (OCR zgubił l)
    // "u Nazwa"  → "ul. Nazwa"  (OCR zgubił l i kropkę)
    //
    // Warunek bezpieczeństwa: lookbehind wyklucza środek słowa —
    // przed "u" musi być spacja, newline lub początek linii.
    // Lookahead wymaga wielkiej litery — tylko nazwy własne (ulice).
    // ----------------------------------------------------------
    private val OCR_UL_PREFIX = Regex(
        """(?<![a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ])(?:[uU][lI1]\.?[^\S\n]*|[uU]\.[^\S\n]*|(?:ulica|ULICA)[^\S\n]+)(?=[A-ZŁŚŹĆŃĄĘÓŻ])"""
    )

    // ----------------------------------------------------------
    // OCR_PESEL_DIGITS v1.4: naprawa liter zamiennych na cyfry w numerze PESEL
    //
    // OCR myli cyfry z literami: T→7, I/l→1, O→0, S→5, B→8, G→6, Z→2
    // Reguła działa TYLKO w kontekście po słowie "PESEL" — zero ryzyka
    // fałszywych zamian w pozostałym tekście.
    //
    // Przykład: "PESEL T2030375656" → "PESEL 72030375656"
    // ----------------------------------------------------------
    private val OCR_PESEL_DIGITS = Regex(
        """(?i)(?<=PESEL\s{0,3}:?\s{0,3})([TIlOSBGZ0-9]{11})(?!\d)"""
    )
    private val OCR_NUMERIC_CHAR_MAP = mapOf(
        'T' to '7', 'I' to '1', 'l' to '1',
        'O' to '0', 'S' to '5', 'B' to '8',
        'G' to '6', 'Z' to '2',
    )

    // ----------------------------------------------------------
    // OCR_NIP_DIGITS: NIP z kreskami (XXX-XXX-XX-XX) lub bez (10 cyfr)
    // Słowa kluczowe: NIP / NlP / N1P (artefakty OCR I→l/1)
    // ----------------------------------------------------------
    private val OCR_NIP_DIGITS = Regex(
        """(?i)(?<=(?:NIP|NlP|N1P)\s{0,3}:?\s{0,3})([TIlOSBGZ0-9][TIlOSBGZ0-9\-]{8,11}[TIlOSBGZ0-9])(?!\d)"""
    )

    // ----------------------------------------------------------
    // OCR_REGON_DIGITS: REGON 9-cyfrowy lub 14-cyfrowy
    // ----------------------------------------------------------
    private val OCR_REGON_DIGITS = Regex(
        """(?i)(?<=REGON\s{0,3}:?\s{0,3})([TIlOSBGZ0-9]{9}(?:[TIlOSBGZ0-9]{5})?)(?!\d)"""
    )

    // ----------------------------------------------------------
    // OCR_IBAN_DIGITS: IBAN / Nr konta — 26–32 znaków (może mieć spacje)
    // ----------------------------------------------------------
    private val OCR_IBAN_DIGITS = Regex(
        """(?i)(?<=(?:IBAN|Nr konta)\s{0,3}:?\s{0,3})([TIlOSBGZ0-9A-Z][TIlOSBGZ0-9A-Z ]{24,36}[TIlOSBGZ0-9A-Z])(?!\w)"""
    )

    // ----------------------------------------------------------

    fun normalize(rawText: String): NormalizationResult {
        var text = rawText
        var corrections = 0

        // 1. Naprawa "Sp.z o.0." → "Sp. z o.o."
        text = LEGAL_ZERO_RE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}o."
        }

        // 2. Cyfra 1 między literami → l
        text = OCR_ONE_AS_L.replace(text) { m ->
            corrections++
            "l"
        }

        // 3. Cyfra 0 między literami → o
        text = OCR_ZERO_AS_O.replace(text) { m ->
            corrections++
            "o"
        }

        // 4. Pipe | między literami → l
        text = OCR_PIPE_AS_L.replace(text) { m ->
            corrections++
            "l"
        }

        // 5. OCR-spacja w środku nazwy ulicy (tylko po prefiksie)
        text = OCR_STREET_MIDSPACE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}"
        }

        // 6. OCR-spacja w nazwie miasta — sprawdza zamkniętą listę
        text = OCR_CITY_MIDSPACE.replace(text) { m ->
            val candidate = m.groupValues[1] + m.groupValues[2]
            if (KNOWN_CITY_FORMS.contains(candidate.lowercase())) {
                corrections++
                candidate
            } else {
                m.value  // nie skleja — brak w liście
            }
        }

        // 7. OCR-spacja przed TLD emaila — "jan@onet pl" → "jan@onet.pl"
        text = OCR_EMAIL_TLDSPACE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}.${m.groupValues[2]}"
        }

        // 8. OCR-spacja w local-part emaila — "jan kowalski@wp.pl" → "jan_kowalski@wp.pl"
        //    Po kroku 7 — lookahead wymaga już poprawnej domeny z kropką
        text = OCR_EMAIL_LOCALSPACE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}_${m.groupValues[2]}"
        }

        // 9. OCR: "u. Nazwa" lub "u Nazwa" → "ul. Nazwa"
        text = OCR_UL_PREFIX.replace(text) { m ->
            corrections++
            "ul. "
        }

        // 10. OCR: litery zamienione na cyfry w numerze PESEL
        text = OCR_PESEL_DIGITS.replace(text) { m ->
            val fixed = m.groupValues[1].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[1]) corrections++
            fixed
        }

        // 11. OCR: litery zamienione na cyfry w NIP (z kreskami lub bez)
        text = OCR_NIP_DIGITS.replace(text) { m ->
            val fixed = m.groupValues[1].map { if (it == '-') it else OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[1]) corrections++
            fixed
        }

        // 12. OCR: litery zamienione na cyfry w REGON (9 lub 14 cyfr)
        text = OCR_REGON_DIGITS.replace(text) { m ->
            val fixed = m.groupValues[1].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[1]) corrections++
            fixed
        }

        // 13. OCR: litery zamienione na cyfry w IBAN / Nr konta
        text = OCR_IBAN_DIGITS.replace(text) { m ->
            val fixed = m.groupValues[1].map { if (it == ' ') it else OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[1]) corrections++
            fixed
        }

        return NormalizationResult(text, corrections)
    }

    fun assessQuality(text: String, mlKitConfidence: Float?): QualityAssessment {
        val issues = mutableListOf<String>()

        if (mlKitConfidence != null && mlKitConfidence < 0.7f) {
            issues.add("niska pewność OCR (${(mlKitConfidence * 100).toInt()}%)")
        }

        val hasPolishChars = text.any { it in "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ" }
        if (text.length > 100 && !hasPolishChars) {
            issues.add("brak polskich znaków diakrytycznych — matching nazwisk może być niepełny")
        }

        if (text.length < 30 && mlKitConfidence != null) {
            issues.add("bardzo krótki tekst — możliwy błąd skanowania")
        }

        return QualityAssessment(
            showWarning = issues.isNotEmpty(),
            issues = issues
        )
    }
}
