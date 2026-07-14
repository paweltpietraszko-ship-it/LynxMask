package com.lynxmask.app

import org.junit.Before
import org.junit.Test

/**
 * Test sklejania encji OSOBA i słów mylonych z imieniem/nazwiskiem — zero OCR.
 *
 * Uruchomienie: gradlew :app:testDebugUnitTest --tests "*.NameEngineStickingTest"
 *
 * Plik wejściowy: src/test/resources/engine_golden_imiona_nazwiska.txt
 * Format tagów:  [OSOBA:wartość] — wiele tagów na linii muszą trafić do RÓŻNYCH
 *                tokenów (test sklejania dwóch sąsiadujących encji w jedną).
 *                [NONE:wartość] — to słowo nie może zostać zamaskowane wcale.
 */
class NameEngineStickingTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        if (!LookupTables.initialized) error("LookupTables nie załadowane — sprawdź src/test/resources/*.json")
    }

    private fun surnameStem(value: String): String {
        val last = value.substringAfterLast(" ").lowercase()
        val cut = minOf(2, last.length - 3).coerceAtLeast(0)
        return last.dropLast(cut)
    }

    @Test
    fun `silnik nie skleja sasiednich osob i nie maskuje slow pospolitych`() {
        val lines = NameEngineStickingTest::class.java.classLoader
            ?.getResourceAsStream("engine_golden_imiona_nazwiska.txt")
            ?.bufferedReader()?.readLines()
            ?: error("Nie znaleziono engine_golden_imiona_nazwiska.txt w classpath")

        val tagRegex = Regex("""\[(\w+):([^\]]+)\]""")
        var total = 0; var passed = 0; var failed = 0

        for (line in lines) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("===")) continue
            val tags = tagRegex.findAll(line).toList()
            if (tags.isEmpty()) continue
            val input = line.substringBefore(" [").trim()

            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val osobaTokens = result.tokenMap.entries.filter { it.key.startsWith("OSOBA_") }

            total++
            var lineOk = true
            val usedTokenKeys = mutableSetOf<String>()
            val details = StringBuilder()

            for (tag in tags) {
                val type = tag.groupValues[1]
                val value = tag.groupValues[2]
                if (type == "NONE") {
                    val leaked = osobaTokens.any { (_, original) ->
                        original.split(Regex("[\\s-]+")).any { it.equals(value, ignoreCase = true) }
                    }
                    if (leaked) {
                        lineOk = false
                        details.append("  [NONE:$value] ZAMASKOWANE mimo że nie powinno\n")
                    }
                } else {
                    val stem = surnameStem(value)
                    val match = osobaTokens.firstOrNull { (key, original) ->
                        key !in usedTokenKeys && original.lowercase().contains(stem)
                    }
                    if (match == null) {
                        lineOk = false
                        details.append("  [OSOBA:$value] BRAK dopasowania w wolnym (nieużytym) tokenie — sklejone z inną encją?\n")
                    } else {
                        usedTokenKeys += match.key
                    }
                }
            }

            if (lineOk) {
                passed++
                println("PASS: $input")
            } else {
                failed++
                println("FAIL: $input")
                print(details)
                println("  tokeny OSOBA: ${osobaTokens.joinToString { "${it.key}='${it.value}'" }.ifEmpty { "(brak)" }}")
            }
        }

        println("\n─── Wynik: $passed/$total PASS, $failed FAIL ───")
        if (failed > 0) error("$failed/$total linii nie przeszło (sklejanie lub słowo pospolite zamaskowane) — szczegóły wyżej")
    }
}
