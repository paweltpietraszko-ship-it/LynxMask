package com.lynxmask.app

// LibraryScreen.kt — v2.3
// Nowa architektura: lista sesji (półka) → ekran sesji z akcjami
// Usunięte: rozwijane wiersze, tokeny w liście, przyciski wstecz
// Dodane: nawigacja lista→sesja, 5 przycisków akcji, odpowiedzi AI
//
// ZMIANA v2.1 (BUG-LIB-5): LaunchedEffect(Unit) → LaunchedEffect(selectedSession)
//   — lista nie odświeżała się po zamknięciu szczegółów sesji.
// ZMIANA v2.2 (BUG-LIB-EDIT): "Edytuj dokument" miał pustą lambdę.
//   Fix: SOURCE_DOCUMENT. "Odkryj dane" poprawiony na AI_RESPONSE (duplikat SOURCE_DOCUMENT).
// ZMIANA v2.3: brak (tylko korekta komentarzy)

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.components.LynxDangerTextButton
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onDepseudo: (sessionId: String, mode: DepseudoMode) -> Unit
) {
    val context          = LocalContext.current
    val coroutineScope   = rememberCoroutineScope()

    var sessions        by remember { mutableStateOf<List<SessionStore.SessionRecord>>(emptyList()) }
    var isLoading       by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf<SessionStore.SessionRecord?>(null) }

    // BUG-LIB-5: LaunchedEffect(Unit) ładował listę tylko raz — po powrocie z SessionDetailScreen
    // lista pozostawała nieaktualna. Trigger na selectedSession: null = start lub powrót z detali.
    LaunchedEffect(selectedSession) {
        if (selectedSession == null) {
            isLoading = true
            sessions  = withContext(Dispatchers.IO) { SessionStore.listSessions(context) }
            isLoading = false
        }
    }

    // Back: jeśli sesja otwarta → wróć do listy, inaczej → wyjdź z biblioteki
    BackHandler(enabled = selectedSession != null) {
        selectedSession = null
    }

    if (selectedSession != null) {
        val session = selectedSession!!
        SessionDetailScreen(
            session        = session,
            onBack         = { selectedSession = null },
            onDepseudo     = { mode -> onDepseudo(session.sesjaId, mode) },
            onSessionUpdated = { updatedSession ->
                selectedSession = updatedSession
                sessions = sessions.map { if (it.sesjaId == updatedSession.sesjaId) updatedSession else it }
            },
            onSessionDeleted = {
                sessions = sessions.filter { it.sesjaId != session.sesjaId }
                selectedSession = null
            }
        )
    } else {
        SessionListScreen(
            sessions  = sessions,
            isLoading = isLoading,
            onSessionClick = { session ->
                selectedSession = session
            }
        )
    }
}

// ── Lista sesji (półka) ───────────────────────────────────────────────────────

@Composable
private fun SessionListScreen(
    sessions: List<SessionStore.SessionRecord>,
    isLoading: Boolean,
    onSessionClick: (SessionStore.SessionRecord) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(LynxColors.Background)
    ) {
        // Nagłówek
        Column(modifier = Modifier.fillMaxWidth().background(LynxColors.Sidebar)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "BIBLIOTEKA (${sessions.size})",
                    fontFamily    = LynxTypography.Mono,
                    fontSize      = 12.sp,
                    color         = LynxColors.Blue,
                    letterSpacing = 1.5.sp
                )
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = LynxColors.Blue)
            }

            sessions.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Brak sesji.\nUkryj dokument lub zapisz zamaskowany obraz.",
                    color     = LynxColors.TextMuted,
                    fontSize  = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier  = Modifier.padding(LynxSpacing.lg)
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = LynxSpacing.xs)
            ) {
                items(sessions, key = { it.sesjaId }) { session ->
                    SessionListItem(session = session, onClick = { onSessionClick(session) })
                    HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: SessionStore.SessionRecord,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = session.description.ifEmpty { session.sesjaId },
                fontSize   = 15.sp,
                fontWeight = FontWeight.Medium,
                color      = if (session.description.isNotEmpty()) LynxColors.TextPrimary else LynxColors.BlueLight,
                fontFamily = if (session.description.isEmpty()) LynxTypography.Mono else null,
                maxLines   = 1
            )
            if (session.description.isNotEmpty()) {
                Text(
                    text       = session.sesjaId,
                    fontFamily = LynxTypography.Mono,
                    fontSize   = 11.sp,
                    color      = LynxColors.TextDim
                )
            } else if (session.isImage) {
                Text(
                    text     = "Obraz",
                    fontSize = 11.sp,
                    color    = LynxColors.TextDim
                )
            }
        }
        Text(
            text     = session.createdAt.take(10) + if (session.isImage) " · obraz" else "",
            fontSize = 12.sp,
            color    = LynxColors.TextSecondary
        )
    }
}

// ── Ekran sesji (akcje) ───────────────────────────────────────────────────────

@Composable
private fun SessionDetailScreen(
    session: SessionStore.SessionRecord,
    onBack: () -> Unit,
    onDepseudo: (DepseudoMode) -> Unit,
    onSessionUpdated: (SessionStore.SessionRecord) -> Unit,
    onSessionDeleted: () -> Unit
) {
    val context        = LocalContext.current
    val clipboard      = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    var responses       by remember { mutableStateOf<List<SessionStore.ResponseRecord>>(emptyList()) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText      by remember(session.description) { mutableStateOf(session.description) }
    var previewText     by remember { mutableStateOf<String?>(null) }
    var showPreview     by remember { mutableStateOf(false) }
    var imageBitmap     by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(session.sesjaId) {
        if (session.isImage) {
            imageBitmap = withContext(Dispatchers.IO) {
                SessionStore.loadRedactedImage(context, session.sesjaId)?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } else {
            responses = withContext(Dispatchers.IO) { SessionStore.listResponses(context, session.sesjaId) }
        }
    }

    // Dialog: zmień nazwę
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Zmie\u0144 nazw\u0119") },
            text  = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text("np. Umowa, S\u0105d...") },
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = LynxColors.Blue,
                        unfocusedBorderColor = LynxColors.Border
                    )
                )
            },
            confirmButton = {
                LynxPrimaryButton(onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        SessionStore.updateDescription(context, session.sesjaId, renameText)
                        withContext(Dispatchers.Main) {
                            onSessionUpdated(session.copy(description = renameText))
                            showRenameDialog = false
                        }
                    }
                }) { Text("Zapisz", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                LynxGhostButton(onClick = { showRenameDialog = false }) { Text("Anuluj") }
            },
            containerColor = LynxColors.Surface
        )
    }

    // Dialog: usuń sesję
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Usu\u0144 sesj\u0119?") },
            text  = {
                Text(
                    "Sesja ${session.sesjaId} zostanie trwale usuni\u0119ta wraz ze wszystkimi odpowiedziami.",
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                LynxDangerTextButton(onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        SessionStore.deleteSession(context, session.sesjaId)
                        withContext(Dispatchers.Main) {
                            showDeleteDialog = false
                            onSessionDeleted()
                        }
                    }
                }, label = "Usuń")
            },
            dismissButton = {
                LynxGhostButton(onClick = { showDeleteDialog = false }) { Text("Anuluj") }
            },
            containerColor = LynxColors.Surface
        )
    }

    // Modal: podgląd odpowiedzi
    if (showPreview && previewText != null) {
        Dialog(
            onDismissRequest = { showPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f),
                color    = LynxColors.Surface,
                shape    = RoundedCornerShape(LynxShapes.CardRadius)
            ) {
                Column(Modifier.padding(LynxSpacing.lg)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text("Odpowied\u017a AI", fontWeight = FontWeight.Medium, color = LynxColors.TextPrimary)
                        IconButton(onClick = { showPreview = false }) {
                            Text("\u2715", color = LynxColors.TextMuted, fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(LynxSpacing.sm))
                    Text(
                        text       = previewText!!,
                        modifier   = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        fontSize   = 13.sp,
                        color      = LynxColors.TextPrimary,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }

    // Ekran sesji
    Column(modifier = Modifier.fillMaxSize().background(LynxColors.Background)) {

        // Nagłówek sesji
        Column(modifier = Modifier.fillMaxWidth().background(LynxColors.Sidebar)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm)
            ) {
                Text(
                    text       = session.description.ifEmpty { session.sesjaId },
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color      = LynxColors.TextPrimary
                )
                Text(
                    text       = session.sesjaId + "  \u00b7  " + session.createdAt.take(10),
                    fontFamily = LynxTypography.Mono,
                    fontSize   = 11.sp,
                    color      = LynxColors.TextDim
                )
            }
        }

        // Przyciski akcji
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LynxSpacing.md),
            verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
        ) {
            if (session.isImage) {
                imageBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Podgląd zamaskowanego obrazu",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(LynxSpacing.sm))
                }
                SessionActionButton(label = "Udostępnij do innej aplikacji") {
                    coroutineScope.launch {
                        val bmp = imageBitmap ?: withContext(Dispatchers.IO) {
                            SessionStore.loadRedactedImage(context, session.sesjaId)?.let { bytes ->
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        } ?: return@launch
                        val uri = withContext(Dispatchers.IO) {
                            ImageRedactionPipeline.saveToCache(bmp, context)
                        }
                        val fwd = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(fwd, "Udostępnij bezpieczny obraz"))
                    }
                }
            } else {
                SessionActionButton(label = "Edytuj dokument") {
                    onDepseudo(DepseudoMode.MASKED_VIEW)
                }
                SessionActionButton(label = "Odkryj dane") {
                    onDepseudo(DepseudoMode.SOURCE_DOCUMENT)
                }
                SessionActionButton(label = "Dodaj odpowied\u017a AI") {
                    onDepseudo(DepseudoMode.AI_RESPONSE)
                }
            }
            SessionActionButton(label = "Zmie\u0144 nazw\u0119") {
                showRenameDialog = true
            }
            SessionActionButton(
                label = "Usu\u0144 sesj\u0119",
                isDestructive = true
            ) {
                showDeleteDialog = true
            }

            // Odpowiedzi AI (tylko sesje tekstowe)
            if (!session.isImage && responses.isNotEmpty()) {
                Spacer(Modifier.height(LynxSpacing.sm))
                Text(
                    "ODPOWIEDZI AI (${responses.size})",
                    fontFamily    = LynxTypography.Mono,
                    fontSize      = 10.sp,
                    color         = LynxColors.Blue,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(LynxSpacing.xs))
                responses.forEach { response ->
                    ResponseItem(
                        response = response,
                        onCopy   = { coroutineScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("masked", response.content))) } },
                        onPreview = {
                            previewText = response.content
                            showPreview = true
                        },
                        onDelete = {
                            coroutineScope.launch(Dispatchers.IO) {
                                SessionStore.deleteResponse(context, response.id)
                                val updated = SessionStore.listResponses(context, session.sesjaId)
                                withContext(Dispatchers.Main) { responses = updated }
                            }
                        }
                    )
                    HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)
                }
            }

            Spacer(Modifier.height(LynxSpacing.xl))
        }
    }
}

@Composable
private fun SessionActionButton(
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    if (isDestructive) {
        LynxSecondaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), accent = LynxColors.Red) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LynxColors.Red)
        }
    } else {
        LynxPrimaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ResponseItem(
    response: SessionStore.ResponseRecord,
    onCopy: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LynxSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                response.content.take(50) + if (response.content.length > 50) "\u2026" else "",
                fontSize  = 13.sp,
                color     = LynxColors.TextSecondary,
                maxLines  = 1
            )
            Text(
                response.createdAt.take(10),
                fontSize = 11.sp,
                color    = LynxColors.TextDim
            )
        }
        LynxGhostButton(onClick = onPreview) {
            Text("Podgl\u0105d", fontSize = 12.sp, color = LynxColors.Blue)
        }
        LynxGhostButton(onClick = onCopy) {
            Text("Kopiuj", fontSize = 12.sp, color = LynxColors.TextMuted)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(LynxSpacing.TouchTarget)) {
            Text("\u2715", fontSize = 14.sp, color = LynxColors.Red.copy(alpha = 0.7f))
        }
    }
}
