package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

/**
 * Diagnoza BUG-OSOBA-DZIALAJACY (11.07): "Osoba" i "Działający" maskowane jako OSOBA
 * mimo istniejącego guardu MorfologikHelper.isDefinitelyNotPerson() w NameEngine
 * (Warstwa "Samo nazwisko"). Test drukuje realne tagi Morfologika dla tych słów,
 * żeby ustalić czy guard faktycznie klasyfikuje je jako "nie-osoba", czy któryś tag
 * psuje `t.all { ... }`.
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
        assertTrue(
            "'osoba' powinno być rozpoznane jako NIE-osoba (rzeczownik pospolity)",
            MorfologikHelper.isDefinitelyNotPerson("osoba")
        )
    }
}
