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
// wywołują zwrócony callback).
//
// Uproszczone 10.07: eksport zapisuje wprost gotowy, zamaskowany tekst (patrz DocxWriter.kt)
// — nie potrzebuje już artefaktu/tokenMap/sourceText.

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/**
 * @return callback do wywołania z ekranu wyniku: `onSaveDocx(text)`, gdzie [text] to
 * dokładnie ten zamaskowany tekst, który user widzi w podglądzie (z uwzględnieniem ręcznych
 * odsłonięć/dodatkowych maskowań).
 *
 * Sam launcher SAF jest zarejestrowany tutaj (wymóg Compose — bezwarunkowo w drzewie
 * kompozycji), dane przechodzą przez wewnętrzny stan między kliknięciem przycisku
 * a callbackiem `CreateDocument`.
 */
@Composable
internal fun rememberDocxExportLauncher(scope: CoroutineScope): (String) -> Unit {
    val context = LocalContext.current
    var pendingText by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DOCX_MIME)
    ) { uri ->
        val text = pendingText ?: return@rememberLauncherForActivityResult
        pendingText = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val writeResult = context.contentResolver.openOutputStream(uri)?.use { out ->
                writeDocxFromText(text, out)
            } ?: DocxWriteResult.Error("Nie udało się otworzyć pliku do zapisu")
            // BUG-DOCX-PUSTY-PLIK-FIX (09.07, zgłoszenie Pawła z telefonu): CreateDocument
            // tworzy plik w momencie wyboru lokalizacji, ZANIM cokolwiek do niego wpiszemy —
            // przy błędzie zapisu zostawał więc pusty, zepsuty plik na dysku, mylące razem
            // z komunikatem błędu. Sprzątamy go, gdy zapis się nie powiódł.
            if (writeResult !is DocxWriteResult.Success) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                    // Sprzątanie best-effort — brak uprawnień/nieobsługiwany URI nie może
                    // przesłonić prawdziwego komunikatu o błędzie niżej.
                }
            }
            withContext(Dispatchers.Main) {
                val message = when (writeResult) {
                    is DocxWriteResult.Success -> "DOCX zapisany"
                    is DocxWriteResult.Error -> "Błąd zapisu: ${writeResult.message}"
                }
                val duration = if (writeResult is DocxWriteResult.Success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                Toast.makeText(context, message, duration).show()
            }
        }
    }

    return { text ->
        pendingText = text
        launcher.launch("zamaskowany.docx")
    }
}
