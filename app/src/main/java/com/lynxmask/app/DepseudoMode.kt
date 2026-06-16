package com.lynxmask.app

// DepseudoMode.kt — v1.0
// Enum trybu ekranu DepseudonymizationScreen.
// Współdzielony między LibraryScreen.kt i DepseudonymizationScreen.kt.
// Osobny plik — nie zagnieżdżony w żadnym composable — żeby oba pliki importowały
// ten sam typ bez wzajemnej zależności.

enum class DepseudoMode {
    /** Odwróć maskowanie dokumentu źródłowego.
     *  Ładuje masked_text_enc + mapę tokenów z SessionStore automatycznie. */
    SOURCE_DOCUMENT,

    /** Odwróć maskowanie odpowiedzi AI.
     *  Użytkownik wkleja tekst z tokenami ręcznie. */
    AI_RESPONSE
}
