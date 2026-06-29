package com.lynxmask.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRouteTest {

    @Test
    fun longOcrText_noCardMarkers_routesToTextPipeline() {
        assertEquals(
            ImageInputKind.PAGE,
            classifyImageInput(
                ImageRouteContext(
                    ocrCharCount = 120,
                    ocrText = "A".repeat(120),
                    faceCount = 0
                )
            )
        )
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

    @Test
    fun shortText_noCardMarkers_routesToTextPipeline() {
        // 40 znaków bez twarzy/karty → PAGE (brak strefy AMBIGUOUS)
        assertEquals(
            ImageInputKind.PAGE,
            classifyImageInput(
                ImageRouteContext(
                    ocrCharCount = 40,
                    ocrText = "Krótki tekst bez markerów dowodu",
                    faceCount = 0
                )
            )
        )
        assertTrue(
            shouldRouteImageToTextPipeline(ocrCharCount = 40, forceImageRedact = false)
        )
    }

    @Test
    fun minOcrChars_noCardMarkers_routesToTextPipeline() {
        // Dokładnie próg MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE → PAGE
        assertEquals(
            ImageInputKind.PAGE,
            classifyImageInput(
                ImageRouteContext(
                    ocrCharCount = MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE,
                    ocrText = "A".repeat(MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE),
                    faceCount = 0
                )
            )
        )
    }

    @Test
    fun vehicleRegMiddle_notTextPipeline() {
        val middleCrop = """
            C.1.1 KOWALSKI
            C.1.2 JAN
            D.1 FIAT
            D.3 PUNTO
            E WA12345
            VIN ZFA31200001234567
            Pojemnosc silnika 1242 cm3
        """.trimIndent()
        assertEquals(ImageInputKind.CARD, classifyImageInput(
            ImageRouteContext(ocrCharCount = middleCrop.length, ocrText = middleCrop, faceCount = 0)
        ))
        assertFalse(
            shouldRouteImageToTextPipeline(
                ocrCharCount = middleCrop.length,
                forceImageRedact = false,
                vehicleDocument = true
            )
        )
    }

    @Test
    fun detectsVehicleRegByVin() {
        assertTrue(looksLikeVehicleRegistration("ZFA31200001234567"))
    }

    @Test
    fun repeatedLetters_notVehicleReg() {
        assertFalse(looksLikeVehicleRegistration("A".repeat(80)))
    }

    @Test
    fun shiftCodes_notVehicleReg() {
        assertFalse(looksLikeVehicleRegistration("N1 D1 D2 N2 SZMULIK PIOTR grafik"))
    }

    @Test
    fun handwrittenScheduleNote_notVehicleReg() {
        val note = "DNIA 06.02 SZKOLENIE BHP U KIEROWNIKA FIRMY O GODZ. 9:00"
        assertFalse(looksLikeVehicleRegistration(note))
    }

    @Test
    fun ibanNumber_notVehicleReg() {
        // IBAN zaczyna się od 2 liter (PL) + cyfry — fałszywy VIN bez min. 3 liter
        assertFalse(looksLikeVehicleRegistration("PL61 1020 1026 0000 0422 7020 1111"))
    }

    @Test
    fun bankForm_withIban_notCardDocument() {
        val form = "Nr Rachunku: PL61 1020 1026 0000 0422 7020 1111\nPosiadacz rachunku: Jan Kowalski\nNIP: 123-456-78-90"
        assertFalse(looksLikeCardDocument(form))
    }

    @Test
    fun workSchedule_noCardMarkers_routesToTextPipeline() {
        val schedule = """
            PLAN PRACY HARMONOGRAM
            Nazwisko i imię    N1  D1  D2  N2
            SZMULIK PIOTR      6   14  22  6
            KOWALSKI JAN       14  22  6   14
        """.trimIndent()
        assertEquals(
            ImageInputKind.PAGE,
            classifyImageInput(
                ImageRouteContext(
                    ocrCharCount = schedule.length,
                    ocrText = schedule,
                    faceCount = 0
                )
            )
        )
    }
}
