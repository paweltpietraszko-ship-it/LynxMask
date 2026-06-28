package com.lynxmask.app

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * S10 — Wyciek telefonu z separatorem kropkowym.
 *
 * Guard TELEFON_PELNY łapie formaty z kropką (600.123.456, 22.765.43.21),
 * silnik (StructuralEngine) ich nie maskował → PII wyciek w tokenMap.
 *
 * Uruchom po fixie: gradlew :app:testDebugUnitTest --tests "*.PhoneDotSeparatorTest"
 */
class PhoneDotSeparatorTest {

    @Before fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting()
    }

    private fun masked(input: String): Boolean {
        val result = PseudonymEngine.pseudonymize(input, emptyList())
        return result.tokenMap.keys.any { it.startsWith("NUMER_") }
    }

    // ── Formaty z separatorem kropkowym (były wycieki) ───────────────────────

    @Test fun `telefon mobilny z kropkami maskowany`() {
        assertTrue("600.123.456 nie zamaskowany", masked("600.123.456"))
    }

    @Test fun `telefon stacjonarny z kropkami maskowany`() {
        assertTrue("22.765.43.21 nie zamaskowany", masked("22.765.43.21"))
    }

    @Test fun `telefon z prefiksem 48 i kropkami maskowany`() {
        assertTrue("48.600.123.456 nie zamaskowany", masked("48.600.123.456"))
    }

    @Test fun `telefon z prefiksem plus48 i kropkami maskowany`() {
        assertTrue("+48.600.123.456 nie zamaskowany", masked("+48.600.123.456"))
    }

    // ── Formaty już działające (regres) ──────────────────────────────────────

    @Test fun `telefon mobilny ze spacją maskowany`() {
        assertTrue("600 123 456 nie zamaskowany", masked("600 123 456"))
    }

    @Test fun `telefon mobilny z myślnikiem maskowany`() {
        assertTrue("600-123-456 nie zamaskowany", masked("600-123-456"))
    }

    @Test fun `telefon stacjonarny ze spacją maskowany`() {
        assertTrue("22 765 43 21 nie zamaskowany", masked("22 765 43 21"))
    }

    @Test fun `telefon z nawiasami maskowany`() {
        assertTrue("(22) 765-43-21 nie zamaskowany", masked("(22) 765-43-21"))
    }

    @Test fun `telefon z prefiksem plus48 maskowany`() {
        assertTrue("+48 600 123 456 nie zamaskowany", masked("+48 600 123 456"))
    }
}
