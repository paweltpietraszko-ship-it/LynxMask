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

/**
 * @return callback do wywołania z ekranu wyniku: `onSaveDocx(artifact, tokenMap)`.
 * Sam launcher SAF jest zarejestrowany tutaj (wymóg Compose — bezwarunkowo w drzewie
 * kompozycji), artefakt+tokenMap przechodzą przez wewnętrzny stan między kliknięciem
 * przycisku a callbackiem `CreateDocument`.
 */
@Composable
internal fun rememberDocxExportLauncher(scope: CoroutineScope): (DocxArtifact, Map<String, String>) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<DocxArtifact, Map<String, String>>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DOCX_MIME)
    ) { uri ->
        val (artifact, tokenMap) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val writeResult = context.contentResolver.openOutputStream(uri)?.use { out ->
                writeDocxArtifact(artifact, tokenMap, out)
            } ?: DocxWriteResult.Error("Nie udało się otworzyć pliku do zapisu")
            withContext(Dispatchers.Main) {
                when (writeResult) {
                    is DocxWriteResult.Success ->
                        Toast.makeText(context, "DOCX zapisany", Toast.LENGTH_SHORT).show()
                    is DocxWriteResult.MissingTokens ->
                        Toast.makeText(
                            context,
                            "Część danych nie dopasowała się do dokumentu — eksport przerwany, " +
                                "żeby nic nie zostało jawne. Użyj Kopiuj/Wyślij zamiast tego.",
                            Toast.LENGTH_LONG
                        ).show()
                    is DocxWriteResult.Error ->
                        Toast.makeText(context, "Błąd zapisu: ${writeResult.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    return { artifact, tokenMap ->
        pending = artifact to tokenMap
        launcher.launch("zamaskowany.docx")
    }
}
