package com.startupmini.nyachat.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Penyimpanan foto profil lokal per pengguna (audit #2). Konsisten dengan
 * lampiran chat: file hanya ada di perangkat yang mengunggahnya dan TIDAK ikut
 * sinkron ke cloud (mengikuti kebijakan lampiran — lihat `chat_attach_no_sync`).
 * Foto disimpan di filesDir/avatars/, tersampling kecil agar hemat penyimpanan.
 *
 * Kunci = nama pengguna (univ per workspace); hash nama dipakai sebagai nama
 * file supaya aman dari karakter ilegal di path.
 */
object AvatarStore {

    private fun avatarsDir(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    private fun keyHash(name: String): String {
        val h = name.hashCode() and 0x7FFFFFFF
        return Integer.toHexString(h)
    }

    private fun fileFor(context: Context, name: String): File =
        File(avatarsDir(context), "${keyHash(name)}.jpg")

    /** Simpan foto avatar untuk [name]. Return path absolut, null bila gagal. */
    suspend fun saveAvatar(context: Context, name: String, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = fileFor(context, name)
                if (!writeSampledImage(context, uri, dest)) return@runCatching null
                dest.absolutePath
            }.getOrNull()
        }

    /** Path avatar untuk [name], null bila belum ada. */
    fun getAvatarPath(context: Context, name: String): String? {
        val f = fileFor(context, name)
        return if (f.exists()) f.absolutePath else null
    }

    /** Hapus foto profil [name] (jika ada). */
    fun deleteAvatar(context: Context, name: String) {
        fileFor(context, name).delete()
    }

    // ===== Profil & Akun (r1.2.1) =====

    /**
     * Simpan foto profil CUSTOM user — nama file FIXED (`custom.jpg`) supaya
     * ganti nama user tidak menghilangkan foto (tidak seperti [saveAvatar] yang
     * berkunci nama). Sampling 512px + JPEG 85 agar ringan. Return path absolut.
     */
    suspend fun saveCustomAvatar(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(avatarsDir(context), "custom.jpg")
                if (!writeSampledImage(context, uri, dest)) return@runCatching null
                dest.absolutePath
            }.getOrNull()
        }

    /**
     * Unduh & cache foto profil GOOGLE ke `google_<uid>.jpg` (cache lokal).
     * Hanya salinan untuk avatar aplikasi — akun Google TIDAK diubah. Panggil
     * dari Dispatchers.IO (akses jaringan). Return path, null bila gagal.
     */
    fun cacheGooglePhoto(context: Context, url: String, uid: String): String? =
        runCatching {
            val dest = File(avatarsDir(context), "google_${uid.take(16)}.jpg")
            if (dest.exists() && dest.length() > 0) return@runCatching dest.absolutePath
            val input = java.net.URL(url).openStream()
            input.use {
                val bmp = BitmapFactory.decodeStream(it) ?: return@runCatching null
                val scaled = if (bmp.width > 512 || bmp.height > 512) {
                    val ratio = 512f / maxOf(bmp.width, bmp.height)
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * ratio).toInt().coerceAtLeast(1),
                        (bmp.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else bmp
                dest.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            dest.absolutePath
        }.getOrNull()

    /** Path cache foto Google untuk [uid], null bila belum di-cache. */
    fun getCachedGooglePhoto(context: Context, uid: String): String? {
        val f = File(avatarsDir(context), "google_${uid.take(16)}.jpg")
        return if (f.exists()) f.absolutePath else null
    }

    /** Path foto profil CUSTOM user (`custom.jpg`), null bila belum ada. */
    fun getCustomAvatarPath(context: Context): String? {
        val f = File(avatarsDir(context), "custom.jpg")
        return if (f.exists()) f.absolutePath else null
    }

    /**
     * Decode & tulis gambar dari [uri] ke [dest] dengan sampling ≤512px + JPEG 85
     * (avatar hanya ~200dp di layar, jadi hemat memori & penyimpanan).
     * Dipakai [saveAvatar] & [saveCustomAvatar] (DRY). Return false bila gagal.
     */
    private fun writeSampledImage(context: Context, uri: Uri, dest: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 512 && opts.outHeight / (sample * 2) >= 512) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return false
        dest.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return true
    }
}
