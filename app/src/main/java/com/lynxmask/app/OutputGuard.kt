package com.lynxmask.app

// OutputGuard.kt — Warstwa 6: Output Guard
// Wersja: 1.7
//
// Zwraca List<GuardHit> z poziomami RED (pewne PII) i YELLOW (podejrzane).
// Tokeny własne (format TYP_XXX_NNN) zastępowane przez ⟦TOKEN⟧ przed skanowaniem.
//
// v1.7: Przepisana runOutputGuard() — zwraca List<GuardHit> zamiast List<String>.
//       Usunięte nieużywane listy GUARD_HIGH/MEDIUM/LOW.
//       NAMES_GUARD_CITY_SKIP zachowany (używany przez NameEngine guard w przyszłości).

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

    // Zamień własne tokeny na placeholder żeby ich nie flagować
    val tokenRegex = Regex("""\b[A-Z]{2,}_[A-Z]{3}_\d{3}\b""")
    val textWithoutTokens = tokenRegex.replace(pseudonymizedText, "⟦TOKEN⟧")

    // HIGH — każde dopasowanie to wyciek
    val highPatterns = listOf(
        "PESEL" to Regex("""\b\d{11}\b"""),
        "NIP"   to Regex("""\b\d{3}[-\s]?\d{3}[-\s]?\d{2}[-\s]?\d{2}\b"""),
        "IBAN"  to Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}\b"""),
        "EMAIL" to Regex("""\b[a-zA-Z0-9._%+\-]+@[a-zA-Z][a-zA-Z0-9\-]*\.[a-zA-Z]{2,}\b"""),
        "DOWOD" to Regex("""\b[A-Z]{3}\s?\d{6}\b""")
    )

    // YELLOW — podejrzane ale mogą być fałszywym alarmem
    val yellowPatterns = listOf(
        "SYGNATURA" to Regex("""\b\d{1,6}[/\-]\d{1,6}\b"""),
        "TELEFON"   to Regex("""\b(?:\+?48[-\s]?)?\d{3}[-\s]\d{3}[-\s]\d{3}\b"""),
        "LICZBA"    to Regex("""\b(?!(?:19|20)\d{2}\b)\d{7,10}\b"""),
        "REGON"     to Regex("""\b\d{9}\b"""),
        "PL_PREFIX" to Regex("""\bPL\d{4,}\b""")
    )

    for ((label, regex) in highPatterns) {
        regex.findAll(textWithoutTokens).forEach { match ->
            hits.add(GuardHit(
                label = label,
                level = "RED",
                matchedText = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1
            ))
        }
    }

    for ((label, regex) in yellowPatterns) {
        regex.findAll(textWithoutTokens).forEach { match ->
            hits.add(GuardHit(
                label = label,
                level = "YELLOW",
                matchedText = match.value,
                startIndex = match.range.first,
                endIndex = match.range.last + 1
            ))
        }
    }

    return hits
}
