package com.lynxmask.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrNormalizerPaszportTest {

    @Test
    fun `paszport I jako 1 naprawiany`() {
        val r = OcrNormalizer.normalize("paszport: AB I234567")
        assertTrue(r.normalizedText.contains("AB 1234567"))
        assertEquals(1, r.corrections)
    }

    @Test
    fun `paszport O jako 0 naprawiany`() {
        val r = OcrNormalizer.normalize("Nr paszportu: EF O1234S6")
        assertTrue(r.normalizedText.contains("EF 0123456"))
        assertTrue(r.corrections > 0)
    }

    @Test
    fun `paszport bez artefaktow bez zmian`() {
        val r = OcrNormalizer.normalize("paszport: EF 0123456")
        assertEquals("paszport: EF 0123456", r.normalizedText)
        assertEquals(0, r.corrections)
    }

    @Test
    fun `seria paszportu nienaruszona`() {
        val r = OcrNormalizer.normalize("paszport AB S234567")
        assertTrue(r.normalizedText.contains("AB"))
        assertFalse("seria nie powinna być zmieniona", r.normalizedText.contains("A8"))
        assertTrue(r.normalizedText.contains("5234567"))
    }

    @Test
    fun `paszportu odmiana obsluzona`() {
        val r = OcrNormalizer.normalize("Nr paszportu: CD I234567")
        assertTrue(r.normalizedText.contains("CD 1234567"))
    }

    @Test
    fun `paszportem odmiana obsluzona`() {
        val r = OcrNormalizer.normalize("legitymuje sie paszportem AB I234567")
        assertTrue(r.normalizedText.contains("AB 1234567"))
    }
}
