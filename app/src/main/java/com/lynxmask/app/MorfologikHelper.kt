package com.lynxmask.app

import morfologik.stemming.polish.PolishStemmer

// MorfologikHelper — POS tagging dla polskiego przez słownik morfologiczny PoliMorf.
// Zastępuje ręcznie budowane listy końcówek (TITLE_ADJECTIVE_ENDINGS) i większość OSOBA_DENYLIST.
//
// Tag format NKJP: "klasa:liczba:przypadek:rodzaj:stopień", np.:
//   adj:sg:nom:m3:pos  → przymiotnik, lp, mianownik, m. nieżywotny, stopień równy
//   subst:sg:nom:m1    → rzeczownik, lp, mianownik, m. osobowy
//   verb:fin:sg:ter:imperf:refl.nonrefl → czasownik
//   adv                → przysłówek
//
// Niejednoznaczność: Morfologik może zwrócić wiele tagów dla jednego słowa.
// Reguła: funkcje zwracają true tylko gdy WSZYSTKIE tagi spełniają warunek — zero false positives.
//
// Thread safety: PolishStemmer jest thread-safe (słownik immutable, lookup tworzy nowe obiekty).

object MorfologikHelper {

    private val stemmer by lazy {
        val s = PolishStemmer()
        // Weryfikacja czy słownik załadował — testowe słowo jednoznacznie adj
        val testTags = s.lookup("rejonowy").map { it.tag.toString() }
        android.util.Log.i("MorfologikHelper",
            "Słownik załadowany. rejonowy→tags=$testTags (oczekiwane: [adj:...])")
        s
    }

    // Zwraca wszystkie tagi POS dla słowa (lowercase). Pusta lista = słowo nieznane słownikowi.
    fun tags(word: String): List<String> =
        stemmer.lookup(word.lowercase()).map { it.tag.toString() }

    // true gdy słowo jest DEFINITYWNIE przymiotnikiem — wszystkie tagi zaczynają się od "adj".
    // Niejednoznaczne (adj + subst) → false. Nieznane → false (fallback do starego regexu).
    fun isAdjective(word: String): Boolean {
        val t = tags(word)
        return t.isNotEmpty() && t.all { it.startsWith("adj") }
    }

    // true gdy słowo definitywnie NIE może być osobą — wszystkie tagi należą do klas nieosobowych.
    // Zastępuje OSOBA_DENYLIST dla słów znanych słownikowi.
    // Klasy nieosobowe: przymiotnik, przysłówek, spójnik, przyimek, czasownik, liczebnik,
    //                   wykrzyknik, partykuła, zaimek, forma bezosobowa.
    fun isDefinitelyNotPerson(word: String): Boolean {
        val t = tags(word)
        if (t.isEmpty()) return false
        return t.all { tag ->
            tag.startsWith("adj") ||
            tag.startsWith("adv") ||
            tag.startsWith("conj") ||
            tag.startsWith("prep") ||
            tag.startsWith("verb") ||
            tag.startsWith("num") ||
            tag.startsWith("interj") ||
            tag.startsWith("part") ||
            tag.startsWith("ppron") ||
            tag.startsWith("siebie") ||
            tag.startsWith("qub") ||
            tag.startsWith("ger")   // rzeczownik odsłowny — nie jest osobą
        }
    }
}
