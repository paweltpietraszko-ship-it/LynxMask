package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

/**
 * Testy OCR_DIGIT_IN_CONTEXT (krok 14) + rozszerzeń OCR_PESEL_SPLIT i OCR_IBAN_SPLIT (v2.0).
 * Scenariusz: 'l' lub 'O' wśród cyfr — OCR myli litery z cyframi.
 */
class OcrNormalizerDigitContextTest {

    // ── OCR_DIGIT_IN_CONTEXT ────────────────────────────────────────────────────

    @Test
    fun `OCR_DIGIT_IN_CONTEXT pojedyncze l miedzy cyframi`() {
        val result = OcrNormalizer.normalize("wartość 9l04")
        assertTrue("'l' między cyframi powinno być '1'",
            result.normalizedText.contains("9104"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT podwojne ll miedzy cyframi`() {
        val result = OcrNormalizer.normalize("kod 52ll0732")
        assertTrue("'ll' między cyframi powinno być '11'",
            result.normalizedText.contains("52110732"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT O miedzy cyframi`() {
        val result = OcrNormalizer.normalize("numer 7408O934")
        assertTrue("'O' między cyframi powinno być '0'",
            result.normalizedText.contains("74080934"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT male o miedzy cyframi NIP`() {
        // "965690-71o1" → "965690-7101" — małe 'o' w NIP pomijane przez [lOI], bug krok 14
        val result = OcrNormalizer.normalize("NIP 965690-71o1")
        assertTrue("małe 'o' między cyframi powinno być '0'",
            result.normalizedText.contains("965690-7101"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT male o miedzy cyframi ogolnie`() {
        val result = OcrNormalizer.normalize("numer 4o8")
        assertTrue("małe 'o' między cyframi powinno być '0'",
            result.normalizedText.contains("408"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT nie zmienia l w srodku slowa`() {
        // 'l' w słowie "Kowalski" — nie otoczone cyframi → bez zmian
        val result = OcrNormalizer.normalize("Kowalski")
        assertEquals("słowo bez cyfr nie powinno być zmienione",
            "Kowalski", result.normalizedText)
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT naprawia l przed myslnikiem w NIP`() {
        val r = OcrNormalizer.normalize("52l-334-15-33")
        assertTrue(r.normalizedText.contains("521-334"))
        assertFalse(r.normalizedText.contains("52l"))
    }

    @Test
    fun `OCR_NIP_BARE3322 naprawia cyrylice Z w segmencie`() {
        val r = OcrNormalizer.normalize("521-3\u04174-15-33")
        assertTrue(r.normalizedText.contains("334"))
        assertFalse(r.normalizedText.contains("\u0417"))
    }

    @Test
    fun `OCR_PHONE_AFTER_KW naprawia 48 60l po tel`() {
        val r = OcrNormalizer.normalize("tel: 48 60l 234 567")
        assertTrue(r.normalizedText.contains("601 234 567"))
        assertFalse(r.normalizedText.contains("60l"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT naprawia l przez spacje gdy po spacji cyfra`() {
        // v2.1: 'l' po cyfrze + spacja + cyfra → naprawia (kontekst liczby z grupami)
        // "60l 234 567" → "601 234 567" (np. numer telefonu rozbity przez OCR)
        val result = OcrNormalizer.normalize("tel: 60l 234 567")
        assertTrue("'l' przed spacją+cyfrą powinno być konwertowane",
            result.normalizedText.contains("601 234"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT nie zmienia gdy po spacji litera`() {
        // "3l abc" — po spacji litera, nie cyfra → bez zmian
        val result = OcrNormalizer.normalize("poz. 3l abc")
        assertFalse("'l' przed spacją+literą nie powinno być konwertowane",
            result.normalizedText.contains("31 abc"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT naprawia O w grupie IBAN przed spacja`() {
        // "325O 0003" → "3250 0003" — 'O' na końcu grupy cyfr IBAN (v2.1)
        val input = "Nr konta: PL03 325O 0003 6637 2867 44O1 4154"
        val result = OcrNormalizer.normalize(input)
        assertTrue("'O' na końcu grupy IBAN powinno być '0'",
            result.normalizedText.contains("3250 0003"))
    }

    // ── OCR_PESEL_SPLIT v2.0 — l/O + spacja ────────────────────────────────────

    @Test
    fun `OCR_PESEL_SPLIT v2 naprawa l i spacja w PESEL`() {
        val input = "PESEL: 9l0405 l2367"
        val result = OcrNormalizer.normalize(input)
        assertTrue("'9l0405 l2367' powinno być naprawione do '91040512367'",
            result.normalizedText.contains("91040512367"))
    }

    @Test
    fun `OCR_PESEL_SPLIT v2 naprawa l i spacja PESEL2`() {
        val input = "PE5EL: 7408l934 521"
        val result = OcrNormalizer.normalize(input)
        assertTrue("'7408l934 521' powinno być naprawione do '74081934521'",
            result.normalizedText.contains("74081934521"))
    }

    @Test
    fun `OCR_PESEL_SPLIT v2 wsteczna kompatybilnosc tylko spacja`() {
        // Istniejące przypadki z samymi spacjami nadal działają
        val input = "PESEL: 6802041 8568"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Spacja w PESEL (bez liter) nadal naprawiana",
            result.normalizedText.contains("68020418568"))
    }

    // ── OCR_IBAN_SPLIT v2.0 — l/O + spacja ─────────────────────────────────────

    @Test
    fun `OCR_IBAN_SPLIT v2 naprawa l i spacja w IBAN`() {
        val input = "IBAN: PL89l090l0l474495552 52ll0732"
        val result = OcrNormalizer.normalize(input)
        assertTrue("IBAN z 'l' i spacją powinien być naprawiony",
            result.normalizedText.contains("PL89109010147449555252110732"))
    }

    @Test
    fun `OCR_IBAN_SPLIT v2 wsteczna kompatybilnosc tylko spacja`() {
        // Istniejące przypadki z samymi spacjami nadal działają
        val input = "PL02114019872105222748309 170"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Spacja w IBAN (bez liter) nadal naprawiana",
            result.normalizedText.contains("PL02114019872105222748309170"))
    }

    @Test
    fun `OCR_IBAN_SPLIT v2 nie skleja gdy suma nie wynosi 26`() {
        val input = "PL89l090 52ll"
        val result = OcrNormalizer.normalize(input)
        assertFalse("Krótki numer z literami nie powinien być sklejony",
            result.normalizedText.contains("PL89109052110"))
    }

    // ── OCR_IBAN_NEWLINE v2.3 — IBAN przez newline ──────────────────────────────

    @Test
    fun `OCR_IBAN_NEWLINE skleja IBAN przez newline`() {
        // 14 cyfr w pierwszej linii + 12 cyfr w drugiej = 26 łącznie
        val input = "Nr konta: PL89 1090 1014 7449\n5552 5211 0732"
        val result = OcrNormalizer.normalize(input)
        assertTrue("IBAN przez newline powinien być sklejony",
            result.normalizedText.contains("PL89109010147449555252110732"))
    }

    @Test
    fun `OCR_IBAN_NEWLINE nie skleja gdy suma cyfr nie wynosi 26`() {
        val input = "PL89 1090\n52 11"
        val result = OcrNormalizer.normalize(input)
        assertFalse("Krótki IBAN przez newline nie powinien być sklejony",
            result.normalizedText.contains("PL89109052"))
    }

    @Test
    fun `OCR_IBAN_NEWLINE nie ingeruje w IBAN jednolinijkowy`() {
        // Istniejący OCR_IBAN_SPLIT obsługuje jednoliniowy — NEWLINE nie może go zepsuć
        val input = "PL02114019872105222748309 170"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Jednoliniowy IBAN ze spacją nadal naprawiany przez IBAN_SPLIT",
            result.normalizedText.contains("PL02114019872105222748309170"))
    }

    // ── OCR_CITY_MIDSPACE v2.4 — fold() bez ogonków (N5) ───────────────────────

    @Test
    fun `OCR_CITY_MIDSPACE skleja Bialystok bez ogonkow`() {
        // OCR daje "Bialy stok" (bez ł) — fold("bialystok") ∈ KNOWN_CITY_FORMS_FOLDED
        val result = OcrNormalizer.normalize("adres: Bialy stok 15-001")
        assertTrue("'Bialy stok' powinno być sklejone do 'Bialystok'",
            result.normalizedText.contains("Bialystok"))
    }

    @Test
    fun `OCR_CITY_MIDSPACE skleja Krakow bez ogonkow`() {
        // OCR daje "Kra kow" (bez ó) — fold("krakow") ∈ KNOWN_CITY_FORMS_FOLDED
        val result = OcrNormalizer.normalize("miasto: Kra kow, 30-001")
        assertTrue("'Kra kow' powinno być sklejone do 'Krakow'",
            result.normalizedText.contains("Krakow"))
    }

    @Test
    fun `OCR_CITY_MIDSPACE z ogonkami nadal dziala`() {
        // Nie regres — istniejące "Wars zawa" nadal działa
        val result = OcrNormalizer.normalize("Wars zawa centrum")
        assertTrue("'Wars zawa' nadal powinno być sklejone do 'Warszawa'",
            result.normalizedText.contains("Warszawa"))
    }

    // ── OCR_ZERO_RUN_IN_AMOUNT (krok 14b, 08.07) — run 2+ liter O w kwocie ──────

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT naprawia OOO oddzielone spacja od cyfry`() {
        // OCR_DIGIT_IN_CONTEXT nie łapie tego — spacja przed "OOO" łamie lookbehind (?<=\d)
        val result = OcrNormalizer.normalize("kwota: 15 OOO,OO zł")
        assertTrue(result.normalizedText.contains("15 000,00"))
        assertFalse(result.normalizedText.contains("OOO"))
    }

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT naprawia cala kwote bez etykiety`() {
        val result = OcrNormalizer.normalize("15 OOO,OO zł")
        assertTrue(result.normalizedText.contains("15 000,00"))
    }

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT naprawia druga grupe ktora nie dotyka cyfry bezposrednio`() {
        // "23O 5OO PLN" — historyczny przypadek (test_anchor_full.txt): stary
        // OCR_DIGIT_IN_CONTEXT naprawiał tylko "23O" (bo "3" jest tuż przed "O"), nie
        // dotykał "5OO" bo po nim jest "PLN" (litery), nie cyfra — wymóg lookaheadu (?=\d)
        // nie był spełniony. Nowa reguła łapie CAŁE wyrażenie liczbowe naraz.
        val result = OcrNormalizer.normalize("23O 5OO PLN")
        assertTrue("Oczekiwano '230 500 PLN', wynik: ${result.normalizedText}",
            result.normalizedText.contains("230 500"))
    }

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT naprawia kwote ze spacjami wokol przecinka`() {
        val result = OcrNormalizer.normalize("15 OOO , OO")
        assertTrue("Oczekiwano '15 000 , 00', wynik: ${result.normalizedText}",
            result.normalizedText.contains("15 000 , 00"))
    }

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT nie rusza pojedynczej litery O`() {
        // Tylko RUN 2+ liter O jest sygnałem OCR dla TEJ reguły — pojedyncze "O" zostaje
        // nietknięte przez OCR_ZERO_RUN_IN_AMOUNT, zbyt duże ryzyko FP.
        // UWAGA (druga poprawka tego testu, 08.07): "5O1" (litera MIĘDZY dwiema cyframi)
        // to dokładnie kształt który OSOBNA, WCZEŚNIEJSZA reguła OCR_DIGIT_IN_CONTEXT
        // (krok 14, sprzed dzisiejszej sesji) świadomie konwertuje — pierwsza wersja tego
        // testu myliła to z moją nową regułą. "5O" na końcu wyrazu (bez cyfry zaraz po)
        // nie dotyka ANI OCR_DIGIT_IN_CONTEXT ANI OCR_PHONE_AFTER_KW — zweryfikowane Javą.
        val result = OcrNormalizer.normalize("zebrał 5O punktów")
        assertTrue("Pojedyncze 'O' na końcu liczby (bez cyfry po nim) nie powinno być zamienione: ${result.normalizedText}",
            result.normalizedText.contains("5O"))
    }

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT nie rusza czystego tekstu bez cyfr`() {
        val result = OcrNormalizer.normalize("dr Kowalski")
        assertEquals("Tekst bez cyfr na początku nie powinien się zmienić",
            "dr Kowalski", result.normalizedText)
    }

    @Test
    fun `OCR_ZERO_RUN_IN_AMOUNT nie psuje czystej kwoty bez liter O`() {
        val result = OcrNormalizer.normalize("49 999,99 zł")
        assertTrue(result.normalizedText.contains("49 999,99"))
    }

    // ── OCR_ONE_AS_L / OCR_ZERO_AS_O — nie rusza kodów referencyjnych (12.07) ──────────

    @Test
    fun `OCR_ONE_AS_L nie rusza cyfry w numerze ksiegi wieczystej`() {
        // BUG-1-JAKO-L-NA-KODZIE: "PO1P/00793300/9" to prawdziwy kod (nr KW), nie OCR —
        // '1' otoczone literami, ale zaraz potem "/cyfra" — sygnatura numeru referencyjnego.
        val result = OcrNormalizer.normalize("Nr księgi wieczystej: PO1P/00793300/9")
        assertTrue("cyfra '1' w kodzie KW nie powinna zmienić się na 'l': ${result.normalizedText}",
            result.normalizedText.contains("PO1P/00793300/9"))
    }

    @Test
    fun `OCR_ONE_AS_L nadal naprawia 1 jako l w zwyklym slowie bez ukosnika`() {
        // Brak regresji: prawdziwy tekst po OCR (litera otoczona literami, BEZ sąsiedztwa
        // "/cyfra") nadal jest naprawiany jak dotąd.
        val result = OcrNormalizer.normalize("Zleceniobiorca: Kowa1ski")
        assertTrue("'Kowa1ski' bez kodu referencyjnego obok powinno nadal naprawiać się do 'Kowalski': ${result.normalizedText}",
            result.normalizedText.contains("Kowalski"))
    }

    @Test
    fun `OCR_ZERO_AS_O nie rusza cyfry w kodzie z ukosnikiem`() {
        val result = OcrNormalizer.normalize("Nr sprawy: KA0K/00133461/5")
        assertTrue("cyfra '0' w kodzie z ukośnikiem nie powinna zmienić się na 'o': ${result.normalizedText}",
            result.normalizedText.contains("KA0K/00133461/5"))
    }
}
