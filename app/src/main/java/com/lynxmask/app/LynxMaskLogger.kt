package com.lynxmask.app

import android.util.Log

/**
 * LynxMaskLogger.kt
 * Centralny logger dla debugowania pipeline pseudonimizacji.
 * W release buildzie wszystkie logi są wyłączone automatycznie.
 */
object LynxMaskLogger {

    private const val TAG_OCR = "LynxMask_OCR"
    private const val TAG_NORM = "LynxMask_NORM"
    private const val TAG_ENGINE = "LynxMask_ENGINE"
    private const val TAG_GUARD = "LynxMask_GUARD"
    private const val TAG_UI = "LynxMask_UI"

    private val isDebug = BuildConfig.DEBUG  // sesja 8: nie hardcoded — PII nie leci do Logcata w release

    // ============================================================
    // OCR
    // ============================================================
    fun ocrRawText(text: String) {
        if (!isDebug) return
        Log.d(TAG_OCR, "=== RAW OCR TEXT (${text.length} znaków) ===")
        // Dzielimy na linie żeby nie uciąć w logcat
        text.lines().forEachIndexed { i, line ->
            Log.d(TAG_OCR, "L${i+1}: $line")
        }
    }

    fun ocrEmpty() {
        if (!isDebug) return
        Log.w(TAG_OCR, "OCR zwrócił pusty tekst")
    }

    fun ocrError(error: String) {
        Log.e(TAG_OCR, "Błąd OCR: $error")
    }

    // ============================================================
    // Normalizacja OCR
    // ============================================================
    fun normInput(text: String) {
        if (!isDebug) return
        Log.d(TAG_NORM, "=== WEJŚCIE NORMALIZACJI (${text.length} znaków) ===")
        Log.d(TAG_NORM, text.take(500)) // Pierwsze 500 znaków
    }

    fun normOutput(text: String, corrections: List<String>) {
        if (!isDebug) return
        Log.d(TAG_NORM, "=== WYJŚCIE NORMALIZACJI (${text.length} znaków) ===")
        Log.d(TAG_NORM, text.take(500))
        if (corrections.isEmpty()) {
            Log.d(TAG_NORM, "Brak korekt OCR")
        } else {
            Log.d(TAG_NORM, "Korekty (${corrections.size}):")
            corrections.forEach { Log.d(TAG_NORM, "  → $it") }
        }
    }

    // ============================================================
    // Engine pseudonimizacji
    // ============================================================
    fun engineStart(textLength: Int, dictSize: Int) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "=== START PSEUDONIMIZACJI ===")
        Log.d(TAG_ENGINE, "Tekst: $textLength znaków, Słownik: $dictSize wpisów")
    }

    fun engineTokenFound(token: String, original: String, source: String) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "TOKEN: $token ← \"${original.take(50)}\" [$source]")
    }

    fun engineResult(text: String, tokenCount: Int, flagCount: Int, riskScore: String) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "=== WYNIK PSEUDONIMIZACJI ===")
        Log.d(TAG_ENGINE, "Tokenów: $tokenCount, Flag: $flagCount, Risk: $riskScore")
        Log.d(TAG_ENGINE, "Tekst wyjściowy (pierwsze 500):")
        Log.d(TAG_ENGINE, text.take(500))
    }

    fun engineNoTokens() {
        if (!isDebug) return
        Log.w(TAG_ENGINE, "UWAGA: Pseudonimizacja nie znalazła żadnych encji")
        Log.w(TAG_ENGINE, "Sprawdź czy PseudonymEngine jest wywoływany z prawidłowym tekstem")
    }

    fun enginePatternMatch(pattern: String, match: String) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "REGEX HIT [$pattern]: \"${match.take(30)}\"")
    }

    fun enginePatternMiss(description: String, text: String) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "REGEX MISS [$description]: \"${text.take(30)}\"")
    }

    fun engineWhiteList(word: String) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "BIAŁA LISTA: \"$word\" → zostaje")
    }

    fun engineFlag(fragment: String, reason: String) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "FLAGA: \"${fragment.take(40)}\" → $reason")
    }

    // ============================================================
    // Output Guard
    // ============================================================
    fun guardWarning(label: String, match: String) {
        if (!isDebug) return
        Log.w(TAG_GUARD, "GUARD WARNING [$label]: \"${match.take(30)}\"")
    }

    fun guardClean() {
        if (!isDebug) return
        Log.d(TAG_GUARD, "Output guard: brak ostrzeżeń")
    }

    // ============================================================
    // UI
    // ============================================================
    fun uiCopied(textLength: Int) {
        if (!isDebug) return
        Log.d(TAG_UI, "Skopiowano do schowka: $textLength znaków")
    }

    fun uiTokenRevealed(token: String) {
        if (!isDebug) return
        Log.d(TAG_UI, "Token odkryty przez użytkownika: $token")
    }

    fun uiFlagAction(fragment: String, action: String) {
        if (!isDebug) return
        Log.d(TAG_UI, "Flaga: \"${fragment.take(30)}\" → $action")
    }

    // ============================================================
    // Diagnostyka pipeline — wywołaj gdy podejrzewasz że
    // pseudonimizacja nie działa
    // ============================================================
    fun diagnosePipeline(rawText: String, result: PseudonymResult) {
        if (!isDebug) return
        Log.d(TAG_ENGINE, "=== DIAGNOSTYKA PIPELINE ===")
        Log.d(TAG_ENGINE, "Wejście: ${rawText.length} znaków")
        Log.d(TAG_ENGINE, "Wyjście: ${result.pseudonymizedText.length} znaków")
        Log.d(TAG_ENGINE, "Czy tekst się zmienił: ${rawText != result.pseudonymizedText}")
        Log.d(TAG_ENGINE, "Liczba tokenów: ${result.tokenMap.size}")
        Log.d(TAG_ENGINE, "Tokeny:")
        result.tokenMap.forEach { (token, original) ->
            Log.d(TAG_ENGINE, "  $token = \"${original.take(40)}\"")
        }
        Log.d(TAG_ENGINE, "Flagi (${result.flags.size}):")
        result.flags.forEach { flag ->
            Log.d(TAG_ENGINE, "  → \"${flag.fragment.take(40)}\" : ${flag.reason}")
        }
        Log.d(TAG_ENGINE, "Risk Score: ${result.riskScore}")
        if (result.tokenMap.isEmpty()) {
            Log.e(TAG_ENGINE, "PROBLEM: Brak tokenów — silnik nie wykrył żadnych encji!")
            Log.e(TAG_ENGINE, "Sprawdź: 1) Czy OcrNormalizer zwraca tekst  2) Czy STRUCTURAL_PATTERNS działają  3) Czy tekst wejściowy jest niepusty")
        }
    }
}
