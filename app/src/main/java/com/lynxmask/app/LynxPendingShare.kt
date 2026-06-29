package com.lynxmask.app

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.mutableIntStateOf

/** Kolejka dokumentu z share / file pickera — jedna ścieżka przez MainActivity. */
object LynxPendingShare {
    @Volatile private var pending: Intent? = null
    private val revisionState = mutableIntStateOf(0)

    val revision: Int get() = revisionState.intValue

    fun peek(): Intent? = pending?.let { Intent(it) }

    /** Tekst z huba — osobna akcja, żeby nie gubić się z ACTION_VIEW / share. */
    fun storeHubText(text: String) {
        store(
            Intent(LynxNavExtras.ACTION_HUB_TEXT).apply {
                putExtra(LynxNavExtras.EXTRA_HUB_TEXT, text)
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
            }
        )
    }

    fun store(intent: Intent) {
        pending = Intent(intent).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        revisionState.intValue++
    }

    fun clear() {
        if (pending == null) return
        pending = null
        revisionState.intValue++
    }
}

fun isIncomingDocumentIntent(intent: Intent?): Boolean {
    if (intent == null) return false
    return when (intent.action) {
        LynxNavExtras.ACTION_HUB_TEXT ->
            !intent.getStringExtra(LynxNavExtras.EXTRA_HUB_TEXT).isNullOrBlank()
        Intent.ACTION_SEND ->
            !intent.type.isNullOrBlank() || !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
        Intent.ACTION_VIEW -> intent.data != null
        else -> false
    }
}

/**
 * Share sheet nadaje URI tylko ShareTargetActivity — po [Activity.finish] dostęp znika.
 * Trzeba przekazać uprawnienie w obrębie pakietu zanim MainActivity otworzy plik.
 */
fun Activity.retainIncomingUriPermissions(source: Intent) {
    val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    val pkg = packageName

    fun grant(uri: Uri?) {
        if (uri == null) return
        try {
            grantUriPermission(pkg, uri, read)
        } catch (_: SecurityException) {
            // niektóre content:// nie pozwalają na grant — spróbujemy i tak
        }
    }

    grant(source.data)
    grant(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    )
    source.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) {
            grant(clip.getItemAt(i).uri)
        }
    }
}
