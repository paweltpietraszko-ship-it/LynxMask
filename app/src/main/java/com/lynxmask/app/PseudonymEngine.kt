package com.lynxmask.app

/**
 * PseudonymEngine.kt — Silnik pseudonimizacji LynxMask v2.2
 *
 * Zmiany v2.1 (sesja 7):
 *   - GUARD: weryfikacja LookupTables.initialized przed uruchomieniem;
 *             debug → wyjątek, release → log + degraded mode
 *   - ADDR-FIX: address pre-processing regex — \s → [^\S\n] w nazwie ulicy
 *   - DICT-FIX: Warstwa 1 — String.replace() → regex z lookbehind/lookahead
 *                (\b nie obsługuje polskich diakrytyków; "Jan" nie podmienia
 *                 "Janusz" ani "Jański")
 *   - P1-INFRA: token sesji SESJA_{6xALPHANUM} dodany do tekstu przed
 *                Warstwą 1; sessionId wystawiony w PseudonymResult.
 *                UWAGA: warstwa storage (SQLCipher sessions table) — P1 Phase 2
 *
 * Zmiany v2.2 (sesja 7):
 *   - DEAD-CODE: Detection/DetectionConfidence/DetectionSource usunięte —
 *                potwierdzone martwy kod po przejrzeniu wszystkich plików silnika
 *
 * Architektura (kolejność wykonania):
 *   Warstwa 0:   OcrNormalizer (normalizacja przed detekcją)
 *   === RUNDA 1 ===
 *   Warstwa 2:   Regex strukturalne → StructuralEngine.kt
 *   Warstwa 3:   Czarna lista kontekstowa + propagacja → NameEngine.kt
 *   Warstwa 3d:  Wzorce adresów (po NameEngine)
 *   === ZBIERACZE RESZTEK ===
 *   Warstwa 4a:  Słownik użytkownika (zbieracz resztek)
 *   Warstwa 4b:  AnchorEngine (zbieracz resztek kotwicowy) → AnchorEngine.kt
 *   === RUNDA 2 — ten sam assignToken, te same liczniki ===
 *   Warstwa 2':  Regex strukturalne (runda 2 — resztki po AnchorEngine)
 *   Warstwa 3':  Czarna lista kontekstowa (runda 2)
 *   Warstwa 3d': Wzorce adresów (runda 2)
 *   === FINALIZACJA ===
 *   Warstwa 5:   Detekcja algorytmiczna (TYLKO FLAGI) → NameEngine.kt
 *   Warstwa 6:   Output Guard + Risk Score → OutputGuard.kt
 *
 * Tokeny zgodne z Triangulum:
 *   FIRMA_{nnn}, OSOBA_{nnn}, NUMER_{nnn}, KWOTA_{nnn}, ADRES_{nnn}
 */

// ============================================================
// Stałe tokenów — zgodne z Triangulum
// ============================================================
internal const val TOKEN_FIRMA = "FIRMA"
internal const val TOKEN_OSOBA = "OSOBA"
internal const val TOKEN_NUMER = "NUMER"
internal const val TOKEN_EMAIL = "EMAIL"
internal const val TOKEN_KWOTA = "KWOTA"
internal const val TOKEN_ADRES = "ADRES"

// ============================================================
// Data classes
// ============================================================
// Detection, DetectionConfidence, DetectionSource — usunięte (martwy kod).
// Nie były używane w żadnym pliku silnika. Usunięto po weryfikacji
// StructuralEngine.kt, LookupTables.kt, UserDictionary.kt.
// Jeśli UI files ich używają — przywróć i oznacz jako TODO do integracji.

data class PseudonymResult(
    val pseudonymizedText: String,
    val sessionId: String,                  // P1-INFRA: identyfikator sesji do depseudonimizacji
    val tokenMap: Map<String, String>,      // token → oryginał (RAM only)
    val flags: List<PseudonymFlag>,          // miejsca do sprawdzenia przez użytkownika
    val riskScore: RiskScore,
    val qualityWarning: String?,            // ostrzeżenie jakości OCR
    val guardHits: List<GuardHit> = emptyList(),  // wycieki wykryte przez OutputGuard
    val trace: List<DetectionTrace> = emptyList(),
    val tokenLayers: Map<String, String> = emptyMap()  // token → layer, tylko DEBUG (AddressEngine v0 diagnostyka)
)

data class PseudonymFlag(
    val fragment: String,
    val reason: String,
    val isContextual: Boolean = false       // true = identyfikacja przez rolę
)

enum class RiskScore { GREEN, YELLOW, RED }

data class DetectionTrace(
    val layer: String,
    val rule: String,
    val matchedText: String,
    val token: String
)

// ============================================================
// Regex TOKEN — do wykrywania istniejących tokenów
// ============================================================
internal val TOKEN_RE = Regex("""\b(FIRMA|OSOBA|NUMER|EMAIL|KWOTA|ADRES)_(\d{3})(?!\d)""")

// Sprawdza czy dopasowanie NAPRAWDĘ nakłada się zakresem na istniejący token — nie
// przybliżone okno znaków (to dawało false positive: token na POPRZEDNIEJ linii
// blokował niepowiązany match na NASTĘPNEJ linii, gdy okno było szersze niż odległość
// do najbliższego \n — BUG-TOKEN-AMPUTACJA fix v2, po tym jak proste rozszerzenie
// okna do -10 znaków naprawiło jeden przypadek ale zepsuło sąsiedni, Cursor+Claude 01.07).
// Właściwe sprawdzenie: znajdź WSZYSTKIE istniejące tokeny w tekście, porównaj rzeczywiste
// zakresy. Łapie zarówno dopasowanie zaczynające się W ŚRODKU tokenu (np. "039 49 999,99
// z1" zaczynające się w cyfrach "NUMER_039"), jak i dopasowanie będące samym PREFIKSEM
// tokenu (np. "ADRES" przy "ADRES_004" — historyczny BUG-OGONY), bez fałszywego blokowania
// niepowiązanych dopasowań które tylko leżą blisko (inna linia, inny fragment tekstu).
internal fun matchOverlapsToken(text: String, range: IntRange): Boolean {
    return TOKEN_RE.findAll(text).any { it.range.first <= range.last && range.first <= it.range.last }
}

// ============================================================
// Normalizacja canonical — z Triangulum [V4-2]
// ============================================================
private fun canonicalValue(value: String): String =
    value.replace(Regex("""[\s\-]"""), "")

// ============================================================
// GŁÓWNY SILNIK
// ============================================================
object PseudonymEngine {

    fun pseudonymize(
        rawText: String,
        userDictionary: List<Pair<String, String>> = emptyList(),
        guardAllowlist: List<Pair<String, String>> = emptyList(),
        mlKitConfidence: Float? = null,
        profileType: String = "general",  // z onboardingu
        traceMode: Boolean = false
    ): PseudonymResult {

        val traceLog = mutableListOf<DetectionTrace>()

        // --- Warstwa 0: Normalizacja OCR ---
        val normResult = OcrNormalizer.normalize(rawText)
        val quality = OcrNormalizer.assessQuality(normResult.normalizedText, mlKitConfidence)
        var text = normResult.normalizedText

        // --- GUARD: weryfikacja inicjalizacji LookupTables ---
        // Lazy regex w NameEngine.kt kompilują się przy pierwszym użyciu.
        // Jeśli LookupTables nie są gotowe — skompilują się z fallbackiem
        // 200 imion i NIGDY nie zostaną przebudowane (lazy = raz na zawsze).
        if (!LookupTables.initialized) {
            if (BuildConfig.DEBUG) {
                error("PseudonymEngine: LookupTables.initialize(context) musi być "
                    + "wywołane przed pierwszym pseudonymize(). Silnik w trybie "
                    + "awaryjnym — 200 imion zamiast 1874.")
            } else {
                android.util.Log.e("PseudonymEngine",
                    "DEGRADED MODE: LookupTables nie zainicjowane. "
                    + "Detekcja imion ograniczona do 200 fallback names.")
            }
        }

        // --- Pre-processing: rozdzielanie emaili sklejonych (wielokrotne @ bez spacji) ---
        // OcrNormalizer krok 0b może sklejać emaile z osobnych linii dokumentu.
        // Jeśli w ciągu bez spacji/newline jest więcej niż jedno @, wstawiamy \n po TLD.
        // Lista TLD zamiast [a-z]{2,6} — zapobiega backtrackowi na .gov → .pl split
        run {
            val tlds = "pl|com|net|org|eu|gov|info|biz|de|uk|fr|it|nl|be|at|cz|sk|hu|ro|io|co|me|edu"
            text = Regex("""\.(?:$tlds)(?=[a-zA-Z0-9][^\s@\n]*@)""").replace(text) { m -> m.value + "\n" }
        }

        // --- Pre-processing: naprawa emaili z błędami OCR ---
        // anna. nowak(@wp.pl  → anna.nowak@wp.pl
        // robert.jablonski@ finanse24.pl → robert.jablonski@finanse24.pl
        text = text.replace("(@", "@")                                              // (@ → @
        // ADDR-EMAIL-FIX: lookbehind (?<=[a-z0-9]{2}) wyklucza 2-literowe skróty adresowe
        // (al., ul., pl., os.) — bez niego "al. Jerozolimskie" → "al.Jerozolimskie"
        // i AddressEngine STREET_NO_ZIP ([^\S\n]+ po skrócie) nie może dopasować.
        //
        // BUG-SKLEJANIE-AKAPITOW-FIX (09.07, diagnoza Cursor traceMode na "Wizja Caude 2.docx"):
        // \s+ we wszystkich trzech regexach obejmowało \n — na czystym DOCX (bez żadnej
        // degradacji OCR do naprawienia) sklejało koniec zdania z początkiem NASTĘPNEGO
        // akapitu ("ludzi.\nMimo" → "ludzi.Mimo"), co potem dawało AnchorEngine A.8 (SYGNATURA,
        // [^\n]+) fałszywie długi zasięg przez kilka akapitów i psuło eksport DOCX
        // (MissingTokens — tokenMap odnosił się do sklejonego tekstu, nie surowego artefaktu).
        // [^\S\n]+ zamiast \s+ — ten sam wzorzec co wcześniejsze fixy cross-newline w tym
        // tygodniu (KWOTA, EMAIL, kotwice keyword). Intencja tych trzech regexów (sklejanie
        // maila rozbitego spacją NA TEJ SAMEJ linii) zostaje w pełni zachowana.
        text = Regex("""(?<=[a-z0-9]{2})([a-z0-9])\.[^\S\n]+([a-z0-9])""", RegexOption.IGNORE_CASE) // anna. nowak → anna.nowak
            .replace(text) { m -> "${m.groupValues[1]}.${m.groupValues[2]}" }
        text = Regex("""@[^\S\n]+([a-z0-9])""", RegexOption.IGNORE_CASE)               // @ wp → @wp
            .replace(text) { m -> "@${m.groupValues[1]}" }
        text = Regex("""([a-z0-9])[^\S\n]+@([a-z0-9])""", RegexOption.IGNORE_CASE)    // abc @wp → abc@wp
            .replace(text) { m -> "${m.groupValues[1]}@${m.groupValues[2]}" }

        // --- Pre-processing: naprawa adresu podzielonego przez newline OCR ---
        // "ul. Kazimierza Wielkiego\n14/3" → "ul. Kazimierza Wielkiego 14/3"
        // Bez tego regex ADRES nie łączy nazwy ulicy z numerem budynku.
        // ADDR-FIX v2.1: [^\S\n] zamiast \s w nazwie ulicy — \s przepuszczał \n
        // i mógł zszywać fragmenty z różnych akapitów
        text = Regex(
            """((?:ul|al|pl|os)\.[^\S\n][A-ZŁŚŹĆŃĄĘÓŻ][a-ząćęłńóśźża-zA-Z[^\S\n]\-]{2,50})\n(\d{1,4}[A-Za-z]?(?:/\d{1,4}[A-Za-z]?)?)""",
            RegexOption.IGNORE_CASE
        ).replace(text) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }

        // --- Struktury danych sesji ---
        val tokenMap = mutableMapOf<String, String>()
        val tokenLayers = mutableMapOf<String, String>()  // AddressEngine v0 diagnostyka, tylko DEBUG
        val reverseMap = mutableMapOf<String, String>()
        val counters = mutableMapOf<String, Int>()
        val flags = mutableListOf<PseudonymFlag>()

        fun assignToken(value: String, tokenType: String, layer: String = "UNKNOWN", rule: String = "UNKNOWN"): String {
            val canonical = canonicalValue(value)
            reverseMap[canonical]?.let { return it }
            val count = (counters[tokenType] ?: 0) + 1
            counters[tokenType] = count
            val token = "${tokenType}_${count.toString().padStart(3, '0')}"
            tokenMap[token] = value
            reverseMap[canonical] = token
            if (BuildConfig.DEBUG) {
                tokenLayers[token] = layer
            }
            if (traceMode) {
                traceLog.add(DetectionTrace(layer = layer, rule = rule, matchedText = value, token = token))
            }
            // TODO-1: debug log owinięty w BuildConfig.DEBUG — nie wycieka PII do logcata w release
            if (tokenType == TOKEN_OSOBA && BuildConfig.DEBUG) {
                android.util.Log.w("PSE_OSOBA", "$token → \"$value\"  [${Thread.currentThread().stackTrace.getOrNull(3)?.methodName}]")
            }
            return token
        }

        // --- P1-INFRA: Token sesji ---
        // SESJA_{6xALPHANUM} dodany na początku tekstu pozwala depseudonimizatorowi
        // zidentyfikować mapę tokenów po tygodniu lub roku.
        // Nie trafia do tokenMap — to nie PII, to identyfikator sesji.
        // PseudonymResultPanel odczytuje sessionId z PseudonymResult.sessionId.
        // Storage (SQLCipher sessions table) — P1 Phase 2.
        val sessionId = java.util.UUID.randomUUID().toString()
            .replace("-", "").take(6).uppercase()
        text = "SESJA_$sessionId\n$text"

        // --- Warstwa 0b: AddressEngine — jedyny silnik ADRES ---
        // Konsolidacja 07.07 (po audycie Cursora, testy zielone + benchmark na telefonie):
        // stare źródła (applyPostalCityPatterns, ADDRESS_PATTERNS, NameEngine.applyStreetLookup)
        // usunięte — duplikowały ten sam kształt, gorzej guardowane. AnchorEngine A.11*
        // zostaje (kotwica na resztkach, nie równoległy silnik strukturalny).
        text = applyAddressEngine(text, ::assignToken)

        // --- Warstwa 2: Regex strukturalne ---
        if (BuildConfig.DEBUG) {
            android.util.Log.d("LynxMask", "STRUCTURAL_PATTERNS: ${STRUCTURAL_PATTERNS.size}")
        }
        for ((tokenType, pattern) in STRUCTURAL_PATTERNS) {
            text = pattern.replace(text) { matchResult ->
                val match = matchResult.value
                if (TOKEN_RE.containsMatchIn(match)) return@replace match

                // S5 — walidacja sumy kontrolnej PESEL i NIP
                // pre/suf dodane poniżej — zapobiega sklejaniu tokenów
                // Walidację stosujemy TYLKO do wzorców PESEL i NIP (lookup po pattern string),
                // żeby nie blokować telefonów, IBAN, sygnatur ani innych wzorców.
                //
                // PESEL_PATTERN_STRINGS / NIP_PATTERN_STRINGS: zbiory pattern stringów wzorców
                // z STRUCTURAL_PATTERNS, które produkują PESEL lub NIP — zdefiniowane w StructuralEngine.kt.
                //
                // Logika dla PESEL:
                //   • digits.length == 11 → pełny PESEL → sprawdź sumę kontrolną
                //   • digits.length < 11  → OCR zgubił cyfrę → maskuj bez sprawdzania (nie ma sumy)
                //   • digits.startsWith("48") → PL prefiks tel. 48XXX... → pomiń (nie PESEL)
                //
                // Logika dla NIP:
                //   • digits.length == 10 → sprawdź sumę kontrolną NIP
                val digits = match.filter { it.isDigit() }
                if (pattern.pattern in PESEL_PATTERN_STRINGS && !digits.startsWith("48")) {
                    if (digits.length == 11 && !isValidPesel(digits)) return@replace match
                }
                if (pattern.pattern in NIP_PATTERN_STRINGS) {
                    if (digits.length == 10 && !isValidNip(digits)) return@replace match
                }

                val token = assignToken(match, tokenType, layer = "STRUCTURAL", rule = tokenType)
                val before = if (matchResult.range.first > 0) text[matchResult.range.first - 1] else ' '
                val after  = if (matchResult.range.last + 1 < text.length) text[matchResult.range.last + 1] else ' '
                val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
                val suf = if (after.isLetterOrDigit()  || after  == '_') " " else ""
                pre + token + suf
            }
        }

        // --- Warstwa 2b: PESEL standalone D-class (suma kontrolna = kotwica) ---
        // BUG-MIGRACJA 01.07 (feature/entity-migration): przeniesione z AnchorEngine A.4c.
        // Musi być PO STRUCTURAL_PATTERNS (TOKEN_RE guard chroni już zamaskowane PESEL-e
        // z gołych cyfr) ale przed NameEngine — kształt nie zależy od kontekstu osoby/adresu.
        text = applyPeselShapeChecksum(text) { value, tokenType ->
            assignToken(value, tokenType, layer = "STRUCTURAL", rule = "PESEL_SHAPE_CHECKSUM")
        }

        // --- Warstwa 3: Czarna lista kontekstowa ---
        text = applyContextualBlacklist(text, { value, tokenType ->
            assignToken(value, tokenType, layer = "NAME_ENGINE", rule = "CONTEXTUAL")
        }, profileType)

        // --- Warstwa 3c: Propagacja nazwisk ---
        // Jeśli wykryto "Jan Kowalski" → OSOBA_001,
        // każde samotne "Kowalski", "Kowalskiego", "Kowalskiemu", "Kowalską" → OSOBA_001
        // Lookup surnames rozszerzają propagację na formy żeńskie (-ska, -skiej, -ską).
        //
        // ZNANE OGRANICZENIE (P5): propagacja działa tylko dla form wyrazów już WYKRYTYCH
        // w tokenMap. Imię/nazwisko pojawiające się PO tokenie w tekście ("dr OSOBA_003
        // Lewandowska-Karpowicz") nie jest łapane — wymagałoby osobnego przebiegu skanującego
        // tekst po tokenizacji w poszukiwaniu wielkich liter za tokenami OSOBA.
        //
        // ZNANE OGRANICZENIE: filtr it.length > 3 celowo wyklucza imiona ≤ 3 znaki ("Jan",
        // "Ewa") — propagacja \bJan[a-ząćęłńóśźż]{0,6}\b trafiałaby "Janusz", "Jański" itd.
        tokenMap.entries
            .filter { it.key.startsWith(TOKEN_OSOBA) }
            .forEach { (token, fullName) ->
                val words = fullName.trim().split(Regex("""\s+"""))
                if (words.size >= 2) {
                    words.filter { it.length > 3 && it[0].isUpperCase() }.forEach { namePart ->
                        // Regex-based propagacja dla znanych form
                        val propagateRegex = Regex(
                            """\b${Regex.escape(namePart)}[a-ząćęłńóśźż]{0,6}\b""",
                            RegexOption.IGNORE_CASE
                        )
                        text = propagateRegex.replace(text) { match ->
                            if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
                            if (isOnWhiteList(match.value)) return@replace match.value
                            token
                        }
                        // Lookup-based propagacja dla form żeńskich (kamińska, kowalskiej…)
                        if (LookupTables.initialized) {
                            val namePartLower = namePart.lowercase()
                            val relatedForms = LookupTables.surnamesForms
                                .filter { it.startsWith(namePartLower.take(5)) && it != namePartLower }
                            for (form in relatedForms) {
                                val formRegex = Regex("""\b${Regex.escape(form)}\b""", RegexOption.IGNORE_CASE)
                                text = formRegex.replace(text) { match ->
                                    if (TOKEN_RE.containsMatchIn(match.value)) return@replace match.value
                                    if (isOnWhiteList(match.value)) return@replace match.value
                                    token
                                }
                            }
                        }
                    }
                }
            }

        // --- Warstwa 4a: Słownik użytkownika (zbieracz resztek) ---
        // UWAGA: przeniesiony z Warstwy 1 — musi działać PO silnikach strukturalnych.
        // W Warstwie 1 UserDictionary kradł fragmenty emaili i nazwisk z par imię+nazwisko,
        // powodując że StructuralEngine i NameEngine dostawały już zniszczony tekst.
        // Jako zbieracz resztek operuje na tekście gdzie EMAIL i OSOBA są już zamaskowane.
        val validTokenTypes = setOf(TOKEN_FIRMA, TOKEN_OSOBA, TOKEN_NUMER, TOKEN_EMAIL, TOKEN_KWOTA, TOKEN_ADRES)
        for ((dictValue, tokenType) in userDictionary) {
            if (dictValue.isBlank()) continue
            val safeType = if (tokenType in validTokenTypes) tokenType else TOKEN_OSOBA
            val token = assignToken(dictValue, safeType, layer = "DICT", rule = "USER_DICTIONARY")
            val escapedValue = Regex.escape(dictValue)
            val notWordChar = """[a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ0-9]"""
            val dictRegex = Regex(
                "(?<!$notWordChar)$escapedValue(?!$notWordChar)",
                RegexOption.IGNORE_CASE
            )
            val snap = text
            text = dictRegex.replace(snap) { m ->
                if (matchOverlapsToken(snap, m.range)) m.value else token
            }
        }

        // --- Warstwa 4b: AnchorEngine (zbieracz resztek kotwicowy) ---
        // Operuje wyłącznie na tym co Warstwy 2–4a przeoczyły.
        // Nie może popsuć istniejących tokenów — każdy przebieg pomija TOKEN_RE.
        text = applyAnchorEngine(text) { value, tokenType ->
            assignToken(value, tokenType, layer = "ANCHOR", rule = tokenType)
        }

        // --- Runda 2: Structural + Name + Address na resztkach po AnchorEngine ---
        // Ten sam assignToken (te same liczniki, ta sama tokenMap) — zero kolizji tokenów.
        // TOKEN_RE w każdym silniku chroni już zamaskowane fragmenty przed ponownym przetworzeniem.
        for ((tokenType, pattern) in STRUCTURAL_PATTERNS) {
            text = pattern.replace(text) { matchResult ->
                val match = matchResult.value
                if (TOKEN_RE.containsMatchIn(match)) return@replace match
                val digits = match.filter { it.isDigit() }
                if (pattern.pattern in PESEL_PATTERN_STRINGS && !digits.startsWith("48")) {
                    if (digits.length == 11 && !isValidPesel(digits)) return@replace match
                }
                if (pattern.pattern in NIP_PATTERN_STRINGS) {
                    if (digits.length == 10 && !isValidNip(digits)) return@replace match
                }
                val token = assignToken(match, tokenType, layer = "STRUCTURAL_R2", rule = tokenType)
                val before = if (matchResult.range.first > 0) text[matchResult.range.first - 1] else ' '
                val after  = if (matchResult.range.last + 1 < text.length) text[matchResult.range.last + 1] else ' '
                val pre = if (before.isLetterOrDigit() || before == '_') " " else ""
                val suf = if (after.isLetterOrDigit()  || after  == '_') " " else ""
                pre + token + suf
            }
        }
        text = applyContextualBlacklist(text, { value, tokenType ->
            assignToken(value, tokenType, layer = "NAME_ENGINE_R2", rule = "CONTEXTUAL")
        }, profileType)

        // --- Warstwa 5: Detekcja algorytmiczna → TYLKO FLAGI ---
        detectAlgorithmicFlags(text, flags, guardAllowlist)

        // --- Warstwa 6: Output Guard ---
        val allGuardHits = runOutputGuard(text, tokenMap)
        val guardHits = if (guardAllowlist.isEmpty()) allGuardHits else {
            allGuardHits.filter { hit ->
                guardAllowlist.none { (value, ruleType) ->
                    hit.matchedText.equals(value, ignoreCase = true) && hit.label == ruleType
                }
            }
        }
        val guardWarnings = guardHits.map { "${it.level} ${it.label}: ${it.matchedText}" }
        val riskScore = calculateRiskScore(tokenMap, guardWarnings)

        val qualityWarning = if (quality.showWarning) {
            "Jakość rozpoznawania tekstu może być niska: ${quality.issues.joinToString(", ")}"
        } else null

        return PseudonymResult(
            pseudonymizedText = text,
            sessionId = sessionId,
            tokenMap = tokenMap,
            flags = flags,
            riskScore = riskScore,
            qualityWarning = qualityWarning,
            guardHits = guardHits,
            trace = traceLog,
            tokenLayers = tokenLayers
        )
    }

    // ============================================================
    // Risk Score
    // ============================================================
    private fun calculateRiskScore(
        tokenMap: Map<String, String>,
        guardWarnings: List<String>
    ): RiskScore {
        var score = 0

        // Wagi per typ tokenu
        for (token in tokenMap.keys) {
            score += when {
                token.startsWith("NUMER") -> 10
                token.startsWith("OSOBA") -> 15
                token.startsWith("ADRES") -> 20
                token.startsWith("FIRMA") -> 5
                token.startsWith("KWOTA") -> 3
                else -> 0
            }
        }

        // Ostrzeżenia guarda podnoszą ryzyko
        score += guardWarnings.size * 25

        return when {
            score == 0 -> RiskScore.GREEN
            score < 30 -> RiskScore.YELLOW
            else -> RiskScore.RED
        }
    }

    // ============================================================
    // Odkrywanie tokenu (np. VIN w dowodzie rejestracyjnym)
    // ============================================================
    fun revealToken(token: String, tokenMap: Map<String, String>): String? =
        tokenMap[token]
}
