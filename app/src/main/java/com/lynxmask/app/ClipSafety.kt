package com.lynxmask.app

/** Placeholder w schowku zamiast fragmentu wykrytego przez OutputGuard RED a pominiętego przez silnik. */
internal const val CLIP_REDACTED_PLACEHOLDER = "[UKRYTE]"

/**
 * Tekst bezpieczny do wklejenia ze schowka — pseudonimizowany + RED hity zastąpione placeholderem.
 */
internal fun clipSafeMaskedText(result: PseudonymResult): String {
    var text = result.pseudonymizedText
        .removePrefix("SESJA_${result.sessionId}\n")
        .ifBlank { result.pseudonymizedText }
    result.guardHits
        .filter { it.level == "RED" && it.matchedText.isNotBlank() }
        .distinctBy { it.matchedText }
        .sortedByDescending { it.matchedText.length }
        .forEach { hit ->
            text = text.replace(hit.matchedText, CLIP_REDACTED_PLACEHOLDER)
        }
    return text
}

/** Schowek uznany za czysty — brak flag, brak RED Guard, risk GREEN. */
internal fun isClipboardClean(result: PseudonymResult): Boolean =
    result.flags.isEmpty()
        && result.riskScore == RiskScore.GREEN
        && result.guardHits.none { it.level == "RED" }
