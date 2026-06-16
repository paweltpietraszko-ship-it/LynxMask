package com.lynxmask.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BenchmarkReceiver — tylko odbiera broadcast i startuje BenchmarkService.
 * Cała robota (OCR + silnik) wykonywana jest w Service, nie tutaj.
 *
 * Manifest: patrz AndroidManifest.xml
 */
class BenchmarkReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.lynxmask.app.BENCHMARK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val fileName = intent.getStringExtra("file") ?: return

        val serviceIntent = Intent(context, BenchmarkService::class.java)
        serviceIntent.putExtra("file", fileName)
        context.startService(serviceIntent)
    }
}
