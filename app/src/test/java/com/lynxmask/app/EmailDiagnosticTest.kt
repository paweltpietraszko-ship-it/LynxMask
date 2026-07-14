package com.lynxmask.app

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Diagnostyka + regresja BUG-EMAIL-DOUBLE-SPACE (09.07): linia 9 z
 * testy/test_email_izolowany_09_07.txt — podwójna degradacja OCR (spacja przed @ i przed .pl)
 * w kontekście WIELOLINIOWYM (poprzednie linie tworzą tokeny, których ogon ("001") potrafił
 * zostać błędnie wciągnięty jako opcjonalny prefiks A.2 — BUG-EMAIL-TOKEN-PREFIX-FIX v2,
 * AnchorEngine.kt). Test diagnostyczny (druk trace/Guard) rozszerzony o realną asercję —
 * poprzednia wersja tylko drukowała, więc przechodziła zielono mimo realnego wycieku.
 *
 * Uruchom: .\gradlew :app:testDebugUnitTest --tests "*.EmailDiagnosticTest" --info
 * Szukaj linii TRACE: i OCR: w logu.
 */
class EmailDiagnosticTest {

    @Before fun setup()    { LookupTables.initializeForTesting() }
    @After  fun teardown() { LookupTables.resetForTesting() }

    private val line9 = "p1otr.wisn1ewski @ kance1aria .pl"
    private val line7 = "piotr.wisniewski @kancelaria.pl"

    @Test
    fun `TRACE OCR normalizer linia 9`() {
        val r = OcrNormalizer.normalize(line9)
        println("=== OCR INPUT line9 ===")
        println(line9)
        println("=== OCR OUTPUT ===")
        println(r.normalizedText)
        println("corrections=${r.corrections}")
    }

    @Test
    fun `TRACE DIAGNOSTIC linia 9 izolowana`() {
        val r = PseudonymEngine.pseudonymize(line9, traceMode = true)
        println("=== INPUT line9 ===")
        println(line9)
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        println("tokenLayers: ${r.tokenLayers}")
        r.trace.forEach {
            println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}")
        }
    }

    @Test
    fun `TRACE DIAGNOSTIC linia 7 kontrola`() {
        val r = PseudonymEngine.pseudonymize(line7, traceMode = true)
        println("=== INPUT line7 ===")
        println(line7)
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        r.trace.filter { it.rule == TOKEN_EMAIL || it.layer == "ANCHOR" }
            .forEach {
                println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}")
            }
    }

    @Test
    fun `TRACE DIAGNOSTIC pelny plik 9 linii`() {
        val input = """
            piotr.wisniewski@kancelaria.pl
            m.kowalczyk@nfz.gov.pl
            biuro@wrozlaw-adwokaci.pl
            p1otr.w1sn1ewski@kance1aria.pl
            m.kowa1czyk@nfz.g0v.p1
            biuro@wroc1aw-adwokac1.p1
            piotr.wisniewski @kancelaria.pl
            m.kowalczyk@ nfz.gov.pl
            p1otr.wisn1ewski @ kance1aria .pl
        """.trimIndent()
        val r = PseudonymEngine.pseudonymize(input, traceMode = true)
        println("=== INPUT file (${input.lines().size} lines) ===")
        input.lines().forEachIndexed { i, l -> println("${i + 1}: $l") }
        println("OUTPUT:\n${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        r.trace.filter { it.rule == TOKEN_EMAIL }
            .forEach {
                println("TRACE EMAIL: layer=${it.layer} matchedText='${it.matchedText}' token=${it.token}")
            }
        println("guardHits: ${r.guardHits}")
        val emailFragmentHits = r.guardHits.filter { it.label == "EMAIL_FRAGMENT" }
        emailFragmentHits.forEach {
            println("GUARD EMAIL_FRAGMENT: matchedText='${it.matchedText}' pos=${it.startIndex}-${it.endIndex}")
        }
        // linia 9 — czy local-part wypada z outputu?
        val line9out = r.pseudonymizedText.lines().last()
        println("LINE9 OUTPUT ONLY: $line9out")
        val hasLeak = listOf("plotr", "wisn", "piotr", "kancelaria", "kance1aria").any {
            line9out.contains(it, ignoreCase = true)
        }
        println("LINE9 LEAK detected=$hasLeak")

        assertFalse("Linia 9 nie powinna zostawiać local-part/domeny jawnej w kontekście " +
            "wieloliniowym: $line9out", hasLeak)
        assertTrue("Guard nie powinien znaleźć EMAIL_FRAGMENT gdy linia 9 zamaskowana poprawnie: " +
            emailFragmentHits, emailFragmentHits.isEmpty())
        assertTrue("Linia 9 powinna dostać token EMAIL przez którąś warstwę (STRUCTURAL/ANCHOR)",
            r.trace.any { it.rule == TOKEN_EMAIL && it.matchedText.contains("kancelaria", ignoreCase = true) })
    }
}
