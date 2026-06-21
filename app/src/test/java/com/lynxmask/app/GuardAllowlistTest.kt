package com.lynxmask.app

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Testy logiki GuardAllowlist — in-memory, bez Contextu (EncryptedFile wymaga androidTest).
 * Sprawdzają: deduplikację, trim, contains, remove, rozróżnienie par (value, ruleType).
 *
 * addDirect/removeDirect pomijają Context i zapis na dysk — testują tylko logikę in-memory.
 */
class GuardAllowlistTest {

    @Before fun setUp() = GuardAllowlist.resetForTesting()

    private fun addUnsafe(value: String, ruleType: String) = GuardAllowlist.addDirect(value, ruleType)

    private fun removeUnsafe(value: String, ruleType: String) = GuardAllowlist.removeDirect(value, ruleType)

    // --- contains ---

    @Test fun `contains zwraca false dla pustej listy`() {
        assertFalse(GuardAllowlist.contains("1/2023", "SYGNATURA"))
    }

    @Test fun `contains zwraca true po dodaniu`() {
        addUnsafe("1/2023", "SYGNATURA")
        assertTrue(GuardAllowlist.contains("1/2023", "SYGNATURA"))
    }

    @Test fun `contains ignoruje wielkosc liter`() {
        addUnsafe("Lipowa", "SYGNATURA")
        assertTrue(GuardAllowlist.contains("lipowa", "SYGNATURA"))
        assertTrue(GuardAllowlist.contains("LIPOWA", "SYGNATURA"))
    }

    @Test fun `contains rozroznia ruleType`() {
        addUnsafe("1/2023", "SYGNATURA")
        assertFalse(GuardAllowlist.contains("1/2023", "LICZBA"))
    }

    @Test fun `contains przycinaje biale znaki`() {
        addUnsafe("  1/2023  ", "SYGNATURA")
        assertTrue(GuardAllowlist.contains("1/2023", "SYGNATURA"))
    }

    // --- deduplikacja ---

    @Test fun `add nie duplikuje tego samego wpisu`() {
        addUnsafe("1/2023", "SYGNATURA")
        addUnsafe("1/2023", "SYGNATURA")
        assertEquals(1, GuardAllowlist.entries.size)
    }

    @Test fun `ta sama wartosc rozny ruleType to dwa wpisy`() {
        addUnsafe("1/2023", "SYGNATURA")
        addUnsafe("1/2023", "LICZBA")
        assertEquals(2, GuardAllowlist.entries.size)
    }

    // --- remove ---

    @Test fun `remove usuwa wpis`() {
        addUnsafe("1/2023", "SYGNATURA")
        removeUnsafe("1/2023", "SYGNATURA")
        assertFalse(GuardAllowlist.contains("1/2023", "SYGNATURA"))
    }

    @Test fun `remove nie usuwa wpisu z innym ruleType`() {
        addUnsafe("1/2023", "SYGNATURA")
        addUnsafe("1/2023", "LICZBA")
        removeUnsafe("1/2023", "SYGNATURA")
        assertFalse(GuardAllowlist.contains("1/2023", "SYGNATURA"))
        assertTrue(GuardAllowlist.contains("1/2023", "LICZBA"))
    }

    @Test fun `remove na nieistniejacym wpisie nie rzuca wyjatku`() {
        removeUnsafe("nieistniejacy", "SYGNATURA")
        assertEquals(0, GuardAllowlist.entries.size)
    }

    // --- getAll ---

    @Test fun `entries zwraca kopie nie referencje`() {
        addUnsafe("A", "SYGNATURA")
        val snapshot = GuardAllowlist.entries
        addUnsafe("B", "SYGNATURA")
        assertEquals(1, snapshot.size)
        assertEquals(2, GuardAllowlist.entries.size)
    }
}
