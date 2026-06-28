package com.lynxmask.app

// LoginScreen.kt — v2.1 UI: wspólne przyciski Lynx, forma podniesiona pod klawiaturę (imePadding).

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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.components.LynxDangerTextButton
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

// Klawiatura: imePadding(); forma lekko poniżej środka (po korekcie z góry).
private val LoginFormOffset = 38.dp

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
    var showRecoveryChoice by remember { mutableStateOf(false) }

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
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        showPasswordForm = true
                    }
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
        if (!isFirstRun && !migrationNeeded && canBiometric) {
            biometricPrompt.authenticate(promptInfo)
        } else if (migrationNeeded) {
            showPasswordForm = true
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        } else {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
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
                .fillMaxWidth()
                .align(Alignment.Center)
                .offset(y = LoginFormOffset)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LynxSpacing.lg)
                .padding(bottom = LoginFormOffset),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.88f),
                shape = RoundedCornerShape(LynxShapes.CardRadius),
                colors = CardDefaults.cardColors(containerColor = LynxColors.Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "PSE",
                                fontFamily = LynxTypography.Mono,
                                fontSize = 9.sp,
                                color = LynxColors.Blue,
                                modifier = Modifier
                                    .border(1.dp, LynxColors.BorderActive, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "LynxMask Mobile",
                                fontFamily = LynxTypography.Sans,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LynxColors.TextPrimary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Pseudonimizacja dokumentów",
                            fontSize = 12.sp,
                            color = LynxColors.TextSecondary
                        )
                    }

                    HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = LynxColors.Blue
                            )
                            Text(
                                "Zaloguj się odciskiem palca",
                                fontSize = 14.sp,
                                color = LynxColors.TextSecondary
                            )
                        }
                        LynxPrimaryButton(
                            onClick = { biometricPrompt.authenticate(promptInfo) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Odblokuj", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        LynxGhostButton(
                            onClick = { showPasswordForm = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Użyj hasła", fontSize = 12.sp, color = LynxColors.TextDim)
                        }
                        if (onExpressMode != null) {
                            LynxGhostButton(
                                onClick  = onExpressMode,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "⚡ Tryb Express — bez logowania",
                                    fontSize = 12.sp,
                                    color    = LynxColors.Amber
                                )
                            }
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
                                Text("Wpisz hasło...", color = LynxColors.TextDim, fontSize = 14.sp)
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
                            Text(errorMessage, fontSize = 12.sp, color = LynxColors.Red)
                        }
                    }

                    LynxPrimaryButton(
                        onClick = { if (!isLoading) submit() },
                        enabled = password.isNotEmpty() && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = LynxColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                when {
                                    recoveryStep == RecoveryStep.NEW_PASSWORD -> "Ustaw nowe hasło"
                                    effectiveIsFirstRun -> "Ustaw i odblokuj"
                                    else -> "Odblokuj"
                                },
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                    }

                    if (!effectiveIsFirstRun && canBiometric) {
                        LynxGhostButton(
                            onClick = { biometricPrompt.authenticate(promptInfo) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = LynxColors.TextSecondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Odblokuj odciskiem palca",
                                fontSize = 13.sp,
                                color = LynxColors.TextSecondary
                            )
                        }
                    }

                    if (!effectiveIsFirstRun && recoveryStep == RecoveryStep.NONE) {
                        var showResetDialog by remember { mutableStateOf(false) }

                        // Krok 1: pytanie o klucz dostępu
                        if (showRecoveryChoice) {
                            AlertDialog(
                                onDismissRequest = { showRecoveryChoice = false },
                                shape = RoundedCornerShape(LynxShapes.CardRadius),
                                title = { Text("Nie pamiętasz hasła?") },
                                text = {
                                    Text(
                                        "Masz klucz dostępu wygenerowany przy pierwszym logowaniu?",
                                        fontSize = 13.sp, lineHeight = 19.sp
                                    )
                                },
                                confirmButton = {
                                    LynxPrimaryButton(onClick = {
                                        showRecoveryChoice = false
                                        recoveryStep = RecoveryStep.ENTER_KEY
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Tak, mam klucz", fontSize = 13.sp)
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
                                title = { Text("Resetuj hasło?") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            "UWAGA: cała biblioteka dokumentów zostanie trwale usunięta.",
                                            fontSize = 13.sp, lineHeight = 19.sp,
                                            fontWeight = FontWeight.Bold, color = LynxColors.Red
                                        )
                                        Text(
                                            "Reset jest nieodwracalny i służy tylko gdy hasło jest całkowicie zapomniane.",
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
                                        Text("Anuluj")
                                    }
                                },
                                containerColor = LynxColors.Surface
                            )
                        }

                        LynxGhostButton(
                            onClick = { showRecoveryChoice = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Zapomniałem hasła", fontSize = 12.sp, color = LynxColors.TextDim)
                        }
                    }

                    if (effectiveIsFirstRun) {
                        Text(
                            "Hasło zabezpiecza dostęp do sesji.\nBez niego dane są niedostępne.",
                            fontSize = 11.sp,
                            color = LynxColors.TextDim,
                            lineHeight = 16.sp
                        )
                    }
                    } // else (password form)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = LynxSpacing.lg)
                .padding(horizontal = LynxSpacing.lg),
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
                        fontSize = 12.sp,
                        color    = LynxColors.Amber
                    )
                }
            }
            Text(
                BuildConfig.VERSION_NAME + " — lynxmask.app",
                fontFamily = LynxTypography.Mono,
                fontSize   = 10.sp,
                color      = LynxColors.TextDim
            )
        }
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
        Text("Klucz dostępu", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Zapisz klucz w bezpiecznym miejscu. Pozwoli odzyskać dostęp bez utraty danych gdy zapomnisz hasła.",
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
            Text("Kopiuj klucz", fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = keySaved, onCheckedChange = onSavedChange)
            Spacer(Modifier.width(8.dp))
            Text("Zapisałem klucz w bezpiecznym miejscu", fontSize = 13.sp)
        }
        LynxPrimaryButton(
            onClick = onConfirm,
            enabled = keySaved,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Kontynuuj", fontWeight = FontWeight.Medium, fontSize = 14.sp)
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
        Text("Wpisz klucz dostępu", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Wpisz 24-znakowy klucz wygenerowany przy pierwszym logowaniu (myślniki opcjonalne).",
            fontSize = 13.sp, lineHeight = 19.sp, color = LynxColors.TextDim
        )
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("KLUCZ DOSTĘPU") },
            placeholder = { Text("XXXXXX-XXXXXX-XXXXXX-XXXXXX", fontFamily = LynxTypography.Mono) },
            isError = error.isNotEmpty(),
            supportingText = if (error.isNotEmpty()) ({ Text(error, color = MaterialTheme.colorScheme.error) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onVerify() }),
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(fontFamily = LynxTypography.Mono, letterSpacing = 1.sp)
        )
        LynxPrimaryButton(
            onClick = onVerify,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zweryfikuj klucz", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}
