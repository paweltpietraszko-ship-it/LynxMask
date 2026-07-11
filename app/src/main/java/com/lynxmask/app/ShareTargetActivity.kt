package com.lynxmask.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Legacy entry z share sheet / menedżera plików — przekierowuje do MainActivity.
 * Całe przetwarzanie: login → IncomingDocumentFlow (jedna ścieżka).
 */
class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // BUG-SHARE-CZARNY-EKRAN (11.07): oficjalny wzorzec Androida dla "RoutingActivity"
        // (developer.android.com/.../splash-screen/migrate) — installSplashScreen() SAM w
        // sobie nie wystarczy, trzeba jeszcze setKeepOnScreenCondition{true} PRZED forwardem,
        // inaczej splash (z ikoną) nie "przenosi się" przez granicę przejścia do MainActivity
        // (u nas dodatkowo granica taska, bo ShareTargetActivity ma inny taskAffinity).
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { true }
        retainIncomingUriPermissions(intent)
        LynxPendingShare.store(intent)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("NewApi")
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
}