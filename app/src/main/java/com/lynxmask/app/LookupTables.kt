package com.lynxmask.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * LookupTables.kt
 *
 * Ładuje z app/src/main/assets/:
 *   names_inflected.json    — odmienione formy imion (Morfeusz2)
 *   surnames_top1000.json   — top 1000 nazwisk + formy żeńskie generowane programowo
 *   street_names.json       — top 8000 nazw ulic z GUS TERYT (bez prefiksu ul./al.)
 *
 * Każdy set jest automatycznie rozszerzany o wersje bez diakrytyk —
 * "szymański" i "szymanski" trafią w ten sam token.
 * Zero zmian w JSON, zero kosztu wydajnościowego (HashSet.contains = O(1)).
 *
 * Wywołaj LookupTables.initialize(context) przed pierwszym pseudonymize().
 *
 * Zmiany v1.1 (sesja 7):
 *   - LOG-FIX: loadFormsFromAsset loguje nazwę pliku przy błędzie — debugging
 *   - INIT-FIX: _initialized = true TYLKO gdy names i surnames załadowane;
 *               wcześniej true nawet gdy wszystkie pliki zwróciły emptySet()
 *
 * Zmiany v1.2 (sesja 9):
 *   - initializeForTesting() — inicjalizacja bez Context dla testów JVM (src/test/)
 *   - resetForTesting() — czysty stan między testami
 */
object LookupTables {

    private var _namesForms: Set<String> = emptySet()
    private var _surnamesForms: Set<String> = emptySet()
    private var _streetForms: Set<String> = emptySet()
    private var _cityForms: Set<String> = emptySet()
    private var _medForms: Set<String> = emptySet()
    private var _citySurnameOverlap: Set<String> = emptySet()
    private var _surnameSuffixes: Set<String> = emptySet()
    private var _initialized = false

    val initialized: Boolean get() = _initialized
    val namesForms: Set<String> get() = _namesForms
    val surnamesForms: Set<String> get() = _surnamesForms
    val streetForms: Set<String> get() = _streetForms
    val cityForms: Set<String> get() = _cityForms
    val medForms: Set<String> get() = _medForms
    // BUG-SLOWNIK-POSPOLITE-SLOWA (10.07, przywrócenie Warstwy 1 po diagnozie "chorego
    // termometru" 09.07): rozszerzony słownik nazwisk (39k, próg ≥100 w rejestrze PESEL/GUS)
    // łapie sporo rzadkich, ale prawdziwych nazwisk identycznych z pospolitymi słowami
    // ("Osoba", "Łączna"). Sufiks nazwiskotwórczy (ski/cki/owicz/ak/uk...) jako sygnał
    // kształtu — używany TYLKO jako strażnik w NameEngine, żaden osobny mechanizm
    // rdzeń+sufiks (Warstwa 2, 08.07) nie jest tu przywracany.
    val surnameSuffixes: Set<String> get() = _surnameSuffixes

    // BUG-GORA-OSOBA-FIX (04.07): słowa będące jednocześnie drugim członem dwuwyrazowej
    // nazwy miejscowości (np. "Góra" w "Zielona Góra"/"Jelenia Góra") i nazwiskiem z
    // top-1000 — chroni CAŁĄ tę klasę (44 słowa) w NameEngine.isOnWhiteList() przed regułą
    // "samo nazwisko", bez poszerzania sprawdzania na cały (bardzo duży) cityForms, co
    // obniżyłoby recall OSOBA dla zwykłych nazwisk gdzie indziej. Wygenerowane programowo —
    // patrz generate_city_surname_overlap.py, uruchomić ponownie gdy zmienią się źródłowe
    // słowniki (cities_forms.json / surnames_top1000.json), nie utrzymywać ręcznie.
    val citySurnameOverlap: Set<String> get() = _citySurnameOverlap

    // PERF-PRÓBA (11.07): zrównoleglenie 7 niezależnych słowników przez coroutines
    // (Dispatchers.Default) było TU WOLNIEJSZE niż sekwencyjnie na telefonie (23,9s vs 9,4s) —
    // zmierzone na realnym urządzeniu, nie na desktopie. Prawdopodobna przyczyna: budowanie
    // kilku ogromnych zbiorów stringów naraz (343k+ + 30k+ elementów jednocześnie w pamięci)
    // winduje presję GC bardziej niż oszczędza na współbieżności przy ograniczonej liczbie
    // rdzeni telefonu. Wniosek zapisany, NIE próbować ponownie bez nowego pomiaru na telefonie.
    fun initialize(context: Context) {
        if (_initialized) return
        val tA0 = System.currentTimeMillis()
        val names = loadFormsFromAsset(context, "names_inflected.json")
        val baseSurnames = loadFormsFromAsset(context, "surnames_top1000.json")
        val streets = loadFormsFromAsset(context, "street_names.json")
        val cities = loadFlatListFromAsset(context, "cities_forms.json") { s ->
            s.length >= 4 && s.none { it.isDigit() }
        }
        val med = loadFlatListFromAsset(context, "medical_facilities.json")
        val overlap = loadFlatListFromAsset(context, "city_surname_overlap.json")
        val suffixes = loadFlatListFromAsset(context, "surname_suffixes.json")

        _namesForms    = names.withAsciiVariants()
        _surnamesForms = (baseSurnames + generateFeminineVariants(baseSurnames)).withAsciiVariants()
        _streetForms   = streets.withAsciiVariants()
        _cityForms     = cities.withAsciiVariants()
        _medForms      = med.withAsciiVariants()
        _citySurnameOverlap = overlap.withAsciiVariants()
        _surnameSuffixes = suffixes
        val tA1 = System.currentTimeMillis()
        if (BuildConfig.DEBUG) android.util.Log.d("LynxTiming",
            "sequential-init=${tA1-tA0}ms surnames.size=${_surnamesForms.size}")

        // INIT-FIX v1.1: initialized tylko gdy krytyczne pliki załadowane.
        // Street/city/med mogą być puste (degrades gracefully). Names+surnames puste = silnik ślepy.
        _initialized  = _namesForms.isNotEmpty() && _surnamesForms.isNotEmpty()

        if (BuildConfig.DEBUG) android.util.Log.d("LookupTables",
            "Załadowano: ${_namesForms.size} form imion, " +
            "${_surnamesForms.size} form nazwisk, " +
            "${_streetForms.size} nazw ulic, " +
            "${_cityForms.size} form miast, " +
            "${_medForms.size} terminów medycznych, " +
            "${_citySurnameOverlap.size} słów na przecięciu miast/nazwisk (z wariantami ASCII)."
        )
    }

    /**
     * Inicjalizacja do testów jednostkowych na JVM (src/test/).
     * Nie wymaga Context ani plików assets — testy chodzą bez urządzenia.
     * Wywołaj w @Before każdej klasy testowej silnika.
     *
     * Wstrzykuje minimalny słownik wystarczający do testów.
     * Rozszerz parametry jeśli test wymaga specyficznych form.
     */
    @Suppress("unused")
    fun initializeForTesting(
        names: Set<String> = setOf(
            "jan", "jana", "janowi", "janie",
            "anna", "anny", "annie", "anną",
            "piotr", "piotra", "piotrowi",
            "maria", "marię", "marii",
            "adam", "adama", "adamowi",
            "katarzyna", "katarzyny", "katarzynie"
        ),
        surnames: Set<String> = setOf(
            "kowalski", "kowalskiego", "kowalskiemu", "kowalskim",
            "kowalska", "kowalskiej", "kowalską",
            "nowak", "nowaka", "nowakowi",
            "malinowski", "malinowskiego", "malinowska",
            "wiśniewski", "wiśniewska", "wiśniewskiej",
            "szymański", "szymańska", "szymańskiego"
        ),
        streets: Set<String> = setOf(
            "lipowa", "lipowej", "lipową",
            "marszałkowska", "marszałkowskiej",
            "długa", "długiej", "krótka", "krótkiej",
            "słoneczna", "słonecznej"
        ),
        cities: Set<String> = setOf(
            "warszawa", "krakow", "gdansk", "wroclaw", "poznan",
            "lodz", "katowice", "lublin", "bydgoszcz", "gdynia"
        ),
        med: Set<String> = setOf(
            "szpital", "klinika", "przychodnia", "poradnia", "ambulatorium"
        ),
        citySurnameOverlap: Set<String> = setOf("góra", "górka", "górny", "róg", "kępa"),
        surnameSuffixes: Set<String> = setOf("ski", "ska", "cki", "cka", "owicz", "ak", "uk")
    ) {
        _namesForms    = names
        _surnamesForms = surnames
        _streetForms   = streets
        _cityForms     = cities
        _medForms      = med
        _citySurnameOverlap = citySurnameOverlap
        _surnameSuffixes = surnameSuffixes
        _initialized   = true
        resetRegexCache()
    }

    /** Ładuje pełny słownik z classpath (src/test/resources/) — dla unit testów na JVM. */
    @Suppress("unused")
    fun initializeFromClasspath() {
        if (_initialized) return
        val names    = loadFormsFromClasspath("names_inflected.json")
        val surnames = loadFormsFromClasspath("surnames_top1000.json")
        val streets  = loadFormsFromClasspath("street_names.json")
        val cities   = loadFlatListFromClasspath("cities_forms.json") { s ->
            s.length >= 4 && s.none { it.isDigit() }
        }
        val med      = loadFlatListFromClasspath("medical_facilities.json")
        val overlap  = loadFlatListFromClasspath("city_surname_overlap.json")
        val suffixes = loadFlatListFromClasspath("surname_suffixes.json")
        _namesForms    = names.withAsciiVariants()
        _surnamesForms = (surnames + generateFeminineVariants(surnames)).withAsciiVariants()
        _streetForms   = streets.withAsciiVariants()
        _cityForms     = cities.withAsciiVariants()
        _medForms      = med.withAsciiVariants()
        _citySurnameOverlap = overlap.withAsciiVariants()
        _surnameSuffixes = suffixes
        _initialized   = _namesForms.isNotEmpty() && _surnamesForms.isNotEmpty()
        if (_initialized) resetRegexCache()
    }

    private fun loadFormsFromClasspath(filename: String): Set<String> {
        return try {
            val text = LookupTables::class.java.classLoader
                ?.getResourceAsStream(filename)?.bufferedReader()?.readText()
                ?: return emptySet()
            val obj = org.json.JSONObject(text)
            val forms = mutableSetOf<String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                forms.add(key)
                val arr = obj.getJSONArray(key)
                for (i in 0 until arr.length()) {
                    val f = arr.getString(i)
                    if (f.length >= 2) forms.add(f)
                }
            }
            forms
        } catch (e: Exception) { emptySet() }
    }

    /** Resetuje stan — używany między testami jeśli potrzeba czystego slate. */
    @Suppress("unused")
    fun resetForTesting() {
        _namesForms    = emptySet()
        _surnamesForms = emptySet()
        _streetForms   = emptySet()
        _cityForms     = emptySet()
        _medForms      = emptySet()
        _citySurnameOverlap = emptySet()
        _surnameSuffixes = emptySet()
        _initialized   = false
        resetRegexCache()
    }

    // Rozszerza set o wersje bez polskich znaków diakrytycznych.
    // "krakowskie przedmieście" → dodaje też "krakowskie przedmiescie".
    // Oryginalne formy zostają — matching działa w obie strony.
    private fun Set<String>.withAsciiVariants(): Set<String> {
        val ascii = mapNotNull { form ->
            val stripped = stripDiacritics(form)
            if (stripped != form) stripped else null   // dodaj tylko jeśli różni się
        }
        return this + ascii
    }

    // PERF-FIX (11.07): regex kompilowany RAZ, nie przy każdym wywołaniu stripDiacritics —
    // funkcja woła się per-słowo dla każdej formy w każdym słowniku (setki tysięcy razy przy
    // 39k słowniku nazwisk + 30k miast), kompilacja regexa w pętli dominowała czas startu apki
    // (22s init → w tym ~20s w withAsciiVariants/stripDiacritics, zmierzone LynxTiming).
    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    private fun stripDiacritics(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            // Ł/ł ma kreską (stroke, U+0141/U+0142) — nie jest combining mark,
            // NFD jej nie rozkłada. Ręczna konwersja żeby "łukasz" → "lukasz".
            .replace('ł', 'l').replace('Ł', 'L')

    private fun loadFlatListFromAsset(
        context: Context,
        filename: String,
        filter: (String) -> Boolean = { true }
    ): Set<String> {
        return try {
            val json = context.assets.open(filename).bufferedReader().readText()
            val arr = org.json.JSONArray(json)
            val forms = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).lowercase()
                if (filter(s)) forms.add(s)
            }
            forms
        } catch (e: Exception) {
            android.util.Log.e("LookupTables", "Błąd ładowania $filename: ${e.message}")
            emptySet()
        }
    }

    private fun loadFlatListFromClasspath(
        filename: String,
        filter: (String) -> Boolean = { true }
    ): Set<String> {
        return try {
            val text = LookupTables::class.java.classLoader
                ?.getResourceAsStream(filename)?.bufferedReader()?.readText()
                ?: return emptySet()
            val arr = org.json.JSONArray(text)
            val forms = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).lowercase()
                if (filter(s)) forms.add(s)
            }
            forms
        } catch (e: Exception) { emptySet() }
    }

    private fun loadFormsFromAsset(context: Context, filename: String): Set<String> {
        return try {
            val json = context.assets.open(filename).bufferedReader().readText()
            val obj = JSONObject(json)
            val forms = mutableSetOf<String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                forms.add(key)
                val arr = obj.getJSONArray(key)
                for (i in 0 until arr.length()) {
                    val f = arr.getString(i)
                    if (f.length >= 2) forms.add(f)
                }
            }
            forms
        } catch (e: Exception) {
            // LOG-FIX v1.1: loguj nazwę pliku żeby wiedzieć co nie załadowało się
            android.util.Log.e("LookupTables", "Błąd ładowania $filename: ${e.message}")
            emptySet()
        }
    }

    private fun generateFeminineVariants(forms: Set<String>): Set<String> {
        val feminine = mutableSetOf<String>()
        val replacements = listOf(
            "skiego"  to "skiej",  "skiemu" to "skiej",  "skimi"  to "skimi",
            "skich"   to "skich",  "skim"   to "ską",    "scy"    to "skie",
            "ski"     to "ska",    "ckiego" to "ckiej",  "ckiemu" to "ckiej",
            "ckich"   to "ckich",  "ckim"   to "cką",    "ccy"    to "ckie",
            "cki"     to "cka",    "dzkiego" to "dzkiej","dzkiemu" to "dzkiej",
            "dzkich"  to "dzkich", "dzkim"  to "dzką",   "dzcy"   to "dzkie",
            "dzki"    to "dzka"
        )
        for (form in forms) {
            for ((mEnd, fEnd) in replacements) {
                if (form.endsWith(mEnd)) {
                    val fForm = form.dropLast(mEnd.length) + fEnd
                    if (fForm != form) feminine.add(fForm)
                    break
                }
            }
        }
        return feminine
    }
}
