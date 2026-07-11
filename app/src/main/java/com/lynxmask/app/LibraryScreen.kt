package com.lynxmask.app

// LibraryScreen.kt — v2.4
// Lista sesji → ekran sesji → akcje (nawigacja UX v1, 28.06.2026)
//
// ZMIANA v2.4: stan sesji w MainTabNav (openSessionId); widoczny ← Wstecz;
//   poprawione nazwy akcji; pusta biblioteka z CTA.

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.lynxmask.app.document.rememberDocxExportLauncher
import com.lynxmask.app.document.rememberPdfExportLauncher
import com.lynxmask.app.document.rememberXlsxExportLauncher
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.components.LynxDangerTextButton
import com.lynxmask.app.ui.components.LynxFilledButton
import com.lynxmask.app.ui.components.LynxFlatRow
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxScreenHeader
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    openSessionId: String?,
    activeDepseudoMode: DepseudoMode?,
    onOpenSession: (String) -> Unit,
    onCloseSession: () -> Unit,
    onCloseDepseudo: () -> Unit,
    onGoToHub: () -> Unit,
    onDepseudo: (sessionId: String, mode: DepseudoMode) -> Unit
) {
    val context = LocalContext.current

    var sessions  by remember { mutableStateOf<List<SessionStore.SessionRecord>>(emptyList()) }
    var isLoading       by remember { mutableStateOf(true) }

    val selectedSession = openSessionId?.let { id -> sessions.find { it.sesjaId == id } }

    LaunchedEffect(openSessionId) {
        isLoading = true
        LynxAppInit.ensureReady(context.applicationContext)
        sessions  = withContext(Dispatchers.IO) { SessionStore.listSessions(context) }
        isLoading = false
    }

    BackHandler(enabled = activeDepseudoMode != null) {
        onCloseDepseudo()
    }
    BackHandler(enabled = activeDepseudoMode == null && openSessionId != null) {
        onCloseSession()
    }

    when {
        activeDepseudoMode != null && selectedSession != null -> {
            key(activeDepseudoMode) {
                DepseudonymizationScreen(
                    preselectedSessionId = selectedSession.sesjaId,
                    initialMode          = activeDepseudoMode,
                    fromLibrary          = true,
                    onBack               = onCloseDepseudo,
                    onRestoreOriginal    = {
                        onDepseudo(selectedSession.sesjaId, DepseudoMode.SOURCE_DOCUMENT)
                    }
                )
            }
        }
        selectedSession != null -> {
            SessionDetailScreen(
                session          = selectedSession,
                onBack           = onCloseSession,
                onDepseudo       = { mode -> onDepseudo(selectedSession.sesjaId, mode) },
                onSessionUpdated = { updatedSession ->
                    sessions = sessions.map { if (it.sesjaId == updatedSession.sesjaId) updatedSession else it }
                },
                onSessionDeleted = {
                    sessions = sessions.filter { it.sesjaId != selectedSession.sesjaId }
                    onCloseSession()
                }
            )
        }
        openSessionId != null && !isLoading && sessions.none { it.sesjaId == openSessionId } -> {
            LaunchedEffect(openSessionId) { onCloseSession() }
        }
        else -> {
            SessionListScreen(
                sessions       = sessions,
                isLoading      = isLoading,
                onGoToHub      = onGoToHub,
                onSessionClick = { session -> onOpenSession(session.sesjaId) }
            )
        }
    }
}

// ── Lista sesji (półka) ───────────────────────────────────────────────────────

@Composable
private fun SessionListScreen(
    sessions: List<SessionStore.SessionRecord>,
    isLoading: Boolean,
    onGoToHub: () -> Unit,
    onSessionClick: (SessionStore.SessionRecord) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(LynxColors.Background)
    ) {
        LynxScreenHeader(
            title = "Biblioteka",
            sectionLabel = "BAZA SESJI",
            subtitle = if (!isLoading) "${sessions.size} zapisanych" else null
        )

        when {
            isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = LynxColors.Blue)
            }

            sessions.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(LynxSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Brak zapisanych sesji",
                    fontFamily = LynxTypography.Sans,
                    color = LynxColors.TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(LynxSpacing.sm))
                Text(
                    "Ukryj dokument lub obraz — potem wróć tutaj.",
                    fontFamily = LynxTypography.Sans,
                    color = LynxColors.TextMuted,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(LynxSpacing.lg))
                LynxFilledButton(
                    label = "Ukryj pierwszy dokument",
                    icon = Icons.Outlined.UploadFile,
                    onClick = onGoToHub,
                    modifier = Modifier.fillMaxWidth(0.88f)
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = LynxSpacing.xs,
                    bottom = LynxSpacing.xl
                )
            ) {
                items(sessions, key = { it.sesjaId }) { session ->
                    SessionListItem(session = session, onClick = { onSessionClick(session) })
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LynxSpacing.md, vertical = 14.dp)
    ) {
        Text(
            text = session.libraryTitle(),
            fontFamily = LynxTypography.Sans,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = LynxColors.TextPrimary,
            lineHeight = 21.sp,
            maxLines = 2
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = session.libraryMetaLine(),
            fontFamily = LynxTypography.Sans,
            fontSize = 12.sp,
            color = LynxColors.TextDim,
            maxLines = 1
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = LynxSpacing.md),
        color = LynxColors.Border.copy(alpha = 0.45f),
        thickness = 0.5.dp
    )
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

    // Document Rebuilder (10.07): eksport z Biblioteki dla sesji, które pochodzą z
    // DOCX/PDF/Excel — ten sam zapis co z ekranu wyniku, tylko wołany stąd. Rejestrujemy
    // wszystkie trzy bezwarunkowo (wymóg Compose — launcher musi być zarejestrowany zawsze,
    // niezależnie od tego, czy przycisk się akurat pokaże).
    val saveDocx = rememberDocxExportLauncher(coroutineScope)
    val savePdf = rememberPdfExportLauncher(coroutineScope)
    val saveXlsx = rememberXlsxExportLauncher(coroutineScope)

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
            title = { Text("Zmie\u0144 nazw\u0119", fontFamily = LynxTypography.Sans) },
            text  = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text("np. Umowa, S\u0105d...", fontFamily = LynxTypography.Sans) },
                    shape         = RoundedCornerShape(LynxShapes.ButtonRadius),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = LynxColors.Blue,
                        unfocusedBorderColor = LynxColors.Border
                    )
                )
            },
            confirmButton = {
                LynxFilledButton(
                    label = "Zapisz",
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            SessionStore.updateDescription(context, session.sesjaId, renameText)
                            withContext(Dispatchers.Main) {
                                onSessionUpdated(session.copy(description = renameText))
                                showRenameDialog = false
                            }
                        }
                    }
                )
            },
            dismissButton = {
                LynxGhostButton(onClick = { showRenameDialog = false }) { Text("Anuluj", fontFamily = LynxTypography.Sans) }
            },
            containerColor = LynxColors.Surface
        )
    }

    // Dialog: usuń sesję
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Usuń dokument?", fontFamily = LynxTypography.Sans) },
            text  = {
                Text(
                    "Dokument zostanie trwale usunięty wraz z odpowiedziami AI.",
                    fontFamily = LynxTypography.Sans,
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
                LynxGhostButton(onClick = { showDeleteDialog = false }) { Text("Anuluj", fontFamily = LynxTypography.Sans) }
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
                        Text("Odpowied\u017a AI", fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Medium, color = LynxColors.TextPrimary)
                        IconButton(onClick = { showPreview = false }) {
                            Text("\u2715", fontFamily = LynxTypography.Sans, color = LynxColors.TextMuted, fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(LynxSpacing.sm))
                    Text(
                        text       = previewText!!,
                        modifier   = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        fontFamily = LynxTypography.Sans,
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

        LynxScreenHeader(
            title = session.libraryTitle(),
            sectionLabel = "DOKUMENT",
            subtitle = session.libraryMetaLine(),
            backLabel = "Biblioteka",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(LynxSpacing.md),
            verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
        ) {
            if (session.isImage) {
                imageBitmap?.let { bmp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(LynxShapes.CardRadius),
                        colors = CardDefaults.cardColors(containerColor = LynxColors.Surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Podgląd zamaskowanego obrazu",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .padding(LynxSpacing.sm),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (session.isImage) "OBRAZ" else "DOKUMENT",
                    fontFamily = LynxTypography.Mono,
                    fontSize = 9.sp,
                    color = LynxColors.Blue,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (session.isImage) {
                    SessionActionButton(label = "Udostępnij do innej aplikacji", icon = Icons.Outlined.Share) {
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
                    SessionActionButton(label = "Podgląd zamaskowanego", icon = Icons.Outlined.Visibility) {
                        onDepseudo(DepseudoMode.MASKED_VIEW)
                    }
                    SessionActionButton(label = "Przywróć oryginał", icon = Icons.Outlined.Restore) {
                        onDepseudo(DepseudoMode.SOURCE_DOCUMENT)
                    }
                    SessionActionButton(label = "Dodaj odpowiedź AI", icon = Icons.Outlined.AutoAwesome) {
                        onDepseudo(DepseudoMode.AI_RESPONSE)
                    }
                    val exportLabel = when (session.sourceFormat) {
                        SessionStore.SOURCE_FORMAT_DOCX -> "Zapisz DOCX"
                        SessionStore.SOURCE_FORMAT_PDF -> "Zapisz PDF"
                        SessionStore.SOURCE_FORMAT_XLSX -> "Zapisz Excel"
                        else -> null
                    }
                    if (exportLabel != null) {
                        SessionActionButton(label = exportLabel, icon = Icons.Outlined.Download) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val text = SessionStore.loadMaskedText(context, session.sesjaId)
                                withContext(Dispatchers.Main) {
                                    if (text == null) {
                                        Toast.makeText(context, "Nie udało się wczytać tekstu sesji", Toast.LENGTH_SHORT).show()
                                    } else when (session.sourceFormat) {
                                        SessionStore.SOURCE_FORMAT_DOCX -> saveDocx(text)
                                        SessionStore.SOURCE_FORMAT_PDF -> savePdf(text)
                                        SessionStore.SOURCE_FORMAT_XLSX -> saveXlsx(text)
                                    }
                                }
                            }
                        }
                    }
                }
                SessionActionButton(label = "Zmień nazwę", icon = Icons.Outlined.Edit) {
                    showRenameDialog = true
                }
            }

            HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

            SessionActionButton(
                label = "Usuń dokument",
                icon = Icons.Outlined.Delete,
                isDestructive = true
            ) {
                showDeleteDialog = true
            }

            if (!session.isImage) {
                Text(
                    "ODPOWIEDZI AI (${responses.size})",
                    fontFamily = LynxTypography.Mono,
                    fontSize = 9.sp,
                    color = LynxColors.Blue,
                    letterSpacing = 1.5.sp
                )
                if (responses.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(LynxShapes.CardRadius),
                        colors = CardDefaults.cardColors(containerColor = LynxColors.ActiveNav.copy(alpha = 0.6f))
                    ) {
                        Text(
                            "Brak zapisanych odpowiedzi.\nUżyj „Dodaj odpowiedź AI” po otrzymaniu wyniku z asystenta.",
                            modifier = Modifier.padding(LynxSpacing.md),
                            fontFamily = LynxTypography.Sans,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = LynxColors.TextSecondary
                        )
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(LynxShapes.CardRadius),
                        colors = CardDefaults.cardColors(containerColor = LynxColors.Surface)
                    ) {
                        Column(Modifier.padding(horizontal = LynxSpacing.md)) {
                            responses.forEachIndexed { index, response ->
                                if (index > 0) {
                                    HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)
                                }
                                ResponseItem(
                                    response = response,
                                    onCopy = {
                                        coroutineScope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText("masked", response.content))
                                            )
                                        }
                                    },
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionActionButton(
    label: String,
    icon: ImageVector,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    LynxFlatRow(
        label = label,
        icon = icon,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        iconTint = if (isDestructive) LynxColors.Red else LynxColors.BlueLight,
        labelColor = if (isDestructive) LynxColors.Red else null
    )
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
                fontFamily = LynxTypography.Sans,
                fontSize  = 13.sp,
                color     = LynxColors.TextSecondary,
                maxLines  = 1
            )
            Text(
                response.createdAt.take(10),
                fontFamily = LynxTypography.Sans,
                fontSize = 11.sp,
                color    = LynxColors.TextDim
            )
        }
        LynxGhostButton(onClick = onPreview) {
            Text("Podgl\u0105d", fontFamily = LynxTypography.Sans, fontSize = 12.sp, color = LynxColors.Blue)
        }
        LynxGhostButton(onClick = onCopy) {
            Text("Kopiuj", fontFamily = LynxTypography.Sans, fontSize = 12.sp, color = LynxColors.TextMuted)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(LynxSpacing.TouchTarget)) {
            Text("\u2715", fontFamily = LynxTypography.Sans, fontSize = 14.sp, color = LynxColors.Red.copy(alpha = 0.7f))
        }
    }
}

/** Tytuł widoczny dla użytkownika — bez technicznego ID sesji. */
private fun SessionStore.SessionRecord.libraryTitle(): String = when {
    description.isNotBlank() -> description.trim()
    isImage -> "Zamaskowany obraz"
    else -> "Dokument tekstowy"
}

/** Jedna linia metadanych: typ + data (bez UUID). */
private fun SessionStore.SessionRecord.libraryMetaLine(): String {
    val kind = if (isImage) "Obraz" else "Tekst"
    return "$kind · ${formatLibraryDate(createdAt)}"
}

private fun formatLibraryDate(iso: String): String {
    val datePart = iso.take(10)
    val parts = datePart.split("-")
    if (parts.size != 3) return datePart
    val year = parts[0]
    val month = parts[1].toIntOrNull() ?: return datePart
    val day = parts[2].toIntOrNull() ?: parts[2]
    val months = listOf(
        "", "sty", "lut", "mar", "kwi", "maj", "cze",
        "lip", "sie", "wrz", "pa\u017a", "lis", "gru"
    )
    val monthLabel = months.getOrNull(month) ?: parts[1]
    return "$day $monthLabel $year"
}
