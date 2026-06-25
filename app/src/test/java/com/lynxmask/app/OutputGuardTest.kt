package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Test

class OutputGuardTest {

    private fun guard(text: String): List<GuardHit> =
        runOutputGuard(text, emptyMap())

    @Test
    fun peselFlaggedAsYellow() {
        val hits = guard("PESEL: 90010112345")
        val hit = hits.find { it.label == "PESEL" }
        assertNotNull("PESEL powinien być wykryty", hit)
        assertEquals("YELLOW", hit!!.level)
    }

    @Test
    fun nipFlaggedAsRed() {
        val hits = guard("NIP podatnika: 123-456-78-90")
        val hit = hits.find { it.label == "NIP" }
        assertNotNull("NIP powinien być wykryty", hit)
        assertEquals("RED", hit!!.level)
    }

    @Test
    fun emailFlaggedAsRed() {
        val hits = guard("Kontakt: jan@firma.pl")
        val hit = hits.find { it.label == "EMAIL" }
        assertNotNull("EMAIL powinien być wykryty", hit)
        assertEquals("RED", hit!!.level)
    }

    @Test
    fun dowodFlaggedAsRed() {
        val hits = guard("Dowód osobisty ABC123456")
        val hit = hits.find { it.label == "DOWOD" }
        assertNotNull("Dowód ABC123456 powinien być wykryty", hit)
        assertEquals("RED", hit!!.level)
    }

    @Test
    fun dowodOcrSeriesDigitFlaggedAsRed() {
        val hits = guard("Nr dowodu osobistego 2TS935950")
        assertTrue(hits.any { it.label == "DOWOD" && it.level == "RED" })
    }

    @Test
    fun ibanGarbledPlFlaggedAsRed() {
        val hits = guard("Nr konta PL5001013sesoZ2901z0")
        assertTrue(hits.any { it.label == "IBAN" && it.level == "RED" })
    }

    @Test
    fun sygnatura2024FlaggedAsYellow() {
        val hits = guard("Sprawa 2024/1234")
        val hit = hits.find { it.label == "SYGNATURA" }
        assertNotNull("SYGNATURA powinien być wykryty", hit)
        assertEquals("YELLOW", hit!!.level)
    }

    @Test
    fun ownTokenNotFlagged() {
        // OSOBA_001 — aktualny format tokenu (TYPE_NNN), musi być wykluczony przez token exclusion regex
        val hits = guard("Dane osoby OSOBA_001 zostały ukryte")
        val red = hits.filter { it.level == "RED" }
        assertTrue(
            "Token OSOBA_001 nie powinien być flagowany jako RED, hits: $red",
            red.isEmpty()
        )
    }

    @Test
    fun plainTextNoHits() {
        val hits = guard("Umowa z dnia 10 marca")
        val red = hits.filter { it.level == "RED" }
        assertTrue(
            "Zwykły tekst nie powinien mieć hitów RED, hits: $red",
            red.isEmpty()
        )
    }
}
