package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryKeyManagerTest {

    @Test
    fun `key has 4 groups of 6 separated by dashes`() {
        repeat(20) {
            val key = RecoveryKeyManager.generateKey()
            val groups = key.split("-")
            assertEquals(4, groups.size)
            groups.forEach { assertEquals(6, it.length) }
        }
    }

    @Test
    fun `total key length is 27 chars`() {
        repeat(20) {
            assertEquals(27, RecoveryKeyManager.generateKey().length)
        }
    }

    @Test
    fun `key uses only allowed alphabet`() {
        val allowed = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toSet()
        repeat(50) {
            val key = RecoveryKeyManager.generateKey()
            key.replace("-", "").forEach { char ->
                assertTrue("Niedozwolony znak: $char", char in allowed)
            }
        }
    }

    @Test
    fun `no visually ambiguous characters`() {
        repeat(50) {
            val key = RecoveryKeyManager.generateKey()
            assertFalse("Znaleziono niejednoznaczny znak", key.any { it in "0O1IL" })
        }
    }

    @Test
    fun `keys are unique`() {
        val keys = (1..200).map { RecoveryKeyManager.generateKey() }.toSet()
        assertEquals(200, keys.size)
    }

    @Test
    fun `normalize strips dashes and uppercases`() {
        assertEquals("ABCDEFGHIJKL", RecoveryKeyManager.normalize("abcdef-ghijkl"))
        assertEquals("ABCDEF", RecoveryKeyManager.normalize("  abcdef  "))
        assertEquals("ABCDEF", RecoveryKeyManager.normalize("ABCDEF"))
    }
}
