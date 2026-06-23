package com.lynxmask.app

// SessionStore.kt — v1.5 (BUG-SS-1 + AUD-M05, 23.06.2026)
//
// ZMIANY v1.4:
//
//   [RODO-4a] deleteExpiredSessions(context, ttlDays):
//     Nowa metoda publiczna. TTL domyślnie wyłączony (ttlDays=0 → return).
//     Usuwa sessions + powiązane responses i audit_log starsze niż cutoff.
//     Kolejność usuwania: responses → audit_log → sessions (integralność).
//     cutoff = Instant.now().minusSeconds(ttlDays * 86400L).toString()
//     Format ISO-8601 UTC pasuje do created_at — porównanie leksykograficzne poprawne.
//     Podpięcie: wywołać z ekranu Zabezpieczenia (TODO) lub przy starcie init().
//     Wywoływana PRZED init() lub po init() — obsługuje oba przypadki (ensureInit).
//
//   [RODO-4b] deleteAllData(context):
//     Wariant B (decyzja architektoniczna): usuwa WSZYSTKO łącznie z audit_log.
//     Art. 17 RODO — prawo do zapomnienia. Użytkownik = własny administrator.
//     Kolejność: responses → audit_log → sessions (integralność).
//     Transakcja atomowa — albo wszystko albo nic.
//     Wywołać z przycisku w ekranie Zabezpieczenia (TODO — ekran nie istnieje).
//     Log.i po usunięciu (przed zamknięciem) — trafia do Logcat, nie do bazy.
//
// BUGI ZAUWAŻONE PRZY OKAZJI (nie naprawiane w tym potoku — poza zakresem RODO):
//
//   [BUG-SS-1] save() — INSERT OR REPLACE bez opisu i masked_text_enc w ścieżce
//     bez maskedText. Jeśli ta sama sesja_id zapisana ponownie bez maskedText,
//     traci masked_text_enc i description. Nie jest problemem przy auto-save
//     (każda pseudonimizacja = nowa sesja_id), ale łatwy do wywołania błędnie.
//
//   [BUG-SS-2] PRAGMA foreign_keys = ON — ustawione, ale w DDL nie ma FOREIGN KEY
//     constraints. Pragma nie ma żadnego efektu. Jeśli w przyszłości dodane FK,
//     zadziała automatycznie.
//
//   [BUG-SS-3] init() wywołuje migracje ALTER TABLE synchronicznie na wątku
//     wywołującym (potencjalnie Main thread przy pierwszym uruchomieniu).
//     Ryzyko ANR przy dużej bazie. Fix: wywołać init() z Dispatchers.IO.
//
// NIE ZMIENIONO:
//   init(), save(), loadTokenMap(), loadMaskedText(), listSessions(),
//   deleteSession(), updateDescription(), recordAudit(),
//   saveResponse(), listResponses(), deleteResponse(),
//   getDb(), openDatabase(), getOrCreateKey(), encryptBytes(), decryptBytes(),
//   getOrCreateSQLCipherPassphrase(), parseTokenMapJson(),
//   resetForTesting(), injectCorruptBlobForTesting(), readAuditLogForTesting(),
//   tokenMapJson() extension.

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "LynxMask_SessionStore"
private const val KEYSTORE_ALIAS = "lynxmask_db_key"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val PREFS_NAME = "lynxmask_session_store"
private const val PREFS_KEY_ENC_PASS = "enc_sqlcipher_passphrase"
private const val DB_NAME = "sessions.db"
private const val GCM_IV_LENGTH = 12

object SessionStore {

    @Volatile private var initialized = false
    @Volatile private var cachedDb: SQLiteDatabase? = null

    // ── API publiczne ─────────────────────────────────────────────────────────

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val db = openDatabase(context.applicationContext)
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS sessions (
                        sesja_id      TEXT PRIMARY KEY,
                        token_map_enc BLOB NOT NULL,
                        token_count   INTEGER NOT NULL DEFAULT 0,
                        created_at    TEXT NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS audit_log (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        sesja_id  TEXT NOT NULL,
                        action    TEXT NOT NULL,
                        timestamp TEXT NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS responses (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        sesja_id   TEXT NOT NULL,
                        content    TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )"""
                )
                // MIGRACJA v1.2: kolumna description dla istniejących baz.
                try {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) { /* kolumna już istnieje */ }
                // MIGRACJA v1.3: zamaskowany tekst dokumentu
                try {
                    db.execSQL("ALTER TABLE sessions ADD COLUMN masked_text_enc BLOB")
                } catch (_: Exception) { /* kolumna już istnieje */ }
                cachedDb = db
                initialized = true
                Log.i(TAG, "SessionStore zainicjalizowany: ${context.getDatabasePath(DB_NAME).absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "KRYTYCZNY BŁĄD init(): ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Zapisuje sesję po pseudonimizacji. Zwraca true przy sukcesie.
     * tokenMapJson = JSON z mapą token→original.
     *
     * BUG-SS-1 FIX: UPSERT zamiast INSERT OR REPLACE — nie nadpisuje description
     * ani masked_text_enc NULLem gdy kolumna nie jest wymieniona w SET.
     * AUD-M05 FIX: zwraca false przy błędzie (Keystore/AES-GCM) zamiast cichego null.
     */
    fun save(context: Context, sesjaId: String, tokenMapJson: String, tokenCount: Int,
             maskedText: String = ""): Boolean {
        return try {
            ensureInit(context)
            val key = getOrCreateKey()
            val encBlob = encryptBytes(key, tokenMapJson.toByteArray(Charsets.UTF_8))
            val now = Instant.now().toString()
            val db = getDb(context)
            if (maskedText.isNotEmpty()) {
                val maskedEnc = encryptBytes(key, maskedText.toByteArray(Charsets.UTF_8))
                db.execSQL(
                    """INSERT INTO sessions (sesja_id, token_map_enc, token_count, created_at, masked_text_enc)
                       VALUES (?, ?, ?, ?, ?)
                       ON CONFLICT(sesja_id) DO UPDATE SET
                           token_map_enc   = excluded.token_map_enc,
                           token_count     = excluded.token_count,
                           created_at      = excluded.created_at,
                           masked_text_enc = excluded.masked_text_enc""",
                    arrayOf(sesjaId, encBlob, tokenCount.toString(), now, maskedEnc)
                )
            } else {
                db.execSQL(
                    """INSERT INTO sessions (sesja_id, token_map_enc, token_count, created_at)
                       VALUES (?, ?, ?, ?)
                       ON CONFLICT(sesja_id) DO UPDATE SET
                           token_map_enc = excluded.token_map_enc,
                           token_count   = excluded.token_count,
                           created_at    = excluded.created_at""",
                    arrayOf(sesjaId, encBlob, tokenCount.toString(), now)
                )
            }
            Log.i(TAG, "Sesja zapisana: $sesjaId ($tokenCount tokenów, tekst=${maskedText.isNotEmpty()})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Błąd save() [$sesjaId]: ${e.message}", e)
            false
        }
    }

    /**
     * Ładuje mapę tokenów dla danej sesji.
     * Zwraca null jeśli sesja nie istnieje lub błąd odszyfrowania.
     */
    fun loadTokenMap(context: Context, sesjaId: String): Map<String, String>? {
        return try {
            ensureInit(context)
            val key = getOrCreateKey()
            getDb(context).rawQuery(
                "SELECT token_map_enc FROM sessions WHERE sesja_id = ?",
                arrayOf(sesjaId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val blob = cursor.getBlob(0)
                val json = decryptBytes(key, blob).toString(Charsets.UTF_8)
                parseTokenMapJson(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd loadTokenMap() [$sesjaId]: ${e.message}", e)
            null
        }
    }

    /**
     * Ładuje zamaskowany tekst dokumentu.
     * Zwraca null jeśli nie zapisano (starsze sesje) lub błąd odszyfrowania.
     */
    fun loadMaskedText(context: Context, sesjaId: String): String? {
        return try {
            ensureInit(context)
            val key = getOrCreateKey()
            getDb(context).rawQuery(
                "SELECT masked_text_enc FROM sessions WHERE sesja_id = ?",
                arrayOf(sesjaId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val blob = cursor.getBlob(0) ?: return null
                decryptBytes(key, blob).toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd loadMaskedText() [$sesjaId]: ${e.message}", e)
            null
        }
    }

    data class SessionRecord(
        val sesjaId: String,
        val tokenCount: Int,
        val createdAt: String,
        val description: String = ""
    )

    /**
     * Zwraca listę sesji posortowaną od najnowszej.
     */
    fun listSessions(context: Context): List<SessionRecord> {
        return try {
            ensureInit(context)
            getDb(context).rawQuery(
                "SELECT sesja_id, token_count, created_at, description FROM sessions ORDER BY created_at DESC",
                null
            ).use { cursor ->
                val list = mutableListOf<SessionRecord>()
                while (cursor.moveToNext()) {
                    list.add(
                        SessionRecord(
                            sesjaId     = cursor.getString(0),
                            tokenCount  = cursor.getInt(1),
                            createdAt   = cursor.getString(2),
                            description = cursor.getString(3)
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd listSessions(): ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Usuwa sesję, jej audit log i wszystkie zapisane odpowiedzi.
     */
    fun deleteSession(context: Context, sesjaId: String) {
        try {
            ensureInit(context)
            val db = getDb(context)
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM responses WHERE sesja_id = ?", arrayOf(sesjaId))
                db.execSQL("DELETE FROM audit_log WHERE sesja_id = ?", arrayOf(sesjaId))
                db.execSQL("DELETE FROM sessions WHERE sesja_id = ?", arrayOf(sesjaId))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.i(TAG, "Sesja usunięta: $sesjaId")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd deleteSession() [$sesjaId]: ${e.message}", e)
        }
    }

    /**
     * Ustawia opis sesji widoczny w Bibliotece.
     */
    fun updateDescription(context: Context, sesjaId: String, description: String) {
        try {
            ensureInit(context)
            getDb(context).execSQL(
                "UPDATE sessions SET description = ? WHERE sesja_id = ?",
                arrayOf(description.trim(), sesjaId)
            )
            recordAudit(context, sesjaId, "description_updated")
            Log.i(TAG, "Opis zaktualizowany: $sesjaId")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd updateDescription() [$sesjaId]: ${e.message}", e)
        }
    }

    /**
     * Zapisuje zdarzenie do audit_log. Zero PII — tylko sesja_id + akcja.
     */
    fun recordAudit(context: Context, sesjaId: String, action: String) {
        try {
            ensureInit(context)
            val now = Instant.now().toString()
            getDb(context).execSQL(
                "INSERT INTO audit_log (sesja_id, action, timestamp) VALUES (?, ?, ?)",
                arrayOf(sesjaId, action, now)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Błąd recordAudit() [$sesjaId/$action]: ${e.message}", e)
        }
    }

    // ── Odpowiedzi AI ─────────────────────────────────────────────────────────

    data class ResponseRecord(val id: Long, val sesjaId: String, val content: String, val createdAt: String)

    fun saveResponse(context: Context, sesjaId: String, content: String) {
        try {
            ensureInit(context)
            val now = Instant.now().toString()
            getDb(context).execSQL(
                "INSERT INTO responses (sesja_id, content, created_at) VALUES (?, ?, ?)",
                arrayOf(sesjaId, content, now)
            )
            Log.i(TAG, "Odpowiedź zapisana: $sesjaId (${content.length} znaków)")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd saveResponse() [$sesjaId]: ${e.message}", e)
        }
    }

    fun listResponses(context: Context, sesjaId: String): List<ResponseRecord> {
        return try {
            ensureInit(context)
            getDb(context).rawQuery(
                "SELECT id, sesja_id, content, created_at FROM responses WHERE sesja_id = ? ORDER BY created_at DESC",
                arrayOf(sesjaId)
            ).use { cursor ->
                val list = mutableListOf<ResponseRecord>()
                while (cursor.moveToNext()) {
                    list.add(
                        ResponseRecord(
                            id        = cursor.getLong(0),
                            sesjaId   = cursor.getString(1),
                            content   = cursor.getString(2),
                            createdAt = cursor.getString(3)
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd listResponses() [$sesjaId]: ${e.message}", e)
            emptyList()
        }
    }

    fun deleteResponse(context: Context, responseId: Long) {
        try {
            ensureInit(context)
            getDb(context).execSQL(
                "DELETE FROM responses WHERE id = ?",
                arrayOf(responseId.toString())
            )
            Log.i(TAG, "Odpowiedź usunięta: id=$responseId")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd deleteResponse() [id=$responseId]: ${e.message}", e)
        }
    }

    // ── RODO: TTL i "usuń wszystkie dane" ────────────────────────────────────

    /**
     * [RODO Art. 5 — minimalizacja danych]
     *
     * Usuwa sesje starsze niż ttlDays dni wraz z powiązanymi odpowiedziami
     * i wpisami audit_log.
     *
     * ttlDays = 0 (domyślnie) → wyłączone. Wywołanie jest idempotentne.
     *
     * cutoff obliczany jako Instant.now().minusSeconds(ttlDays * 86400L).
     * Format ISO-8601 UTC zgodny z created_at — porównanie leksykograficzne
     * działa poprawnie (np. "2026-05-01T..." < "2026-06-01T...").
     *
     * PODPIĘCIE UI: wywołać z ekranu Zabezpieczenia (TODO) z wartością z prefs.
     * Przykład: SessionStore.deleteExpiredSessions(context, ttlDays = prefs.getInt("ttl", 0))
     */
    fun deleteExpiredSessions(context: Context, ttlDays: Int = 0) {
        if (ttlDays <= 0) return
        try {
            ensureInit(context)
            val cutoff = Instant.now()
                .minusSeconds(ttlDays.toLong() * 86_400L)
                .toString()
            val db = getDb(context)
            db.beginTransaction()
            try {
                // Kolejność: zależne rekordy przed sesjami (integralność danych)
                db.execSQL(
                    "DELETE FROM responses WHERE sesja_id IN (SELECT sesja_id FROM sessions WHERE created_at < ?)",
                    arrayOf(cutoff)
                )
                db.execSQL(
                    "DELETE FROM audit_log WHERE sesja_id IN (SELECT sesja_id FROM sessions WHERE created_at < ?)",
                    arrayOf(cutoff)
                )
                db.execSQL(
                    "DELETE FROM sessions WHERE created_at < ?",
                    arrayOf(cutoff)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.i(TAG, "Wygasłe sesje usunięte (TTL=${ttlDays}d, cutoff=$cutoff)")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd deleteExpiredSessions(): ${e.message}", e)
        }
    }

    /**
     * [RODO Art. 17 — prawo do bycia zapomnianym]
     *
     * Usuwa WSZYSTKIE dane z bazy: responses, audit_log, sessions.
     * Wariant B (decyzja architektoniczna): audit_log usuwany razem z resztą.
     * Uzasadnienie: użytkownik jest własnym administratorem swoich danych.
     * Art. 17 ma pierwszeństwo przed zasadą accountability.
     *
     * Transakcja atomowa — albo wszystko albo nic.
     * Plik bazy (sessions.db) pozostaje na dysku (pusty) — dane usunięte.
     *
     * PODPIĘCIE UI: wywołać z przycisku "Usuń wszystkie dane" w ekranie
     * Zabezpieczenia (TODO — ekran nie istnieje w tej wersji).
     */
    fun deleteAllData(context: Context) {
        try {
            ensureInit(context)
            val db = getDb(context)
            db.beginTransaction()
            try {
                // Kolejność: zależne rekordy przed sesjami (integralność danych)
                db.execSQL("DELETE FROM responses")
                db.execSQL("DELETE FROM audit_log")
                db.execSQL("DELETE FROM sessions")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.i(TAG, "Wszystkie dane usunięte (Art. 17 RODO)")
        } catch (e: Exception) {
            Log.e(TAG, "Błąd deleteAllData(): ${e.message}", e)
        }
    }

    // ── Prywatne: połączenie z bazą ───────────────────────────────────────────

    private fun getDb(context: Context): SQLiteDatabase =
        cachedDb ?: synchronized(this) {
            cachedDb ?: openDatabase(context.applicationContext).also { cachedDb = it }
        }

    private fun openDatabase(context: Context): SQLiteDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = getOrCreateSQLCipherPassphrase(context)
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath,
            passphrase,
            null,
            null,
            null
        ).also { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }

    private fun ensureInit(context: Context) {
        if (!initialized) init(context.applicationContext)
    }

    // ── Prywatne: Keystore AES-256-GCM ───────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encryptBytes(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    private fun decryptBytes(key: SecretKey, ivAndCiphertext: ByteArray): ByteArray {
        require(ivAndCiphertext.size > GCM_IV_LENGTH) { "Zbyt krótki blob: ${ivAndCiphertext.size}B" }
        val iv = ivAndCiphertext.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = ivAndCiphertext.sliceArray(GCM_IV_LENGTH until ivAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    // ── Prywatne: passphrase SQLCipher ────────────────────────────────────────

    private fun getOrCreateSQLCipherPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREFS_KEY_ENC_PASS, null)
        val key = getOrCreateKey()
        if (stored != null) {
            return try {
                val encBlob = Base64.decode(stored, Base64.NO_WRAP)
                decryptBytes(key, encBlob)
            } catch (e: Exception) {
                Log.e(TAG, "Nie można odszyfrować passphrase — baza niedostępna: ${e.message}", e)
                throw e
            }
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encBlob = encryptBytes(key, passphrase)
        prefs.edit()
            .putString(PREFS_KEY_ENC_PASS, Base64.encodeToString(encBlob, Base64.NO_WRAP))
            .apply()
        Log.i(TAG, "Wygenerowano nowy passphrase SQLCipher")
        return passphrase
    }

    // ── Prywatne: parser tokenMap ─────────────────────────────────────────────

    private fun parseTokenMapJson(json: String): Map<String, String> {
        return try {
            val obj = org.json.JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Błąd parsowania tokenMap JSON: ${e.message}")
            emptyMap()
        }
    }

    // ── Wsparcie testowe (tylko DEBUG) ────────────────────────────────────────

    @VisibleForTesting
    fun resetForTesting() {
        if (!BuildConfig.DEBUG) return
        synchronized(this) {
            cachedDb?.close()
            cachedDb = null
            initialized = false
        }
    }

    @VisibleForTesting
    fun injectCorruptBlobForTesting(context: Context, sesjaId: String, corruptBlob: ByteArray) {
        if (!BuildConfig.DEBUG) return
        try {
            getDb(context).execSQL(
                "UPDATE sessions SET token_map_enc = ? WHERE sesja_id = ?",
                arrayOf(corruptBlob, sesjaId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "injectCorruptBlobForTesting: ${e.message}")
        }
    }

    @VisibleForTesting
    fun readAuditLogForTesting(context: Context, sesjaId: String): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        return try {
            getDb(context).rawQuery(
                "SELECT action, timestamp FROM audit_log WHERE sesja_id = ?",
                arrayOf(sesjaId)
            ).use { cursor ->
                val entries = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    entries.add("${cursor.getString(0)}@${cursor.getString(1)}")
                }
                entries
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ── Extension: serializacja tokenMap do JSON ──────────────────────────────────

fun PseudonymResult.tokenMapJson(): String =
    tokenMap.entries.joinToString(",", "{", "}") { (k, v) ->
        val escaped = v
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        "\"$k\":\"$escaped\""
    }
