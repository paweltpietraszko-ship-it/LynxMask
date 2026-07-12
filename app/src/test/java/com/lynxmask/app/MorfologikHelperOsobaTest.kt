package com.lynxmask.app

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
}
