package com.lynxmask.app

// DebugLogBuffer.kt — v1.1 (Potok RODO, 10.06.2026)
//
// ZMIANY v1.1:
//   [RODO] logPseudonymResult(): usunięto logowanie oryginalnych wartości tokenów.
//          Linie: pętla tokenMap — "$token → \"$original\"" zastąpione
//          agregacją po typach tokenów (bez oryginalnych wartości).
//          Powód: original = PII (nazwisko, adres, NIP) — nie może trafić do schowka.
//
//   [RODO] logPseudonymResult(): usunięto logowanie flag.fragment.
//          Linia: "$typ #${i+1}: \"${flag.fragment}\"" → tylko flag.reason.
//          Powód: fragment = wycinki tekstu źródłowego = potencjalne PII.
//
//   [RODO] logOcrAnalysis() sekcja [1] RAW OCR: usunięto logowanie treści tekstu.
//          Linie: rawText.lines().take(30) → tylko metadane (długość, liczba linii).
//          Powód: rawText zawiera surowe PII z dokumentu.
//
//   [RODO] logOcrAnalysis() sekcja [2] NORMALIZACJA: usunięto logowanie
//          znormalizowanego tekstu. Zostaje tylko liczba poprawek.
//          Powód: norm.normalizedText = PII po lekkiej normalizacji OCR.
//
//   [RODO] logOcrAnalysis() sekcja [3] TOKENY: usunięto logowanie oryginalnych
//          wartości. "$token → \"$original\"" → tylko "$token".
//          Powód: original = PII.
//
//   [RODO] logOcrAnalysis() sekcja [4] FLAGI: usunięto flag.fragment.
//          Pozostaje tylko typ i flag.reason.
//          Powód: fragment = wycinek tekstu = potencjalne PII.
//
//   [RODO] logOcrAnalysis() sekcja [5] TEKST WYJŚCIOWY: ZACHOWANA bez zmian.
//          Tekst wyjściowy jest pseudonimizowany — nie zawiera PII.
//
//   [NOWY] clearOnExit() — wywołać w MainActivity.onStop() lub onDestroy().
//
// ZACHOWANE BEZ ZMIAN:
//   log(), getAll(), clear(), size(), logcat output — bez modyfikacji.
//   Wartość diagnostyczna: typy i liczby tokenów, powody flag, RiskScore,
//   OCR quality, liczba linii i znaków tekstu, poprawki normalizacji.

import android.util.Log

/**
 * DebugLogBuffer — bufor logów w RAM do debugowania na telefonie.
 *
 * Problem który rozwiązuje:
 *   - FLAG_SECURE blokuje screenshoty
 *   - Logcat wymaga kabla USB i Android Studio
 *   - Claude potrzebuje tekstu, nie obrazu
 *
 * Rozwiązanie:
 *   - Silnik pisze logi tutaj podczas analizy
 *   - Przycisk "Kopiuj logi" w ResultScreen kopiuje do schowka
 *   - Paweł wkleja tekst bezpośrednio do chatu z Claude
 *
 * Właściwości:
 *   - Ring buffer max MAX_ENTRIES wpisów (starsze wypadają)
 *   - Thread-safe (synchronized)
 *   - Dane tylko w RAM — znikają przy zamknięciu aplikacji
 *   - Pisze też do Logcat równolegle (tag LynxMask_Debug)
 *
 * POLITYKA RODO:
 *   Bufor nie przechowuje treści dokumentów ani oryginalnych wartości tokenów.
 *   Logowane są wyłącznie metadane: liczby, typy, powody flag, statusy.
 */
object DebugLogBuffer {

    private const val MAX_ENTRIES = 400
    private const val LOGCAT_TAG = "LynxMask_Debug"

    private val buffer = ArrayDeque<String>()

    /** Dodaj wpis do bufora. Wywołuj z DebugLogBuffer.log(...) */
    @Synchronized
    fun log(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val ts = System.currentTimeMillis() % 1_000_000  // ostatnie 6 cyfr timestamp
        val entry = "$ts [$tag] $message"
        buffer.addLast(entry)
        if (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        Log.d(LOGCAT_TAG, entry)
    }

    /** Zwraca cały bufor jako jeden string gotowy do skopiowania */
    @Synchronized
    fun getAll(): String = buffer.joinToString("\n")

    /** Czyści bufor przed nową analizą */
    @Synchronized
    fun clear() = buffer.clear()

    /** Liczba wpisów w buforze */
    @Synchronized
    fun size(): Int = buffer.size

    /**
     * Czyści bufor przy zamknięciu aplikacji.
     * Wywołaj w MainActivity.onStop() lub onDestroy().
     * [RODO] Zapewnia że RAM nie przechowuje logów po zakończeniu sesji.
     */
    fun clearOnExit() = clear()

    /**
     * Loguje pełny wynik PseudonymEngine — wywoływane po każdej analizie.
     *
     * [RODO v1.1] Loguje tylko:
     *   - Łączną liczbę tokenów
     *   - Typy tokenów i ich liczby (OSOBA: 2, FIRMA: 1, …)
     *   - Powody flag (BEZ fragmentów tekstu dokumentu)
     *   - RiskScore i OCR quality warning
     *
     * NIE loguje oryginalnych wartości tokenów (nazwisk, adresów, NIP-ów).
     */
    fun logPseudonymResult(result: PseudonymResult, source: String = "?") {
        log("Engine", "════ WYNIK [$source] ════")
        log("Engine", "Tokeny zamaskowane: ${result.tokenMap.size}")

        // Agregacja per typ — BEZ oryginalnych wartości (RODO)
        result.tokenMap.keys
            .filter { !it.startsWith("SESJA") }
            .groupBy { it.substringBefore("_") }
            .entries
            .sortedBy { it.key }
            .forEach { (type, tokens) ->
                log("Engine", "  $type: ${tokens.size} token(s)")
            }

        log("Engine", "Flagi do sprawdzenia: ${result.flags.size}")
        result.flags.forEachIndexed { i, flag ->
            val typ = if (flag.isContextual) "KONTEKST" else "FLAGA"
            // [RODO] flag.fragment usunięty — zawiera wycinki tekstu dokumentu
            log("Engine", "  $typ #${i+1}: ${flag.reason}")
        }
        log("Engine", "RiskScore: ${result.riskScore}")
        result.qualityWarning?.let { log("Engine", "OCR warning: $it") }
        log("Engine", "════════════════════════")
    }

    /**
     * Raport OCR do wklejenia do Claude — diagnostyka techniczna BEZ PII.
     *
     * [RODO v1.1] Loguje wyłącznie:
     *   [1] Metadane tekstu: długość w znakach i liczba linii
     *   [2] Liczba poprawek OCR (1→l, 0→o itd.)
     *   [3] Nazwy tokenów (OSOBA_001, FIRMA_002…) BEZ oryginalnych wartości
     *   [4] Typy i powody flag BEZ fragmentów tekstu źródłowego
     *   [5] Pseudonimizowany tekst wyjściowy (PII zastąpione tokenami — bezpieczny)
     *
     * Wywołaj "Kopiuj logi" w aplikacji i wklej wynik do Claude.
     */
    @Synchronized
    fun logOcrAnalysis(rawText: String, result: PseudonymResult) {
        // Normalizacja potrzebna wyłącznie dla liczby poprawek w sekcji [2].
        // Uwaga: podwójna normalizacja — silnik już to wykonał przy pseudonimizacji.
        // Refaktor (przyszły): przyjąć NormalizationResult jako parametr.
        val norm = OcrNormalizer.normalize(rawText)
        val sessionId = result.sessionId

        log("OCR", "")
        log("OCR", "╔══ RAPORT OCR [SESJA: $sessionId] ══╗")

        // [1] Metadane tekstu — BEZ treści (RODO)
        log("OCR", "")
        log("OCR", "[1] RAW OCR: ${rawText.length} znaków, ${rawText.lines().size} linii")
        // Treść tekstu usunięta — rawText zawiera surowe PII z dokumentu.

        // [2] Normalizacja — tylko liczba poprawek, BEZ znormalizowanego tekstu
        log("OCR", "")
        if (norm.corrections > 0) {
            log("OCR", "[2] NORMALIZACJA: ${norm.corrections} poprawek OCR (1→l, 0→o, |→l, spacja w ul.)")
        } else {
            log("OCR", "[2] NORMALIZACJA: brak poprawek (tekst wyglądał czysto)")
        }
        // Treść znormalizowanego tekstu usunięta — zawiera PII.

        // [3] Nazwy tokenów — BEZ oryginalnych wartości (RODO)
        log("OCR", "")
        log("OCR", "[3] ZAMASKOWANE: ${result.tokenMap.size} tokenów")
        if (result.tokenMap.isEmpty()) {
            log("OCR", "    !! BRAK TOKENÓW — silnik nic nie wykrył !!")
        } else {
            result.tokenMap.keys
                .filter { !it.startsWith("SESJA") }
                .sorted()
                .forEach { token ->
                    // [RODO] Logujemy tylko token, NIE oryginalną wartość
                    log("OCR", "    $token")
                }
        }

        // [4] Flagi — tylko typ i powód, BEZ fragmentów tekstu (RODO)
        log("OCR", "")
        if (result.flags.isNotEmpty()) {
            log("OCR", "[4] FLAGI DO SPRAWDZENIA: ${result.flags.size}")
            result.flags.forEach { flag ->
                val typ = if (flag.isContextual) "KONTEKST" else "FLAGA"
                // [RODO] flag.fragment usunięty — wycinek tekstu = potencjalne PII
                log("OCR", "    ▶ [$typ] ${flag.reason}")
            }
        } else {
            log("OCR", "[4] FLAGI: brak")
        }

        // [5] Pseudonimizowany tekst wyjściowy — ZACHOWANY (PII zastąpione tokenami)
        log("OCR", "")
        log("OCR", "[5] RISK: ${result.riskScore}" +
            (result.qualityWarning?.let { " | JAKOŚĆ OCR: $it" } ?: " | jakość OCR: OK"))
        log("OCR", "    Tekst wyjściowy (pseudonimizowany):")
        val displayText = result.pseudonymizedText.removePrefix("SESJA_${sessionId}\n")
        displayText.lines().take(30).forEachIndexed { i, line ->
            log("OCR", "    ${(i+1).toString().padStart(2)}: $line")
        }
        if (displayText.lines().size > 30) {
            log("OCR", "    ... (${displayText.lines().size} linii łącznie)")
        }

        log("OCR", "")
        log("OCR", "╚══ KONIEC RAPORTU ══╝")
        log("OCR", "")
    }
}
