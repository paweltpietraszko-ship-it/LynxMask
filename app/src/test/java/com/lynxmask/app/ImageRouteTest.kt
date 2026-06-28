package com.lynxmask.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRouteTest {

    @Test
    fun scanWithText_usesTextPipeline() {
        assertTrue(
            shouldRouteImageToTextPipeline(ocrCharCount = 120, forceImageRedact = false)
        )
    }

    @Test
    fun emptyPhoto_usesImageRedact() {
        assertFalse(
            shouldRouteImageToTextPipeline(ocrCharCount = 0, forceImageRedact = false)
        )
    }

    @Test
    fun forceRedact_skipsTextPipelineEvenWithOcr() {
        assertFalse(
            shouldRouteImageToTextPipeline(ocrCharCount = 500, forceImageRedact = true)
        )
    }

    @Test
    fun idCardWithFace_usesImageRedact() {
        assertFalse(
            shouldRouteImageToTextPipeline(
                ocrCharCount = 200,
                forceImageRedact = false,
                faceCount = 1,
                identityDocument = true
            )
        )
    }

    @Test
    fun legitymacjaKeywords_withoutFace_usesImageRedact() {
        assertFalse(
            shouldRouteImageToTextPipeline(
                ocrCharCount = 80,
                forceImageRedact = false,
                faceCount = 0,
                identityDocument = true
            )
        )
    }

    @Test
    fun detectsPolishIdCard() {
        assertTrue(
            looksLikeIdentityDocument(
                "RZECZPOSPOLITA POLSKA\nDOWÓD OSOBISTY\nSERIA I NUMER ABC123456"
            )
        )
    }

    @Test
    fun detectsLegitymacja() {
        assertTrue(looksLikeIdentityDocument("LEGITYMACJA SZKOLNA\nImię i nazwisko: Jan Kowalski"))
    }

    @Test
    fun contractScan_notIdentityDoc() {
        assertFalse(
            looksLikeIdentityDocument("UMOWA NAJMU LOKALU\nStrony umowy postanawiają...")
        )
    }

    @Test
    fun invoiceWithPeselField_notIdentityDoc() {
        // Faktura z polem PESEL nie jest dokumentem tożsamości — ma iść przez OCR, nie ImageRedact
        assertFalse(
            looksLikeIdentityDocument(
                "FAKTURA VAT\nNIP sprzedawcy: 123-456-78-90\nPESEL nabywcy: 83100812345\nNr konta: PL61 1020 1026 0000 0422 7020 1111"
            )
        )
    }

    @Test
    fun contractWithPesel_notIdentityDoc() {
        assertFalse(
            looksLikeIdentityDocument(
                "UMOWA ZLECENIA\nZleceniobiorca PESEL: 90010112345\nData urodzenia: 1990-01-01"
            )
        )
    }
}
