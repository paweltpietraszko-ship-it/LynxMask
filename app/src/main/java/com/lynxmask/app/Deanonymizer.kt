package com.lynxmask.app

// Deanonymizer.kt — v1.0
//
// Logika odtwarzania oryginalnego tekstu z tokenów pseudonimizacji.
// Odpowiednik restoreText() z Pseudominizer/Depseudonimizuj.tsx v1.5 [BUG-6].
//
// ARCHITEKTURA NA PRZYSZŁOŚĆ (Potok 7):
//   Po zmianie formatu tokenów z OSOBA_001 na OSOBA_A4X_001 (session suffix)
//   detectSessionId() będzie szukał suffixu w tokenach zamiast SESJA_XXXXXX.
//   Zmiana w jednym miejscu — detectSessionId() i SESJA_PATTERN.
//   DepseudonymizationScreen i reszta kodu nie wymagają zmian.
//
// UWAGA: restore() iteruje po wpisach mapy — kolejność zależy od implementacji Map.
//   W Kotlinie LinkedHashMap (domyślny) zachowuje kolejność wstawiania.
//   Tokeny nie nakładają się na siebie (FIRMA_001 ≠ FIRMA_0011 bo 3-cyfrowe)
//   więc kolejność nie ma znaczenia dla poprawności.

object Deanonymizer {

    // Wzorzec SESJA_ — jedyne miejsce z hardcoded formatem.
    // Capture group 1 = sam identyfikator (np. "45E188"), BEZ prefiksu "SESJA_".
    // Ważne: DB przechowuje "45E188" (result.sessionId), nie "SESJA_45E188".
    // Po Potoku 7: zamienić na wzorzec suffixu tokenów.
    private val SESJA_PATTERN = Regex("SESJA_([A-Z0-9]{6})")

    /**
     * Odtwarza oryginalny tekst przez zamianę tokenów na oryginały z mapy.
     *
     * Odpowiednik restoreText() z Depseudonimizuj.tsx [BUG-6]:
     *   iteruje po kluczach mapy zamiast TOKEN_MATCH regex —
     *   bezpieczniejsze gdy wartości zawierają znaki specjalne.
     *
     * @param pseudonymizedText tekst z tokenami (np. "Firma FIRMA_001 zleca...")
     * @param tokenMap mapa token → oryginał (z SessionStore.loadTokenMap)
     * @return tekst z przywróconymi oryginałami
     */
    fun restore(pseudonymizedText: String, tokenMap: Map<String, String>): String {
        var result = pseudonymizedText
        for ((token, original) in tokenMap) {
            result = result.replace(token, original)
        }
        return result
    }

    /**
     * Wykrywa identyfikator sesji SESJA_XXXXXX w tekście.
     *
     * Używane w DepseudonymizationScreen do autodetektu — użytkownik wkleja
     * odpowiedź AI i sesja jest identyfikowana automatycznie.
     *
     * @return pierwsze dopasowanie SESJA_[A-Z0-9]{6} lub null jeśli brak
     */
    fun detectSessionId(text: String): String? = SESJA_PATTERN.find(text)?.groupValues?.get(1)
}
