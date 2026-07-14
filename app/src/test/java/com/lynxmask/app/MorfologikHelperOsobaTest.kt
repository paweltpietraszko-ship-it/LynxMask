package com.lynxmask.app

import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert.*

/**
 * Diagnoza + regresja BUG-OSOBA-DZIALAJACY (11.07): "Osoba" i "Działający" maskowane jako
 * OSOBA mimo istniejącego guardu MorfologikHelper.isDefinitelyNotPerson() w NameEngine
 * (Warstwa "Samo nazwisko"). Diagnoza (12.07) ujawniła że "działający"/"działając" mają
 * tagi pact/pcon (imiesłowy) — osobna klasa gramatyczna, nieobjęta ówczesną listą
 * warunków. Fix: dopisana cała rodzina imiesłowów (pact/pcon/pant/ppas) do
 * MorfologikHelper.isDefinitelyNotPerson, nie tylko te dwa konkretne tagi.
 */
class MorfologikHelperOsobaTest {

    @Test
    fun `diagnoza tagow dla Osoba i Dzialajacy`() {
        val words = listOf("osoba", "działający", "działając", "łączna", "zapłaty", "tomka", "danych")
        for (w in words) {
            val tags = MorfologikHelper.tags(w)
            val notPerson = MorfologikHelper.isDefinitelyNotPerson(w)
            println("word=$w tags=$tags isDefinitelyNotPerson=$notPerson")
        }
    }

    @Test
    fun `rzeczowniki pospolite kolidujace z nazwiskami sa rozpoznawane jako NIE-osoba`() {
        assertTrue("'osoba' (subst)", MorfologikHelper.isDefinitelyNotPerson("osoba"))
        assertTrue("'łączna' (adj)", MorfologikHelper.isDefinitelyNotPerson("łączna"))
        assertTrue("'zapłaty' (subst)", MorfologikHelper.isDefinitelyNotPerson("zapłaty"))
    }

    @Test
    fun `imieslowy koliduja z nazwiskami i sa rozpoznawane jako NIE-osoba`() {
        assertTrue("'działający' (pact)", MorfologikHelper.isDefinitelyNotPerson("działający"))
        assertTrue("'działając' (pcon)", MorfologikHelper.isDefinitelyNotPerson("działając"))
    }

    companion object {
        // BUG-TESTY-WOLNE-SLOWNIK-FIX (14.07): raz na klasę zamiast raz na każdy z 4 testów.
        @BeforeClass
        @JvmStatic
        fun setupLookupTables() {
            LookupTables.resetForTesting()
            LookupTables.initializeFromClasspath()
            if (!LookupTables.initialized) error("LookupTables nie załadowane — sprawdź src/test/resources/*.json")
        }

        @AfterClass
        @JvmStatic
        fun teardownClass() {
            LookupTables.resetForTesting()
        }
    }

    /**
     * BUG-SLOWNIK-POSPOLITE-SLOWA-CAPS (12.07, benchmark_results/clean): "ZAPŁATY" w nagłówku
     * faktury ("ŁĄCZNIE DO ZAPŁATY:", generator.py:775) maskowane jako OSOBA — guard dodany
     * rano dla małych liter (3a) nie objął osobnego bloku ALL-CAPS (3b-CAPS). Test na PEŁNYM
     * silniku (nie tylko MorfologikHelper w izolacji) w OBU wariantach wielkości liter —
     * dokładnie ta klasa regresji (duplikat logiki bez duplikatu guardu) którą złapał benchmark.
     */
    @Test
    fun `ZAPLATY w naglowku faktury nie jest maskowane w zadnej wielkosci liter`() {
        val lower = PseudonymEngine.pseudonymize("Łącznie do zapłaty: 500 PLN", emptyList())
        val upper = PseudonymEngine.pseudonymize("ŁĄCZNIE DO ZAPŁATY: 500 PLN", emptyList())
        assertFalse("małe litery: 'zapłaty' zamaskowane jako OSOBA", lower.tokenMap.values.any { it.equals("zapłaty", ignoreCase = true) })
        assertFalse("ALL-CAPS: 'ZAPŁATY' zamaskowane jako OSOBA", upper.tokenMap.values.any { it.equals("zapłaty", ignoreCase = true) })
    }
}
