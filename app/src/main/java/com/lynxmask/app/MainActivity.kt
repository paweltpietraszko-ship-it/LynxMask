package com.lynxmask.app

// MainActivity.kt — v2.1 (10.06.2026)
// Hub z nawigacją 2+2, nowe nazwy Ukryj/Odkryj, bez sidebar
//
// ZMIANA v2.1 (Potok RODO):
//   SecurityModal: rozbudowany o "Usuń wszystkie dane" z dwuetapowym potwierdzeniem.
//   Wywołanie: SessionStore.deleteAllData(context) na Dispatchers.IO.
//   Wymagane przez Art. 17 RODO — użytkownik musi móc usunąć swoje dane.
//   Dodane importy: rememberCoroutineScope, Dispatchers, launch.

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.core.view.WindowCompat
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxMaskTheme
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { MAIN, LIBRARY, DEPSEUDO }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!BuildConfig.DEBUG) window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // [BUG-SS-3 fix] init() wykonuje I/O (Keystore + SQLite + ALTER TABLE) — musi być poza Main thread
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LookupTables.initialize(this@MainActivity)
                SessionStore.init(this@MainActivity)
            }
            getExternalFilesDir("bench")?.mkdirs()
            setContent { LynxMaskTheme { AppNavigation() } }
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    var onboardingDone by remember         { mutableStateOf(isOnboardingDone(context)) }
    var authenticated  by rememberSaveable { mutableStateOf(false) }
    when {
        !onboardingDone -> OnboardingScreen(onFinished = { onboardingDone = true })
        !authenticated  -> LoginScreen(
            isFirstRun      = !isPasswordSet(context),
            onAuthenticated = { authenticated = true }
        )
        else -> MainTabNav()
    }
}

@Composable
private fun MainTabNav() {
    val context = LocalContext.current
    var appScreen            by rememberSaveable { mutableStateOf(AppScreen.MAIN) }
    var selectedSessionId    by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDepseudoMode by rememberSaveable { mutableStateOf(DepseudoMode.AI_RESPONSE) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                setClass(context, ShareTargetActivity::class.java)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    BackHandler(enabled = appScreen != AppScreen.MAIN) {
        if (appScreen == AppScreen.DEPSEUDO) {
            selectedSessionId    = null
            selectedDepseudoMode = DepseudoMode.AI_RESPONSE
        }
        appScreen = AppScreen.MAIN
    }

    when (appScreen) {
        AppScreen.MAIN -> Column(
            modifier = Modifier.fillMaxSize().background(LynxColors.Background)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                HubScreen(
                    onFileClick = {
                        filePickerLauncher.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/pdf", "text/plain", "image/*"
                        ))
                    },
                    onDepseudoClick = {
                        selectedSessionId    = null
                        selectedDepseudoMode = DepseudoMode.AI_RESPONSE
                        appScreen = AppScreen.DEPSEUDO
                    },
                    onTextSubmit = { text ->
                        context.startActivity(
                            Intent(context, ShareTargetActivity::class.java).apply {
                                action = Intent.ACTION_SEND
                                type   = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                        )
                    }
                )
            }
            BottomNavBar(
                current    = appScreen,
                onNavigate = { screen ->
                    if (screen == AppScreen.DEPSEUDO) {
                        selectedSessionId    = null
                        selectedDepseudoMode = DepseudoMode.AI_RESPONSE
                    }
                    appScreen = screen
                }
            )
        }

        AppScreen.LIBRARY -> LibraryScreen(
            onBack     = { appScreen = AppScreen.MAIN },
            onDepseudo = { id, mode ->
                selectedSessionId    = id
                selectedDepseudoMode = mode
                appScreen = AppScreen.DEPSEUDO
            }
        )

        AppScreen.DEPSEUDO -> DepseudonymizationScreen(
            preselectedSessionId = selectedSessionId,
            initialMode          = selectedDepseudoMode,
            onBack = { selectedSessionId = null; appScreen = AppScreen.MAIN }
        )
    }
}

// ── Hub — strona startowa ─────────────────────────────────────────────────────
@Composable
private fun HubScreen(
    onFileClick: () -> Unit,
    onDepseudoClick: () -> Unit,
    onTextSubmit: (String) -> Unit
) {
    var pastedText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LynxColors.Background)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Wersja — górny prawy
        Box(modifier = Modifier.fillMaxWidth().padding(end = LynxSpacing.md, top = LynxSpacing.sm)) {
            Text(
                "${BuildConfig.VERSION_NAME}  LynxMask Mobile",
                modifier   = Modifier.align(Alignment.CenterEnd),
                fontSize   = 10.sp,
                color      = LynxColors.TextDim
            )
        }

        Spacer(Modifier.weight(1f))

        // Logo
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PSE",
                fontFamily = LynxTypography.Mono,
                fontSize   = 9.sp,
                color      = LynxColors.Blue,
                modifier   = Modifier
                    .border(1.dp, LynxColors.BorderActive, RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "LynxMask",
                fontFamily = LynxTypography.Sans,
                fontSize   = 42.sp,
                fontWeight = FontWeight.Light,
                color      = LynxColors.TextPrimary
            )
            Text(
                "Mobile",
                fontFamily    = LynxTypography.Mono,
                fontSize      = 13.sp,
                color         = LynxColors.TextDim,
                letterSpacing = 3.sp
            )
        }

        Spacer(Modifier.weight(1f))

        // Akcje
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LynxSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
        ) {
            // Wybierz plik
            Button(
                onClick  = onFileClick,
                modifier = Modifier.fillMaxWidth().height(LynxSpacing.TouchTarget),
                shape    = RoundedCornerShape(LynxShapes.ButtonRadius),
                colors   = ButtonDefaults.buttonColors(containerColor = LynxColors.Blue)
            ) {
                Text("Wybierz plik", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            // Wklej tekst
            OutlinedTextField(
                value         = pastedText,
                onValueChange = { pastedText = it },
                modifier      = Modifier.fillMaxWidth().heightIn(min = LynxSpacing.TouchTarget),
                placeholder   = {
                    Text("Wklej tekst...", color = LynxColors.TextDim, fontSize = 14.sp)
                },
                shape  = RoundedCornerShape(LynxShapes.CardRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = LynxColors.Blue,
                    unfocusedBorderColor = LynxColors.Border
                )
            )
            if (pastedText.isNotBlank()) {
                Button(
                    onClick  = { onTextSubmit(pastedText) },
                    modifier = Modifier.fillMaxWidth().height(LynxSpacing.TouchTarget),
                    shape    = RoundedCornerShape(LynxShapes.ButtonRadius),
                    colors   = ButtonDefaults.buttonColors(containerColor = LynxColors.Blue)
                ) {
                    Text("Pseudonimizuj", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

// ── Dolna nawigacja 2+2 ───────────────────────────────────────────────────────
@Composable
private fun BottomNavBar(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    var showSecurity by remember { mutableStateOf(false) }

    if (showSecurity) {
        SecurityModal(onDismiss = { showSecurity = false })
    }

    Column(modifier = Modifier.fillMaxWidth().background(LynxColors.Sidebar)) {
        HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

        // Górny rząd: Ukryj dane | Odkryj dane
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LynxSpacing.sm, vertical = LynxSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
        ) {
            NavButton(
                label    = "Ukryj dane",
                selected = current == AppScreen.MAIN,
                modifier = Modifier.weight(1f),
                onClick  = { onNavigate(AppScreen.MAIN) }
            )
            NavButton(
                label    = "Odkryj dane",
                selected = current == AppScreen.DEPSEUDO,
                modifier = Modifier.weight(1f),
                onClick  = { onNavigate(AppScreen.DEPSEUDO) }
            )
        }

        // Dolny rząd: Biblioteka | Zabezpieczenia
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = LynxSpacing.sm)
                .padding(bottom = LynxSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
        ) {
            NavButton(
                label    = "Biblioteka",
                selected = current == AppScreen.LIBRARY,
                modifier = Modifier.weight(1f),
                onClick  = { onNavigate(AppScreen.LIBRARY) }
            )
            NavButton(
                label    = "Zabezpieczenia",
                selected = false,
                modifier = Modifier.weight(1f),
                onClick  = { showSecurity = true }
            )
        }
    }
}

@Composable
private fun NavButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick  = onClick,
        modifier = modifier.height(LynxSpacing.TouchTarget),
        shape    = RoundedCornerShape(LynxShapes.ButtonRadius),
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (selected) LynxColors.Blue else LynxColors.ActiveNav
        )
    ) {
        Text(
            label,
            fontSize   = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color      = if (selected) LynxColors.TextPrimary else LynxColors.TextMuted,
            maxLines   = 1
        )
    }
}

// ── Zabezpieczenia — modal Art. 17 RODO ──────────────────────────────────────
@Composable
private fun SecurityModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteInProgress  by remember { mutableStateOf(false) }

    // Krok 2 — dialog potwierdzenia nieodwracalnej operacji
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deleteInProgress) showDeleteConfirm = false },
            title = { Text("Usun\u0105\u0107 wszystkie dane?") },
            text  = {
                Text(
                    "Ta operacja jest nieodwracalna.\n\n" +
                    "Wszystkie sesje, mapy token\u00f3w i odpowiedzi AI zostan\u0105 " +
                    "trwale usuni\u0119te z urz\u0105dzenia.",
                    fontSize = 13.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteInProgress = true
                        scope.launch(Dispatchers.IO) {
                            SessionStore.deleteAllData(context)
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                deleteInProgress  = false
                                showDeleteConfirm = false
                                onDismiss()
                            }
                        }
                    },
                    enabled = !deleteInProgress
                ) {
                    if (deleteInProgress) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color       = LynxColors.Red
                        )
                    } else {
                        Text(
                            "Usu\u0144 wszystko",
                            color      = LynxColors.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick  = { showDeleteConfirm = false },
                    enabled  = !deleteInProgress
                ) { Text("Anuluj") }
            },
            containerColor = LynxColors.Surface
        )
    }

    // Krok 1 — główny modal Zabezpieczenia
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zabezpieczenia") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)) {

                // Informacje o szyfrowaniu
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "SZYFROWANIE",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 9.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "AES-256-GCM \u2014 Android Keystore\n" +
                        "Has\u0142o: PBKDF2-HMAC-SHA256 (310 000 iter.)\n" +
                        "Dane wychodz\u0105ce: \u017cadne\n" +
                        "Baza: SQLCipher",
                        fontSize   = 12.sp,
                        lineHeight = 19.sp,
                        color      = LynxColors.TextSecondary
                    )
                }

                HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

                // Usuwanie danych — Art. 17 RODO
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "DANE",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 9.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Usu\u0144 wszystkie sesje, mapy token\u00f3w i odpowiedzi z urz\u0105dzenia.",
                        fontSize   = 12.sp,
                        lineHeight = 17.sp,
                        color      = LynxColors.TextSecondary
                    )
                    Button(
                        onClick  = { showDeleteConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LynxSpacing.TouchTarget),
                        shape  = androidx.compose.foundation.shape.RoundedCornerShape(LynxShapes.ButtonRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LynxColors.Surface
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LynxColors.Red)
                    ) {
                        Text(
                            "Usu\u0144 wszystkie dane",
                            color      = LynxColors.Red,
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zamknij", color = LynxColors.Blue)
            }
        },
        containerColor = LynxColors.Surface
    )
}

@Composable
fun MainScreen(onFileClick: () -> Unit) {
    HubScreen(onFileClick = onFileClick, onDepseudoClick = {}, onTextSubmit = {})
}
