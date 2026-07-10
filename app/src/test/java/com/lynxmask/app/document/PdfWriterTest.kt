package com.lynxmask.app.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// PdfWriterTest.kt — testuje tylko CZYSTE funkcje (wrapTextForPdf/wrapParagraph,
// paginateLines, justifiedPositions). Pomiar szerokości jest parametrem (measure), więc
// testowalne w JVM bez Androida — podstawiamy prostą "1 znak = 1 jednostka szerokości"
// zamiast Paint.measureText. writePdfFromText używa android.graphics.pdf.PdfDocument/
// Canvas/Paint — Android framework, "Stub!" poza Robolectrikiem/testem instrumentalnym.
class PdfWriterTest {

    private val charWidthMeasure: (String) -> Float = { it.length.toFloat() }
    private fun texts(lines: List<WrappedLine>) = lines.map { it.text }

    @Test fun `krotkie linie nie sa lamane`() {
        val lines = wrapTextForPdf("Krótka linia", maxWidth = 90f, measure = charWidthMeasure)
        assertEquals(listOf("Krótka linia"), texts(lines))
        assertTrue(lines.single().isParagraphEnd)
    }

    @Test fun `dlugi akapit jest lamany po slowach, ostatnia linia oznaczona jako koniec akapitu`() {
        val paragraph = (1..20).joinToString(" ") { "słowo$it" }
        val lines = wrapTextForPdf(paragraph, maxWidth = 30f, measure = charWidthMeasure)
        assertTrue("Powinno powstać więcej niż jedna linia", lines.size > 1)
        texts(lines).forEach { assertTrue("Linia '$it' przekracza limit", it.length <= 30) }
        assertEquals(paragraph, texts(lines).joinToString(" "))
        assertTrue(lines.last().isParagraphEnd)
        assertTrue("Środkowe linie NIE są końcem akapitu", lines.dropLast(1).none { it.isParagraphEnd })
    }

    @Test fun `bardzo dlugie pojedyncze slowo jest ucinane na granicy znakow`() {
        val word = "a".repeat(250)
        val lines = wrapTextForPdf(word, maxWidth = 90f, measure = charWidthMeasure)
        assertTrue(lines.size >= 3)
        assertEquals(word, texts(lines).joinToString(""))
    }

    @Test fun `puste linie (akapity) sa zachowane`() {
        val lines = wrapTextForPdf("Pierwszy\n\nTrzeci", maxWidth = 90f, measure = charWidthMeasure)
        assertEquals(listOf("Pierwszy", "", "Trzeci"), texts(lines))
    }

    @Test fun `lamanie uzywa przekazanej funkcji pomiaru, nie liczby znakow`() {
        val measure: (String) -> Float = { s -> s.sumOf { c -> if (c == 'w') 10 else 2 }.toFloat() }
        assertEquals(listOf("www i"), texts(wrapTextForPdf("www i", maxWidth = 34f, measure = measure)))
        assertEquals(listOf("www", "i"), texts(wrapTextForPdf("www i", maxWidth = 33f, measure = measure)))
    }

    private fun wl(vararg text: String) = text.map { WrappedLine(it, isParagraphEnd = true) }

    @Test fun `paginacja dzieli linie wedlug rzeczywistej wysokosci`() {
        val lines = (1..25).map { WrappedLine("linia$it", isParagraphEnd = true) }
        val pages = paginateLines(lines, usableHeight = 100f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(3, pages.size)
        assertEquals(10, pages[0].size)
        assertEquals(10, pages[1].size)
        assertEquals(5, pages[2].size)
        assertEquals(lines, pages.flatten())
    }

    @Test fun `pusta linia (przerwa akapitu) liczy sie jako wieksza wysokosc niz zwykla linia`() {
        val lines = (1..9).map { WrappedLine("linia$it", isParagraphEnd = true) } + wl("")
        val pages = paginateLines(lines, usableHeight = 100f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(2, pages.size)
        assertEquals(9, pages[0].size)
        assertEquals(wl(""), pages[1])
    }

    @Test fun `strona nie konczy sie przedwczesnie gdy jeszcze jest miejsce`() {
        val lines = (1..10).map { WrappedLine("linia$it", isParagraphEnd = true) }
        val pages = paginateLines(lines, usableHeight = 100f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(1, pages.size)
        assertEquals(10, pages[0].size)
    }

    @Test fun `pusty tekst daje jedna pusta strone`() {
        val pages = paginateLines(emptyList(), usableHeight = 100f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(1, pages.size)
        assertTrue(pages[0].isEmpty())
    }

    @Test fun `linia bedaca samym paragrafem jest rozpoznana`() {
        assertTrue(isSectionMarker("§1"))
        assertTrue(isSectionMarker("§ 12"))
        assertTrue(isSectionMarker("§10."))
        assertTrue(isSectionMarker("  §3  "))
    }

    @Test fun `paragraf z krotkim tytulem obok tez jest rozpoznany`() {
        // Ogólna reguła (jak przy UMOWA) — numer + krótki tytuł, nie tylko goły numer.
        assertTrue(isSectionMarker("§3 Definicje"))
        assertTrue(isSectionMarker("§ 1. Postanowienia ogólne"))
        assertTrue(isSectionMarker("§10 Odpowiedzialność stron"))
    }

    @Test fun `zwykle zdanie zaczynajace sie od paragrafu nie jest naglowkiem`() {
        assertFalse(isSectionMarker("Zgodnie z § 3 niniejszej umowy"))
        assertFalse(isSectionMarker("§1 Postanowienia ogólne obowiązują od dnia podpisania niniejszej umowy przez obie strony."))
        assertFalse(isSectionMarker("§3 stanowi, że każda ze stron zobowiązuje się"))
        assertFalse(isSectionMarker("REGULAMIN"))
        assertFalse(isSectionMarker("4"))
        assertFalse(isSectionMarker(""))
    }

    @Test fun `tytul zaczynajacy sie od slowa-kotwicy jest rozpoznany, niezaleznie od kwalifikatora`() {
        assertTrue(isTitleKeyword("REGULAMIN"))
        assertTrue(isTitleKeyword("Umowa"))
        assertTrue(isTitleKeyword("  zaświadczenie  "))
        assertTrue(isTitleKeyword("Podanie"))
        assertTrue(isTitleKeyword("umowa najmu"))
        assertTrue(isTitleKeyword("UMOWA PRZEWŁASZCZENIA NA ZABEZPIECZENIE"))
        assertTrue(isTitleKeyword("Zaświadczenie o zatrudnieniu i wynagrodzeniu"))
        assertTrue(isTitleKeyword("Regulamin Karty Lojalnościowej"))
    }

    @Test fun `slowo-kotwica odmienione lub w srodku zdania nie jest naglowkiem`() {
        assertFalse(isTitleKeyword("na podstawie niniejszej umowy"))
        assertFalse(isTitleKeyword("Kowalski"))
        assertFalse(isTitleKeyword("Umową objęte są wszystkie strony"))
    }

    @Test fun `zdanie zaczynajace sie od slowa-kotwicy nie jest naglowkiem`() {
        assertFalse(isTitleKeyword("Umowa zostaje zawarta na czas nieokreślony i wchodzi w życie z dniem jej podpisania."))
        assertFalse(isTitleKeyword("Umowa najmu,"))
        assertFalse(isTitleKeyword("Umowa o świadczenie usług telekomunikacyjnych zawarta pomiędzy stronami niniejszym oto"))
    }

    @Test fun `isCenteredHeading laczy oba wzorce`() {
        assertTrue(isCenteredHeading("§4"))
        assertTrue(isCenteredHeading("§4 Definicje"))
        assertTrue(isCenteredHeading("UMOWA"))
        assertFalse(isCenteredHeading("Zwykła linia tekstu"))
    }

    @Test fun `znacznik strony OCR jest rozpoznany`() {
        assertTrue(isPageMarker("── Strona 1 ──"))
        assertTrue(isPageMarker("── Strona 12 ──"))
        assertTrue(isPageMarker("  ── Strona 2 ──  "))
        assertFalse(isPageMarker("Strona 2"))
        assertFalse(isPageMarker("── Strona dwa ──"))
        assertFalse(isPageMarker("Zgodnie ze stroną 2 umowy"))
    }

    @Test fun `znacznik strony wymusza podzial i znika z wyniku`() {
        val lines = listOf(
            WrappedLine("Treść strony 1.", isParagraphEnd = true),
            WrappedLine("── Strona 2 ──", isParagraphEnd = true),
            WrappedLine("Treść strony 2.", isParagraphEnd = true)
        )
        val pages = paginateLines(lines, usableHeight = 1000f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(2, pages.size)
        assertEquals(wl("Treść strony 1."), pages[0])
        assertEquals(wl("Treść strony 2."), pages[1])
    }

    @Test fun `znacznik na poczatku nie tworzy pustej pierwszej strony`() {
        val lines = listOf(WrappedLine("── Strona 1 ──", isParagraphEnd = true), WrappedLine("Treść.", isParagraphEnd = true))
        val pages = paginateLines(lines, usableHeight = 1000f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(1, pages.size)
        assertEquals(wl("Treść."), pages[0])
    }

    @Test fun `znacznik na koncu nie tworzy pustej ostatniej strony`() {
        val lines = listOf(WrappedLine("Treść.", isParagraphEnd = true), WrappedLine("── Strona 2 ──", isParagraphEnd = true))
        val pages = paginateLines(lines, usableHeight = 1000f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(1, pages.size)
        assertEquals(wl("Treść."), pages[0])
    }

    @Test fun `BUG-PDF-PUSTA-STRONA-1 - naglowek sesji plus pierwszy znacznik nie tworzy prawie pustej strony`() {
        // Realny przypadek Pawła: "SESJA_XXXXXX" (linia dodana przez silnik) + OD RAZU
        // "── Strona 1 ──" (OCR wstawia go też dla PIERWSZEJ strony, nie tylko kolejnych)
        // — pierwsza wersja wymuszała podział TUTAJ, zostawiając stronę 1 z samym nagłówkiem
        // sesji i całą resztą treści od strony 2.
        val lines = listOf(
            WrappedLine("SESJA_1A9BE4", isParagraphEnd = true),
            WrappedLine("── Strona 1 ──", isParagraphEnd = true),
            WrappedLine("Treść dokumentu.", isParagraphEnd = true)
        )
        val pages = paginateLines(lines, usableHeight = 1000f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals("Nie powinno być osobnej, prawie pustej pierwszej strony", 1, pages.size)
        assertEquals(wl("SESJA_1A9BE4", "Treść dokumentu."), pages[0])
    }

    @Test fun `tylko PIERWSZY znacznik Strona 1 jest zwolniony z wymuszania podzialu`() {
        // Gdyby "Strona 1" pojawiła się DRUGI raz (nie powinno się zdarzyć w realnym
        // pipeline, ale reguła ma być odporna) — tylko naprawdę pierwsze wystąpienie jest
        // zwolnione, nie każde "Strona 1".
        val lines = listOf(
            WrappedLine("A", isParagraphEnd = true),
            WrappedLine("── Strona 1 ──", isParagraphEnd = true),
            WrappedLine("B", isParagraphEnd = true),
            WrappedLine("── Strona 1 ──", isParagraphEnd = true),
            WrappedLine("C", isParagraphEnd = true)
        )
        val pages = paginateLines(lines, usableHeight = 1000f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(2, pages.size)
        assertEquals(wl("A", "B"), pages[0])
        assertEquals(wl("C"), pages[1])
    }

    @Test fun `znacznik Strona 2 jako pierwszy napotkany nadal wymusza podzial (nie jest zwolniony)`() {
        // Tylko numer 1 jest traktowany jako "początek dokumentu" — jeśli z jakiegoś powodu
        // pierwszy napotkany znacznik to "Strona 2" (np. strona 1 nie miała tekstu z OCR),
        // to WCIĄŻ oznacza prawdziwe przejście, nie jest zwalniany.
        val lines = listOf(
            WrappedLine("Treść strony 1.", isParagraphEnd = true),
            WrappedLine("── Strona 2 ──", isParagraphEnd = true),
            WrappedLine("Treść strony 2.", isParagraphEnd = true)
        )
        val pages = paginateLines(lines, usableHeight = 1000f, lineHeight = 10f, paragraphGap = 16f)
        assertEquals(2, pages.size)
    }

    @Test fun `justowanie rozciaga odstepy tak zeby ostatnie slowo konczylo sie na maxWidth`() {
        // 3 słowa po 5 jednostek każde = 15, maxWidth=27 -> 12 do rozdzielenia na 2 odstępy = 6 każdy.
        val positions = justifiedPositions(listOf("aaaaa", "bbbbb", "ccccc"), maxWidth = 27f, measure = charWidthMeasure)
        assertEquals(listOf(0f, 11f, 22f), positions)
        // Ostatnie słowo (5 jednostek) zaczyna się na 22 -> kończy się na 27 = maxWidth.
    }

    @Test fun `justowanie pojedynczego slowa nie rozciaga niczego`() {
        assertEquals(listOf(0f), justifiedPositions(listOf("słowo"), maxWidth = 90f, measure = charWidthMeasure))
        assertEquals(listOf(0f), justifiedPositions(emptyList(), maxWidth = 90f, measure = charWidthMeasure))
    }

    @Test fun `justowanie nie daje ujemnych odstepow gdy slowa juz przekraczaja maxWidth`() {
        val positions = justifiedPositions(listOf("aaaaaaaaaa", "bbbbbbbbbb"), maxWidth = 5f, measure = charWidthMeasure)
        assertEquals(listOf(0f, 10f), positions)
    }
}
