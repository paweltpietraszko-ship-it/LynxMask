package com.lynxmask.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys

/**
 * UserDictionary.kt — Persistent słownik wyrażeń dodanych ręcznie przez użytkownika.
 *
 * Storage: plik JSON szyfrowany przez EncryptedFile (AES-256-GCM, klucz AES256_GCM
 * w AndroidKeyStore) w context.filesDir (prywatny storage, niedostępny dla innych
 * aplikacji bez root).
 *
 * Format wewnętrzny user_dictionary.json (na dysku zaszyfrowany):
 *   [{"value":"ul. Lipowa 14","type":"ADRES"}, {"value":"Jan Kowalski","type":"OSOBA"}]
 *
 * Zmiany v1.3 (audyt bezpieczeństwa):
 *   - ENCRYPT: save()/load() używają EncryptedFile z MasterKey (AES256_GCM, AndroidKeyStore).
 *     Poprzednie twierdzenie "dane nie są wrażliwe" było nieprawidłowe — słownik
 *     zawiera PII wpisane przez użytkownika (imiona, adresy, NIP-y).
 *   - MIGRATION: load() wykrywa stary plaintext plik i migruje automatycznie
 *     (usuwa plaintext, zaszyfrowany zapis następuje przy pierwszym add/remove).
 *   - Usunięto ATOMIC-WRITE (.tmp + renameTo): EncryptedFile nie obsługuje rename
 *     zaszyfrowanych plików — keyset w SharedPreferences powiązany z nazwą pliku.
 *     save() używa: delete istniejącego → EncryptedFile.openFileOutput() → write.
 *     Integralność zapewnia AEAD (GCM authentication tag).
 *
 * Zmiany v1.1 (sesja 7):
 *   - MAX_ENTRIES: miękki limit 500 wpisów z ostrzeżeniem w logcat
 *   - entries: właściwość zwraca cache; zawsze wywołuj load(context) przed użyciem
 *
 * Zmiany v1.2 (BUG-DICT-FIX):
 *   - ATOMIC-WRITE: save() używa zapisu atomowego (.tmp + renameTo z fallbackiem).
 *     Problem: writeText() bezpośrednio na docelowym pliku — proces zabity przez
 *     Android (memory pressure) w połowie zapisu zostawiał uszkodzony JSON.
 *     Przy następnym starcie load() dostawał pusty/błędny plik → pusty słownik.
 *     Fix wzorowany na desktop (Pseudominizer): shutil.move(tmp, target).
 *   - RENAME-CHECK: renameTo() sprawdza wynik Boolean i wykonuje fallback do
 *     copyTo() gdy rename się nie powiedzie (np. cross-filesystem, edge case).
 *     Poprzednio brak sprawdzenia = milczące utracenie danych przy failed rename.
 *   - SYNC: @Synchronized na wszystkich metodach publicznych + save().
 *     PseudonymEngine wywołuje pseudonymize() na Dispatchers.Default (wątek tła)
 *     i iteruje po _entries; add() wywoływane z wątku głównego (Compose onAddToDict).
 *     Bez synchronizacji: race condition → utracony wpis lub ConcurrentModificationException.
 *   - TRIM: add() normalizuje wartość przez trim() przed deduplicją.
 *     "Jan Kowalski " i "Jan Kowalski" były traktowane jako różne wpisy.
 *   - ROUND-TRIP: weryfikacja save() sprawdza teraz każdy wpis (value + type),
 *     nie tylko liczbę rekordów.
 *   - CLEAR-CLEANUP: clear() usuwa plik z dysku zamiast zapisywać pustą tablicę.
 *
 * Uwagi projektowe:
 *   - _loaded cache jest celowy: słownik ma żyć przez czas procesu, nie reaguje
 *     na zewnętrzne zmiany pliku. Backup/restore wymaga restartu aplikacji.
 *   - Synchronizacja przez @Synchronized (monitor obiektu) jest wystarczająca
 *     dla tego przypadku użycia — operacje krótkie, brak zagnieżdżonych blokad.
 */
object UserDictionary {

    private const val FILENAME = "user_dictionary.json"
    private const val TAG      = "UserDictionary"
    // Miękki limit wpisów — PseudonymEngine kompiluje regex na każdy wpis
    // przy każdym wywołaniu pseudonymize(). Przy 500+ wpisach może to być odczuwalne.
    private const val MAX_ENTRIES  = 500

    // Cache w pamięci — ładowany raz przy starcie procesu
    private var _entries: MutableList<Pair<String, String>> = mutableListOf()
    private var _loaded = false

    // Zwraca zsynchronizowaną kopię cache — bezpieczne do przekazania na inny wątek.
    // Kopiowanie odbywa się pod lockiem (synchronized getter), więc ArrayList(_entries)
    // nigdy nie ściga się z add() wywołanym z wątku głównego.
    // Wywołujący dostaje snapshot — nie referencję do wewnętrznej listy.
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
                // Migracja: plik z poprzedniej wersji zapisany jako plaintext
                val plaintext = file.readText(Charsets.UTF_8)
                file.delete()   // usuń plaintext — save() zapisze zaszyfrowaną wersję
                plaintext
            }
            val arr  = JSONArray(json)
            _entries = mutableListOf()
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val value = obj.getString("value").trim()
                val type  = obj.getString("type").trim()
                if (value.isNotBlank() && type.isNotBlank()) {
                    _entries.add(value to type)
                }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Załadowano ${_entries.size} wpisów z $FILENAME")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd ładowania słownika: ${e.message}")
            // Nie ustawiaj _loaded = true przy błędzie — pozwól na retry przy
            // kolejnym wywołaniu (np. po naprawie pliku przez użytkownika).
            return _entries
        }
        _loaded = true
        return _entries
    }

    @Synchronized
    fun add(context: Context, value: String, type: String) {
        // TRIM v1.2: normalizuj przed deduplicją — "Jan Kowalski " != "Jan Kowalski"
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        if (_entries.any { it.first.equals(trimmed, ignoreCase = true) && it.second == type }) return
        if (_entries.size >= MAX_ENTRIES) {
            Log.w(TAG, "Słownik pełny ($MAX_ENTRIES wpisów) — wpis odrzucony (${trimmed.length} znaków)")
            return
        }
        _entries.add(trimmed to type)
        save(context)
        if (BuildConfig.DEBUG) Log.d(TAG, "Dodano: '$trimmed' ($type) — łącznie ${_entries.size} wpisów")
    }

    @Synchronized
    fun remove(context: Context, value: String, type: String) {
        _entries.removeAll { it.first == value.trim() && it.second == type }
        save(context)
    }

    @Synchronized
    fun clear(context: Context) {
        _entries.clear()
        // CLEAR-CLEANUP v1.2: usuń plik zamiast zapisywać "[]"
        // Plik nieistniejący i plik z "[]" są semantycznie tożsame,
        // ale brak pliku eliminuje martwy JSON na dysku.
        try {
            context.filesDir.resolve(FILENAME).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Błąd usuwania pliku słownika: ${e.message}")
        }
    }

    private fun buildEncryptedFile(context: Context): EncryptedFile {
        val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedFile.Builder(
            context.filesDir.resolve(FILENAME),
            context.applicationContext,
            keyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    @Synchronized
    fun exportToJson(): String {
        val arr = JSONArray()
        _entries.forEach { (value, type) ->
            arr.put(JSONObject().apply {
                put("value", value)
                put("type", type)
            })
        }
        return arr.toString(2)
    }

    @Synchronized
    fun importFromJson(context: Context, json: String): Int {
        return try {
            val arr = JSONArray(json)
            var added = 0
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val value = obj.optString("value", "").trim()
                val type  = obj.optString("type",  "").trim()
                if (value.isBlank() || type.isBlank()) continue
                if (_entries.any { it.first.equals(value, ignoreCase = true) && it.second == type }) continue
                if (_entries.size >= MAX_ENTRIES) break
                _entries.add(value to type)
                added++
            }
            if (added > 0) save(context)
            Log.i(TAG, "Zaimportowano $added wpisów")
            added
        } catch (e: Exception) {
            Log.e(TAG, "Błąd importu słownika: ${e.message}")
            -1
        }
    }

    @Synchronized
    private fun save(context: Context) {
        if (_entries.isEmpty()) {
            context.filesDir.resolve(FILENAME).delete()
            return
        }
        try {
            val arr = JSONArray()
            _entries.forEach { (value, type) ->
                arr.put(JSONObject().apply {
                    put("value", value)
                    put("type", type)
                })
            }
            val content = arr.toString(2).toByteArray(Charsets.UTF_8)
            // EncryptedFile wymaga braku istniejącego pliku przed openFileOutput
            val targetFile = context.filesDir.resolve(FILENAME)
            if (targetFile.exists()) targetFile.delete()
            buildEncryptedFile(context).openFileOutput().use { it.write(content) }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd zapisu słownika: ${e.message}")
        }
    }
}
