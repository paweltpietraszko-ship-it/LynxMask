package com.lynxmask.app

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object RecoveryKeyManager {

    private const val PREFS_RECOVERY = "lynxmask_recovery"
    private const val KEY_HASH       = "recovery_key_hash"
    private const val KEY_SALT       = "recovery_key_salt"
    // Alfabet bez wizualnie niejednoznacznych znaków: 0/O, 1/I/L usunięte
    private const val ALPHABET       = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val PBKDF2_ITER    = 120_000

    fun generateKey(): String {
        val rng = SecureRandom()
        return (1..4).joinToString("-") {
            (1..6).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")
        }
    }

    fun saveKey(context: Context, plainKey: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(normalize(plainKey), salt)
        context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun isKeySet(context: Context): Boolean =
        context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
            .getString(KEY_HASH, null) != null

    fun verify(context: Context, input: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE)
        val salt     = Base64.decode(prefs.getString(KEY_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(KEY_HASH, null) ?: return false, Base64.NO_WRAP)
        return pbkdf2(normalize(input), salt).contentEquals(expected)
    }

    fun clearKey(context: Context) {
        context.getSharedPreferences(PREFS_RECOVERY, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun normalize(key: String): String = key.replace("-", "").trim().uppercase()

    private fun pbkdf2(key: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(key.toCharArray(), salt, PBKDF2_ITER, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
