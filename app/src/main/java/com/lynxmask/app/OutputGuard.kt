package com.lynxmask.app

// OutputGuard.kt — Warstwa 6: Output Guard
// Wersja: 2.0
//
// Zwraca List<GuardHit> z poziomami RED (pewne PII) i YELLOW (podejrzane z kontekstem).
// Tokeny własne (format TYP_NNN) zastępowane przez ⟦TOKEN⟧ przed skanowaniem.
//
// v2.0: Nowe wzorce RED: PESEL_SPACE, TELEFON_PELNY (szerszy — kropka, 0048, nawiasy).
//       YELLOW SYGNATURA i LICZBA zawężone kotwicą słowną (okno 35 znaków przed hitem).
//       Nowe YELLOW: URODZENIE, MIEJSCE_UR, EMAIL_FRAGMENT.
//       Usunięte: REGON, PL_PREFIX (silnik maskuje), TELEFON (superseded przez TELEFON_PELNY RED).
//       Naprawiony token exclusion regex — aktualny format TYPE_NNN.

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

    // Zamień własne tokeny na placeholder — FIRMA i ORGANIZACJA oba przez czas migracji taksonomii
    val tokenRe = Regex("""\b(?:OSOBA|ADRES|NUMER|ORGANIZACJA|FIRMA|EMAIL|TELEFON|SYGNATURA|DATA|KWOTA)_\d{3}\b""")
    val text = tokenRe.replace(pseudonymizedText, "⟦TOKEN⟧")

    fun hit(label: String, level: String, m: MatchResult) =
        GuardHit(label, level, m.value, m.range.first, m.range.last + 1)

    // ── RED — każde dopasowanie to wyciek ────────────────────────────────────
    val redPatterns = listOf(
        // 11 cyfr z rzędu — standardowy PESEL
        "PESEL"         to Regex("""\b\d{11}\b"""),
        // 11 cyfr z dowolnym separatorem (spacja/myślnik) między każdą parą — artefakt OCR
        "PESEL_SPACE"   to Regex("""\b(?:\d[ \-]?){10}\d\b"""),
        "NIP"           to Regex("""\b\d{3}[-\s]?\d{3}[-\s]?\d{2}[-\s]?\d{2}\b"""),
        "IBAN"          to Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}\b"""),
        "EMAIL"         to Regex("""\b[a-zA-Z0-9._%+\-]+@[a-zA-Z][a-zA-Z0-9\-]*\.[a-zA-Z]{2,}\b"""),
        "DOWOD"         to Regex("""\b[A-Z]{3}\s?\d{6}\b"""),
        // Telefon: kropka/myślnik/spacja jako separator, prefiks +48/0048/48 opcjonalny.
        // Dwie gałęzie — obszarowe (kierunkowy 2-cyfrowy + local 7-cyfrowy) lub mobilna (9 cyfr bez kierunkowego).
        // Polski kierunkowy to zawsze 2 cyfry (nie 3) — zapobiega interpretacji NIP XXX jako kierunkowy.
        // Mandatory separator blokuje 9-cyfrowy compact (REGON).
        "TELEFON_PELNY" to Regex("""\b(?:(?:\+48|0048|48)[ \-.]?)?(?:(?:\(?\d{2}\)?[ \-.])\d{3}[ \-.](?:\d{3}[ \-.]?\d{3}|\d{2}[ \-.]?\d{2})|\d{3}[ \-.](?:\d{3}[ \-.]?\d{3}))\b"""),
    )
    for ((label, re) in redPatterns)
        re.findAll(text).forEach { hits += hit(label, "RED", it) }

    // ── YELLOW z kotwicą słowną (okno 35 znaków przed hitem) ────────────────
    fun before(pos: Int) = text.substring(maxOf(0, pos - 35), pos)

    // SYGNATURA: wymaga kontekstu nr/numer/sygn/akt/sprawa/repertorium/poz w pobliżu
    val CTX_SYGN = Regex("""(?i)\b(?:nr|numer|sygn(?:atura)?|akt[auy]?|spraw[ayi]|repertorium|poz)\b""")
    Regex("""\b\d{1,6}[/\-]\d{1,6}\b""").findAll(text).forEach { m ->
        if (CTX_SYGN.containsMatchIn(before(m.range.first)))
            hits += hit("SYGNATURA", "YELLOW", m)
    }

    // LICZBA: wymaga kontekstu nr/numer/poz/pwz/karta/id w pobliżu
    val CTX_LICZBA = Regex("""(?i)\b(?:nr|numer|poz|pwz|karta|id)\b""")
    Regex("""\b(?!(?:19|20)\d{2}\b)\d{7,10}\b""").findAll(text).forEach { m ->
        if (CTX_LICZBA.containsMatchIn(before(m.range.first)))
            hits += hit("LICZBA", "YELLOW", m)
    }

    // ── YELLOW bezwarunkowe (kontekst wbudowany w regex) ─────────────────────
    val yellowPatterns = listOf(
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
