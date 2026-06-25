package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Test

class ClipSafetyTest {

    @Test
    fun `clipSafeMaskedText zastepuje RED guard hit placeholderem`() {
        val result = PseudonymResult(
            pseudonymizedText = "Kontakt: 512 345 678",
            sessionId = "ABC123",
            tokenMap = emptyMap(),
            flags = emptyList(),
            riskScore = RiskScore.YELLOW,
            qualityWarning = null,
            guardHits = listOf(
                GuardHit("TELEFON_PELNY", "RED", "512 345 678", 9, 20)
            )
        )
        val safe = clipSafeMaskedText(result)
        assertFalse(safe.contains("512 345 678"))
        assertTrue(safe.contains(CLIP_REDACTED_PLACEHOLDER))
    }

    @Test
    fun `isClipboardClean wymaga braku RED guard`() {
        val withRed = PseudonymResult(
            pseudonymizedText = "x",
            sessionId = "A",
            tokenMap = emptyMap(),
            flags = emptyList(),
            riskScore = RiskScore.GREEN,
            qualityWarning = null,
            guardHits = listOf(GuardHit("NIP", "RED", "521-334-15-33", 0, 13))
        )
        assertFalse(isClipboardClean(withRed))

        val clean = withRed.copy(guardHits = emptyList())
        assertTrue(isClipboardClean(clean))
    }
}
