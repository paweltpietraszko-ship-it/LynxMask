package com.lynxmask.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey

/**
 * GuardAllowlist.kt — Trwały słownik wartości potwierdzonych przez użytkownika jako nie-PII.
 *
 * Gdy OutputGuard flaguje wartość jako YELLOW, użytkownik może kliknąć "Nie maskuj" —
 * para (wartość, typ_reguły) trafia tutaj i Guard pomija ją przy kolejnych dokumentach.
 *
 * Wpisy są trwałe (permanentne). Błędne wpisy można usunąć przez remove().
 * Ekran zarządzania wpisami — sekcja 15 MASTER (odłożone).
 *
 * Storage: plik JSON szyfrowany przez EncryptedFile (AES-256-GCM, AndroidKeyStore),
 * identyczny mechanizm jak UserDictionary v1.3.
 *
 * Format guard_allowlist.json (na dysku zaszyfrowany):
 *   [{"value":"1/2023","ruleType":"SYGNATURA"}, {"value":"734/1","ruleType":"SYGNATURA"}]
 *
 * Klucz dopasowania: para (value, ruleType) — nie goła wartość. Niskoentropijne wartości
 * (np. "1/2023") mają ryzyko kolizji z cudzym prawdziwym PII przy innym typie reguły.
 *
 * API: add, contains, remove, getAll, clear, load.
 * Zasada niezależności (sekcja 18 MASTER): GuardAllowlist NIE może być współdzielona
 * z UserDictionary — Guard i silnik mają osobne martwe punkty.
 */
object GuardAllowlist {

    private const val FILENAME   = "guard_allowlist.json"
    private const val TAG        = "GuardAllowlist"
    private const val MAX_ENTRIES = 500

    private var _entries: MutableList<Pair<String, String>> = mutableListOf()
    private var _loaded = false

    val entries: List<Pair<String, String>>
        @Synchronized get() = ArrayList(_entries)

    @Synchronized
    fun load(context: Context): List<Pair<String, String>> {
        if (_loaded) return _entries
        try {
            val file = context.filesDir.resolve(FILENAME)
            if (!file.exists()) {
                _loaded = true
                return _entries
            }
            val json = try {
                buildEncryptedFile(context).openFileInput()
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                val plaintext = file.readText(Charsets.UTF_8)
                file.delete()
                plaintext
            }
            val arr = JSONArray(json)
            _entries = mutableListOf()
            for (i in 0 until arr.length()) {
                val obj      = arr.getJSONObject(i)
                val value    = obj.getString("value").trim()
                val ruleType = obj.getString("ruleType").trim()
                if (value.isNotBlank() && ruleType.isNotBlank()) {
                    _entries.add(value to ruleType)
                }
            }
            Log.d(TAG, "Załadowano ${_entries.size} wpisów z $FILENAME")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd ładowania allowlisty: ${e.message}")
            return _entries
        }
        _loaded = true
        return _entries
    }

    @Synchronized
    fun add(context: Context, value: String, ruleType: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        if (_entries.any { it.first.equals(trimmed, ignoreCase = true) && it.second == ruleType }) return
        if (_entries.size >= MAX_ENTRIES) {
            Log.w(TAG, "GuardAllowlist pełna ($MAX_ENTRIES wpisów) — wpis odrzucony: '$trimmed'")
            return
        }
        _entries.add(trimmed to ruleType)
        save(context)
        Log.d(TAG, "Dodano: '$trimmed' ($ruleType) — łącznie ${_entries.size} wpisów")
    }

    @Synchronized
    fun contains(value: String, ruleType: String): Boolean {
        val trimmed = value.trim()
        return _entries.any { it.first.equals(trimmed, ignoreCase = true) && it.second == ruleType }
    }

    @Synchronized
    fun remove(context: Context, value: String, ruleType: String) {
        _entries.removeAll { it.first == value.trim() && it.second == ruleType }
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        _entries.clear()
        try {
            context.filesDir.resolve(FILENAME).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Błąd usuwania pliku allowlisty: ${e.message}")
        }
    }

    /** Tylko do testów — resetuje stan in-memory bez dotykania dysku. */
    @Synchronized
    fun resetForTesting() {
        _entries = mutableListOf()
        _loaded = false
    }

    /** Tylko do testów — dodaje wpis bez Contextu (pomija zapis na dysk). */
    @Synchronized
    internal fun addDirect(value: String, ruleType: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        if (_entries.any { it.first.equals(trimmed, ignoreCase = true) && it.second == ruleType }) return
        if (_entries.size >= MAX_ENTRIES) return
        _entries.add(trimmed to ruleType)
    }

    /** Tylko do testów — usuwa wpis bez Contextu (pomija zapis na dysk). */
    @Synchronized
    internal fun removeDirect(value: String, ruleType: String) {
        _entries.removeAll { it.first == value.trim() && it.second == ruleType }
    }

    private fun buildEncryptedFile(context: Context): EncryptedFile {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context.applicationContext,
            context.filesDir.resolve(FILENAME),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    @Synchronized
    private fun save(context: Context) {
        if (_entries.isEmpty()) {
            context.filesDir.resolve(FILENAME).delete()
            return
        }
        try {
            val arr = JSONArray()
            _entries.forEach { (value, ruleType) ->
                arr.put(JSONObject().apply {
                    put("value", value)
                    put("ruleType", ruleType)
                })
            }
            val content = arr.toString(2).toByteArray(Charsets.UTF_8)
            val targetFile = context.filesDir.resolve(FILENAME)
            if (targetFile.exists()) targetFile.delete()
            buildEncryptedFile(context).openFileOutput().use { it.write(content) }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd zapisu allowlisty: ${e.message}")
        }
    }
}
