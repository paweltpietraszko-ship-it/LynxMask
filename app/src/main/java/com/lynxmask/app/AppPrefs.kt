package com.lynxmask.app

import android.content.Context

internal const val APP_PREFS_NAME = "lynxmask_prefs"

// Jednorazowy disclaimer wymagający akceptacji przed akcją Kopiuj/Wyślij.
// AUDYT-PRAWNIK: powiązane z DisclaimerDialog w PseudonymResultPanel.kt — obie lokalizacje do przeglądu.
internal fun isDisclaimerAccepted(context: Context): Boolean =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean("send_disclaimer_accepted", false)

internal fun markDisclaimerAccepted(context: Context) =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean("send_disclaimer_accepted", true).apply()
