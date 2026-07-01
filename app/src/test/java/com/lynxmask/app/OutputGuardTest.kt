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

    // BUG-GUARD-DCLASS (wniosek właściciela 01.07): Guard tylko ostrzega, nie zamienia
    // tekstu, więc powinien być tolerancyjny na OCR (D-klasa) tak jak silnik — inaczej
    // ciąg zdegradowany przez OCR (litera zamiast cyfry) jest całkowicie niewidoczny
    // dla Guarda, mimo że w realnym dokumencie prawie zawsze to zniekształcone PII.
    @Test
    fun `pesel zdegradowany przez OCR literami flagowany jako YELLOW`() {
        val hits = guard("PESEL: 9OO4O512345")
        assertTrue(
            "PESEL z literami O (OCR) powinien byc wykryty",
            hits.any { it.label == "PESEL" && it.level == "YELLOW" }
        )
    }

    @Test
    fun `pesel ze spacjami i literami OCR flagowany jako PESEL_SPACE`() {
        val hits = guard("dokument zawiera 7S121867890 jako identyfikator")
        assertTrue(
            "PESEL-shape z literą S (OCR) powinien byc wykryty",
            hits.any { (it.label == "PESEL" || it.label == "PESEL_SPACE") && it.level == "YELLOW" }
        )
    }

    @Test
    fun `nip zdegradowany przez OCR literami flagowany jako RED`() {
        val hits = guard("NIP: S26-021-15-81")
        assertTrue(
            "NIP z literą S (OCR) powinien byc wykryty",
            hits.any { it.label == "NIP" && it.level == "RED" }
        )
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

    // ── OSOBA_NIEZAMASKOWANE (sesja 28.06) ──────────────────────────────────

    @Test
    fun `garbled imie nazwisko po etykiecie flagowane jako YELLOW`() {
        val hits = guard("Imię i nazwisko: Monka Nowakosa")
        assertTrue(
            "Monka Nowakosa powinna byc flagowana YELLOW OSOBA_NIEZAMASKOWANE",
            hits.any { it.label == "OSOBA_NIEZAMASKOWANE" && it.level == "YELLOW" }
        )
    }

    @Test
    fun `garbled nazwisko po ZLECENIOBIORCA flagowane`() {
        val hits = guard("ZLECENIOBIORCA:\nImię i nazwisko: Monka Nowakosa")
        assertTrue(
            "Monka Nowakosa po ZLECENIOBIORCA powinna byc flagowana",
            hits.any { it.label == "OSOBA_NIEZAMASKOWANE" && it.level == "YELLOW" }
        )
    }

    @Test
    fun `imie nazwisko bez etykiety nie flagowane`() {
        // Brak kontekstu osobowego — "Umowa Zlecenia" nie powinno triggerować
        val hits = guard("Umowa Zlecenia nr 017 zawarta dnia")
        assertFalse(
            "Umowa Zlecenia nie jest imieniem — nie powinna byc flagowana",
            hits.any { it.label == "OSOBA_NIEZAMASKOWANE" }
        )
    }
}
