package com.lynxmask.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────────────────
// LynxMask – schemat kolorów
//
// DARK:  charcoal identyczny z desktop Pseudominizer (theme.ts v2.3)
//        Użytkownik desktop od razu rozpoznaje środowisko na telefonie.
//        Na AMOLED (Galaxy A53): bg #0f1117 ≈ true black = oszczędność baterii.
//
// LIGHT: jasny odpowiednik tej samej palety marki – ten sam niebieski akcent,
//        neutralne tła, bez fioletów i różów z szablonu Android Studio.
//        Użytkownik może wybrać tryb w ustawieniach (TODO: DataStore preference).
//
// Kolory: zob. DesignTokens.kt  →  LynxColors
// dynamicColor celowo wyłączony – systemowa tapeta nie nadpisuje palety aplikacji
// ──────────────────────────────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(

    // Główny akcent – niebieski z theme.ts (#3b82f6)
    primary                = LynxColors.Blue,
    onPrimary              = Color.White,
    primaryContainer       = LynxColors.BlueBg,
    onPrimaryContainer     = LynxColors.BlueLight,

    // Drugorzędny – blueLight jako subtelniejszy akcent
    secondary              = LynxColors.BlueLight,
    onSecondary            = LynxColors.Background,
    secondaryContainer     = LynxColors.ActiveNav,
    onSecondaryContainer   = LynxColors.TextSecondary,

    // Tła
    background             = LynxColors.Background,
    onBackground           = LynxColors.TextPrimary,

    // Powierzchnia kart, arkuszy, dialogów
    surface                = LynxColors.Surface,
    onSurface              = LynxColors.TextPrimary,
    surfaceVariant         = LynxColors.Sidebar,
    onSurfaceVariant       = LynxColors.TextMuted,

    // Ramki
    outline                = LynxColors.Border,
    outlineVariant         = LynxColors.BorderActive,

    // Błędy
    error                  = LynxColors.Red,
    onError                = LynxColors.Background,
    errorContainer         = LynxColors.RedBg,
    onErrorContainer       = LynxColors.Red,
)

private val LightColorScheme = lightColorScheme(

    // Ten sam niebieski akcent co dark – spójność marki niezależnie od trybu
    primary                = LynxColors.Blue,
    onPrimary              = Color.White,
    primaryContainer       = Color(0xFFD6E8FF),
    onPrimaryContainer     = Color(0xFF001A45),

    secondary              = Color(0xFF1A3562),
    onSecondary            = Color.White,
    secondaryContainer     = Color(0xFFDAE2FF),
    onSecondaryContainer   = Color(0xFF001160),

    background             = Color(0xFFF4F6FC),   // jasny niebieskawo-biały
    onBackground           = Color(0xFF1A2340),

    surface                = Color(0xFFFFFFFF),
    onSurface              = Color(0xFF1A2340),
    surfaceVariant         = Color(0xFFE4EAF6),
    onSurfaceVariant       = Color(0xFF44546A),

    outline                = Color(0xFFBFD0F0),
    outlineVariant         = LynxColors.Blue,

    error                  = Color(0xFFB3261E),
    onError                = Color.White,
    errorContainer         = Color(0xFFF9DEDC),
    onErrorContainer       = Color(0xFF410E0B),
)

// ──────────────────────────────────────────────────────────────────────────────

@Composable
fun LynxMaskTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}

// TODO (sesja przyszła): dodać przełącznik dark/light sterowany przez użytkownika
//   1. val darkMode by appSettingsDataStore.darkMode.collectAsState()
//   2. przekazać do LynxMaskTheme(darkTheme = darkMode)
//   Parametr darkTheme: Boolean jest już gotowy – nie wymaga zmiany sygnatury.
