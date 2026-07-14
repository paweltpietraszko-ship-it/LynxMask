package com.lynxmask.app

// LoginScreen.kt — v2.2 UI: nagłówek jak Hub, jeden Express, szybki start bez ciężkiej karty.

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.components.LynxDangerTextButton
import com.lynxmask.app.ui.components.LynxFilledButton
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
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

private enum class RecoveryStep { NONE, SHOW_KEY, ENTER_KEY, NEW_PASSWORD }

private const val PREFS_LOGIN        = "lynxmask_login"
private const val KEY_PASSWORD_HASH  = "password_hash"
private const val KEY_PASSWORD_SALT  = "password_salt"
private const val KEY_HASH_VERSION   = "hash_version"
private const val HASH_VERSION_CURRENT = 2

fun isPasswordSet(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
    val version = prefs.getInt(KEY_HASH_VERSION, 1)
    if (version < HASH_VERSION_CURRENT && prefs.getString(KEY_PASSWORD_HASH, null) != null) {
        prefs.edit().clear().apply()
        return false
    }
    return prefs.getString(KEY_PASSWORD_HASH, null) != null
}

fun setLoginPassword(context: Context, password: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val hash = hashWithSalt(password, salt)
    context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE).edit()
        .putString(KEY_PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
        .putString(KEY_PASSWORD_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
        .putInt(KEY_HASH_VERSION, HASH_VERSION_CURRENT)
        .apply()
}

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

private fun hashWithSalt(password: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

@Composable
fun LoginScreen(
    isFirstRun: Boolean = false,
    onAuthenticated: () -> Unit,
    onExpressMode: (() -> Unit)? = null
) {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()

    var password        by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf("") }
    var isLoading       by remember { mutableStateOf(false) }
    val focusRequester  = remember { FocusRequester() }

    var recoveryStep  by rememberSaveable { mutableStateOf(RecoveryStep.NONE) }
    var generatedKey  by remember { mutableStateOf("") }
    var keySaved      by remember { mutableStateOf(false) }
    var recoveryInput by rememberSaveable { mutableStateOf("") }
    var recoveryError by remember { mutableStateOf("") }
    var showRecoveryChoice  by remember { mutableStateOf(false) }
    var showPrivacyPolicy   by remember { mutableStateOf(false) }

    // Dla istniejących użytkowników bez klucza: sprawdź po logowaniu i wygeneruj klucz
    fun checkKeyThenAuthenticate() {
        val keySet = RecoveryKeyManager.isKeySet(context)
        if (!keySet) {
            generatedKey = RecoveryKeyManager.generateKey()
            keySaved = false
            recoveryStep = RecoveryStep.SHOW_KEY
        } else {
            onAuthenticated()
        }
    }

    val canBiometric = remember(context) {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    var showPasswordForm by remember { mutableStateOf(false) }

    val biometricPrompt = remember(context) {
        BiometricPrompt(
            context as FragmentActivity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    checkKeyThenAuthenticate()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        showPasswordForm = true
                    }
                    // ERROR_USER_CANCELED → zostaje karta biometryczna z przyciskiem Express
                }
            }
        )
    }

    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("LynxMask")
            .setSubtitle("Przyłóż palec, aby odblokować")
            .setNegativeButtonText("Użyj hasła")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
    }

    var migrationNeeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!isFirstRun) {
            val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_HASH_VERSION, 1) < HASH_VERSION_CURRENT) {
                withContext(Dispatchers.IO) { prefs.edit().clear().apply() }
                migrationNeeded = true
            }
        }
        if (migrationNeeded) {
            showPasswordForm = true
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        } else if (isFirstRun || !canBiometric) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
        // canBiometric && !isFirstRun → karta biometryczna, użytkownik sam naciska "Odblokuj"
    }

    val effectiveIsFirstRun = isFirstRun || migrationNeeded
    val showBiometricCard = canBiometric && !effectiveIsFirstRun && !showPasswordForm &&
        recoveryStep == RecoveryStep.NONE

    fun submit() {
        if (password.length < 4) {
            errorMessage = "Hasło musi mieć co najmniej 4 znaki"
            return
        }
        isLoading = true
        errorMessage = ""
        scope.launch {
            when {
                recoveryStep == RecoveryStep.NEW_PASSWORD -> {
                    withContext(Dispatchers.IO) { setLoginPassword(context, password) }
                    onAuthenticated()
                }
                effectiveIsFirstRun -> {
                    withContext(Dispatchers.IO) { setLoginPassword(context, password) }
                    generatedKey = RecoveryKeyManager.generateKey()
                    recoveryStep = RecoveryStep.SHOW_KEY
                    isLoading = false
                }
                else -> {
                    val ok = withContext(Dispatchers.IO) { verifyLoginPassword(context, password) }
                    isLoading = false
                    if (ok) checkKeyThenAuthenticate()
                    else { errorMessage = "Błędne hasło"; password = "" }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LynxColors.Background)
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LynxSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // BUG-WERSJA-NIESPOJNA (11.07): ta sama etykieta co w Hub, ten sam róg —
            // wcześniej wersja siedziała na dole ekranu logowania, inaczej niż wszędzie indziej.
            Box(modifier = Modifier.fillMaxWidth().padding(top = LynxSpacing.sm)) {
                Text(
                    "${BuildConfig.VERSION_NAME}  LynxMask Mobile",
                    modifier = Modifier.align(Alignment.CenterEnd),
                    fontFamily = LynxTypography.Sans,
                    fontSize = 10.sp,
                    color = LynxColors.TextDim
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = LynxSpacing.md, bottom = LynxSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LynxLoginHeader()
                Spacer(Modifier.height(LynxSpacing.xl))

                Column(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
                ) {
                    if (recoveryStep == RecoveryStep.SHOW_KEY) {
                        ShowKeyContent(
                            key        = generatedKey,
                            keySaved   = keySaved,
                            onSavedChange = { keySaved = it },
                            onConfirm  = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        RecoveryKeyManager.saveKey(context, generatedKey)
                                    }
                                    onAuthenticated()
                                }
                            }
                        )
                    } else if (recoveryStep == RecoveryStep.ENTER_KEY) {
                        EnterKeyContent(
                            input       = recoveryInput,
                            onInputChange = { recoveryInput = it; recoveryError = "" },
                            error       = recoveryError,
                            onVerify    = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        RecoveryKeyManager.verify(context, recoveryInput)
                                    }
                                    if (ok) {
                                        recoveryStep = RecoveryStep.NEW_PASSWORD
                                        password = ""
                                        recoveryError = ""
                                    } else {
                                        recoveryError = "Nieprawidłowy klucz"
                                    }
                                }
                            }
                        )
                    } else if (showBiometricCard) {
                        // BUG-LOGIN-TEKST-POWTORZONY (11.07): "Odblokuj aplikację" jako osobna
                        // etykieta nad przyciskiem "Odblokuj" powtarzało to samo dwa razy —
                        // jeden wypełniony przycisk z ikoną niesie ten sam komunikat raz.
                        LynxFilledButton(
                            label = "Odblokuj aplikację",
                            icon = Icons.Outlined.Fingerprint,
                            onClick = { biometricPrompt.authenticate(promptInfo) }
                        )
                        LynxGhostButton(
                            onClick = { showPasswordForm = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Użyj hasła", fontFamily = LynxTypography.Sans, fontSize = 12.sp, color = LynxColors.TextDim)
                        }
                    } else {
                    if (migrationNeeded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, LynxColors.Blue, RoundedCornerShape(LynxShapes.ChipRadius))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "Dla bezpieczeństwa konieczne jest ponowne ustawienie hasła.",
                                fontFamily = LynxTypography.Sans,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = LynxColors.TextSecondary
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                recoveryStep == RecoveryStep.NEW_PASSWORD -> "NOWE HASŁO"
                                effectiveIsFirstRun -> "USTAW HASŁO"
                                else -> "HASŁO GŁÓWNE"
                            },
                            fontFamily = LynxTypography.Mono,
                            fontSize = 10.sp,
                            color = LynxColors.Blue,
                            letterSpacing = 1.5.sp
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { if (!isLoading) { password = it; errorMessage = "" } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            placeholder = {
                                Text("Wpisz hasło...", fontFamily = LynxTypography.Sans, color = LynxColors.TextDim, fontSize = 14.sp)
                            },
                            singleLine = true,
                            enabled = !isLoading,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { if (!isLoading) submit() }),
                            trailingIcon = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        contentDescription = if (passwordVisible) {
                                            "Ukryj hasło"
                                        } else {
                                            "Pokaż hasło"
                                        },
                                        tint = LynxColors.TextSecondary
                                    )
                                }
                            },
                            shape = RoundedCornerShape(LynxShapes.ButtonRadius),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LynxColors.ActiveNav,
                                unfocusedContainerColor = LynxColors.ActiveNav,
                                focusedBorderColor = LynxColors.Blue,
                                unfocusedBorderColor = LynxColors.Border
                            )
                        )
                        if (errorMessage.isNotEmpty()) {
                            Text(errorMessage, fontFamily = LynxTypography.Sans, fontSize = 12.sp, color = LynxColors.Red)
                        }
                    }

                    LynxFilledButton(
                        label = when {
                            recoveryStep == RecoveryStep.NEW_PASSWORD -> "Ustaw nowe hasło"
                            effectiveIsFirstRun -> "Ustaw i odblokuj"
                            else -> "Odblokuj"
                        },
                        enabled = password.isNotEmpty(),
                        loading = isLoading,
                        onClick = { if (!isLoading) submit() }
                    )

                    if (!effectiveIsFirstRun && recoveryStep == RecoveryStep.NONE) {
                        var showResetDialog by remember { mutableStateOf(false) }

                        // Krok 1: pytanie o klucz dostępu
                        if (showRecoveryChoice) {
                            AlertDialog(
                                onDismissRequest = { showRecoveryChoice = false },
                                shape = RoundedCornerShape(LynxShapes.CardRadius),
                                title = { Text("Nie pamiętasz hasła?", fontFamily = LynxTypography.Sans) },
                                text = {
                                    Text(
                                        "Masz klucz dostępu wygenerowany przy pierwszym logowaniu?",
                                        fontFamily = LynxTypography.Sans,
                                        fontSize = 13.sp, lineHeight = 19.sp
                                    )
                                },
                                confirmButton = {
                                    LynxPrimaryButton(onClick = {
                                        showRecoveryChoice = false
                                        recoveryStep = RecoveryStep.ENTER_KEY
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Tak, mam klucz", fontFamily = LynxTypography.Sans, fontSize = 13.sp)
                                    }
                                },
                                dismissButton = {
                                    LynxDangerTextButton(onClick = {
                                        showRecoveryChoice = false
                                        showResetDialog = true
                                    }, label = "Nie, resetuj dane")
                                },
                                containerColor = LynxColors.Surface
                            )
                        }

                        // Krok 2: potwierdzenie wipe (tylko gdy brak klucza)
                        if (showResetDialog) {
                            AlertDialog(
                                onDismissRequest = { showResetDialog = false },
                                shape = RoundedCornerShape(LynxShapes.CardRadius),
                                title = { Text("Resetuj hasło?", fontFamily = LynxTypography.Sans) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "UWAGA: cała biblioteka dokumentów zostanie trwale usunięta.",
                                            fontFamily = LynxTypography.Sans,
                                            fontSize = 13.sp, lineHeight = 19.sp,
                                            fontWeight = FontWeight.Bold, color = LynxColors.Red
                                        )
                                        Text(
                                            "Reset jest nieodwracalny i służy tylko gdy hasło jest całkowicie zapomniane.",
                                            fontFamily = LynxTypography.Sans,
                                            fontSize = 13.sp, lineHeight = 19.sp
                                        )
                                    }
                                },
                                confirmButton = {
                                    LynxDangerTextButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    SessionStore.deleteAllData(context)
                                                    UserDictionary.clear(context)
                                                    GuardAllowlist.clear(context)
                                                    RecoveryKeyManager.clearKey(context)
                                                    context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
                                                        .edit().clear().apply()
                                                }
                                                migrationNeeded = true
                                                showResetDialog = false
                                            }
                                        },
                                        label = "Resetuj"
                                    )
                                },
                                dismissButton = {
                                    LynxGhostButton(onClick = { showResetDialog = false }) {
                                        Text("Anuluj", fontFamily = LynxTypography.Sans)
                                    }
                                },
                                containerColor = LynxColors.Surface
                            )
                        }

                        LynxGhostButton(
                            onClick = { showRecoveryChoice = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Zapomniałem hasła", fontFamily = LynxTypography.Sans, fontSize = 12.sp, color = LynxColors.TextDim)
                        }
                    }

                    if (effectiveIsFirstRun) {
                        Text(
                            "Hasło zabezpiecza dostęp do sesji.\nBez niego dane są niedostępne.",
                            fontFamily = LynxTypography.Sans,
                            fontSize = 11.sp,
                            color = LynxColors.TextDim,
                            lineHeight = 16.sp
                        )
                    }
                    } // else (password form)
                }
            }

            Column(
                modifier = Modifier
                    .padding(bottom = LynxSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
            ) {
                if (onExpressMode != null && recoveryStep == RecoveryStep.NONE) {
                    LynxGhostButton(
                        onClick  = onExpressMode,
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        Text(
                            "⚡ Tryb Express — bez logowania",
                            fontFamily = LynxTypography.Sans,
                            fontSize = 12.sp,
                            color    = LynxColors.Amber
                        )
                    }
                }
                LynxGhostButton(
                    onClick  = { showPrivacyPolicy = true },
                    modifier = Modifier.fillMaxWidth(0.88f)
                ) {
                    Text(
                        "Polityka prywatności",
                        fontFamily = LynxTypography.Sans,
                        fontSize = 11.sp,
                        color    = LynxColors.TextDim
                    )
                }
                if (showPrivacyPolicy) {
                    PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
                }
            }
        }
    }
}


@Composable
private fun LynxLoginHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            fontFamily = LynxTypography.Sans,
            fontSize = 13.sp,
            color = LynxColors.TextSecondary
        )
    }
}

@Composable
private fun ShowKeyContent(
    key: String,
    keySaved: Boolean,
    onSavedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Klucz dostępu", fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Zapisz klucz w bezpiecznym miejscu. Pozwoli odzyskać dostęp bez utraty danych gdy zapomnisz hasła.",
            fontFamily = LynxTypography.Sans,
            fontSize = 13.sp, lineHeight = 19.sp, color = LynxColors.TextDim
        )
        Text(
            key,
            fontFamily = LynxTypography.Mono,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        LynxGhostButton(
            onClick = { clipboard.setText(AnnotatedString(key)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Kopiuj klucz", fontFamily = LynxTypography.Sans, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = keySaved, onCheckedChange = onSavedChange)
            Spacer(Modifier.width(8.dp))
            Text("Zapisałem klucz w bezpiecznym miejscu", fontFamily = LynxTypography.Sans, fontSize = 13.sp)
        }
        LynxPrimaryButton(
            onClick = onConfirm,
            enabled = keySaved,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Kontynuuj", fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EnterKeyContent(
    input: String,
    onInputChange: (String) -> Unit,
    error: String,
    onVerify: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Wpisz klucz dostępu", fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Wpisz 24-znakowy klucz wygenerowany przy pierwszym logowaniu (myślniki opcjonalne).",
            fontFamily = LynxTypography.Sans,
            fontSize = 13.sp, lineHeight = 19.sp, color = LynxColors.TextDim
        )
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("KLUCZ DOSTĘPU", fontFamily = LynxTypography.Sans) },
            placeholder = { Text("XXXXXX-XXXXXX-XXXXXX-XXXXXX", fontFamily = LynxTypography.Mono) },
            isError = error.isNotEmpty(),
            supportingText = if (error.isNotEmpty()) ({ Text(error, fontFamily = LynxTypography.Sans, color = MaterialTheme.colorScheme.error) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onVerify() }),
            shape = RoundedCornerShape(LynxShapes.ButtonRadius),
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = LynxTypography.Mono, letterSpacing = 1.sp)
        )
        LynxPrimaryButton(
            onClick = onVerify,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zweryfikuj klucz", fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}
