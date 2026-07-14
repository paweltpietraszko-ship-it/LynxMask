package com.lynxmask.app.document

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.lynxmask.app.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

// PdfWriter.kt — Document Rebuilder, ten sam minimalistyczny wzorzec co DocxWriter.kt
// (decyzja właściciela 10.07): zapisujemy gotowy, już zamaskowany tekst jako nowy,
// samodzielny PDF — bez odwzorowania oryginalnego layoutu, bez szukania czegokolwiek
// w oryginalnym pliku. `android.graphics.pdf.PdfDocument` jest częścią Androida — zero
// nowych zależności, zero problemu licencyjnego (który dotyczy np. iText AGPL).
//
// Wejście PDF w LynxMask zawsze idzie przez OCR (`ocrFromPdfUri`, PdfRenderer + ML Kit —
// patrz IncomingDocumentFlow.kt) — traktujemy PDF jak skan niezależnie od tego czy ma
// natywną warstwę tekstu. To upraszcza też zapis: nie ma warstwy tekstu do zachowania.
//
// FIX (10.07, zgłoszenie Pawła — "wygląda jak wydruk z komputera z lat 70"): pierwsza
// wersja łamała linie po STAŁEJ liczbie znaków i używała domyślnego Paint bez fontu —
// przy proporcjonalnym foncie stała liczba znaków daje bardzo nierówny prawy margines
// (typowy "maszynowy" wygląd), a brak jawnego typeface wygląda przypadkowo. Fix: łamanie
// po RZECZYWISTEJ szerokości tekstu (Paint.measureText) + jawny czytelny font sans-serif.
//
// TRZECIE PRZEJŚCIE (10.07, zrzut Pawła "Strona 1 z 2" vs "── Strona 2 ──" nie zgadzają
// się): DRUGIE przejście dodało WŁASNY licznik stron w stopce — ale `ocrFromPdfUri`
// (DocumentExtractor.kt) już wstawia w tekst znacznik "── Strona N ──" między stronami
// ŹRÓDŁOWEGO PDF-a. Dwa liczniki naraz (mój — stron WYNIKOWEGO pliku, cudzy — stron
// ŹRÓDŁOWEGO pliku) nieuchronnie się rozjeżdżają. Fix: usunięta stopka — zostaje tylko
// sensowny, istniejący znacznik z tekstu źródłowego, teraz wymuszający PRAWDZIWY podział
// strony (patrz isPageMarker/paginateLines) zamiast być zwykłym tekstem.
//
// CZWARTE PRZEJŚCIE (10.07, zrzut Pawła — "§3 Definicje" nie centrowane, brak justowania):
// isSectionMarker poszerzony o krótki tytuł obok numeru (ten sam wzorzec co isTitleKeyword).
// Dopisane prawdziwe justowanie (wyrównanie do OBU marginesów) dla zwykłych linii akapitu —
// wcześniej tylko lewy margines był równy, prawy poszarpany (normalne dla left-align, ale
// Paweł chciał wygląd "normalnego dokumentu" = justowany, jak w Wordzie). Ostatnia linia
// każdego akapitu NIE jest justowana (standard typograficzny — krótka końcówka akapitu
// rozciągnięta na całą szerokość wyglądałaby źle, puste dziury między słowami).

private const val PAGE_WIDTH = 595   // A4 w punktach (72 dpi), jak Android PdfDocument oczekuje
private const val PAGE_HEIGHT = 842
private const val MARGIN = 56f
private const val FONT_SIZE = 12f
private const val PARAGRAPH_GAP_FACTOR = 1.6f  // pusta linia (akapit) liczy się jako 1,6× wysokości linii
private const val MAX_TITLE_WORDS = 8
private val SENTENCE_END = setOf('.', ',', ';', '!', '?')

// Wąski, bezpieczny wzorzec — linia zaczynająca się od "§" + numeru, opcjonalnie z krótkim
// tytułem obok ("§3", "§3 Definicje", "§3. Postanowienia ogólne") — ale NIE cały akapit,
// który przypadkiem zaczyna się od paragrafu ("§3 stanowi, że każda ze stron zobowiązuje
// się..." — za długie i/albo kończy się kropką zdania).
private val SECTION_MARKER_PREFIX_RE = Regex("""^§\s?\d+\.?""")
internal fun isSectionMarker(line: String): Boolean {
    val trimmed = line.trim()
    val match = SECTION_MARKER_PREFIX_RE.find(trimmed) ?: return false
    if (match.range.first != 0) return false
    val rest = trimmed.substring(match.value.length).trim()
    if (rest.isEmpty()) return true
    // Interpunkcja zdania GDZIEKOLWIEK w reszcie, nie tylko na końcu — "§3 stanowi, że..."
    // ma przecinek w środku (zwykłe zdanie), nie na końcu linii. Prawdziwy tytuł ("§3
    // Definicje") nigdy nie ma wewnętrznej interpunkcji zdania.
    if (rest.any { it in SENTENCE_END }) return false
    val words = rest.split(Regex("""\s+"""))
    return words.size <= MAX_TITLE_WORDS
}

// OGÓLNA reguła zamiast listy fraz ("umowa najmu"/"umowa przewłaszczenia" itd. — nie da się
// wymienić wszystkich wariantów): linia jest tytułem, gdy PIERWSZE słowo to (nieodmienione)
// słowo-kotwica ("umowa", nie "umową"/"umowy" — odmienione formy prawie zawsze są w środku
// zdania), linia jest KRÓTKA (tytuły dokumentów w PL rzadko przekraczają ~8 słów) i NIE
// KOŃCZY SIĘ interpunkcją zdania. Case-insensitive — OCR miesza wielkość liter.
private val TITLE_KEYWORDS = setOf(
    "regulamin", "umowa", "zaświadczenie", "podanie", "wniosek", "oświadczenie",
    "protokół", "uchwała", "zarządzenie", "faktura", "pozew", "wyrok",
    "postanowienie", "skarga", "apelacja", "pełnomocnictwo", "aneks",
    "porozumienie", "deklaracja", "zawiadomienie", "decyzja", "sprawozdanie",
    "upoważnienie"
)

internal fun isTitleKeyword(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.last() in SENTENCE_END) return false
    val words = trimmed.split(Regex("""\s+"""))
    if (words.size > MAX_TITLE_WORDS) return false
    return words.first().lowercase() in TITLE_KEYWORDS
}

internal fun isCenteredHeading(line: String): Boolean = isSectionMarker(line) || isTitleKeyword(line)

// Silnik OCR (`ocrFromPdfUri`, DocumentExtractor.kt:140) wstawia między stronami źródłowego
// PDF-a znacznik "── Strona N ──" — rozpoznajemy go i wymuszamy PRAWDZIWY podział strony w
// wyniku w tym miejscu (patrz paginateLines), więc granice stron wynikowego PDF-a odpowiadają
// oryginałowi. Sam znacznik znika z tekstu — nowa strona SAMA W SOBIE jest tym podziałem.
private val PAGE_MARKER_RE = Regex("""^──\s*Strona\s+(\d+)\s*──$""")
internal fun isPageMarker(line: String): Boolean = PAGE_MARKER_RE.matches(line.trim())
internal fun pageMarkerNumber(line: String): Int? =
    PAGE_MARKER_RE.find(line.trim())?.groupValues?.get(1)?.toIntOrNull()

/**
 * Łamie jeden akapit na linie mieszczące się w [maxWidth], mierzone przez [measure]
 * (w produkcji: `Paint::measureText`). CZYSTA funkcja — pomiar jest parametrem, więc
 * testowalna w JVM z podstawioną funkcją, bez zależności od Androida wprost. Łamie po
 * słowach; pojedyncze słowo szersze niż cała linia (długi identyfikator/URL) ucina się na
 * granicy znaków, żeby nie wyjechało poza stronę.
 */
internal fun wrapParagraph(paragraph: String, maxWidth: Float, measure: (String) -> Float): List<String> {
    if (paragraph.isEmpty()) return listOf("")
    val result = mutableListOf<String>()
    var current = StringBuilder()
    for (word in paragraph.split(" ")) {
        var w = word
        while (measure(w) > maxWidth && w.length > 1) {
            var cut = w.length - 1
            while (cut > 1 && measure(w.take(cut)) > maxWidth) cut--
            if (current.isNotEmpty()) { result += current.toString(); current = StringBuilder() }
            result += w.take(cut)
            w = w.drop(cut)
        }
        val candidate = if (current.isEmpty()) w else "$current $w"
        if (current.isNotEmpty() && measure(candidate) > maxWidth) {
            result += current.toString()
            current = StringBuilder(w)
        } else {
            current = StringBuilder(candidate)
        }
    }
    result += current.toString()
    return result
}

/**
 * Jedna zawinięta linia gotowa do narysowania. [isParagraphEnd] = ostatnia wynikowa linia
 * oryginalnego (przed łamaniem) wiersza tekstu — używane do wyłączenia justowania na końcu
 * akapitu (standard typograficzny: ostatnia, zwykle krótka linia zostaje do lewej, nie
 * rozciągnięta na całą szerokość).
 */
internal data class WrappedLine(val text: String, val isParagraphEnd: Boolean)

/** Łamie CAŁY tekst (wieloakapitowy, "\n"-rozdzielony) na linie — patrz [wrapParagraph]. */
internal fun wrapTextForPdf(text: String, maxWidth: Float, measure: (String) -> Float): List<WrappedLine> =
    text.split("\n").flatMap { paragraph ->
        val wrapped = wrapParagraph(paragraph, maxWidth, measure)
        wrapped.mapIndexed { i, line -> WrappedLine(line, isParagraphEnd = i == wrapped.lastIndex) }
    }

/**
 * Dzieli linie na strony wedle RZECZYWISTEJ wysokości — pusta linia (przerwa akapitu)
 * kosztuje [paragraphGap], zwykła linia [lineHeight]. Łamie stronę dopiero gdy kolejna
 * linia naprawdę by się nie zmieściła w [usableHeight]. Linia będąca znacznikiem strony
 * źródłowej (patrz [isPageMarker]) wymusza podział i sama znika z wyniku — CZYSTA funkcja,
 * testowalna w JVM.
 */
internal fun paginateLines(
    lines: List<WrappedLine>,
    usableHeight: Float,
    lineHeight: Float,
    paragraphGap: Float
): List<List<WrappedLine>> {
    if (lines.isEmpty()) return listOf(emptyList())
    val pages = mutableListOf<List<WrappedLine>>()
    var current = mutableListOf<WrappedLine>()
    var height = 0f
    var sawAnyMarker = false
    for (line in lines) {
        if (isPageMarker(line.text)) {
            // BUG-PDF-PUSTA-STRONA-1 (10.07, zrzut Pawła — realny dokument, strona 1
            // wychodziła prawie pusta): OCR wstawia "── Strona 1 ──" ZAWSZE, nawet dla
            // pierwszej strony źródła — a nie tylko między kolejnymi. Ten pierwszy
            // znacznik nigdy nie opisuje prawdziwego przejścia (jesteśmy i tak dopiero na
            // początku wyniku) — wymuszał podział zaraz po samym nagłówku "SESJA_XXXXXX",
            // zostawiając stronę 1 z jedną linią. Fix: pierwszy napotkany znacznik "Strona
            // 1" nic nie wymusza; KAŻDY kolejny (Strona 2, 3, ...) działa jak wcześniej.
            val isRedundantFirstMarker = !sawAnyMarker && pageMarkerNumber(line.text) == 1
            sawAnyMarker = true
            if (!isRedundantFirstMarker && current.isNotEmpty()) {
                pages += current
                current = mutableListOf()
                height = 0f
            }
            continue
        }
        val cost = if (line.text.isEmpty()) paragraphGap else lineHeight
        if (current.isNotEmpty() && height + cost > usableHeight) {
            pages += current
            current = mutableListOf()
            height = 0f
        }
        current += line
        height += cost
    }
    if (current.isNotEmpty() || pages.isEmpty()) pages += current
    return pages
}

/**
 * Oblicza pozycje X (względem lewego marginesu) kolejnych słów w wyjustowanej linii —
 * odstępy między słowami rozciągnięte tak, żeby ostatnie słowo kończyło się dokładnie na
 * [maxWidth]. CZYSTA funkcja — pomiar jest parametrem, testowalna w JVM. Linia z 0-1 słowem
 * nie ma czego rozciągać — zwraca [0f] (naturalne wyrównanie do lewej).
 */
internal fun justifiedPositions(words: List<String>, maxWidth: Float, measure: (String) -> Float): List<Float> {
    if (words.size <= 1) return listOf(0f)
    val wordWidths = words.map { measure(it) }
    val gapWidth = ((maxWidth - wordWidths.sum()) / (words.size - 1)).coerceAtLeast(0f)
    val positions = mutableListOf<Float>()
    var x = 0f
    for (w in wordWidths) {
        positions += x
        x += w + gapWidth
    }
    return positions
}

/**
 * Zapisuje [text] (gotowy, już zamaskowany tekst) jako nowy, samodzielny plik PDF —
 * czytelny font sans-serif, justowanie akapitów, łamanie po rzeczywistej szerokości strony,
 * podział stron dopasowany do znaczników źródła gdy są dostępne. Nie odwzorowuje
 * oryginalnego layoutu — patrz decyzja właściciela 10.07.
 */
internal suspend fun writePdfFromText(
    text: String,
    outputStream: OutputStream,
): DocumentWriteResult = withContext(Dispatchers.IO) {
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = FONT_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val centeredPaint = Paint(paint).apply { textAlign = Paint.Align.CENTER }
        val lineHeight = paint.fontSpacing
        val paragraphGap = lineHeight * PARAGRAPH_GAP_FACTOR
        val maxWidth = PAGE_WIDTH - 2 * MARGIN
        val usableHeight = PAGE_HEIGHT - 2 * MARGIN
        val lines = wrapTextForPdf(text, maxWidth) { paint.measureText(it) }
        val pages = paginateLines(lines, usableHeight, lineHeight, paragraphGap)

        val document = PdfDocument()
        pages.forEachIndexed { pageIndex, pageLines ->
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
            val page = document.startPage(pageInfo)
            var y = MARGIN - paint.ascent()
            for (line in pageLines) {
                val lineText = line.text
                when {
                    isCenteredHeading(lineText) ->
                        page.canvas.drawText(lineText.trim(), PAGE_WIDTH / 2f, y, centeredPaint)
                    lineText.isEmpty() -> { /* tylko przerwa akapitu, nic do narysowania */ }
                    !line.isParagraphEnd -> {
                        val words = lineText.split(" ")
                        val positions = justifiedPositions(words, maxWidth) { paint.measureText(it) }
                        words.forEachIndexed { i, w -> page.canvas.drawText(w, MARGIN + positions[i], y, paint) }
                    }
                    else -> page.canvas.drawText(lineText, MARGIN, y, paint)
                }
                y += if (lineText.isEmpty()) paragraphGap else lineHeight
            }
            document.finishPage(page)
        }
        document.writeTo(outputStream)
        document.close()
        DebugLogBuffer.log("PdfWriter", "Zapisano nowy PDF: ${text.length} znaków, ${pages.size} stron")
        DocumentWriteResult.Success
    } catch (e: Exception) {
        DebugLogBuffer.log("PdfWriter", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
        DocumentWriteResult.Error("${e.javaClass.simpleName}: ${e.message}")
    }
}
