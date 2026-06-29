package com.lynxmask.app

/**
 * Routing zdjęcia/skanu — kolejność decyzji (ważne, nie zmieniać bez testów):
 * 1. wymuszone maskowanie pikseli → CARD
 * 2. dokument karta (dowód / legitymacja / dowód rej.) lub twarz → CARD
 * 3. mało tekstu (< 15 znaków) → CARD (brak tekstu do OCR)
 * 4. jakikolwiek tekst (≥ 15 znaków) bez markerów karty → PAGE (ścieżka OCR / pseudonimizacja)
 */

enum class ImageInputKind {
    /** Dowód, paszport, legitymacja, dowód rej. — maskowanie pikseli (twarz + PII). */
    CARD,
    /** Skan tekstu — ≥ 80 znaków OCR, brak markerów karty/twarzy → ścieżka pseudonimizacji. */
    PAGE,
    /** Zdjęcie z twarzą, mało tekstu — głównie twarze. */
    PHOTO,
    /** OCR 15–79 znaków, brak markerów dowodu — użytkownik wybiera. */
    AMBIGUOUS
}

/** Poniżej tego progu (< 15 znaków OCR) obraz bez markerów karty → maskowanie pikseli. */
internal const val MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE = 15

data class ImageRouteContext(
    val ocrCharCount: Int,
    val ocrText: String,
    val faceCount: Int,
    val forceImageRedact: Boolean = false
)

fun classifyImageInput(ctx: ImageRouteContext): ImageInputKind {
    if (ctx.forceImageRedact) return ImageInputKind.CARD

    val cardDoc = looksLikeCardDocument(ctx.ocrText)

    if (ctx.faceCount > 0 || cardDoc) return ImageInputKind.CARD

    if (ctx.ocrCharCount < MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE) {
        return ImageInputKind.CARD
    }

    return ImageInputKind.PAGE
}

/** Dowód osobisty, legitymacja, dowód rejestracyjny (nawet środek bez nagłówka). */
internal fun looksLikeCardDocument(ocrText: String): Boolean =
    looksLikeIdentityDocument(ocrText) || looksLikeVehicleRegistration(ocrText)

/** Zachowanie zgodne ze starym API (testy regresji). */
internal fun shouldRouteImageToTextPipeline(
    ocrCharCount: Int,
    forceImageRedact: Boolean,
    faceCount: Int = 0,
    identityDocument: Boolean = false,
    vehicleDocument: Boolean = false
): Boolean =
    classifyImageInput(
        ImageRouteContext(
            ocrCharCount = ocrCharCount,
            ocrText = when {
                identityDocument -> "dowod osobisty"
                vehicleDocument -> "VIN C.1.1 D.1 numer vin"
                else -> ""
            },
            faceCount = faceCount,
            forceImageRedact = forceImageRedact
        )
    ) == ImageInputKind.PAGE &&
        ocrCharCount >= MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE

internal fun looksLikeIdentityDocument(ocrText: String): Boolean {
    if (ocrText.isBlank()) return false
    val folded = foldOcrForMarkers(ocrText)
    val markers = listOf(
        "dowod osobist", "d.o.", "dow. os", "dowod os",
        "legitymac", "legitymacj", "school id", "student id", "student card",
        "identity card", "id card", "document no", "document number",
        "numer dowodu", "seria i numer", "nr dowodu",
        "rzeczpospolita polska", "republic of poland",
        "prawo jazdy", "driving licence", "driving license",
        "karta pobytu", "paszport", "passport", "residence permit"
    )
    return markers.any { folded.contains(it) }
}

internal fun looksLikeVehicleRegistration(ocrText: String): Boolean {
    if (ocrText.isBlank()) return false
    val folded = foldOcrForMarkers(ocrText)
    val compact = folded.replace(Regex("""\s+"""), "")

    val markers = listOf(
        "dowod rejestracyjny", "dowod rej", "certyfikat rejestracji",
        "registration certificate", "certificate of registration",
        "numer vin", "numer identyfikacyjny pojazdu", "identification number",
        "tablica rejestracyjna", "nr rejestracyjny", "nr rej", "n rej",
        "c.1.1", "c.1.2", "c.1.3", "c1.1", "c1.2", "c1.3",
        "d.1", "d.2", "d.3",
        "e vin", "marka model", "rodzaj pojazdu", "kategoria pojazdu",
        "pojemnosc silnika", "masa wlasna", "dmc",
        "data pierwszej rejestracji", "rok produkcji",
        "wlasciciel", "wladajacy", "właściciel", "władający"
    )
    if (markers.any { folded.contains(it) }) return true

    if (containsPlausibleVin(compact)) return true

    return false
}

/** VIN = 17 znaków A–H J–N P–R Z + cyfry; min. 4 cyfry; min. 3 litery (odróżnia od IBAN PL+15cyfr). */
private fun containsPlausibleVin(compact: String): Boolean {
    if (compact.length < 17) return false
    val vinChars = Regex("""[A-HJ-NPR-Z0-9]""", RegexOption.IGNORE_CASE)
    for (i in 0..compact.length - 17) {
        val candidate = compact.substring(i, i + 17).uppercase()
        if (candidate.all { it == candidate[0] }) continue
        if (candidate.count { it.isDigit() } < 4) continue
        if (candidate.count { it.isLetter() } < 3) continue
        if (!candidate.all { vinChars.matches(it.toString()) }) continue
        return true
    }
    return false
}

private fun foldOcrForMarkers(ocrText: String): String =
    ocrText.lowercase()
        .replace('ł', 'l').replace('ó', 'o').replace('ą', 'a')
        .replace('ę', 'e').replace('ś', 's').replace('ź', 'z')
        .replace('ż', 'z').replace('ć', 'c').replace('ń', 'n')

fun redactionProfileFor(kind: ImageInputKind, ocrText: String): RedactionProfile = when {
    looksLikeVehicleRegistration(ocrText) -> RedactionProfile.VEHICLE_REG
    kind == ImageInputKind.CARD || looksLikeIdentityDocument(ocrText) -> RedactionProfile.IDENTITY_CARD
    else -> RedactionProfile.GENERAL
}

enum class RedactionProfile {
    IDENTITY_CARD,
    VEHICLE_REG,
    GENERAL
}
