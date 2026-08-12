package com.startupmini.nyachat.data.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** File backup yang tersimpan di folder privat Google Drive (appDataFolder).
 *  [encrypted] = apakah FILE backup ini terenkripsi:
 *  - `true`/`false` → status diketahui dari `appProperties` Drive atau penanda
 *    nama `.enc.json` (semua backup baru).
 *  - `null` → backup lama (belum punya metadata) — status harus di-*probe*
 *    dari isi amplop oleh [DriveBackupController] sebelum ditampilkan. */
data class DriveBackupFile(
    val fileId: String,
    val name: String,
    val createdTime: String,
    val encrypted: Boolean? = null
)

/**
 * Abstraksi operasi Google Drive (P4.5) — dipakai agar [DriveBackupController]
 * bisa di-*unit test* dengan implementasi palsu tanpa jaringan nyata.
 * Produksi memakai [DriveBackupManager] (objek singleton).
 */
interface DriveBackupApi {
    suspend fun getAccessToken(context: Context, email: String): BackupResult<String>
    suspend fun uploadBackup(
        context: Context,
        token: String,
        fileName: String,
        json: String
    ): BackupResult<Unit>
    suspend fun listBackups(context: Context, token: String): BackupResult<List<DriveBackupFile>>
    suspend fun downloadBackup(context: Context, token: String, fileId: String): BackupResult<String>
    suspend fun pruneOldBackups(context: Context, token: String, keep: Int): BackupResult<Unit>
}

/**
 * Backup/restore ke Google Drive via Drive REST API v3. File disimpan di
 * appDataFolder — folder privat per aplikasi (tidak terlihat user, tidak
 * memakai kuota Drive-nya). Token OAuth didapat lewat GoogleAuthUtil memakai
 * akun Google yang sudah dipakai login Firebase — tidak butuh backend sendiri.
 */
object DriveBackupManager : DriveBackupApi {

    private const val TAG = "DriveBackup"
    // Penting: GoogleAuthUtil.getToken WAJIB menyertakan scope profile
    // (userinfo.profile) — tanpa itu panggilan token dilempar/dianggap tidak
    // valid dan backup/restore tidak akan pernah jalan.
    private const val DRIVE_SCOPE =
        "oauth2:https://www.googleapis.com/auth/drive.file " +
            "https://www.googleapis.com/auth/drive.appdata " +
            "https://www.googleapis.com/auth/userinfo.profile"
    private const val API_FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_FILES = "https://www.googleapis.com/upload/drive/v3/files"

    /** Penanda nama backup terenkripsi: `Nyachat-backup-<ts>.enc.json`. */
    const val ENCRYPTED_NAME_SUFFIX = ".enc.json"

    /** Kunci `appProperties` Drive untuk status enkripsi file backup. */
    private const val PROP_ENCRYPTED = "encrypted"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Minta access token Google untuk scope Drive (harus dipanggil di
     * background thread). Kalau user belum pernah menyetujui akses Drive,
     * Play Services melempar UserRecoverableAuthException berisi Intent konsen
     * — return BackupResult.ConsentRequired supaya UI bisa menampilkannya lalu
     * mencoba lagi.
     */
    @Suppress("DEPRECATION") // GoogleAuthUtil.getToken masih dipakai lintas versi Play Services
    override suspend fun getAccessToken(context: Context, email: String): BackupResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val token = GoogleAuthUtil.getToken(context, email, DRIVE_SCOPE)
                BackupResult.Success(token)
            } catch (e: UserRecoverableAuthException) {
                val intent = e.intent
                if (intent != null) BackupResult.ConsentRequired(intent)
                else {
                    // Audit (2026-08-12): detail exception hanya di logcat — UI
                    // menampilkan pesan ramah (DriveBackupController).
                    Log.w(TAG, "Token Drive gagal: ${e.message}")
                    BackupResult.Failure("Token Drive gagal", e)
                }
            } catch (e: GoogleAuthException) {
                Log.w(TAG, "Token Drive gagal: ${e.message}")
                BackupResult.Failure("Token Drive gagal", e)
            }
        }

    /** Buat file baru di appDataFolder lalu unggah isi JSON-nya. */
    override suspend fun uploadBackup(
        context: Context,
        token: String,
        fileName: String,
        json: String
    ): BackupResult<Unit> = withContext(Dispatchers.IO) {
        val fileId = runCatching {
            val meta = JSONObject()
                .put("name", fileName)
                .put("parents", JSONArray().put("appDataFolder"))
                // appProperties = metadata privat per file (temuan #4): picker
                // restore tahu status enkripsi TANPA mengunduh isi — bahkan
                // untuk backup plain yang tidak punya penanda di nama.
                .put(
                    "appProperties",
                    JSONObject().put(
                        PROP_ENCRYPTED,
                        if (fileName.endsWith(ENCRYPTED_NAME_SUFFIX)) "true" else "false"
                    )
                )
            val req = Request.Builder()
                .url(API_FILES)
                .addHeader("Authorization", bearer(token))
                .addHeader("Content-Type", "application/json")
                .post(meta.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Buat file gagal: ${resp.code} ${resp.body?.string().orEmpty()}")
                    null
                } else {
                    JSONObject(resp.body?.string().orEmpty()).optString("id").ifEmpty { null }
                }
            }
        }.getOrNull()
        if (fileId == null) return@withContext BackupResult.Failure("Gagal membuat file di Drive")

        val uploaded = runCatching {
            val req = Request.Builder()
                .url("$UPLOAD_FILES/$fileId?uploadType=media")
                .addHeader("Authorization", bearer(token))
                .addHeader("Content-Type", "application/json")
                .patch(json.toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (!uploaded) {
            Log.w(TAG, "Unggah isi gagal untuk $fileName")
            return@withContext BackupResult.Failure("Gagal mengunggah isi file")
        }
        BackupResult.Success(Unit)
    }

    /** Daftar file backup (paling baru di depan). */
    override suspend fun listBackups(context: Context, token: String): BackupResult<List<DriveBackupFile>> =
        withContext(Dispatchers.IO) {
            val body = runCatching {
                val req = Request.Builder()
                    .url("$API_FILES?spaces=appDataFolder&orderBy=createdTime%20desc&fields=files(id,name,createdTime,appProperties)")
                    .addHeader("Authorization", bearer(token))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                }
            }.getOrElse { "" }
            if (body.isEmpty()) return@withContext BackupResult.Failure("Gagal mengambil daftar backup")
            runCatching {
                val arr = JSONObject(body).optJSONArray("files") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DriveBackupFile(
                        fileId = o.optString("id"),
                        name = o.optString("name"),
                        createdTime = o.optString("createdTime"),
                        encrypted = parseEncryptionStatus(
                            o.optString("name"),
                            o.optJSONObject("appProperties")
                        )
                    )
                }
            }.fold(
                { it -> BackupResult.Success(it) },
                { e ->
                    Log.w(TAG, "Gagal parse daftar backup: ${e.message}")
                    BackupResult.Failure("Gagal memuat daftar backup", e)
                }
            )
        }

    /** Unduh isi file backup (JSON). */
    override suspend fun downloadBackup(context: Context, token: String, fileId: String): BackupResult<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$API_FILES/$fileId?alt=media")
                    .addHeader("Authorization", bearer(token))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.string() ?: throw IllegalStateException("Download body kosong")
                    } else {
                        throw IllegalStateException("Download gagal: ${resp.code}")
                    }
                }
            }.fold(
                { BackupResult.Success(it) },
                { e ->
                    Log.w(TAG, "Gagal mengunduh backup: ${e.message}")
                    BackupResult.Failure("Gagal mengunduh backup", e)
                }
            )
        }

    /** Hapus backup lama — sisakan hanya yang terbaru (keep).
     *  L8: error per file sebelumnya ditelan runCatching; kini diagregasi &
     *  dilaporkan — kalau ada yang gagal dihapus, hasilnya Failure supaya
     *  backup lama tidak menumpuk diam-diam. */
    override suspend fun pruneOldBackups(context: Context, token: String, keep: Int): BackupResult<Unit> {
        val filesResult = listBackups(context, token)
        return when (filesResult) {
            is BackupResult.Success -> {
                val files = filesResult.value
                if (files.size <= keep) BackupResult.Success(Unit)
                else {
                    val failed = files.drop(keep).mapNotNull { file ->
                        runCatching {
                            val req = Request.Builder()
                                .url("$API_FILES/${file.fileId}")
                                .addHeader("Authorization", bearer(token))
                                .delete()
                                .build()
                            client.newCall(req).execute().use { resp ->
                                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                            }
                        }.exceptionOrNull()
                    }
                    if (failed.isEmpty()) BackupResult.Success(Unit)
                    else {
                        Log.w(TAG, "Gagal menghapus ${failed.size} backup lama (${failed.first().message})")
                        BackupResult.Failure(
                            "Gagal menghapus ${failed.size} backup lama",
                            failed.first()
                        )
                    }
                }
            }
            is BackupResult.Failure, is BackupResult.NotFound, is BackupResult.QuotaExceeded, is BackupResult.ConsentRequired -> filesResult
        }
    }

    private fun bearer(token: String) = "Bearer $token"
}

/**
 * Status enkripsi file backup dari metadata list Drive:
 * - penanda nama `.enc.json` → `true`;
 * - `appProperties.encrypted` → nilai eksplisit (semua backup baru);
 * - tanpa keduanya (backup lama) → `null` = belum diketahui, harus di-*probe*
 *   dari isi amplop oleh [DriveBackupController].
 * Dipisah sebagai fungsi murni (internal) supaya bisa di-*unit test* langsung.
 */
internal fun parseEncryptionStatus(name: String, appProperties: JSONObject?): Boolean? {
    if (name.endsWith(DriveBackupManager.ENCRYPTED_NAME_SUFFIX)) return true
    return when (appProperties?.optString("encrypted")) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
