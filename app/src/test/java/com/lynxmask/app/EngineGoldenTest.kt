package com.lynxmask.app

import org.junit.Before
import org.junit.Test

/**
 * Test silnika na złotym pliku tekstowym — zero OCR, pełny słownik z classpath.
 *
 * Uruchomienie: gradlew :app:testDebugUnitTest --tests "*.EngineGoldenTest"
 *
 * Plik wejściowy: src/test/resources/engine_golden_text.txt
 * Format tagów:  [TYP:wartość_GT] — oczekiwana encja w tej linii
 * Typy:          OSOBA, NUMER, ADRES, EMAIL
 */
class EngineGoldenTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        val ok = LookupTables.initialized
        val names = LookupTables.namesForms.size
        val surnames = LookupTables.surnamesForms.size
        println("LookupTables: initialized=$ok  imiona=$names  nazwiska=$surnames")
        if (!ok) error("LookupTables nie załadowane — sprawdź src/test/resources/*.json")
    }

    @Test
    fun `diagnoza przypadkow problematycznych`() {
        // Szukamy w której liście jest "magdaleny"
        val m = "magdaleny"
        val allLists = mapOf(
            "namesForms" to LookupTables.namesForms.contains(m),
            "surnamesForms" to LookupTables.surnamesForms.contains(m),
            "streetForms" to LookupTables.streetForms.contains(m)
        )
        allLists.forEach { (name, v) -> if (v) println("FOUND in $name") }
        // Sprawdź w NameEngine przez dokładną kopię logiki
        println("isOnWhiteList Magdaleny: ${isOnWhiteList("Magdaleny")}")
        println("isOnWhiteList Anny: ${isOnWhiteList("Anny")}")
        val cases = listOf(
            "Anny Jankowskiej.",
            "Magdaleny Jankowskiej.",
            "Magdaleny Kowalskiej."
        )
        for (input in cases) {
            val r = PseudonymEngine.pseudonymize(input, emptyList())
            println("IN:  $input")
            println("OUT: ${r.pseudonymizedText.replace("\n", " ").take(120)}")
            println("MAP: ${r.tokenMap}")
            println()
        }
    }

    @Test
    fun `silnik maskuje wszystkie encje ze złotego pliku`() {
        val lines = EngineGoldenTest::class.java.classLoader
            ?.getResourceAsStream("engine_golden_text.txt")
            ?.bufferedReader()?.readLines()
            ?: error("Nie znaleziono engine_golden_text.txt w classpath")

        val tagRegex = Regex("""\[(\w+):([^\]]+)\]""")
        var total = 0; var passed = 0; var failed = 0

        for (line in lines) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("===")) continue
            val tag = tagRegex.find(line) ?: continue
            val expectedType = tag.groupValues[1]
            val expectedValue = tag.groupValues[2]

            // Usuń tag z tekstu przed podaniem do silnika
            val input = line.substringBefore(" [").trim()

            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val tokenTypePrefix = when (expectedType) {
                "OSOBA" -> "OSOBA_"
                "NUMER" -> "NUMER_"
                "ADRES" -> "ADRES_"
                "EMAIL" -> "EMAIL_"
                else    -> "${expectedType}_"
            }

            // tokenMap: token → oryginał (np. "OSOBA_001" → "Jana Kowalskiego")
            // Dla OSOBA: sprawdzamy rdzeń nazwiska (drop 2 ostatnie znaki) bo silnik maskuje odmianę
            val masked = result.tokenMap.entries.any { (token, original) ->
                if (!token.startsWith(tokenTypePrefix)) return@any false
                val normOrig = original.replace(" ", "").replace("-", "").lowercase()
                val normExp  = expectedValue.replace(" ", "").replace("-", "").lowercase()
                if (expectedType == "OSOBA") {
                    val surnameStem = expectedValue.substringAfterLast(" ")
                        .lowercase().let { it.dropLast(minOf(2, it.length - 3)) }
                    original.lowercase().contains(surnameStem)
                } else {
                    normOrig == normExp || normOrig.contains(normExp) || normExp.contains(normOrig)
                }
            }

            total++
            if (masked) {
                passed++
                println("PASS [$expectedType] $input")
            } else {
                failed++
                println("FAIL [$expectedType] $input")
                println("     oczekiwano: $expectedType zawierający '$expectedValue'")
                val osobaTokens = result.tokenMap.entries
                    .filter { it.key.startsWith(tokenTypePrefix) }
                    .joinToString { "${it.key}='${it.value}'" }
                println("     tokeny $expectedType: ${osobaTokens.ifEmpty { "(brak)" }}")
            }
        }

        println("\n─── Wynik: $passed/$total PASS, $failed FAIL ───")
        if (failed > 0) error("$failed/$total encji nie zamaskowanych — szczegóły wyżej")
    }
}
