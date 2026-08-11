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
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                // Sampling agar ukuran file kecil (avatar hanya ~200dp di layar).
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 512 && opts.outHeight / (sample * 2) >= 512) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                } ?: return@runCatching null
                val dest = fileFor(context, name)
                dest.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
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
}
