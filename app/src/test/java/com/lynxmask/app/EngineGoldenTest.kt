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
            val normExpected = expectedValue.replace(" ", "").replace("-", "").lowercase()
            val masked = result.tokenMap.entries.any { (token, original) ->
                token.startsWith(tokenTypePrefix) &&
                    original.replace(" ", "").replace("-", "").lowercase().let { normOrig ->
                        normOrig == normExpected ||
                        normOrig.contains(normExpected) ||
                        normExpected.contains(normOrig)
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
                println("     wynik:      ${result.pseudonymizedText}")
            }
        }

        println("\n─── Wynik: $passed/$total PASS, $failed FAIL ───")
        if (failed > 0) error("$failed/$total encji nie zamaskowanych — szczegóły wyżej")
    }
}
