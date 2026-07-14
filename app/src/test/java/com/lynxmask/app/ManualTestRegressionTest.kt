package com.lynxmask.app

import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regresja na plikach testy/test_*.txt — dokumentach pisanych ręcznie przez Pawła do
 * weryfikacji konkretnych bugów (PESEL/NIP/adres/email/sklejanie/degradacje OCR itd.).
 * Historia projektu: nie szliśmy dalej dopóki dany plik nie dawał zielonego wyniku na
 * telefonie. To NIE jest test poprawności (nie mamy ground truth dla tych plików — zgodnie
 * z zasadą projektu "kontekst testu tylko w czacie, nie w pliku") — to jest SNAPSHOT
 * REGRESJI: zapisuje wynik maskowania jako punkt odniesienia przy pierwszym uruchomieniu,
 * przy kolejnych porównuje z zapisanym stanem. Zmiana wyniku = czerwony = coś się zmieniło
 * względem ostatnio zatwierdzonego stanu (do ręcznej oceny czy to naprawa czy regresja).
 *
 * Migawki w testy/.golden/<nazwa_pliku>.golden.txt — commitowane do gita, żeby regresja
 * była widoczna dla każdego kto uruchomi test, nie tylko lokalnie.
 *
 * Uruchomienie: gradlew :app:testDebugUnitTest --tests "*.ManualTestRegressionTest"
 */
class ManualTestRegressionTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        if (!LookupTables.initialized) error("LookupTables nie załadowane — sprawdź src/test/resources/*.json")
    }

    private fun findTestyDir(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "testy")
            if (candidate.isDirectory && File(candidate, "test_adres_60.txt").exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Nie znaleziono katalogu testy/ (szukano od ${System.getProperty("user.dir")} w górę)")
    }

    @Test
    fun `pliki testy_test_ nie regresuja wobec ostatnio zatwierdzonego stanu`() {
        val testyDir = findTestyDir()
        val goldenDir = File(testyDir, ".golden")
        goldenDir.mkdirs()

        val files = testyDir.listFiles { f -> f.isFile && f.name.startsWith("test_") && f.name.endsWith(".txt") && f.length() > 0 }
            ?.sortedBy { it.name }
            ?: error("Brak plików test_*.txt w ${testyDir.path}")

        var created = 0
        val changed = mutableListOf<String>()

        for (f in files) {
            val rawText = f.readText(Charsets.UTF_8)
            val result = PseudonymEngine.pseudonymize(rawText, emptyList())
            // SESJA_XXXXXX na początku to losowy identyfikator, inny przy KAŻDYM wywołaniu
            // (ten sam wzorzec usuwania co ClipboardCheckActivity/IncomingDocumentFlow) —
            // bez tego migawka różniłaby się na starcie linii za każdym razem, niezależnie
            // od tego czy samo maskowanie się zmieniło.
            val output = result.pseudonymizedText.removePrefix("SESJA_${result.sessionId}\n")

            val goldenFile = File(goldenDir, "${f.name}.golden.txt")
            if (!goldenFile.exists()) {
                goldenFile.writeText(output, Charsets.UTF_8)
                created++
                println("NOWA MIGAWKA: ${f.name} (pierwsze uruchomienie — brak punktu odniesienia)")
            } else {
                // BUG-GOLDEN-CRLF-FALSZYWY-ALARM (14.07): git core.autocrlf=true na Windows
                // konwertuje pliki .golden.txt na CRLF przy każdym checkout/stash — silnik w
                // pamięci zawsze zwraca \n. Porównanie surowych bajtów widziało "zmianę" mimo
                // identycznej treści linia-po-linii (0 różnic w pętli niżej = pewny sygnał tego
                // mechanizmu). Normalizacja \r\n->\n przed porównaniem, zamiast ufać bajtom.
                val golden = goldenFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
                if (golden != output) {
                    changed += f.name
                    println("ZMIANA: ${f.name} — wynik różni się od ostatnio zatwierdzonego stanu")
                    val goldenLines = golden.lines()
                    val outputLines = output.lines()
                    for (i in 0 until maxOf(goldenLines.size, outputLines.size)) {
                        val g = goldenLines.getOrNull(i)
                        val o = outputLines.getOrNull(i)
                        if (g != o) {
                            println("  linia ${i + 1}:")
                            println("    BYŁO: ${g ?: "(brak linii)"}")
                            println("    JEST: ${o ?: "(brak linii)"}")
                        }
                    }
                }
            }
        }

        println("\n─── ${files.size} plików, $created nowych migawek, ${changed.size} zmienionych ───")

        if (created > 0) {
            println(
                "\nUWAGA: $created nowych migawek zapisanych do ${goldenDir.path} — to PIERWSZY " +
                    "punkt odniesienia (nic nie porównano, bo nie było z czym). Trzeba je ręcznie " +
                    "sprawdzić RAZ (są to dobrze znane pliki testowe) i scommitować jako punkt startowy " +
                    "— dopiero KOLEJNE uruchomienia będą realną regresją."
            )
        }
        if (changed.isNotEmpty()) {
            error(
                "${changed.size}/${files.size} plików zmieniło wynik maskowania względem zapisanej " +
                    "migawki: ${changed.joinToString(", ")} — sprawdź czy to naprawa (zaktualizuj " +
                    "migawkę) czy regresja (napraw silnik)."
            )
        }
    }
}
