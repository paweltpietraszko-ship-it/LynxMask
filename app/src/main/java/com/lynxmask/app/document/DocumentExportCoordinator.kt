package com.lynxmask.app.document

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// DocumentExportCoordinator.kt — Document Rebuilder (feature/document-export).
// Wydzielone z IncomingDocumentFlow.kt (przekroczył próg ~600 linii z CLAUDE.md) — cała
// orkiestracja SAF + zapisu DOCX w jednym miejscu, panel/flow zostają "głupie" (tylko
// wywołują zwrócony callback). Cursor sugerował ten podział w code review 09.07.

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private data class PendingExport(val artifact: DocxArtifact, val tokenMap: Map<String, String>, val sourceText: String)

/**
 * @return callback do wywołania z ekranu wyniku: `onSaveDocx(artifact, tokenMap, sourceText)`.
 * [sourceText] to dokładny tekst wysłany do silnika (po ewentualnej ręcznej korekcie na
 * Review) — writeDocxArtifact porówna go z artifact.plainText i odmówi zapisu jeśli się
 * różnią (audyt Cursora 09.07, KRYTYCZNE #1).
 *
 * Sam launcher SAF jest zarejestrowany tutaj (wymóg Compose — bezwarunkowo w drzewie
 * kompozycji), dane przechodzą przez wewnętrzny stan między kliknięciem przycisku
 * a callbackiem `CreateDocument`.
 */
@Composable
internal fun rememberDocxExportLauncher(scope: CoroutineScope): (DocxArtifact, Map<String, String>, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingExport?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DOCX_MIME)
    ) { uri ->
        val export = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val writeResult = context.contentResolver.openOutputStream(uri)?.use { out ->
                writeDocxArtifact(export.artifact, export.tokenMap, export.sourceText, out)
            } ?: DocxWriteResult.Error("Nie udało się otworzyć pliku do zapisu")
            // BUG-DOCX-PUSTY-PLIK-FIX (09.07, zgłoszenie Pawła z telefonu): CreateDocument
            // tworzy plik w momencie wyboru lokalizacji, ZANIM cokolwiek do niego wpiszemy —
            // przy odmowie zapisu (fail-closed) zostawał więc pusty, zepsuty plik na dysku,
            // mylące razem z komunikatem błędu. Sprzątamy go, gdy zapis się nie powiódł.
            if (writeResult !is DocxWriteResult.Success) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                    // Sprzątanie best-effort — brak uprawnień/nieobsługiwany URI nie może
                    // przesłonić prawdziwego komunikatu o odmowie zapisu niżej.
                }
            }
            withContext(Dispatchers.Main) {
                val message = when (writeResult) {
                    is DocxWriteResult.Success -> "DOCX zapisany"
                    is DocxWriteResult.MissingTokens ->
                        "Część danych nie dopasowała się do dokumentu — eksport przerwany, " +
                            "żeby nic nie zostało jawne. Użyj Kopiuj/Wyślij zamiast tego."
                    is DocxWriteResult.SourceTextMismatch ->
                        "Tekst został ręcznie poprawiony przed maskowaniem — eksport DOCX " +
                            "niedostępny dla edytowanej wersji. Użyj Kopiuj/Wyślij zamiast tego."
                    is DocxWriteResult.UnhandledDocumentParts ->
                        "Ten dokument ma tekst w nagłówku/stopce/przypisach, których faza 1a " +
                            "nie maskuje — eksport DOCX zablokowany dla bezpieczeństwa. Użyj Kopiuj/Wyślij."
                    is DocxWriteResult.Error -> "Błąd zapisu: ${writeResult.message}"
                }
                val duration = if (writeResult is DocxWriteResult.Success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                Toast.makeText(context, message, duration).show()
            }
        }
    }

    return { artifact, tokenMap, sourceText ->
        pending = PendingExport(artifact, tokenMap, sourceText)
        launcher.launch("zamaskowany.docx")
    }
}
