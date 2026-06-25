package com.lynxmask.app

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CRASH_FILE = "crash_report.txt"
// AUDYT-PRAWNIK: adres email do raportów crash — zmień przed wdrożeniem produkcyjnym jeśli potrzebne
private const val CRASH_EMAIL = "p_pietraszko@op.pl"

internal object CrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashReport(throwable)
        } catch (_: Exception) {
            // nie przerywaj obsługi crashu jeśli zapis się nie powiódł
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashReport(throwable: Throwable) {
        val ctx = appContext ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val report = buildString {
            appendLine("LynxMask Crash Report")
            appendLine("Czas: $timestamp")
            appendLine("Urządzenie: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Wersja app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("--- Stack trace ---")
            appendLine(throwable.stackTraceToString())
        }
        File(ctx.filesDir, CRASH_FILE).writeText(report)
    }

    fun hasPendingCrash(context: Context): Boolean =
        File(context.filesDir, CRASH_FILE).exists()

    fun buildEmailIntent(context: Context): Intent {
        val report = File(context.filesDir, CRASH_FILE).readText()
        return Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CRASH_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "LynxMask — raport błędu ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, report)
        }
    }

    fun clearPendingCrash(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }
}
