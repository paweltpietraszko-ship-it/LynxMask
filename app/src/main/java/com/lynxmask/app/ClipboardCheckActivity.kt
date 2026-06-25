package com.lynxmask.app

// ClipboardCheckActivity.kt
// Wersja: 1.5
//
// ZMIANA v1.5 (sesja 23.06 — BUG-FLAG-LIMIT):
//   Dialog schowka pokazywał max 5 flag (take(5) + "...i X więcej").
//   Fix: wszystkie flagi w verticalScroll Column — użytkownik widzi każdą encję.
//
// ZMIANA v1.4 — Potok 6 (08.06.2026):
//   ZADANIE 5: Przycisk "Zamaskuj i zapisz do biblioteki".
//   ClipState.PiiFound: dodano val result: PseudonymResult (trzymamy całość
//     zamiast rozkładać na pola — dostęp przez s.result.sessionId,
//     s.result.tokenMapJson(), s.result.tokenMap.size).
//   Zachowana zgodność: pseudonymizedText, riskScore, flags nadal dostępne
//     przez s.result.pseudonymizedText itd.
//   SessionStore.save() wywołany na Dispatchers.IO przez rememberCoroutineScope().
//   Dodano import LynxColors z ui.theme (zadanie 0b).
//   ZADANIE 6: Toast "Schowek zaktualizowany..." bez zmian (brak słów do zamiany).
//     Nowy przycisk: "Zamaskuj i zapisz do biblioteki".
//
// ZMIANA v1.1 — BUG-14:
//   FLAG_SECURE wyłączony w trybie DEBUG.
//
// ZMIANA v1.2 — BUG-31:
//   SESJA_XXXXXX usunięty z treści wklejanej do schowka.
//   Fix: substringAfter("\n").ifBlank { s.pseudonymizedText }.
//
// ZMIANA v1.3:
//   BUG-CLIP-A (threading): state mutowany na Dispatchers.Default — niezgodne
//     z Compose best practices. Poprzedni kod: withContext(Default) { state = X }.
//     Fix: withContext(Default) zwraca nowy stan jako wartość; state = newState
//     na Main thread (domyślny dispatcher LaunchedEffect).
//     Przy okazji: odczyt schowka przeniesiony przed withContext(Default) —
//     clipboard read bezpieczniejszy na Main (API 29+ soft requirement).
//   BUG-CLIP-B (theme): MaterialTheme zamiast LynxMaskTheme — inne Activity
//     używają LynxMaskTheme. Fix: zmiana + dodanie importu.
//   BUG-CLIP-C (dispatcher): UserDictionary.load() (I/O plikowe) na Dispatchers.Default
//     (CPU). Naprawione razem z BUG-CLIP-A — load() teraz na Dispatchers.IO
//     przez przełącznik wewnątrz withContext.
//   DESIGN GAP (nie naprawiony, wymaga decyzji): brak SessionStore.save() po
//     pseudonimizacji schowka — wynik nie jest persystowany. Użytkownik nie może
//     depseudonimizować. Może być by design (quick scan), ale niespójne z
//     ShareTargetActivity i ResultScreen. TODO: zdecydować i zaimplementować.

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxMaskTheme
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LynxMask_ClipCheck"

class ClipboardCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BUG-14 FIX v1.1: FLAG_SECURE tylko w buildach produkcyjnych.
        // W trybie DEBUG ekran nie jest blokowany — umożliwia testy zrzutami ekranu.
        if (!BuildConfig.DEBUG) window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        LookupTables.initialize(this)
        resetRegexCache()
        setContent {
            LynxMaskTheme {
                ClipboardCheckScreen(onFinished = { finish() })
            }
        }
    }
}

private sealed class ClipState {
    object Checking : ClipState()
    object Empty    : ClipState()
    object Clean    : ClipState()
    // PiiFound przechowuje pełny PseudonymResult — dostęp przez s.result.*
    // Wygodniejsze niż 3+ oddzielne pola i odporne na rozszerzenie PseudonymResult.
    data class PiiFound(val result: PseudonymResult) : ClipState()
    data class Err(val message: String) : ClipState()
}

@Composable
private fun ClipboardCheckScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val composeClip = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ClipState>(ClipState.Checking) }

    LaunchedEffect(Unit) {
        // BUG-CLIP-A FIX v1.3: odczyt schowka na Main thread (API 29+ safe practice).
        // Poprzednio: cały blok w withContext(Default) → state mutowany na tle.
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString()

        if (clipText.isNullOrBlank()) {
            state = ClipState.Empty
            return@LaunchedEffect
        }

        // BUG-CLIP-A FIX: withContext zwraca wartość — state ustawiany z powrotem na Main.
        // BUG-CLIP-C FIX: UserDictionary.load() (I/O plikowe) na Dispatchers.IO,
        //   pseudonymize() (CPU) na Dispatchers.Default przez zagnieżdżony withContext.
        val newState: ClipState = withContext(Dispatchers.IO) {
            try {
                val userDict = UserDictionary.load(context)
                val result = withContext(Dispatchers.Default) {
                    PseudonymEngine.pseudonymize(clipText, userDictionary = userDict)
                }
                if (result.flags.isEmpty() && result.riskScore == RiskScore.GREEN) {
                    ClipState.Clean
                } else {
                    ClipState.PiiFound(result = result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Błąd: ${e.message}", e)
                ClipState.Err(e.message ?: "nieznany błąd")
            }
        }
        state = newState  // Main thread
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is ClipState.Checking -> CircularProgressIndicator()

            is ClipState.Empty -> {
                LaunchedEffect(Unit) {
                    Toast.makeText(context, "Schowek jest pusty", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Schowek pusty")
                    onFinished()
                }
            }

            is ClipState.Clean -> {
                LaunchedEffect(Unit) {
                    Toast.makeText(context, "✓ Schowek wygląda bezpiecznie", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Schowek bez PII — czysto")
                    onFinished()
                }
            }

            is ClipState.PiiFound -> {
                ClipPiiDialog(
                    state = s,
                    onReplaceClipboard = {
                        // BUG-31 FIX v1.2: Odetnij SESJA_XXXXXX\n przed wklejeniem
                        composeClip.setText(AnnotatedString(
                            s.result.pseudonymizedText.substringAfter("\n")
                                .ifBlank { s.result.pseudonymizedText }
                        ))
                        Log.i(TAG, "Schowek zastąpiony. Flags: ${s.result.flags.size}")
                        Toast.makeText(context, "Schowek zaktualizowany — bezpieczna wersja gotowa", Toast.LENGTH_SHORT).show()
                        onFinished()
                    },
                    onReplaceAndSave = {
                        // Zastąp schowek BEZ SESJA_ prefiksu
                        composeClip.setText(AnnotatedString(
                            s.result.pseudonymizedText.substringAfter("\n")
                                .ifBlank { s.result.pseudonymizedText }
                        ))
                        // Zapis sesji do bazy na IO thread
                        coroutineScope.launch(Dispatchers.IO) {
                            val maskedText = s.result.pseudonymizedText
                                .removePrefix("SESJA_${s.result.sessionId}\n")
                            val saved = SessionStore.save(
                                context      = context,
                                sesjaId      = s.result.sessionId,
                                tokenMapJson = s.result.tokenMapJson(),
                                tokenCount   = s.result.tokenMap.size,
                                maskedText   = maskedText
                            )
                            withContext(Dispatchers.Main) {
                                if (saved) {
                                    SessionStore.recordAudit(context, s.result.sessionId, "clipboard_saved")
                                    Log.i(TAG, "Sesja zapisana z schowka: ${s.result.sessionId}")
                                    Toast.makeText(context, "Zapisano do biblioteki", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context,
                                        "Błąd zapisu sesji — dane mogą być niedostępne w bibliotece",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        onFinished()
                    },
                    onClearClipboard = {
                        composeClip.setText(AnnotatedString(""))
                        Log.i(TAG, "Schowek wyczyszczony przez użytkownika")
                        Toast.makeText(context, "Schowek wyczyszczony", Toast.LENGTH_SHORT).show()
                        onFinished()
                    },
                    onDismiss = {
                        Log.i(TAG, "Użytkownik zignorował ostrzeżenie")
                        onFinished()
                    }
                )
            }

            is ClipState.Err -> {
                LaunchedEffect(Unit) {
                    Toast.makeText(context, "Błąd: ${s.message}", Toast.LENGTH_LONG).show()
                    onFinished()
                }
            }
        }
    }
}

@Composable
private fun ClipPiiDialog(
    state: ClipState.PiiFound,
    onReplaceClipboard: () -> Unit,
    onReplaceAndSave: () -> Unit,
    onClearClipboard: () -> Unit,
    onDismiss: () -> Unit
) {
    val riskColor = when (state.result.riskScore) {
        RiskScore.GREEN  -> Color(0xFF4CAF50)
        RiskScore.YELLOW -> Color(0xFFFFC107)
        RiskScore.RED    -> Color(0xFFF44336)
    }
    val riskLabel = when (state.result.riskScore) {
        RiskScore.GREEN  -> "Niskie ryzyko"
        RiskScore.YELLOW -> "Schowek zawiera dane wrażliwe"
        RiskScore.RED    -> "STOP — w schowku są dane osobowe"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).background(riskColor, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(riskLabel, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            // BUG-FLAG-LIMIT: wszystkie flagi w scrollable column, bez obcięcia do 5
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.result.flags.isNotEmpty()) {
                    Text("Znalezione dane wrażliwe:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    state.result.flags.forEach { flag ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", color = Color(0xFFFFC107))
                            Column {
                                Text(text = flag.fragment, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(text = flag.reason, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Główny przycisk — zastąp schowek
                Button(
                    onClick = onReplaceClipboard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Zastąp bezpieczną wersją")
                }
                // Nowy przycisk — zastąp I zapisz sesję do biblioteki
                Button(
                    onClick = onReplaceAndSave,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LynxColors.BlueBg,
                        contentColor   = LynxColors.BlueLight
                    )
                ) {
                    Text("Zamaskuj i zapisz do biblioteki")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClearClipboard) { Text("Wyczyść schowek") }
                TextButton(onClick = onDismiss) {
                    Text("Ignoruj", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
