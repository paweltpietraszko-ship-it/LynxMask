package com.lynxmask.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ──────────────────────────────────────────────────────────────────────────────
// LynxMask Design Tokens  v1.0
// Portowane z: Pseudominizer/src/theme.ts  v2.3
// ──────────────────────────────────────────────────────────────────────────────
// ZASADA: używaj tych stałych wszędzie zamiast hardcoded wartości hex/dp.
// Dzięki temu zmiana palety = zmiana w jednym miejscu.
// ──────────────────────────────────────────────────────────────────────────────

object LynxColors {

    // ── Tła ──────────────────────────────────────────────────────────────────
    // Źródło: theme.ts linie bg / surface / sidebar
    // Paleta charcoal – na AMOLED (Galaxy A53) bg ≈ true black = brak podświetlenia
    val Background  = Color(0xFF0F1117)   // bg:      #0f1117
    val Surface     = Color(0xFF22252B)   // surface: #22252b
    val Sidebar     = Color(0xFF0D0F14)   // sidebar: #0d0f14
    val ActiveNav   = Color(0xFF1A1F2E)   // activeNav: #1a1f2e – tło aktywnej pozycji nav

    // ── Akcent niebieski ──────────────────────────────────────────────────────
    // Źródło: theme.ts linie blue / blueLight / blueBg
    val Blue        = Color(0xFF3B82F6)   // blue:      #3b82f6 – przyciski, linki, PSE kody
    val BlueLight   = Color(0xFF60A5FA)   // blueLight: #60A5FA – hover, ikony aktywne
    val BlueBg      = Color(0xFF1E2D4A)   // blueBg:    #1e2d4a – tło wyróżnionych sekcji

    // ── Sukces ────────────────────────────────────────────────────────────────
    // Źródło: theme.ts linie green / greenBg / greenBorder
    // Material3 nie ma slotu "success" – używamy bezpośrednio z LynxColors
    val Green       = Color(0xFF22C55E)   // green:       #22c55e – ikona OK, badge
    val GreenBg     = Color(0xFF052E16)   // greenBg:     #052e16 – tło karty sukcesu
    val GreenBorder = Color(0xFF166534)   // greenBorder: #166534 – ramka karty sukcesu

    // ── Błąd ──────────────────────────────────────────────────────────────────
    // Źródło: theme.ts linie red / redBg / redBorder
    val Red         = Color(0xFFF87171)   // red:       #f87171 – tekst błędu, ikona
    val RedBg       = Color(0xFF1C0808)   // redBg:     #1c0808 – tło karty błędu
    val RedBorder   = Color(0xFF7F1D1D)   // redBorder: #7f1d1d – ramka karty błędu

    // ── Ostrzeżenie ───────────────────────────────────────────────────────────
    // Źródło: theme.ts linia amber
    // Material3 nie ma slotu "warning" – używamy bezpośrednio z LynxColors
    val Amber       = Color(0xFFFBBF24)   // amber: #fbbf24 – badge uwaga, OutputGuard

    // ── Tekst ─────────────────────────────────────────────────────────────────
    // Źródło: theme.ts linie textPrimary / textSecondary / textMuted / textDim
    // Celowo bez szarości – wszystkie poziomy to odcienie kremowej bieli (v2.3)
    val TextPrimary   = Color(0xFFF5F7FA)   // textPrimary:   #F5F7FA – główna treść
    val TextSecondary = Color(0xFFD1D5DB)   // textSecondary: #D1D5DB – metadane, daty
    val TextMuted     = Color(0xFFB8BEC8)   // textMuted:     #B8BEC8 – placeholdery, podpisy
    val TextDim       = Color(0xFF8899BB)   // textDim:       #8899bb – separatory, nr wersji
                                            //                          niebieskawy, nie szary

    // ── Ramki ─────────────────────────────────────────────────────────────────
    // Źródło: theme.ts linie border / borderActive
    val Border        = Color(0xFF2E3138)   // border:       #2e3138
    val BorderActive  = Color(0xFF3B82F6)   // borderActive: #3b82f6 (= Blue)
}

// ─────────────────────────────────────────────────────────────────────────────

object LynxTypography {
    // Desktop theme.ts: 'JetBrains Mono', 'Cascadia Code', 'Consolas', monospace
    // Mobile: system monospace – JetBrains Mono można dodać jako font asset w przyszłości
    //         (plik .ttf do res/font/, wtedy: FontFamily(Font(R.font.jetbrains_mono)))
    val Mono = FontFamily.Monospace

    // Desktop theme.ts: 'Inter', 'Segoe UI', system-ui, -apple-system
    // Mobile: system default (Android = Google Sans / Roboto) – semantycznie równoważny
    val Sans = FontFamily.Default
}

// ─────────────────────────────────────────────────────────────────────────────

object LynxSpacing {
    // Desktop używał: xs=4, sm=8, md=12, lg=16, xl=24
    // Mobile: md/lg/xl powiększone – kciuk wymaga min. 48dp dla elementu dotykalnego
    val xs          =  4.dp   // separator, ikona wewnętrzny padding
    val sm          =  8.dp   // padding etykiety, gap między elementami wiersza
    val md          = 16.dp   // padding karty, odstęp między sekcjami  [desktop: 12]
    val lg          = 24.dp   // poziomy padding ekranu, duże odstępy    [desktop: 16]
    val xl          = 32.dp   // odstęp między blokami, nagłówek ekranu  [desktop: 24]
    val TouchTarget = 48.dp   // minimalny rozmiar elementu dotykalnego (Material guideline)
}

// ─────────────────────────────────────────────────────────────────────────────

object LynxShapes {
    // Desktop: borderRadius 2–3px – celowo ostre narożniki, "narzędzie nie aplikacja"
    // Mobile: zachowujemy ten sam charakter – LynxMask to narzędzie profesjonalne
    val ButtonRadius = 2.dp
    val CardRadius   = 3.dp
    val ChipRadius   = 2.dp
}
