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
}
