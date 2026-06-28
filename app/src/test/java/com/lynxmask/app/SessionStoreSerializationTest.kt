package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM testy serializacji tokenMap — bez Contextu i bez bazy danych.
 *
 * tokenMapJson() = extension na PseudonymResult (ręczny JSON stringify)
 * parseTokenMapJson() = internal w SessionStore (JSON parse)
 * Razem tworzą roundtrip: Map → JSON → Map
 */
class SessionStoreSerializationTest {

    private fun makeResult(tokenMap: Map<String, String>) = PseudonymResult(
        pseudonymizedText = "",
        sessionId = "TEST01",
        tokenMap = tokenMap,
        flags = emptyList(),
        riskScore = RiskScore.GREEN,
        qualityWarning = null
    )

    // ── tokenMapJson() ────────────────────────────────────────────────────────

    @Test
    fun tokenMapJson_singleEntry_validJson() {
        val json = makeResult(mapOf("OSOBA_001" to "Jan Kowalski")).tokenMapJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"OSOBA_001\""))
        assertTrue(json.contains("\"Jan Kowalski\""))
    }

    @Test
    fun tokenMapJson_emptyMap_producesEmptyObject() {
        val json = makeResult(emptyMap()).tokenMapJson()
        assertEquals("{}", json)
    }

    @Test
    fun tokenMapJson_valueWithQuotes_escapedCorrectly() {
        val json = makeResult(mapOf("FIRMA_001" to "Sp. \"Alfa\"")).tokenMapJson()
        assertTrue(json.contains("\\\"Alfa\\\""))
    }

    @Test
    fun tokenMapJson_valueWithBackslash_escapedCorrectly() {
        val json = makeResult(mapOf("ADRES_001" to "C:\\Users\\test")).tokenMapJson()
        assertTrue(json.contains("C:\\\\Users\\\\test"))
    }

    @Test
    fun tokenMapJson_valueWithNewline_escapedCorrectly() {
        val json = makeResult(mapOf("ADRES_001" to "linia1\nlinia2")).tokenMapJson()
        assertTrue(json.contains("\\n"))
        assertFalse(json.contains("\n"))
    }

    // ── parseTokenMapJson() ───────────────────────────────────────────────────

    @Test
    fun parseTokenMapJson_validJson_returnsMap() {
        val json = """{"OSOBA_001":"Anna Nowak","NUMER_001":"600 100 200"}"""
        val map = SessionStore.parseTokenMapJson(json)
        assertEquals("Anna Nowak", map["OSOBA_001"])
        assertEquals("600 100 200", map["NUMER_001"])
    }

    @Test
    fun parseTokenMapJson_invalidJson_returnsEmptyMap() {
        val map = SessionStore.parseTokenMapJson("nie-json{{{")
        assertTrue(map.isEmpty())
    }

    @Test
    fun parseTokenMapJson_emptyObject_returnsEmptyMap() {
        val map = SessionStore.parseTokenMapJson("{}")
        assertTrue(map.isEmpty())
    }

    // ── roundtrip ─────────────────────────────────────────────────────────────

    @Test
    fun roundtrip_tokenMapJsonThenParse_identicalMap() {
        val original = mapOf(
            "OSOBA_001" to "Jan Kowalski",
            "NUMER_001" to "600-123-456",
            "EMAIL_001" to "jan@example.com",
            "ADRES_001" to "ul. Kwiatowa 5/12"
        )
        val json = makeResult(original).tokenMapJson()
        val parsed = SessionStore.parseTokenMapJson(json)
        assertEquals(original, parsed)
    }

    @Test
    fun roundtrip_specialCharsPreserved() {
        val original = mapOf(
            "FIRMA_001" to "Sp. z o.o. \"Kwiat & Lipa\"",
            "ADRES_001" to "ul. Lipowa\tlinia2"
        )
        val json = makeResult(original).tokenMapJson()
        val parsed = SessionStore.parseTokenMapJson(json)
        assertEquals(original["FIRMA_001"], parsed["FIRMA_001"])
        assertEquals(original["ADRES_001"], parsed["ADRES_001"])
    }
}
