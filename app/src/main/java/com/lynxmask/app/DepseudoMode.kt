package com.lynxmask.app

// DepseudoMode.kt — v1.1
// Enum trybu ekranu DepseudonymizationScreen.
// Współdzielony między LibraryScreen.kt i DepseudonymizationScreen.kt.
// Osobny plik — nie zagnieżdżony w żadnym composable — żeby oba pliki importowały
// ten sam typ bez wzajemnej zależności.

enum class DepseudoMode {
    /** Odwróć maskowanie dokumentu źródłowego. */
    SOURCE_DOCUMENT,

    /** Odwróć maskowanie odpowiedzi AI — użytkownik wkleja tekst z tokenami. */
    AI_RESPONSE,

    /** Podgląd zamaskowanego tekstu bez odwracania — „Podgląd zamaskowanego” w bibliotece. */
    MASKED_VIEW;

    fun screenTitle(fromLibrary: Boolean): String = when (this) {
        MASKED_VIEW     -> "Podgląd zamaskowanego"
        SOURCE_DOCUMENT -> "Przywróć oryginał"
        AI_RESPONSE     -> if (fromLibrary) "Dodaj odpowiedź AI" else "Odpowiedź AI"
    }
}
