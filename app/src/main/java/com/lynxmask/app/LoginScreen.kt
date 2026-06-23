package com.lynxmask.app

// LoginScreen.kt — v2.0 (Potok RODO, 10.06.2026)
//
// ZMIANY v2.0:
//
//   [RODO-3a] hashWithSalt(): SHA-256 → PBKDF2-HMAC-SHA256 (310 000 iteracji)
//     Poprzednie: MessageDigest("SHA-256").apply { update(salt) }.digest(...)
//     Nowe:       PBEKeySpec(password, salt, 310_000, 256) + SecretKeyFactory
//     Standard:   OWASP 2023. Bez nowych zależności (standardowy Android SDK).
//     Usunięto:   import java.security.MessageDigest
//     Dodano:     import javax.crypto.SecretKeyFactory, javax.crypto.spec.PBEKeySpec
//     Linia ~67 oryginału.
//
//   [RODO-3b] Migracja SHA-256 → PBKDF2 (KEY_HASH_VERSION):
//     Nowe stałe: KEY_HASH_VERSION = "hash_version", HASH_VERSION_CURRENT = 2
//     setLoginPassword(): zapisuje pref_hash_version = 2 po każdym ustawieniu.
//     verifyLoginPassword(): jeśli version < 2 → czyści prefs, zwraca false.
//     isPasswordSet(): jeśli version < 2 → czyści prefs i zwraca false
//       (tryb isFirstRun aktywowany automatycznie przez MainActivity).
//     Aplikacja nie jest w produkcji — brak żywych haseł SHA-256 do migracji.
//
//   [BUG-ANR] PBKDF2 na wątku UI → ANR na słabych urządzeniach (Samsung A53):
//     310 000 iteracji PBKDF2 ≈ 1–3s CPU. Uruchomienie na Main → ANR.
//     Fix: submit() używa rememberCoroutineScope() + Dispatchers.IO.
//     Dodano: stan isLoading, CircularProgressIndicator podczas weryfikacji.
//     Import: rememberCoroutineScope, kotlinx.coroutines.{Dispatchers,launch,withContext}
//
//   [RODO-3c] Komunikat migracji ("Dla bezpieczeństwa…"):
//     LaunchedEffect(Unit) wykrywa stary hash przy wejściu na ekran logowania.
//     Po wykryciu: czyści prefs, ustawia migrationNeeded=true.
//     effectiveIsFirstRun = isFirstRun || migrationNeeded → przełącza UI w tryb setup.
//     Baner informacyjny widoczny gdy migrationNeeded=true.
//
//   [BEZPIECZEŃSTWO] spec.clearPassword() po użyciu PBEKeySpec:
//     Usuwa hasło z pamięci JVM po obliczeniu hasha.
//
// NIE ZMIENIONO:
//   Układ wizualny karty logowania, PSE badge, dialog resetu hasła.
//   Minimalna długość hasła (4 znaki) — UX decision, nie zmieniane w tym potoku.
//
// TODO (przyszłe potoki):
//   - BiometricPrompt jako alternatywa (Potok 6.2)
//   - Hasło główne jako klucz szyfrowania SQLCipher (obecnie oddzielone)
//   - Minimum 8 znaków (OWASP) — wymaga decyzji UX

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

// ── Auth helpers ──────────────────────────────────────────────────────────────

private const val PREFS_LOGIN        = "lynxmask_login"
private const val KEY_PASSWORD_HASH  = "password_hash"
private const val KEY_PASSWORD_SALT  = "password_salt"
private const val KEY_HASH_VERSION   = "hash_version"   // [RODO-3b]
private const val HASH_VERSION_CURRENT = 2              // 1=SHA-256 (stary), 2=PBKDF2

/**
 * Sprawdza czy hasło jest ustawione.
 *
 * [RODO-3b] Jeśli znaleziono stary hash (version < 2, SHA-256),
 * czyści prefs i zwraca false — MainActivity zobaczy isFirstRun=true.
 */
fun isPasswordSet(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
    val version = prefs.getInt(KEY_HASH_VERSION, 1)
    if (version < HASH_VERSION_CURRENT && prefs.getString(KEY_PASSWORD_HASH, null) != null) {
        // Stary SHA-256 hash — wymuś reset. Czyścimy tutaj żeby MainActivity
        // dostało isFirstRun=true bez dodatkowej logiki.
        prefs.edit().clear().apply()
        return false
    }
    return prefs.getString(KEY_PASSWORD_HASH, null) != null
}

/**
 * Ustawia lub nadpisuje hasło główne.
 * Generuje nową sól, oblicza PBKDF2, zapisuje z version=2.
 */
fun setLoginPassword(context: Context, password: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val hash = hashWithSalt(password, salt)
    context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE).edit()
        .putString(KEY_PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
        .putString(KEY_PASSWORD_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
        .putInt(KEY_HASH_VERSION, HASH_VERSION_CURRENT)  // [RODO-3b]
        .apply()
}

/**
 * Weryfikuje hasło względem zapisanego hasha PBKDF2.
 * Zwraca false jeśli hasło błędne, prefs puste lub wersja hasha < 2.
 *
 * [RODO-3b] Wersja < 2 nie powinna tu dotrzeć (isPasswordSet() czyści wcześniej),
 * ale obsługujemy defensywnie.
 */
fun verifyLoginPassword(context: Context, password: String): Boolean {
    val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
    if (prefs.getInt(KEY_HASH_VERSION, 1) < HASH_VERSION_CURRENT) {
        prefs.edit().clear().apply()
        return false
    }
    val saltB64 = prefs.getString(KEY_PASSWORD_SALT, null) ?: return false
    val hashB64 = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
    val salt     = Base64.decode(saltB64, Base64.NO_WRAP)
    val expected = Base64.decode(hashB64, Base64.NO_WRAP)
    return hashWithSalt(password, salt).contentEquals(expected)
}

/**
 * PBKDF2-HMAC-SHA256 z 310 000 iteracjami (OWASP 2023).
 *
 * [RODO-3a] Zastępuje poprzedni SHA-256 bez iteracji.
 * spec.clearPassword() usuwa hasło z pamięci JVM po użyciu.
 * SecretKeyFactory — standardowy Android SDK, bez nowych zależności.
 *
 * UWAGA: wywołanie zajmuje 1–3s na słabych urządzeniach.
 * Zawsze wywoływać na Dispatchers.IO, nigdy na Main thread.
 */
private fun hashWithSalt(password: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(
        password.toCharArray(),
        salt,
        310_000,   // iteracje — OWASP 2023
        256        // długość klucza w bitach → 32 bajty output
    )
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    } finally {
        spec.clearPassword()  // czyści hasło z pamięci JVM
    }
}

// ── UI ────────────────────────────────────────────────────────────────────────

@Composable
fun LoginScreen(
    isFirstRun: Boolean = false,
    onAuthenticated: () -> Unit
) {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()

    var password        by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf("") }
    var isLoading       by remember { mutableStateOf(false) }  // [BUG-ANR]
    val focusRequester  = remember { FocusRequester() }

    // [RODO-3c] Wykrycie starego SHA-256 hasha przy wejściu na ekran.
    // Jeśli isPasswordSet() już wyczyściło prefs (isFirstRun=true), ten efekt
    // nie uruchomi sprawdzenia. Obsługuje edge-case: prefs wyczyszczone po
    // renderowaniu ekranu (race condition między MainActivity a pierwszą kompozycją).
    var migrationNeeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!isFirstRun) {
            val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_HASH_VERSION, 1) < HASH_VERSION_CURRENT) {
                withContext(Dispatchers.IO) { prefs.edit().clear().apply() }
                migrationNeeded = true
            }
        }
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    // effectiveIsFirstRun: true gdy pierwsze uruchomienie LUB migracja hasha
    val effectiveIsFirstRun = isFirstRun || migrationNeeded

    // [BUG-ANR] submit() uruchamia PBKDF2 na Dispatchers.IO — nigdy na Main thread.
    fun submit() {
        if (password.length < 4) {
            errorMessage = "Has\u0142o musi mie\u0107 co najmniej 4 znaki"
            return
        }
        isLoading = true
        errorMessage = ""
        scope.launch {
            if (effectiveIsFirstRun) {
                withContext(Dispatchers.IO) { setLoginPassword(context, password) }
                // Powrót na Main thread jest automatyczny po withContext — onAuthenticated
                // jest wywoływane na Main (domyślny dispatcher composable scope).
                onAuthenticated()
            } else {
                val ok = withContext(Dispatchers.IO) {
                    verifyLoginPassword(context, password)
                }
                isLoading = false
                if (ok) {
                    onAuthenticated()
                } else {
                    errorMessage = "B\u0142\u0119dne has\u0142o"
                    password = ""
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LynxColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {

        // ── Karta logowania ───────────────────────────────────────────────
        Card(
            modifier  = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape     = RoundedCornerShape(12.dp),
            colors    = CardDefaults.cardColors(containerColor = LynxColors.Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier            = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
            ) {

                // Nagłówek karty: PSE badge + LynxMask Mobile + subtitle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier            = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "PSE",
                            fontFamily = LynxTypography.Mono,
                            fontSize   = 9.sp,
                            color      = LynxColors.Blue,
                            modifier   = Modifier
                                .border(1.dp, LynxColors.BorderActive, RoundedCornerShape(2.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "LynxMask Mobile",
                            fontFamily = LynxTypography.Sans,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = LynxColors.TextPrimary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pseudonimizacja dokument\u00f3w",
                        fontSize = 12.sp,
                        color    = LynxColors.TextSecondary
                    )
                }

                HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

                // [RODO-3c] Baner migracji hasha — widoczny gdy stary SHA-256 wykryty
                if (migrationNeeded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, LynxColors.Blue, RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "\u2139\uFE0F",  // ℹ️
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
                        )
                        Text(
                            "Dla bezpiecze\u0144stwa konieczne jest ponowne ustawienie has\u0142a.",
                            fontSize   = 12.sp,
                            lineHeight = 17.sp,
                            color      = LynxColors.TextSecondary
                        )
                    }
                }

                // Pole hasła
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (effectiveIsFirstRun) "USTAW HAS\u0141O" else "HAS\u0141O G\u0141\u00d3WNE",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 10.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.5.sp
                    )
                    OutlinedTextField(
                        value         = password,
                        onValueChange = { if (!isLoading) { password = it; errorMessage = "" } },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder   = {
                            Text("Wpisz has\u0142o...", color = LynxColors.TextDim, fontSize = 14.sp)
                        },
                        singleLine            = true,
                        enabled               = !isLoading,
                        visualTransformation  = if (passwordVisible)
                            VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions       = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction    = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (!isLoading) submit() }),
                        trailingIcon    = {
                            IconButton(
                                onClick  = { passwordVisible = !passwordVisible },
                                enabled  = !isLoading
                            ) {
                                Text(
                                    if (passwordVisible) "\uD83D\uDE48" else "\uD83D\uDC41",
                                    fontSize = 16.sp
                                )
                            }
                        },
                        shape  = RoundedCornerShape(LynxShapes.CardRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = LynxColors.Blue,
                            unfocusedBorderColor = LynxColors.Border
                        )
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, fontSize = 12.sp, color = LynxColors.Red)
                    }
                }

                // Przycisk Odblokuj / loading indicator
                Button(
                    onClick  = { if (!isLoading) submit() },
                    enabled  = password.isNotEmpty() && !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LynxSpacing.TouchTarget),
                    shape  = RoundedCornerShape(LynxShapes.ButtonRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = LynxColors.Blue,
                        disabledContainerColor = LynxColors.Surface
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier  = Modifier.size(18.dp),
                            color     = LynxColors.TextPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            if (effectiveIsFirstRun) "Ustaw i odblokuj" else "Odblokuj",
                            fontWeight = FontWeight.Medium,
                            fontSize   = 14.sp
                        )
                    }
                }

                // Zapomnij hasła — widoczne tylko w trybie logowania (nie w firstRun/migracji)
                if (!effectiveIsFirstRun) {
                    var showResetDialog by remember { mutableStateOf(false) }

                    if (showResetDialog) {
                        AlertDialog(
                            onDismissRequest = { showResetDialog = false },
                            title = { Text("Resetuj has\u0142o?") },
                            text  = {
                                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "\u26a0\ufe0f UWAGA: ca\u0142a biblioteka dokument\u00f3w zostanie trwale usuni\u0119ta.",
                                        fontSize = 13.sp, lineHeight = 19.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFFCC3333)
                                    )
                                    Text(
                                        "Je\u015bli pami\u0119tasz has\u0142o, u\u017cyj opcji \u201eZmie\u0144 has\u0142o\u201d w Zabezpieczeniach \u2014 dane zostan\u0105 zachowane.\n\n" +
                                        "Reset jest nieodwracalny i s\u0142u\u017cy tylko gdy has\u0142o jest ca\u0142kowicie zapomniane.",
                                        fontSize = 13.sp, lineHeight = 19.sp
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    // [luka reset has\u0142a fix] kasujemy dane przed usuni\u0119ciem has\u0142a \u2014
                                    // bez tego osoba z fizycznym dost\u0119pem do urz\u0105dzenia resetuje has\u0142o
                                    // i dostaje pe\u0142ny dost\u0119p do zaszyfrowanych sesji przez nowe has\u0142o
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            SessionStore.deleteAllData(context)
                                            UserDictionary.clear(context)
                                            GuardAllowlist.clear(context)
                                            context.getSharedPreferences("lynxmask_login", Context.MODE_PRIVATE)
                                                .edit().clear().apply()
                                        }
                                        // Po skasowaniu danych wróć do trybu "Ustaw hasło"
                                        migrationNeeded = true
                                        showResetDialog = false
                                    }
                                }) {
                                    Text("Resetuj", color = LynxColors.Red, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetDialog = false }) { Text("Anuluj") }
                            },
                            containerColor = LynxColors.Surface
                        )
                    }

                    TextButton(
                        onClick  = { showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Zapomnia\u0142em has\u0142a",
                            fontSize = 12.sp,
                            color    = LynxColors.TextDim
                        )
                    }
                }

                // Podpowiedź przy pierwszym uruchomieniu lub migracji
                if (effectiveIsFirstRun) {
                    Text(
                        "Has\u0142o zabezpiecza dost\u0119p do sesji.\nBez niego dane s\u0105 niedost\u0119pne.",
                        fontSize  = 11.sp,
                        color     = LynxColors.TextDim,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Wersja na dole ekranu
        Text(
            BuildConfig.VERSION_NAME + " \u2014 lynxmask.app",
            modifier      = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LynxSpacing.lg),
            fontFamily    = LynxTypography.Mono,
            fontSize      = 10.sp,
            color         = LynxColors.TextDim
        )
    }
}
