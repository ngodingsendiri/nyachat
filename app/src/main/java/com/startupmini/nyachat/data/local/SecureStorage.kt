package com.startupmini.nyachat.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Penyimpanan rahasia (API keys, PIN) menggunakan Android Keystore langsung.
 * Menggantikan `EncryptedSharedPreferences` (deprecated di security-crypto 1.1.0).
 *
 * - Master key AES-256-GCM dibuat/disimpan di Android Keystore (hardware-backed
 *   jika tersedia).
 * - Data disimpan sebagai `Base64(IV || ciphertext)` di SharedPreferences biasa.
 * - Non-secret prefs (dark mode, role, name, timestamps) pindah ke SharedPreferences
 *   biasa tanpa enkripsi — hanya 3 field (2 API key + PIN) yang terenkripsi.
 */
object SecureStorage {

    private const val KEYSTORE_ALIAS = "money_chat_master_key"
    private const val PREFS_NAME = "secure_store"
    private const val KEY_PREFIX = "enc_"

    /** Initialize master key di Keystore (idempotent). */
    private fun ensureMasterKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGen.generateKey()
        }
        return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Simpan rahasia (dienkripsi dulu).
     * Return true kalau sukses.
     *
     * (audit local/ 2026-08-13) Dipanggil dari IO via [putSecretAsync] —
     * Keystore crypto TIDAK boleh di main thread (jank dengan hardware-backed
     * key). Pemanggil UI WAJIB memakai varian *Async.
     */
    fun putSecret(context: Context, key: String, value: String?): Boolean {
        if (value == null || value.isEmpty()) {
            // Null/empty → hapus
            return deleteSecret(context, key)
        }
        return try {
            val masterKey = ensureMasterKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv // 12 bytes untuk GCM
            val ciphertext = cipher.doFinal(value.toByteArray())
            // Gabung IV + ciphertext → base64
            val combined = iv + ciphertext
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            getPrefs(context).edit().putString(KEY_PREFIX + key, encoded).apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("SecureStorage", "putSecret gagal: ${e.message}")
            false
        }
    }

    /**
     * Baca rahasia (dekripsi).
     * Return null kalau tidak ada / gagal dekripsi.
     * Dipanggil dari IO via [getSecretAsync].
     */
    fun getSecret(context: Context, key: String): String? {
        val encoded = getPrefs(context).getString(KEY_PREFIX + key, null)
            ?: return null
        return try {
            val masterKey = ensureMasterKey(context)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            // IV = 12 byte pertama
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext)
        } catch (e: Exception) {
            android.util.Log.w("SecureStorage", "getSecret gagal: ${e.message}")
            null
        }
    }

    fun deleteSecret(context: Context, key: String): Boolean {
        return try {
            getPrefs(context).edit().remove(KEY_PREFIX + key).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Hapus SEMUA secret (logout/hapus data).
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /** Wrapper suspend untuk penggunaan di coroutine (IO dispatcher). */
    suspend fun putSecretAsync(context: Context, key: String, value: String?): Boolean =
        withContext(Dispatchers.IO) { putSecret(context, key, value) }

    suspend fun getSecretAsync(context: Context, key: String): String? =
        withContext(Dispatchers.IO) { getSecret(context, key) }

    suspend fun deleteSecretAsync(context: Context, key: String): Boolean =
        withContext(Dispatchers.IO) { deleteSecret(context, key) }

    suspend fun clearAllAsync(context: Context) =
        withContext(Dispatchers.IO) { clearAll(context) }
}