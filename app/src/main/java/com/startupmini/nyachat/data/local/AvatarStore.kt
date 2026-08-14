package com.startupmini.nyachat.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.startupmini.nyachat.data.remote.ImageFileUtil
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

    // ===== Mesin kompresi avatar (r1.2.3 — P1) =====
    // Avatar tampil kecil (24-40dp di chat/topbar, ~100dp di profil). Foto asli
    // kamera/gallery bisa 12-48MP — memuat & menyimpannya utuh hanya boros
    // memori, penyimpanan, DAN bandwidth cloud. Strategi:
    //   - [LOCAL_MAX_DIM]: resolusi avatar yang disimpan/dipakai lokal.
    //   - [CLOUD_MAX_DIM] + [CLOUD_QUALITY]: versi UPLOAD ke Firestore (Blob)
    //     di dalam dokumen member — jauh lebih kecil daripada menyimpan file
    //     asli, sehingga biaya penyimpanan & traffic server minimal.
    const val LOCAL_MAX_DIM = 256
    const val CLOUD_MAX_DIM = 128
    const val CLOUD_QUALITY = 72

    private fun avatarsDir(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    // ===== Profil & Akun (r1.2.1) =====

    /**
     * Simpan foto profil CUSTOM user — nama file FIXED (`custom.jpg`) supaya
     * ganti nama user tidak menghilangkan foto (tidak seperti penyimpanan yang
     * berkunci nama — dihapus di audit local/ 2026-08-13 karena tidak dipakai).
     * Sampling [LOCAL_MAX_DIM] + JPEG 85 agar ringan. Return path absolut.
     */
    suspend fun saveCustomAvatar(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(avatarsDir(context), "custom.jpg")
                if (!writeSampledImage(context, uri, dest, LOCAL_MAX_DIM, 85)) return@runCatching null
                dest.absolutePath
            }.getOrNull()
        }

    /**
     * Kompres foto dari [uri] menjadi BYTES JPEG kecil untuk di-upload ke
     * Firestore (Blob di dokumen member) — sampling ≤[CLOUD_MAX_DIM] px +
     * quality [CLOUD_QUALITY] (≈3-10KB). Foto ini ditampilkan anggota lain di
     * header chat / topbar (24-40dp), jadi resolusi kecil sudah tajam. Sangat
     * irit penyimpanan & traffic server dibanding upload file asli.
     * Dipanggil dari Dispatchers.IO. Return null bila gagal.
     */
    fun compressAvatarForCloud(context: Context, uri: Uri): ByteArray? =
        runCatching {
            val bmp = decodeSampled(context, uri, CLOUD_MAX_DIM) ?: return null
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, CLOUD_QUALITY, out)
            out.toByteArray()
        }.getOrNull()

    /**
     * Unduh & cache foto profil GOOGLE ke `google_<uid>.jpg` (cache lokal).
     * Hanya salinan untuk avatar aplikasi — akun Google TIDAK diubah. Panggil
     * dari Dispatchers.IO (akses jaringan). Return path, null bila gagal.
     */
    fun cacheGooglePhoto(context: Context, url: String, uid: String): String? =
        runCatching {
            val dest = File(avatarsDir(context), "google_${uid.take(16)}.jpg")
            if (dest.exists() && dest.length() > 0) return@runCatching dest.absolutePath
            // Baca bytes SEKALI lalu decode sampling (audit local/ 2026-08-13) —
            // sebelumnya decode penuh foto Google 12MP+ baru di-scale, boros
            // memori (~48MB alokasi transien). Stream tidak bisa di-rewind,
            // jadi bounds decode dari bytes yang sudah dibaca.
            val bytes = java.net.URL(url).openStream().use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
            val sample = ImageFileUtil.computeSampleSize(opts.outWidth, opts.outHeight, LOCAL_MAX_DIM)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: return@runCatching null
            val scaled = scaleTo(bmp, LOCAL_MAX_DIM)
            dest.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
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

    // ===== Cache avatar ANGGOTA LAIN (r1.2.3 — P1) =====
    // Foto anggota lain diterima sebagai bytes (Blob Firestore). Disimpan ke
    // disk per uid+version supaya header chat / topbar bisa menampilkannya
    // tanpa men-decode bytes berulang setiap komposisi.

    /** Nama file cache avatar anggota lain, dikunci version (invalidate alami). */
    private fun memberAvatarFile(context: Context, uid: String, version: Long): File =
        File(avatarsDir(context), "member_${uid.take(16)}_$version.jpg")

    /**
     * Simpan bytes avatar anggota lain ke disk cache. Return path, null bila
     * gagal. [version] mencegah file lama kedaluwarsa dipakai ulang.
     */
    fun cacheMemberAvatar(context: Context, uid: String, version: Long, bytes: ByteArray): String? =
        runCatching {
            val dest = memberAvatarFile(context, uid, version)
            if (dest.exists() && dest.length() > 0) return dest.absolutePath
            dest.outputStream().use { it.write(bytes) }
            // Bersihkan file versi LAMA uid ini (audit local/ 2026-08-13): versi
            // naik tiap avatar diperbarui; tanpa ini file lama menumpuk selamanya.
            avatarsDir(context).listFiles()?.forEach { f ->
                if (f != dest && f.name.startsWith("member_${uid.take(16)}_")) f.delete()
            }
            dest.absolutePath
        }.getOrNull()

    /** Path cache avatar anggota lain, null bila belum ter-cache untuk versi ini. */
    fun getMemberAvatarPath(context: Context, uid: String, version: Long): String? {
        val f = memberAvatarFile(context, uid, version)
        return if (f.exists()) f.absolutePath else null
    }

    /**
     * Decode & tulis gambar dari [uri] ke [dest] dengan sampling ≤[maxDim] +
     * quality [quality]. Dipakai seluruh jalur simpan avatar (DRY).
     * Return false bila gagal.
     */
    private fun writeSampledImage(
        context: Context,
        uri: Uri,
        dest: File,
        maxDim: Int,
        quality: Int
    ): Boolean {
        val bmp = decodeSampled(context, uri, maxDim) ?: return false
        dest.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return true
    }

    /** Decode dari [uri] dengan sampling agar sisi terpanjang ≤ [maxDim]. */
    private fun decodeSampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        // P2 (audit performa 2026-08-12): loop lama memakai `&&` — gambar rasio
        // ekstrem (mis. 10000×100) berhenti sampling segera (satu sisi sudah di
        // bawah maxDim) → sample=1 → decode penuh 10000px! Satu sumber
        // kebenaran: computeSampleSize ImageFileUtil (`||` — sisi TERPANJANG
        // yang jadi patokan), sudah di-cover unit test sampling.
        val sample = ImageFileUtil.computeSampleSize(opts.outWidth, opts.outHeight, maxDim)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null
        return scaleTo(bmp, maxDim)
    }

    /** Perkecil bitmap agar sisi terpanjang ≤ [maxDim] (proportional). */
    private fun scaleTo(bmp: Bitmap, maxDim: Int): Bitmap {
        if (bmp.width <= maxDim && bmp.height <= maxDim) return bmp
        val ratio = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
