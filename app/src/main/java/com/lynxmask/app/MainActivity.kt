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
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import com.lynxmask.app.ui.components.LynxNavButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxDangerTextButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxMaskTheme
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppScreen { MAIN, LIBRARY, DEPSEUDO }

class MainActivity : FragmentActivity() {
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
                resetRegexCache()
                EngineSmoke.runOnce()
                SessionStore.init(this@MainActivity)
            }
            getExternalFilesDir("bench")?.mkdirs()
            setContent { LynxMaskTheme { AppNavigation() } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogBuffer.clearOnExit()
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    var onboardingDone   by remember         { mutableStateOf(isOnboardingDone(context)) }
    var authenticated    by rememberSaveable { mutableStateOf(false) }
    var showCrashDialog  by remember         { mutableStateOf(CrashHandler.hasPendingCrash(context)) }

    when {
        !onboardingDone -> OnboardingScreen(onFinished = { onboardingDone = true })
        !authenticated  -> LoginScreen(
            isFirstRun      = !isPasswordSet(context),
            onAuthenticated = { authenticated = true }
        )
        else -> MainTabNav()
    }

    if (showCrashDialog) {
        CrashReportDialog(
            onSend = {
                val intent = CrashHandler.buildEmailIntent(context)
                context.startActivity(Intent.createChooser(intent, "Wyślij raport błędu"))
                CrashHandler.clearPendingCrash(context)
                showCrashDialog = false
            },
            onDismiss = {
                CrashHandler.clearPendingCrash(context)
                showCrashDialog = false
            }
        )
    }
}

@Composable
private fun CrashReportDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nieoczekiwany błąd", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Aplikacja napotkała nieoczekiwany błąd podczas poprzedniego uruchomienia.\n\n" +
                "Raport nie zawiera żadnych danych osobowych — tylko informacje techniczne o urządzeniu.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            LynxPrimaryButton(onClick = onSend, modifier = Modifier.fillMaxWidth()) {
                Text("Wyślij raport")
            }
        },
        dismissButton = {
            LynxGhostButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Pomiń", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LynxColors.Background)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (appScreen) {
                AppScreen.MAIN -> HubScreen(
                    onFileClick = {
                        filePickerLauncher.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/pdf", "text/plain", "image/*"
                        ))
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
                    onBack = {
                        selectedSessionId    = null
                        appScreen = AppScreen.MAIN
                    }
                )
            }
        }
        BottomNavBar(
            current    = appScreen,
            onNavigate = { screen ->
                if (screen == AppScreen.DEPSEUDO) {
                    selectedSessionId    = null
                    selectedDepseudoMode = DepseudoMode.AI_RESPONSE
                }
                if (screen == AppScreen.MAIN) {
                    selectedSessionId = null
                }
                appScreen = screen
            }
        )
    }
}

// ── Hub — strona startowa ─────────────────────────────────────────────────────
@Composable
private fun HubScreen(
    onFileClick: () -> Unit,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = LynxSpacing.md, top = LynxSpacing.sm, start = LynxSpacing.md)
        ) {
            Text(
                "${BuildConfig.VERSION_NAME}  LynxMask Mobile",
                modifier = Modifier.align(Alignment.CenterEnd),
                fontSize = 10.sp,
                color = LynxColors.TextDim
            )
        }

        Spacer(Modifier.weight(0.42f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = LynxSpacing.lg)
        ) {
            Text(
                "PSE",
                fontFamily = LynxTypography.Mono,
                fontSize = 9.sp,
                color = LynxColors.Blue,
                modifier = Modifier
                    .border(1.dp, LynxColors.BorderActive, RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "LynxMask",
                fontFamily = LynxTypography.Sans,
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                color = LynxColors.TextPrimary
            )
            Text(
                "Mobile",
                fontFamily = LynxTypography.Mono,
                fontSize = 13.sp,
                color = LynxColors.TextDim,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Pseudonimizacja dokumentów",
                fontSize = 13.sp,
                color = LynxColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.weight(0.58f))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LynxSpacing.lg),
            shape = RoundedCornerShape(LynxShapes.CardRadius),
            colors = CardDefaults.cardColors(containerColor = LynxColors.Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(LynxSpacing.md),
                verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
            ) {
                LynxPrimaryButton(
                    onClick = onFileClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wybierz plik", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    "PDF · DOCX · TXT · obraz",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 11.sp,
                    color = LynxColors.TextDim,
                    textAlign = TextAlign.Center
                )
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = { pastedText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = LynxSpacing.TouchTarget, max = 120.dp),
                    placeholder = {
                        Text("Wklej tekst...", color = LynxColors.TextDim, fontSize = 14.sp)
                    },
                    shape = RoundedCornerShape(LynxShapes.ButtonRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LynxColors.ActiveNav,
                        unfocusedContainerColor = LynxColors.ActiveNav,
                        focusedBorderColor = LynxColors.Blue,
                        unfocusedBorderColor = LynxColors.Border
                    )
                )
                LynxPrimaryButton(
                    onClick = { onTextSubmit(pastedText) },
                    enabled = pastedText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pseudonimizuj", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(LynxSpacing.md))
    }
}

// ── Dolna nawigacja (hub = treść powyżej, bez osobnej zakładki) ───────────────
@Composable
private fun BottomNavBar(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    var showSecurity by remember { mutableStateOf(false) }

    if (showSecurity) {
        SecurityModal(onDismiss = { showSecurity = false })
    }

    Column(modifier = Modifier.fillMaxWidth().background(LynxColors.Sidebar)) {
        HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = LynxSpacing.sm, vertical = LynxSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
        ) {
            NavButton(
                label    = "Odkryj",
                selected = current == AppScreen.DEPSEUDO,
                modifier = Modifier.weight(1f),
                onClick  = {
                    if (current != AppScreen.DEPSEUDO) onNavigate(AppScreen.DEPSEUDO)
                }
            )
            NavButton(
                label    = "Biblioteka",
                selected = current == AppScreen.LIBRARY,
                modifier = Modifier.weight(1f),
                onClick  = {
                    if (current != AppScreen.LIBRARY) onNavigate(AppScreen.LIBRARY)
                }
            )
            NavButton(
                label    = "Zabezp.",
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
) = LynxNavButton(label = label, selected = selected, onClick = onClick, modifier = modifier)

// ── Zabezpieczenia — modal Art. 17 RODO ──────────────────────────────────────
@Composable
private fun SecurityModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var showDeleteConfirm     by remember { mutableStateOf(false) }
    var deleteInProgress      by remember { mutableStateOf(false) }
    var showChangePassword    by remember { mutableStateOf(false) }
    var showDictManager       by remember { mutableStateOf(false) }
    var dictEntries           by remember { mutableStateOf(UserDictionary.load(context)) }
    var oldPassword         by remember { mutableStateOf("") }
    var newPassword         by remember { mutableStateOf("") }
    var newPasswordConfirm  by remember { mutableStateOf("") }
    var changePasswordError by remember { mutableStateOf<String?>(null) }

    // Fallback SAF dla API < 29 (Android 9 i starsze)
    val exportFallbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val json = UserDictionary.exportToJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Słownik wyeksportowany", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Błąd eksportu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun exportDictionary() {
        scope.launch(Dispatchers.IO) {
            try {
                val json = UserDictionary.exportToJson()
                val filename = "lynxmask_slownik.lynxdict"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                    }
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(json.toByteArray(Charsets.UTF_8)) } }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Zapisano w Pobrane: $filename", Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        exportFallbackLauncher.launch(filename)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Błąd eksportu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: return@launch
                val added = UserDictionary.importFromJson(context, json)
                withContext(Dispatchers.Main) {
                    val msg = if (added >= 0) "Dodano $added wpisów do słownika" else "Błąd: nieprawidłowy plik"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Błąd importu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
                LynxGhostButton(
                    onClick = {
                        deleteInProgress = true
                        scope.launch(Dispatchers.IO) {
                            SessionStore.deleteAllData(context)
                            UserDictionary.clear(context)
                            GuardAllowlist.clear(context)
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
                LynxGhostButton(
                    onClick  = { showDeleteConfirm = false },
                    enabled  = !deleteInProgress
                ) { Text("Anuluj") }
            },
            containerColor = LynxColors.Surface
        )
    }

    // Dialog zmiany hasła
    if (showChangePassword) {
        AlertDialog(
            onDismissRequest = {
                showChangePassword = false
                oldPassword = ""; newPassword = ""; newPasswordConfirm = ""; changePasswordError = null
            },
            title = { Text("Zmień hasło") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (changePasswordError != null) {
                        Text(changePasswordError!!, color = LynxColors.Red, fontSize = 12.sp)
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it; changePasswordError = null },
                        label = { Text("Aktualne hasło", fontSize = 12.sp) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it; changePasswordError = null },
                        label = { Text("Nowe hasło (min. 4 znaki)", fontSize = 12.sp) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = newPasswordConfirm,
                        onValueChange = { newPasswordConfirm = it; changePasswordError = null },
                        label = { Text("Powtórz nowe hasło", fontSize = 12.sp) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                LynxPrimaryButton(onClick = {
                    when {
                        !verifyLoginPassword(context, oldPassword) ->
                            changePasswordError = "Nieprawidłowe aktualne hasło"
                        newPassword.length < 4 ->
                            changePasswordError = "Nowe hasło musi mieć co najmniej 4 znaki"
                        newPassword != newPasswordConfirm ->
                            changePasswordError = "Hasła nie są identyczne"
                        else -> {
                            setLoginPassword(context, newPassword)
                            showChangePassword = false
                            oldPassword = ""; newPassword = ""; newPasswordConfirm = ""; changePasswordError = null
                            Toast.makeText(context, "Hasło zostało zmienione", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("Zmień", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                LynxGhostButton(onClick = {
                    showChangePassword = false
                    oldPassword = ""; newPassword = ""; newPasswordConfirm = ""; changePasswordError = null
                }) { Text("Anuluj") }
            },
            containerColor = LynxColors.Surface
        )
    }

    // Dialog słownika własnego — przeglądaj i usuwaj wpisy
    if (showDictManager) {
        AlertDialog(
            onDismissRequest = { showDictManager = false },
            title = { Text("Słownik własny") },
            text = {
                if (dictEntries.isEmpty()) {
                    Text("Słownik jest pusty.", fontSize = 13.sp, color = LynxColors.TextSecondary)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(dictEntries, key = { "${it.first}|${it.second}" }) { (value, type) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    type,
                                    fontSize   = 10.sp,
                                    color      = LynxColors.Blue,
                                    fontFamily = LynxTypography.Mono,
                                    modifier   = Modifier.width(52.dp)
                                )
                                Text(
                                    value,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            UserDictionary.remove(context, value, type)
                                            val updated = UserDictionary.entries
                                            withContext(Dispatchers.Main) {
                                                dictEntries = updated
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("×", fontSize = 18.sp, color = LynxColors.Red)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                LynxGhostButton(onClick = { showDictManager = false }) { Text("Zamknij") }
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

                // Zmiana hasła
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "HASŁO",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 9.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Zmiana hasła nie usuwa biblioteki dokumentów. Jeśli zapomnisz hasła i użyjesz opcji reset w ekranie logowania — biblioteka zostanie trwale usunięta.",
                        fontSize   = 12.sp,
                        lineHeight = 17.sp,
                        color      = LynxColors.TextSecondary
                    )
                    LynxSecondaryButton(
                        onClick  = { showChangePassword = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Zmień hasło", fontSize = 13.sp)
                    }
                }

                HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

                // Kopia zapasowa słownika użytkownika
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SŁOWNIK",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 9.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Kopia zapasowa słownika własnego. Zalecana przed reinstalacją lub zmianą urządzenia. Format: .lynxdict (JSON)",
                        fontSize   = 12.sp,
                        lineHeight = 17.sp,
                        color      = LynxColors.TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
                    ) {
                        LynxSecondaryButton(
                            onClick  = { exportDictionary() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Eksportuj", fontSize = 13.sp)
                        }
                        LynxSecondaryButton(
                            onClick  = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.weight(1f),
                            accent = LynxColors.Blue
                        ) {
                            Text("Importuj", fontSize = 13.sp, color = LynxColors.Blue)
                        }
                    }
                    LynxSecondaryButton(
                        onClick = { showDictManager = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val n = dictEntries.size
                        Text(
                            if (n > 0) "Przeglądaj słownik ($n wpisów)"
                            else "Słownik jest pusty",
                            fontSize = 13.sp
                        )
                    }
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
                    LynxSecondaryButton(
                        onClick  = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        accent = LynxColors.Red
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
            LynxGhostButton(onClick = onDismiss) {
                Text("Zamknij", color = LynxColors.Blue)
            }
        },
        containerColor = LynxColors.Surface
    )
}

@Composable
fun MainScreen(onFileClick: () -> Unit) {
    HubScreen(onFileClick = onFileClick, onTextSubmit = {})
}
