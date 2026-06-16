package com.lynxmask.app

// OnboardingScreen.kt — Potok RODO (10.06.2026)
// Zmiany vs poprzednia wersja:
//   [RODO-5] Dodano slajd RODO jako ostatni (index 5):
//     Emoji 🛡️ (brief wskazywał 🔒 — zmienione, bo 🔒 już na slajdzie 0).
//     Treść: brak zewnętrznych serwerów, szyfrowanie lokalne, usuwanie danych.
//     Tip: "Zgodnie z zasadą Privacy by Design (Art. 25 RODO)"
//   [FIX] PAGES[3] tip: "Funkcja depseudonimizacji jest w przygotowaniu"
//     → "Użyj zakładki \"Odwróć maskowanie\"..."
//     DepseudonymizationScreen istnieje od Potoku 6 — stary tip był nieaktualny.
//   [POPRZEDNIE] PAGES[1]: zmieniono body (Zadanie 5 wcześniejszego potoku).
//     Usunięto wzmiankę o przycisku "Fotografuj" — ścieżka kamery usunięta.
//     Dodano opis ścieżki "Udostępnij → LynxMask" jako preferowanej drogi.

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// Flaga pierwszego uruchomienia
// ─────────────────────────────────────────────────────────────────────────────

private const val PREFS_NAME = "lynxmask_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_done"

fun isOnboardingDone(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_DONE, false)

private fun markOnboardingDone(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()

// ─────────────────────────────────────────────────────────────────────────────
// Treść kart
// ─────────────────────────────────────────────────────────────────────────────

private data class OnboardingPage(
    val emoji: String,
    val title: String,
    val body: String,
    val tip: String? = null
)

private val PAGES = listOf(
    OnboardingPage(
        emoji = "🔒",
        title = "LynxMask ukrywa dane osobowe",
        body = "Zanim wyślesz dokument do modelu AI, aplikacja zastępuje wszystkie wrażliwe dane tokenami — OSOBA_001, ADRES_001, NUMER_001.\n\nModel widzi treść, ale nie widzi danych.",
        tip = null
    ),
    OnboardingPage(
        emoji = "📄",
        title = "Wgraj dokument trzema sposobami",
        body = "• Przycisk \"Wybierz plik lub obraz\" — otwórz DOCX, PDF, TXT lub zdjęcie z dysku.\n\n• \"Udostępnij\" w innej aplikacji — znajdź LynxMask na liście.\n\n• Zdjęcie dokumentu: zrób aparatem, potem Udostępnij → LynxMask.",
        tip = "Po załadowaniu możesz poprawić tekst zanim go przetworzymy — klawiatura podpowie błędy skanowania."
    ),
    OnboardingPage(
        emoji = "✏️",
        title = "Sprawdź i uzupełnij wynik",
        body = "Po pseudonimizacji widzisz tekst z tokenami. Jeśli coś zostało pominięte:\n\n1. Zaznacz fragment w podglądzie.\n2. Wybierz typ (OSOBA / FIRMA / ADRES / NUMER).\n3. Kliknij \"Maskuj\".",
        tip = "Zaznaczone frazy są zapamiętywane — przy kolejnym dokumencie zostaną wykryte automatycznie."
    ),
    OnboardingPage(
        emoji = "🗺️",
        title = "Jak odczytać odpowiedź AI?",
        body = "Wyślij zanonimizowany tekst do dowolnego modelu AI. Gdy dostaniesz odpowiedź z tokenami, wklej ją z powrotem do LynxMask.\n\nAplikacja zastąpi tokeny oryginalnymi danymi.",
        tip = "Użyj zakładki \"Odwróć maskowanie\" — wklej odpowiedź AI i wybierz sesję."
    ),
    // TODO-10 FIX (sesja 10): slajd o zdjęciach i podpisach.
    // LynxMask nie wykrywa twarzy ani podpisów — użytkownik musi zakryć je sam.
    OnboardingPage(
        emoji = "🤚",
        title = "Twarze i podpisy — zakryj przed skanowaniem",
        body = "LynxMask przetwarza tekst, ale nie wykrywa automatycznie:\n\n• Twarzy i zdjęć na dokumentach\n• Podpisów własnoręcznych\n• Pieczątek z danymi osobowymi\n\nZanim zrobisz zdjęcie dokumentu — zakryj twarz, podpis i pieczątkę palcem lub kartką.",
        tip = "Jeśli wyślesz dokument ze zdjęciem twarzy — AI je zobaczy. Zakrycie zajmuje sekundę."
    ),
    // [RODO Art. 13 + Art. 25] Slajd informacyjny o przetwarzaniu danych.
    // Dodany w Potoku RODO (10.06.2026) — ostatni slajd onboardingu.
    // Emoji 🛡️ zamiast 🔒 (brief) — 🔒 już używane na slajdzie 0, unikamy duplikatu.
    OnboardingPage(
        emoji = "🛡️",
        title = "Twoje dane zostają na urządzeniu",
        body = "LynxMask nie wysyła żadnych danych do zewnętrznych serwerów.\n\n" +
               "Wszystkie dokumenty i mapy tokenów są przechowywane lokalnie, " +
               "zaszyfrowane kluczem powiązanym z twoim urządzeniem.\n\n" +
               "Możesz usunąć wszystkie dane w ustawieniach Zabezpieczeń.",
        tip = "Zgodnie z zasadą Privacy by Design (Art. 25 RODO)"
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// Główny composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(0) }
    val total = PAGES.size
    val current = PAGES[page]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(56.dp))

            // Emoji duże
            Text(
                text = current.emoji,
                fontSize = 72.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Treść — animowana przy zmianie karty
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState > initialState)
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    else
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                },
                label = "page_content"
            ) { p ->
                val pg = PAGES[p]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = pg.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = pg.body,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pg.tip != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 ${pg.tip}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Kropki postępu
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(total) { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == page) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == page)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Przyciski nawigacji
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (page > 0) {
                    OutlinedButton(
                        onClick = { page-- },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Wstecz")
                    }
                } else {
                    // Pomiń — dostępny tylko na pierwszej karcie
                    TextButton(
                        onClick = {
                            markOnboardingDone(context)
                            onFinished()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Pomiń",
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Button(
                    onClick = {
                        if (page < total - 1) {
                            page++
                        } else {
                            markOnboardingDone(context)
                            onFinished()
                        }
                    },
                    modifier = Modifier.weight(2f)
                ) {
                    Text(if (page < total - 1) "Dalej" else "Zacznij używać")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
