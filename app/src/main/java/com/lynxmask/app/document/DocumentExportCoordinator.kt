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
import java.io.OutputStream

// DocumentExportCoordinator.kt — Document Rebuilder (DOCX/PDF/XLSX). Wydzielone z
// IncomingDocumentFlow.kt (przekroczył próg ~600 linii z CLAUDE.md) — cała orkiestracja
// SAF + zapisu w jednym miejscu, panel/flow zostają "głupie" (tylko wywołują callback).
//
// Jeden generyczny launcher (10.07) — DOCX/PDF/XLSX różnią się tylko MIME typem, nazwą
// pliku i funkcją zapisującą; reszta orkiestracji (SAF, sprzątanie pustego pliku po
// błędzie, Toast) jest identyczna, więc nie ma sensu kopiować całego pliku trzy razy.

private typealias DocumentWriter = suspend (String, OutputStream) -> DocumentWriteResult

/**
 * @return callback do wywołania z ekranu wyniku: `onSave(text)`, gdzie [text] to dokładnie
 * ten zamaskowany tekst, który user widzi w podglądzie (z uwzględnieniem ręcznych odsłonięć/
 * dodatkowych maskowań).
 *
 * Sam launcher SAF jest zarejestrowany tutaj (wymóg Compose — bezwarunkowo w drzewie
 * kompozycji), dane przechodzą przez wewnętrzny stan między kliknięciem przycisku
 * a callbackiem `CreateDocument`.
 */
@Composable
private fun rememberDocumentExportLauncher(
    scope: CoroutineScope,
    mimeType: String,
    suggestedFileName: String,
    writer: DocumentWriter,
): (String) -> Unit {
    val context = LocalContext.current
    var pendingText by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeType)
    ) { uri ->
        val text = pendingText ?: return@rememberLauncherForActivityResult
        pendingText = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val writeResult = context.contentResolver.openOutputStream(uri)?.use { out ->
                writer(text, out)
            } ?: DocumentWriteResult.Error("Nie udało się otworzyć pliku do zapisu")
            // BUG-DOCX-PUSTY-PLIK-FIX (09.07, zgłoszenie Pawła z telefonu): CreateDocument
            // tworzy plik w momencie wyboru lokalizacji, ZANIM cokolwiek do niego wpiszemy —
            // przy błędzie zapisu zostawał więc pusty, zepsuty plik na dysku, mylące razem
            // z komunikatem błędu. Sprzątamy go, gdy zapis się nie powiódł.
            if (writeResult !is DocumentWriteResult.Success) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                    // Sprzątanie best-effort — brak uprawnień/nieobsługiwany URI nie może
                    // przesłonić prawdziwego komunikatu o błędzie niżej.
                }
            }
            withContext(Dispatchers.Main) {
                val message = when (writeResult) {
                    is DocumentWriteResult.Success -> "Plik zapisany"
                    is DocumentWriteResult.Error -> "Błąd zapisu: ${writeResult.message}"
                }
                val duration = if (writeResult is DocumentWriteResult.Success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                Toast.makeText(context, message, duration).show()
            }
        }
    }

    return { text ->
        pendingText = text
        launcher.launch(suggestedFileName)
    }
}

@Composable
internal fun rememberDocxExportLauncher(scope: CoroutineScope): (String) -> Unit =
    rememberDocumentExportLauncher(
        scope,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "zamaskowany.docx",
        ::writeDocxFromText,
    )

@Composable
internal fun rememberPdfExportLauncher(scope: CoroutineScope): (String) -> Unit =
    rememberDocumentExportLauncher(scope, "application/pdf", "zamaskowany.pdf", ::writePdfFromText)

@Composable
internal fun rememberXlsxExportLauncher(scope: CoroutineScope): (String) -> Unit =
    rememberDocumentExportLauncher(
        scope,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "zamaskowany.xlsx",
        ::writeXlsxFromText,
    )
