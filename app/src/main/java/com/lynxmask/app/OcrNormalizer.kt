package com.lynxmask.app

// OcrNormalizer.kt — Warstwa 0: Normalizacja tekstu przed pseudonimizacją
// Wersja: 2.6
//
// Zasada: TYLKO deterministyczne, bezpieczne poprawki o zerowym ryzyku fałszywych zmian.
//
// Zmiany v2.6 (22.06):
//   - Krok 0: keyword canonicalization — zdegradowane PESEL/NIP/REGON → czyste słowa kluczowe
//     przed naprawą cyfr i przed StructuralEngine (koniec łatania instancji w lookbehindach)
//
// Zmiany v2.4 (21.06):
//   - N5: OCR_CITY_MIDSPACE — fold() dla kandydata bez ogonków
//     "Bialy stok" → "Bialystok" (OCR bez ł) teraz rozpoznawany przez KNOWN_CITY_FORMS_FOLDED
//     fold() stosowany tylko do porównania; sklejony wynik zachowuje oryginalną formę OCR
//
// Zmiany v2.3 (21.06):
//   - OCR_IBAN_NEWLINE (krok 13aa): IBAN rozłożony przez newline → sklejenie
//     "PL89 1090 1014 7449\n5552 5211 0732" → "PL89109010147449555252110732"
//     Warunek: łączna liczba cyfr == 26 (analogicznie do OCR_IBAN_SPLIT)
//
// Zmiany v2.2 (21.06):
//   - OCR_DOWOD_DIGITS rozszerzony o naprawę SERII dokumentu (odwrotna sytuacja niż krok 11d v2.1):
//     cyfra w pozycji litery serii → litera (OCR_SERIES_CHAR_MAP: 2→Z, 0→O, 1→I, 8→B, 5→S, 6→G, 7→T)
//     "dowód: 2TS935950" → "dowód: ZTS935950" (doc_00020: Z→2 w serii)
//
// Zmiany v2.1 (21.06):
//   - OCR_DIGIT_IN_CONTEXT (krok 14) rozszerzony o lowercase 'o': [lOI] → [lOIo]
//     "71o1" → "7101", "965690-71o1" → "965690-7101" (małe 'o' między cyframi)
//   - OCR_DOWOD_DIGITS (krok 11d, nowy): S→5, O→0, I→1, B→8, G→6, Z→2 w cyfrowej części
//     numeru dowodu osobistego PO słowie kluczowym (dowód/d.o.). Bez kontekstu nie działa.
//
// Zmiany v2.0 (20.06):
//   - OCR_PESEL_SPLIT rozszerzony: akceptuje l/O/I w numerze PESEL + konwertuje przez OCR_NUMERIC_CHAR_MAP
//     "PESEL: 9l0405 l2367" → "PESEL: 91040512367" (wcześniej: tylko spacje, nie l)
//   - OCR_IBAN_SPLIT rozszerzony: akceptuje l/O/I w numerze IBAN + konwertuje przez OCR_NUMERIC_CHAR_MAP
//     "PL89l090l0l474495552 52ll0732" → "PL89109010147449555252110732"
//   - OCR_DIGIT_IN_CONTEXT (krok 14): litera l/O/I otoczona cyframi → cyfra (safety net)
//     "48 60l234567" → "48 601234567" (gdy tylko cyfry sąsiadują — bez spacji)
//
// Zmiany v1.9 (20.06):
//   - OCR_IBAN_SPLIT: spacja wstawiona przez OCR w środku numeru IBAN
//     "PL02114019872105222748309 170" → "PL02114019872105222748309170"
//     Warunek: suma cyfr po obu stronach spacji == 26 (dokładna długość polskiego IBAN)
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
//
// Zmiany v1.4 (18.06):
//   - OCR_EMAIL_TLDSPACE: "jan@onet pl" → "jan@onet.pl"
//   - OCR_EMAIL_LOCALSPACE: "jan kowalski@wp.pl" → "jan_kowalski@wp.pl"
//   - OCR_UL_PREFIX: "u. Nazwa" / "u Nazwa" → "ul. Nazwa"
//   - OCR_NIP_DIGITS: litery jako cyfry po słowie NIP/NlP/N1P
//   - OCR_REGON_DIGITS: litery jako cyfry po słowie REGON
//   - OCR_IBAN_DIGITS: litery jako cyfry po słowie IBAN / Nr konta
//
// Zmiany v1.5 (18.06):
//   - OCR_PESEL_DIGITS: kontekstowa naprawa cyfr po słowie PESEL (T→7, O→0 itd.)
//
// Zmiany v1.8 (20.06):
//   - OCR_NIP_DOT: kropka zamiast ostatniego myślnika NIP — "873-054-80.39" → "873-054-80-39"
//
// Zmiany v1.7 (20.06):
//   - OCR_EMAIL_AT_Q: '@' zamieniony na 'Q' przez OCR — "jan Qonet.pl" → "jan@onet.pl"
//
// Zmiany v1.6 (19.06):
//   - OCR_PESEL_WORD: zastępuje OCR_PESEL_DIGITS — pełny wzorzec słowa obsługuje
//     PESE1, PE5EL, P E S E L, PEB3L, pesel małymi literami
//   - OCR_PESEL_SPLIT: "PESEL: 6802041 8568" → "PESEL: 68020418568"
//   - OCR_NIP_SPLIT: "NIP: 740-61 7-82-26" → "NIP: 740-617-82-26"
//   - OCR_UL_PREFIX rozszerzony: UI./uI./u1./ulica/ULICA

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
    // BUG-ZIELONAGORA-FIX (04.07, diagnoza Cursor): global (?i) sprawiał że grupa 3
    // (miała wymagać MAŁYCH liter — kontynuacja rozbitego słowa) łapała też WIELKĄ literę,
    // więc "Zielona Góra" (dwa osobne, poprawne słowa) było sklejane w "ZielonaGóra" jakby
    // to był jeden wyraz rozbity przez OCR. Fix: (?i) tylko na prefiksie ul/al/pl/os,
    // grupy 2 i 3 z powrotem case-sensitive (grupa 3 = wyłącznie małe litery = kontynuacja).
    private val OCR_STREET_MIDSPACE = Regex(
        """((?i:ul|al|pl|os)\.[^\S\n]*)([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,9})[^\S\n]+([a-ząćęłńóśźż]{2,7})(?=[^\S\n]+\d|[^\S\n]*,|[^\S\n]*\n|[^\S\n]*$)"""
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

    private fun foldPolish(s: String): String = s
        .replace('ą', 'a').replace('ć', 'c').replace('ę', 'e')
        .replace('ł', 'l').replace('ń', 'n').replace('ó', 'o')
        .replace('ś', 's').replace('ź', 'z').replace('ż', 'z')

    private val KNOWN_CITY_FORMS_FOLDED: Set<String> =
        KNOWN_CITY_FORMS.map { foldPolish(it) }.toHashSet()

    private val OCR_CITY_MIDSPACE = Regex(
        """([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,10})[^\S\n]+([a-ząćęłńóśźż]{2,8})"""
    )

    // ----------------------------------------------------------
    // OCR_SURNAME_MIDSPACE: sklejanie nazwisk rozbitych przez OCR
    //
    // S6: "Kowal ski" → "Kowalski", "Malinow ski" → "Malinowski"
    // Ten sam wzorzec co OCR_CITY_MIDSPACE — sprawdza LookupTables.surnamesForms.
    // ----------------------------------------------------------
    private val OCR_SURNAME_MIDSPACE = Regex(
        """([A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźż]{2,10})[^\S\n]+([a-ząćęłńóśźż]{2,8})"""
    )

    // ----------------------------------------------------------
    // OCR_EMAIL_AT_Q v1.7: naprawa '@' zamienionego na 'Q' przez OCR
    //
    // "lukaszszymanski Qinteria.pl" → "lukaszszymanski@interia.pl"
    // Wzorzec: local-part + spacja + Q + domena.tld
    // Warunek bezpieczeństwa: domena musi zaczynać się małą literą lub cyfrą
    // (odróżnia "Qinteria.pl" od "QBASIC.txt" gdzie Q to litera, nie @)
    // ----------------------------------------------------------
    private val OCR_EMAIL_AT_Q = Regex(
        """([\w._%+\-]{3,})[^\S\n]+Q([a-z0-9][\w\-]*\.[a-zA-Z][a-zA-Z0-9]{1,3})\b"""
    )

    // ----------------------------------------------------------
    // OCR_EMAIL_SPACES: naprawa artefaktów spacji w adresach email
    //
    // Kolejność stosowania (KRYTYCZNA):
    //   1. OCR_EMAIL_SLDSPACE: spacja wewnątrz SLD — "@inte ria.pl" → "@interia.pl"
    //   2. OCR_EMAIL_TLDSPACE: spacja przed TLD   — "@onet pl"      → "@onet.pl"
    //   3. OCR_EMAIL_LOCALSPACE: spacja w local-part — "jan k@wp.pl" → "jan_k@wp.pl"
    //      (lookahead wymaga już naprawionej domeny — TLD/SLD muszą być pierwsze)
    //
    // TLD we wszystkich wzorcach: [a-zA-Z][a-zA-Z0-9]{1,3} — przyjmuje cyfry (OCR: "p1"→"pl")
    // ----------------------------------------------------------
    private val OCR_EMAIL_SLDSPACE = Regex(
        """(@[a-zA-Z0-9\-]{2,15})[^\S\n]([a-zA-Z0-9\-]{2,15}\.[a-zA-Z][a-zA-Z0-9]{1,3})\b"""
    )
    private val OCR_EMAIL_TLDSPACE = Regex(
        """(@[a-zA-Z0-9.\-]{2,30})[^\S\n]([a-zA-Z0-9]{2,4})\b"""
    )
    // N3: {1,} zamiast {2,} w fragmencie1 — obsługa jednoliiterowych segmentów ("jan k owal ski@...")
    private val OCR_EMAIL_LOCALSPACE = Regex(
        """([a-zA-Z0-9._%+\-]{1,})[^\S\n]([a-zA-Z0-9._%+\-]{1,})(?=@[a-zA-Z0-9.\-]+\.[a-zA-Z][a-zA-Z0-9]{1,3}\b)"""
    )

    // OCR_EMAIL_COMPACT (krok 0b): spacje wewnątrz emaila przed pozostałymi krokami email
    private val OCR_EMAIL_SPACE_AFTER_AT = Regex("""(@)\s+([a-zA-Z0-9])""")
    // Tylko typowe artefakty OCR — nie skleja "krzysztof nowakowski@" (→ LOCALSPACE _)
    private val OCR_EMAIL_SPACE_BEFORE_AT_DOT = Regex(
        """([a-zA-Z0-9._%+\-]*\.[a-zA-Z0-9._%+\-]+)\s+([a-zA-Z0-9._%+\-]+@)"""
    )
    private val OCR_EMAIL_SPACE_BEFORE_AT_DIGITS = Regex(
        """([a-zA-Z0-9._%+\-]+)\s+([a-zA-Z0-9._%+\-]*\d[a-zA-Z0-9._%+\-]*@)"""
    )

    // ----------------------------------------------------------
    // OCR_ABBREV_SPACE_DOT: spacja wstawiona przez OCR między skrótem a kropką
    // "ul .Marszałkowska" → "ul.Marszałkowska"  (OCR_UL_PREFIX doda spację po kropce)
    // "al .Grunwaldzka"   → "al.Grunwaldzka"
    // Obsługuje: ul, al, os, pl — przed wielką literą lub spacją+wielką literą.
    // ----------------------------------------------------------
    private val OCR_ABBREV_SPACE_DOT = Regex(
        """(?<![a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ])(ul|al|os|pl)[^\S\n]+\.(?=[^\S\n]*[A-ZŁŚŹĆŃĄĘÓŻ])""",
        RegexOption.IGNORE_CASE
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
    // OCR_ADDR_PREFIX (04.07, diagnoza Cursor — BUG-PI-WOLHOCI): analogicznie do OCR_UL_PREFIX,
    // ale dla al./os./pl. — "pI. Nazwa" (duże I zamiast małego l), "aI.", "o5." itp.
    // AnchorEngine A.11 i StructuralEngine ADDRESS_PATTERNS wymagają dosłownie "pl\."/"al\."/"os\." —
    // bez tej normalizacji zdegradowany prefiks nigdy nie trafia w żadną z tych kotwic.
    // "ul." NIE tu — w pełni obsłużone już przez OCR_UL_PREFIX powyżej.
    // ----------------------------------------------------------
    // Lookahead (?=[^\n]{0,55}\d) wymaga numeru budynku w zasięgu linii — odcina większość
    // fałszywych trafień na niezwiązane skróty (np. "AI. Nowak" bez numeru nie jest adresem).
    //
    // BUG-PLN-IBAN-FIX (04.07): separator PO literze prefiksu był całkowicie opcjonalny
    // (zero-width) — regex łapał "PL" wewnątrz "PLN 1234" (waluta) i "PL61..." (IBAN),
    // bo zaraz po literze szła wielka litera/cyfra bez żadnego odstępu. Fix: separator
    // MUSI być — kropka (opcjonalnie + spacja) ALBO co najmniej jedna spacja, nigdy zero znaków.
    // "PLN"/"PL61" nie mają ani kropki, ani spacji po drugiej literze → już nie pasują.
    private val OCR_ADDR_PREFIX = Regex(
        """(?<![a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ])(?:([aA])[lLiI1](?:\.[^\S\n]*|[^\S\n]+)|([oO])[sS5](?:\.[^\S\n]*|[^\S\n]+)|([pP])[lLiI1](?:\.[^\S\n]*|[^\S\n]+))(?=[A-ZŁŚŹĆŃĄĘÓŻ][^\n]{0,55}\d)"""
    )

    // ----------------------------------------------------------
    // Krok 0 — keyword canonicalization: zdegradowany keyword → czysty PESEL/NIP/REGON
    // Wszystkie dalsze kroki (OCR_PESEL_WORD, OCR_NIP_DIGITS, StructuralEngine) widzą tylko canonical form.
    // ----------------------------------------------------------
    private val OCR_KW_PESEL = Regex(
        """(?<![a-zA-Z0-9])P[^\S\n]?[E3B][^\S\n]?[S5B8][^\S\n]?[E3][^\S\n]?[LlI1i|](?![a-zA-Z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val OCR_KW_NIP = Regex(
        """(?<![a-zA-Z0-9])N[^\S\n]?[IiLl1|tTjJ][^\S\n]?P(?![a-zA-Z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val OCR_KW_REGON = Regex(
        """(?<![a-zA-Z0-9])R[^\S\n]?[E3][^\S\n]?G[^\S\n]?[O0][^\S\n]?N(?![a-zA-Z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val OCR_KW_IBAN = Regex(
        """(?<![a-zA-Z0-9])[Il1][^\S\n]?B[^\S\n]?A[^\S\n]?N(?![a-zA-Z0-9])""",
        RegexOption.IGNORE_CASE
    )

    // ----------------------------------------------------------
    // OCR_POSTAL_CODE: kod pocztowy PL XX-XXX z artefaktami O→0, I→1, l→1
    // "2O-1OO" → "20-100", "T2-345" → "72-345"
    // Zamienia TYLKO gdy obie grupy po normalizacji są czystymi cyframi (brak FP na seriach).
    // ----------------------------------------------------------
    private val OCR_POSTAL_CODE = Regex("""(?<!\w)([0-9TIlOo]{2})-([0-9TIlOo]{3})(?!\w)""")

    // ----------------------------------------------------------
    // OCR_POSTAL_SPACE: kod pocztowy z spacją zamiast myślnika: "41 200 Sosnowiec"
    // Naprawia tylko po przecinku (kontekst adresu), przed wielką literą (nazwa miasta).
    // Bezpieczne: kwoty ("cena 41 200 zł") nie są poprzedzone przecinkiem → brak FP.
    // ----------------------------------------------------------
    private val OCR_POSTAL_SPACE = Regex("""(,\s{0,5})(\d{2})\s{1,3}(\d{3})(?=\s+[A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźżA-Za-z])""")

    // ----------------------------------------------------------
    // OCR_PESEL_WORD v1.6: naprawa liter zamiennych na cyfry w numerze PESEL
    //
    // OCR myli cyfry z literami: T→7, I/l→1, O→0, S→5, B→8, G→6, Z→2
    // Obsługuje artefakty OCR w samym słowie "PESEL":
    //   E→3, S→5/B/8, L→1/I/i/l/|, spacje między literami
    // Przykład: "PESE1: T2030375656" → "PESE1: 72030375656"
    // Przykład: "PESEL T2030375656"  → "PESEL 72030375656"
    // ----------------------------------------------------------
    private val OCR_PESEL_WORD = Regex(
        """(?i)(?<![a-zA-Z0-9])P[^\S\n]?[E3][^\S\n]?[S5B8][^\S\n]?[E3][^\S\n]?[LlI1i|]\s{0,3}:?\s{0,3}([TIlOSBGZ0-9]{11})(?!\d)"""
    )
    private val OCR_NUMERIC_CHAR_MAP = mapOf(
        'T' to '7', 'I' to '1', 'l' to '1',
        'O' to '0', 'o' to '0',  // OCR_ZERO_AS_O może zmienić '0' → 'o' w kontekście l0l
        'S' to '5', 'B' to '8',
        'G' to '6', 'Z' to '2',
        'z' to '2',
    )

    /** Homoglify OCR w segmentach liczbowych (np. cyrylica З→3 w gołym NIP — S4 / RODO v2 zad. 4). */
    private val OCR_HOMOGLYPH_DIGIT_MAP: Map<Char, Char> = OCR_NUMERIC_CHAR_MAP + mapOf(
        '\u0417' to '3', '\u0437' to '3', // Cyrillic З
        '\u041E' to '0', '\u043E' to '0', // Cyrillic О
        '\u0406' to '1', '\u0456' to '1', // Cyrillic І
    )

    private fun ocrHomoglyphDigit(c: Char): Char = OCR_HOMOGLYPH_DIGIT_MAP[c] ?: c

    private fun fixOcrDigitSegment(seg: String): String =
        seg.map { ocrHomoglyphDigit(it) }.joinToString("")

    // Odwrotność OCR_NUMERIC_CHAR_MAP — cyfra jako litera w serii dowodu (pozycja zawsze liter)
    private val OCR_SERIES_CHAR_MAP = mapOf(
        '2' to 'Z', '1' to 'I', '0' to 'O',
        '8' to 'B', '5' to 'S', '6' to 'G', '7' to 'T'
    )

    // ----------------------------------------------------------
    // OCR_PESEL_SPLIT: spacja wstawiona przez OCR wewnątrz numeru PESEL + litery jako cyfry
    // Przykład: "PESEL: 6802041 8568"  → "PESEL: 68020418568"
    // Przykład: "PESEL: 9l0405 l2367" → "PESEL: 91040512367"
    // BUG-PESEL-KOD-SKLEJENIE-FIX (Cursor 01.07): [TIlOo0-9\s]{10,14} (klasa znaków
    // ze spacją bez ograniczeń) traktował spację PRZED sąsiednim kodem pocztowym jako
    // kontynuację PESEL-u — "PESEL 90051512340 00-001" → normalizer usuwał spację i
    // zlepiał "9005151234000-001", zanim JAKAKOLWIEK reguła maskująca zobaczyła tekst
    // (OcrNormalizer to Warstwa 0, przed wszystkim innym). Fix: (?:...|\s(?!\d{2}-))
    // zamiast prostej klasy znaków ze spacją — spacja dozwolona jako separator TYLKO
    // gdy nie zaczyna kształtu kodu pocztowego (\d{2}-).
    // ----------------------------------------------------------
    private val OCR_PESEL_SPLIT = Regex(
        """(?i)(P[^\S\n]?[E3][^\S\n]?[S5B8][^\S\n]?[E3][^\S\n]?[LlI1i|]\s{0,3}:?\s{0,3})([TIlOo0-9](?:[TIlOo0-9]|\s(?!\d{2}-)){9,13}[TIlOo0-9])"""
    )

    // ----------------------------------------------------------
    // OCR_NIP_DIGITS: NIP z kreskami (XXX-XXX-XX-XX) lub bez (10 cyfr).
    // Obsługuje "NIP:" i "NIP modyfikator:" (nabywcy, świadka, sprzedawcy itp.).
    // Gr. 1 = keyword + opcjonalny modyfikator + separator; Gr. 2 = garbled cyfry.
    // ----------------------------------------------------------
    private val OCR_NIP_DIGITS = Regex(
        """(?i)(NIP\s{0,3}(?:\w{1,16}\s{0,3})?:?\s{0,3})([TIlOSBGZ0-9][TIlOSBGZ0-9\-]{8,11}[TIlOSBGZ0-9])(?!\d)"""
    )

    // ----------------------------------------------------------
    // OCR_NIP_POSTAL_GLUE (Cursor 01.07): NIP sklejony BEZ separatora z kodem pocztowym
    // — "526-021-15-8100-001" (NIP "526-021-15-81" + kod "00-001", zero spacji między
    // nimi). Bez tego A.5 (kotwica NIP w AnchorEngine) łapał cały ciąg razem, a kod
    // pocztowy nigdy nie stawał się osobnym tokenem ADRES. Wstawia spację w miejscu
    // gdzie kończy się kształt NIP (3-3-2-2, 10 cyfr) a zaczyna kształt kodu (XX-XXX).
    // ----------------------------------------------------------
    private val OCR_NIP_POSTAL_GLUE = Regex(
        """(\d{3}-\d{3}-\d{2}-\d{2})(\d{2}-\d{3})"""
    )

    // ----------------------------------------------------------
    // OCR_NIP_SPLIT: spacja wstawiona przez OCR wewnątrz NIP.
    // Obsługuje "NIP:" i "NIP modyfikator:".
    // Przykład: "NIP nabywcy: 740-61 7-82-26" → "NIP nabywcy: 740-617-82-26"
    // ----------------------------------------------------------
    private val OCR_NIP_SPLIT = Regex(
        """(?i)(NIP\s{0,3}(?:\w{1,16}\s{0,3})?:?\s{0,3})([0-9][0-9\-\s]{10,16}[0-9])"""
    )

    // ----------------------------------------------------------
    // OCR_HOUSE_NUM: I→1, O→0 w numerze budynku po ul./al./pl./os. + nazwa ulicy.
    // Gr. 1 = prefiks ul./al. + nazwa ulicy + spacja; Gr. 2 = garbled numer budynku.
    // Obsługuje 1 lub 2-człowe nazwy ulic.
    // "ul. Słoneczna I9" → "ul. Słoneczna 19", "ul. Niepodległości I35" → "ul. Niepodległości 135"
    // ----------------------------------------------------------
    private val OCR_HOUSE_NUM = Regex(
        """((?i:ul[.,]?|al[.,]?|pl[.,]?|os\.)[^\S\n]+(?:[A-Za-ząćęłńóśźżĄĆĘŁŃÓŚŹŻ\-]{2,50}[^\S\n]+){1,2})([IOl][0-9IOl]{0,3})(?=[,\s\n/]|$)"""
    )

    /** Wyciąga 10 cyfr NIP z fragmentu OCR (z mapą liter→cyfry). */
    private fun nipDigitsFromOcrRaw(raw: String): String =
        raw.map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.filter { it.isDigit() }.joinToString("")

    /** Format kanoniczny 3-3-2-2 (XXX-XXX-XX-XX). */
    private fun formatNip3322(digits: String): String =
        "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 8)}-${digits.substring(8, 10)}"

    /** Alternatywny format 3-2-2-3 (XXX-XX-XX-XXX). */
    private fun formatNip3223(digits: String): String =
        "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5, 7)}-${digits.substring(7, 10)}"

    /**
     * Skleja rozbito-spacjowany NIP do poprawnego formatu z myślnikami.
     * Domyślnie 3-3-2-2; 3-2-2-3 gdy segmenty po myślnikach sugerują ten układ.
     */
    private fun fixOcrNipNumber(raw: String): String {
        val digits = nipDigitsFromOcrRaw(raw)
        if (digits.length != 10) {
            return raw.replace(Regex("""[\s\n\r\t]+"""), "")
        }
        val segments = raw.replace(Regex("""[\s\n\r\t]+"""), "")
            .split('-')
            .map { seg -> nipDigitsFromOcrRaw(seg) }
            .filter { it.isNotEmpty() }
        if (segments.size >= 4 && segments.first().length == 3 && segments.last().length == 3) {
            return formatNip3223(digits)
        }
        return formatNip3322(digits)
    }

    // ----------------------------------------------------------
    // OCR_NIP_DOT: kropka zamiast ostatniego myślnika w NIP
    // Przykład: "873-054-80.39" → "873-054-80-39"
    // Wzorzec bardzo specyficzny (3-3-2.2) — minimalny FP w polskich dokumentach
    // ----------------------------------------------------------
    private val OCR_NIP_DOT = Regex(
        """(\d{3}-\d{3}-\d{2})\.(\d{2})(?!\d)"""
    )

    // OCR_NIP_BARE3322 / 3223: kształt NIP bez słowa kluczowego — l/O/cyrylica w segmentach (S4)
    private val OCR_NIP_BARE3322 = Regex(
        """\b(\d{3})([ \t\-.])([0-9TIlOSBGZ\u0417\u0437\u041E\u043EoOIl]{3})([ \t\-.])([0-9TIlOSBGZ\u0417\u0437\u041E\u043EoOIl]{2})([ \t\-.])([0-9TIlOSBGZ\u0417\u0437\u041E\u043EoOIl]{2})\b"""
    )
    private val OCR_NIP_BARE3223 = Regex(
        """\b(\d{3})([ \t\-.])([0-9TIlOSBGZ\u0417\u0437\u041E\u043EoOIl]{2})([ \t\-.])([0-9TIlOSBGZ\u0417\u0437\u041E\u043EoOIl]{2})([ \t\-.])([0-9TIlOSBGZ\u0417\u0437\u041E\u043EoOIl]{3})\b"""
    )

    private fun replaceNipBareShape(m: MatchResult): String {
        val seps = listOf(m.groupValues[2], m.groupValues[4], m.groupValues[6])
        val segs = listOf(m.groupValues[1], m.groupValues[3], m.groupValues[5], m.groupValues[7])
        val fixed = segs.map { fixOcrDigitSegment(it) }
        if (fixed == segs || fixed.any { s -> s.any { !it.isDigit() } }) return m.value
        return "${fixed[0]}${seps[0]}${fixed[1]}${seps[1]}${fixed[2]}${seps[2]}${fixed[3]}"
    }

    // ----------------------------------------------------------
    // OCR_DOWOD_DIGITS: litery jako cyfry w numerze dowodu osobistego po słowie kluczowym
    // Słowa kluczowe: "dowód/dowod/dow." + "os." + dwukropek/separator
    // Format PL: 3 litery (seria) + 6 cyfr — OCR często myli S→5, O→0, I→1 w cyfrach
    // Naprawia cyfry w części numerycznej (po serii liter), nie w samej serii
    // Przykład: "Nr dowodu: CAN6O1202" → "CAN601202"
    //           "dowód: AHB S45316"    → "AHB 545316" (S→5)
    // ----------------------------------------------------------
    private val OCR_DOWOD_DIGITS = Regex(
        """(?i)(?:dow[oó]d\w{0,4}\b(?:\s+os\w{0,10})?|d\.?o\.)[^\S\n]*[:–\-]?\n?[^\S\n]*([A-Z0-9]{2,3})[^\S\n]?([TIlOSBGZ0-9]{2,3}[^\S\n]?[TIlOSBGZ0-9]{3,4}|[TIlOSBGZ0-9]{6})"""
    )

    // ----------------------------------------------------------
    // OCR_PASZPORT_DIGITS: litery jako cyfry w numerze paszportu po słowie kluczowym
    // Format PL: 2 litery (seria, nie naprawiaj) + 7 cyfr (napraw artefakty OCR)
    // Przykład: "paszport: AB I234567" → "paszport: AB 1234567"
    // ----------------------------------------------------------
    private val OCR_PASZPORT_DIGITS = Regex(
        """(?i)\bpaszport\w{0,2}\b[^\S\n]*[:–\-]?\n?[^\S\n]*([A-Z]{2})[^\S\n]?([TIlOSBGZ0-9]{7,9})"""
    )

    // ----------------------------------------------------------
    // OCR_REGON_DIGITS: REGON 9-cyfrowy lub 14-cyfrowy
    // ----------------------------------------------------------
    private val OCR_REGON_DIGITS = Regex(
        """(?i)(?<=REGON\s{0,3}:?\s{0,3})([TIlOSBGZ0-9]{9}(?:[TIlOSBGZ0-9]{5})?)(?!\d)"""
    )

    // ----------------------------------------------------------
    // OCR_IBAN_NEWLINE v2.3: IBAN rozłożony przez newline
    // "PL89 1090 1014 7449\n5552 5211 0732" → "PL89109010147449555252110732"
    // Warunek bezpieczeństwa: łączna liczba cyfr po PL == 26 (analogicznie do IBAN_SPLIT)
    // ----------------------------------------------------------
    private val OCR_IBAN_NEWLINE = Regex(
        """\bPL([TIlOo0-9 ]{2,30})\n([TIlOo0-9 ]{2,25})\b"""
    )

    // ----------------------------------------------------------
    // OCR_IBAN_SPLIT v2.0: spacja wstawiona przez OCR wewnątrz numeru IBAN + litery jako cyfry
    // "PL02114019872105222748309 170"       → "PL02114019872105222748309170"
    // "PL89l090l0l474495552 52ll0732"       → "PL89109010147449555252110732"
    // Warunek bezpieczeństwa: łączy TYLKO gdy łączna liczba znaków == 26
    // (dokładna długość polskiego IBAN po PL). Chroni przed sklejaniem
    // niezwiązanych liczb zaczynających się od "PL".
    // ----------------------------------------------------------
    private val OCR_IBAN_SPLIT = Regex(
        """\bPL([TIlOo0-9]{2,25})[^\S\n]([TIlOo0-9]{1,24})\b"""
    )

    // ----------------------------------------------------------
    // OCR_PHONE_AFTER_KW (krok 14a): l/O/I w numerze po tel/kom/mob/fax
    // "tel: 48 60l 234 567" → "tel: 48 601 234 567" (S10 / BUG-TEL-PREFIX)
    // ----------------------------------------------------------
    private val OCR_PHONE_AFTER_KW = Regex(
        """(?i)\b(tel(?:efon)?|kom(?:\.|orkowy)?|mob(?:\.|ile)?|fax|faks)\.?[^\S\n]*[:–\-]?[^\S\n]*(\+?(?:48[^\S\n]*)?[\d\s\-().lOIo]{7,22}\d)"""
    )

    // ----------------------------------------------------------
    // OCR_DIGIT_IN_CONTEXT: litera l/O/I po cyfrze, przed cyfrą (z opcj. separatorami) → cyfra
    // "9l04" → "9104"; "52l-334" → "521-334" (myślnik między l a cyfrą — RODO v2 S4)
    // NIE przekracza newline.
    // ----------------------------------------------------------
    private val OCR_DIGIT_IN_CONTEXT = Regex("""(?<=\d)[lOIo]+(?=[\s\-./]*\d)""")

    // ----------------------------------------------------------
    // DE-LEET (krok 15): cyfry jako litery w tokenach zaczynających się wielką
    // literą — tylko gdy wynik trafia w słownik imion lub nazwisk.
    // "Be4ta" → "Beata", "Krzy5zt0f" → "Krzysztof", "N0w1ck1" → "Nowicki"
    // Mapa: 4→a, 3→e, 5→s, 0→o, 1→i (tylko cyfry mylone z literami w nazwach)
    // ----------------------------------------------------------
    private val LEET_MAP = mapOf('4' to 'a', '3' to 'e', '5' to 's', '0' to 'o', '1' to 'i')
    private val LEET_CANDIDATE_RE = Regex("""[A-ZĄĆĘŁŃÓŚŹŻ][a-zA-ZąćęłńóśźżĄĆĘŁŃÓŚŹŻ0-9]{2,}""")

    private fun deLeet(text: String): String {
        if (!LookupTables.initialized) return text
        return LEET_CANDIDATE_RE.replace(text) { match ->
            val token = match.value
            if (token.none { it.isDigit() }) return@replace token
            val converted = token.map { c -> LEET_MAP[c] ?: c }.joinToString("")
            val lower = converted.lowercase()
            if (LookupTables.namesForms.contains(lower) || LookupTables.surnamesForms.contains(lower)) {
                converted
            } else {
                token
            }
        }
    }

    // ----------------------------------------------------------
    // OCR_IBAN_DIGITS: IBAN / Nr konta — 26–32 znaków (może mieć spacje)
    // ----------------------------------------------------------
    // OCR_IBAN_DIGITS: IBAN / Nr konta — 26–32 znaków (może mieć spacje)
    // Bez lookbehind — Android ICU wymaga bounded lookbehind; \w* / \s* w (?<=…) crashuje test.
    // {24,32} = max middle dla PL IBAN z spacjami (P + 32 + last = 34 znaków total).
    // Bez (?!\w): regex matchuje nawet gdy IBAN przylega bez separatora do kolejnego tokenu.
    private val OCR_IBAN_DIGITS = Regex(
        """(?i)((?:IBAN|Nr\s{0,1}kont\w{0,6}|kont\w{0,4}|N\s+kort\w{0,6})\s{0,3}:?\s{0,3})([TIlOSBGZ0-9A-Z][TIlOSBGZ0-9A-Z ]{24,32}[TIlOSBGZ0-9A-Z])"""
    )

    // OCR_IBAN_PL_LOOSE: zdeformowany PL… bez poprawnej struktury (benchmark lvl 1–2)
    private val OCR_IBAN_PL_LOOSE = Regex("""\bPL([TIlOSBGZ0-9A-Za-z\s]{20,40})\b""")

    private fun isGarbledPlIban(raw: String): Boolean {
        val body = raw.drop(2)
        return body.any { it.isLowerCase() } ||
            Regex("""[A-Za-z]{4,}""").containsMatchIn(body)
    }

    private fun fixPlIbanLoose(raw: String): String? {
        val ibanHomoglyph = OCR_HOMOGLYPH_DIGIT_MAP + mapOf('e' to '3', 'E' to '3', 's' to '5', 'S' to '5')
        fun mapC(c: Char) = ibanHomoglyph[c] ?: c
        val homoglyph = raw.map { mapC(it) }.joinToString("")
        if (!homoglyph.uppercase().startsWith("PL")) return null
        val digits = homoglyph.filter { it.isDigit() }
        if (digits.length != 26) return null
        return "PL$digits"
    }

    // ----------------------------------------------------------

    fun normalize(rawText: String): NormalizationResult {
        var text = rawText
        var corrections = 0

        // 0. Keyword canonicalization — PESEL/NIP/REGON zanim dotkniemy cyfr lub wzorców strukturalnych
        text = OCR_KW_PESEL.replace(text) {
            corrections++
            "PESEL"
        }
        text = OCR_KW_NIP.replace(text) {
            corrections++
            "NIP"
        }
        text = OCR_KW_REGON.replace(text) {
            corrections++
            "REGON"
        }
        text = OCR_KW_IBAN.replace(text) {
            corrections++
            "IBAN"
        }

        // 0b. OCR: spacje wewnątrz emaila (@ po local-part) — przed krokami 6b/7
        var prev0b: String
        do {
            prev0b = text
            text = OCR_EMAIL_SPACE_AFTER_AT.replace(text) { m ->
                corrections++
                "${m.groupValues[1]}${m.groupValues[2]}"
            }
        } while (text != prev0b)
        do {
            prev0b = text
            text = OCR_EMAIL_SPACE_BEFORE_AT_DOT.replace(text) { m ->
                corrections++
                "${m.groupValues[1]}${m.groupValues[2]}"
            }
            text = OCR_EMAIL_SPACE_BEFORE_AT_DIGITS.replace(text) { m ->
                corrections++
                "${m.groupValues[1]}${m.groupValues[2]}"
            }
        } while (text != prev0b)

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
            val lower = candidate.lowercase()
            if (KNOWN_CITY_FORMS.contains(lower) || KNOWN_CITY_FORMS_FOLDED.contains(foldPolish(lower))) {
                corrections++
                candidate
            } else {
                m.value  // nie skleja — brak w liście
            }
        }

        // 6c. OCR-spacja w środku nazwiska — sprawdza LookupTables.surnamesForms
        //     S6: "Kowal ski" → "Kowalski" gdy złączone słowo znane w słowniku
        if (LookupTables.initialized) {
            text = OCR_SURNAME_MIDSPACE.replace(text) { m ->
                val candidate = m.groupValues[1] + m.groupValues[2]
                if (LookupTables.surnamesForms.contains(candidate.lowercase())) {
                    corrections++
                    candidate
                } else {
                    m.value
                }
            }
        }

        // 6b. OCR: '@' zamieniony na 'Q' — "lukaszszymanski Qinteria.pl" → "...@interia.pl"
        text = OCR_EMAIL_AT_Q.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}@${m.groupValues[2]}"
        }

        // 7a. OCR-spacja wewnątrz SLD emaila — "jan@inte ria.pl" → "jan@interia.pl"
        text = OCR_EMAIL_SLDSPACE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}${m.groupValues[2]}"
        }

        // 7b. OCR-spacja przed TLD emaila — "jan@onet pl" → "jan@onet.pl"
        text = OCR_EMAIL_TLDSPACE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}.${m.groupValues[2]}"
        }

        // 8. OCR-spacja w local-part emaila — "jan k owal ski@wp.pl" → "jan_k_owal_ski@wp.pl"
        //    N3: pętla do brak zmian — jedna iteracja naprawiała tylko pierwszą spację
        var prev8: String
        do {
            prev8 = text
            text = OCR_EMAIL_LOCALSPACE.replace(text) { m ->
                corrections++
                "${m.groupValues[1]}_${m.groupValues[2]}"
            }
        } while (text != prev8)

        // 8b. OCR: zdegradowany prefiks al./os./pl. ("pI.", "aI.", "o5.") → kanoniczny prefiks
        text = OCR_ADDR_PREFIX.replace(text) { m ->
            corrections++
            when {
                m.groupValues[1].isNotEmpty() -> "al. "
                m.groupValues[2].isNotEmpty() -> "os. "
                else -> "pl. "
            }
        }

        // 8c. OCR: spacja przed kropką skrótu adresowego — "ul .Nazwa" → "ul.Nazwa"
        text = OCR_ABBREV_SPACE_DOT.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}."
        }

        // 9. OCR: "u. Nazwa" lub "u Nazwa" → "ul. Nazwa"
        text = OCR_UL_PREFIX.replace(text) { m ->
            corrections++
            "ul. "
        }

        // 9b. OCR: I→1, O→0 w numerze budynku po ul./al./pl./os. — po OCR_UL_PREFIX
        text = OCR_HOUSE_NUM.replace(text) { m ->
            val fixed = m.groupValues[2].map { when(it) { 'I', 'l' -> '1'; 'O', 'o' -> '0'; else -> it } }.joinToString("")
            if (fixed != m.groupValues[2]) corrections++
            "${m.groupValues[1]}$fixed"
        }

        // 10. OCR: litery zamienione na cyfry w numerze PESEL
        text = OCR_PESEL_WORD.replace(text) { m ->
            val fixed = m.groupValues[1].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[1]) corrections++
            m.value.substring(0, m.value.length - m.groupValues[1].length) + fixed
        }

        // 10b. OCR: spacja wewnątrz numeru PESEL + litery jako cyfry
        text = OCR_PESEL_SPLIT.replace(text) { m ->
            val raw = m.groupValues[2]
            val converted = raw.replace(" ", "").map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (converted != raw) corrections++
            m.groupValues[1] + converted
        }

        // 11. OCR: litery zamienione na cyfry w NIP — "NIP nabywcy: 45I-OS2-35-26" → czyste cyfry
        // Gr.1 = keyword+modyfikator+separator (zachowane), Gr.2 = garbled cyfry (naprawiane)
        text = OCR_NIP_DIGITS.replace(text) { m ->
            val fixed = m.groupValues[2].map { if (it == '-') it else OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[2]) corrections++
            "${m.groupValues[1]}$fixed"
        }

        // 11b. OCR: spacja/newline wewnątrz NIP → 10 cyfr → XXX-XXX-XX-XX (lub 3-2-2-3)
        text = OCR_NIP_SPLIT.replace(text) { m ->
            val raw = m.groupValues[2]
            val fixed = fixOcrNipNumber(raw)
            if (fixed != raw) corrections++
            m.groupValues[1] + fixed
        }

        // 11c. OCR: kropka zamiast ostatniego myślnika w NIP — "873-054-80.39" → "873-054-80-39"
        text = OCR_NIP_DOT.replace(text) { m ->
            corrections++
            "${m.groupValues[1]}-${m.groupValues[2]}"
        }

        // 11c2. OCR: goły NIP (3-3-2-2 / 3-2-2-3) bez keyword — homoglify w segmentach
        text = OCR_NIP_BARE3322.replace(text) { m ->
            val fixed = replaceNipBareShape(m)
            if (fixed != m.value) corrections++
            fixed
        }

        // 11d. OCR: NIP sklejony bez separatora z kodem pocztowym — wstaw spację na granicy
        text = OCR_NIP_POSTAL_GLUE.replace(text) { m ->
            corrections++
            "${m.groupValues[1]} ${m.groupValues[2]}"
        }
        text = OCR_NIP_BARE3223.replace(text) { m ->
            val fixed = replaceNipBareShape(m)
            if (fixed != m.value) corrections++
            fixed
        }

        // 11d. OCR: cyfry jako litery w serii dowodu + litery jako cyfry w numerze (po słowie kluczowym)
        text = OCR_DOWOD_DIGITS.replace(text) { m ->
            val rawSeria = m.groupValues[1]
            val rawNum = m.groupValues[2]
            val fixedSeria = rawSeria.map { OCR_SERIES_CHAR_MAP[it] ?: it }.joinToString("")
            val fixedNum = rawNum.map { if (it.isWhitespace()) it else OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixedSeria != rawSeria || fixedNum != rawNum) corrections++
            val base = m.range.first
            val s1 = m.groups[1]!!.range.first - base
            val e1 = m.groups[1]!!.range.last - base + 1
            val s2 = m.groups[2]!!.range.first - base
            val e2 = m.groups[2]!!.range.last - base + 1
            m.value.substring(0, s1) + fixedSeria + m.value.substring(e1, s2) + fixedNum + m.value.substring(e2)
        }

        // 11d2. OCR: litery jako cyfry w numerze paszportu (seria intaktna, cyfry naprawiane)
        text = OCR_PASZPORT_DIGITS.replace(text) { m ->
            val raw = m.groupValues[2]
            val fixed = raw.map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != raw) corrections++
            m.value.replace(raw, fixed)
        }

        // 12. OCR: litery zamienione na cyfry w REGON (9 lub 14 cyfr)
        text = OCR_REGON_DIGITS.replace(text) { m ->
            val fixed = m.groupValues[1].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.groupValues[1]) corrections++
            fixed
        }

        // 13aa. OCR: IBAN rozłożony przez newline — skleja gdy łączna liczba cyfr == 26
        text = OCR_IBAN_NEWLINE.replace(text) { m ->
            val part1 = m.groupValues[1].replace(" ", "")
                .map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            val part2 = m.groupValues[2].replace(" ", "")
                .map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (part1.length + part2.length == 26) {
                corrections++
                "PL$part1$part2"
            } else m.value
        }

        // 13a. OCR: spacja w środku IBAN + litery jako cyfry — "PL...9 170" → "PL...9170"
        text = OCR_IBAN_SPLIT.replace(text) { m ->
            val part1 = m.groupValues[1].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            val part2 = m.groupValues[2].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (part1.length + part2.length == 26) {
                corrections++
                "PL$part1$part2"
            } else m.value
        }

        // 13. OCR: litery zamienione na cyfry w IBAN / Nr konta
        text = OCR_IBAN_DIGITS.replace(text) { m ->
            val prefix = m.groupValues[1]
            val digits = m.groupValues[2]
            val fixed = digits.map { if (it == ' ') it else OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != digits) corrections++
            prefix + fixed
        }

        // 13c. OCR: zdeformowany polski IBAN PL + homoglify (sesoZ, PLos1690…)
        text = OCR_IBAN_PL_LOOSE.replace(text) { m ->
            if (!isGarbledPlIban(m.value)) return@replace m.value
            val fixed = fixPlIbanLoose(m.value)
            if (fixed != null && fixed != m.value) {
                corrections++
                fixed
            } else m.value
        }

        // 13b. OCR: kod pocztowy PL XX-XXX z artefaktami O→0, I/l→1 (OCR_DIGIT_IN_CONTEXT nie działa przez myślnik)
        text = OCR_POSTAL_CODE.replace(text) { m ->
            val g1 = m.groupValues[1].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            val g2 = m.groupValues[2].map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (g1.all { it.isDigit() } && g2.all { it.isDigit() } &&
                (g1 != m.groupValues[1] || g2 != m.groupValues[2])) {
                corrections++
                "$g1-$g2"
            } else m.value
        }

        // 13c. OCR: kod pocztowy z spacją zamiast myślnika ("41 200 Sosnowiec" → "41-200 Sosnowiec")
        text = OCR_POSTAL_SPACE.replace(text) { m ->
            val fixed = "${m.groupValues[1]}${m.groupValues[2]}-${m.groupValues[3]}"
            if (fixed != m.value) corrections++
            fixed
        }

        // 14a. OCR: numer telefonu po słowie kluczowym — l/O/I → cyfry w bloku numeru
        text = OCR_PHONE_AFTER_KW.replace(text) { m ->
            val numPart = m.groupValues[2]
            val fixed = numPart.map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != numPart) {
                corrections++
                m.value.replace(numPart, fixed)
            } else m.value
        }

        // 14. OCR: litera l/O/I bezpośrednio między cyframi → cyfra (safety net)
        text = OCR_DIGIT_IN_CONTEXT.replace(text) { m ->
            val fixed = m.value.map { OCR_NUMERIC_CHAR_MAP[it] ?: it }.joinToString("")
            if (fixed != m.value) corrections++
            fixed
        }

        // 15. De-leet: cyfry jako litery w tokenach zaczynających się wielką literą
        val beforeDeLeet = text
        text = deLeet(text)
        if (text != beforeDeLeet) corrections++

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
