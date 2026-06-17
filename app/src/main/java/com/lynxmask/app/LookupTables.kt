package com.lynxmask.app

import android.content.Context
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
    private var _initialized = false

    val initialized: Boolean get() = _initialized
    val namesForms: Set<String> get() = _namesForms
    val surnamesForms: Set<String> get() = _surnamesForms
    val streetForms: Set<String> get() = _streetForms

    fun initialize(context: Context) {
        if (_initialized) return
        val names = loadFormsFromAsset(context, "names_inflected.json")
        val baseSurnames = loadFormsFromAsset(context, "surnames_top1000.json")
        val streets = loadFormsFromAsset(context, "street_names.json")

        _namesForms   = names.withAsciiVariants()
        _surnamesForms = (baseSurnames + generateFeminineVariants(baseSurnames)).withAsciiVariants()
        _streetForms  = streets.withAsciiVariants()

        // INIT-FIX v1.1: initialized tylko gdy krytyczne pliki załadowane.
        // Street może być puste (degrades gracefully). Names+surnames puste = silnik ślepy.
        _initialized  = _namesForms.isNotEmpty() && _surnamesForms.isNotEmpty()

        android.util.Log.d("LookupTables",
            "Załadowano: ${_namesForms.size} form imion, " +
            "${_surnamesForms.size} form nazwisk, " +
            "${_streetForms.size} nazw ulic (z wariantami ASCII). " +
            "Przykład: arkadiuszem=${_namesForms.contains("arkadiuszem")}, " +
            "szymanski=${_surnamesForms.contains("szymanski")}, " +
            "lipowa=${_streetForms.contains("lipowa")}"
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
        )
    ) {
        _namesForms   = names
        _surnamesForms = surnames
        _streetForms  = streets
        _initialized  = true
    }

    /** Ładuje pełny słownik z classpath (src/test/resources/) — dla unit testów na JVM. */
    @Suppress("unused")
    fun initializeFromClasspath() {
        if (_initialized) return
        val names    = loadFormsFromClasspath("names_inflected.json")
        val surnames = loadFormsFromClasspath("surnames_top1000.json")
        val streets  = loadFormsFromClasspath("street_names.json")
        _namesForms    = names.withAsciiVariants()
        _surnamesForms = (surnames + generateFeminineVariants(surnames)).withAsciiVariants()
        _streetForms   = streets.withAsciiVariants()
        _initialized   = _namesForms.isNotEmpty() && _surnamesForms.isNotEmpty()
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
        _namesForms   = emptySet()
        _surnamesForms = emptySet()
        _streetForms  = emptySet()
        _initialized  = false
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

    private fun stripDiacritics(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

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
