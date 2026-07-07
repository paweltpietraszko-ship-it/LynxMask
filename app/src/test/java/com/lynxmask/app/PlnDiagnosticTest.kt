package com.lynxmask.app

import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Diagnostyczny test PLN — NIE modyfikuje kodu produkcyjnego.
 * "PLN 1234" zgłoszone jako ADRES, "PLN 12345" jako NUMER (05.07, test na telefonie,
 * linie samodzielne bez kontekstu). Dwa fixy dziś (AddressEngine.kt STREET_FULL +
 * StructuralEngine.kt ADDRESS_PATTERNS[0]) nie tłumaczą tego wg analizy statycznej —
 * żaden z nich nie powinien matchować gołego "PLN 1234" bez kodu pocztowego/miasta.
 * Ten test woła pseudonymize(traceMode = true) i wypisuje result.trace — dokładnie ta
 * metoda, która jednoznacznie znalazła pierwszą przyczynę PLN (StructuralEngine NUMER
 * regex, sesja 04.07 popołudnie).
 *
 * Uruchom: .\gradlew :app:testDebugUnitTest --tests "*.PlnDiagnosticTest" --info
 * Wynik: linie "TRACE:" w logu (widoczne z --info) pokażą layer/rule/matchedText/token
 * dla każdego przypisanego tokenu.
 */
class PlnDiagnosticTest {

    @Before fun setup()    { LookupTables.initializeForTesting() }
    @After  fun teardown() { LookupTables.resetForTesting() }

    @Test
    fun `TRACE DIAGNOSTIC PLN 1234 samodzielna linia`() {
        val r = PseudonymEngine.pseudonymize("PLN 1234", traceMode = true)
        println("=== INPUT: 'PLN 1234' ===")
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        println("tokenLayers: ${r.tokenLayers}")
        r.trace.forEach { println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}") }
    }

    @Test
    fun `TRACE DIAGNOSTIC PLN 12345 samodzielna linia`() {
        val r = PseudonymEngine.pseudonymize("PLN 12345", traceMode = true)
        println("=== INPUT: 'PLN 12345' ===")
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        println("tokenLayers: ${r.tokenLayers}")
        r.trace.forEach { println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}") }
    }

    // Zrzuty z telefonu (05.07, Paweł): "ul. KAMIENNA,PLN 1234" (przecinek, bez spacji) →
    // PLN_1234 dostaje WŁASNY token ADRES. "ul. KAMIENNA PLN 1234" (spacja, bez przecinka) →
    // całość zostaje jawna (guard/leak). "kamienna" musi być w słowniku ulic żeby odtworzyć
    // dokładnie to co dzieje się na prawdziwym urządzeniu (nie ma jej w domyślnym testowym).
    @Test
    fun `TRACE DIAGNOSTIC ul Kamienna przecinek PLN 1234`() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(streets = setOf("kamienna", "kamiennej"))
        val r = PseudonymEngine.pseudonymize("ul. Kamienna,PLN 1234", traceMode = true)
        println("=== INPUT: 'ul. Kamienna,PLN 1234' (przecinek) ===")
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        println("tokenLayers: ${r.tokenLayers}")
        r.trace.forEach { println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}") }
    }

    @Test
    fun `TRACE DIAGNOSTIC ul Kamienna spacja PLN 1234`() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(streets = setOf("kamienna", "kamiennej"))
        val r = PseudonymEngine.pseudonymize("ul. Kamienna PLN 1234", traceMode = true)
        println("=== INPUT: 'ul. Kamienna PLN 1234' (spacja) ===")
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        println("tokenLayers: ${r.tokenLayers}")
        r.trace.forEach { println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}") }
    }

    @Test
    fun `TRACE DIAGNOSTIC ul Jana bez numeru`() {
        val r = PseudonymEngine.pseudonymize("ul. Jana", traceMode = true)
        println("=== INPUT: 'ul. Jana' ===")
        println("OUTPUT: ${r.pseudonymizedText}")
        println("tokenMap: ${r.tokenMap}")
        println("tokenLayers: ${r.tokenLayers}")
        r.trace.forEach { println("TRACE: layer=${it.layer} rule=${it.rule} matchedText='${it.matchedText}' token=${it.token}") }
    }
}
