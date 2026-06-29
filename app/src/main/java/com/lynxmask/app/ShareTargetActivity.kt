package com.lynxmask.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Legacy entry z share sheet / menedżera plików — przekierowuje do MainActivity.
 * Całe przetwarzanie: login → IncomingDocumentFlow (jedna ścieżka).
 */
class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainIncomingUriPermissions(intent)
        LynxPendingShare.store(intent)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }
}