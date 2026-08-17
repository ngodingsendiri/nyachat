package com.startupmini.nyachat.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Hasil penyalinan file dokumen: path lokal + nama asli. */
data class SavedFile(val path: String, val name: String)

/**
 * Utilitas lampiran chat (foto nota & dokumen):
 * - saveImageFromUri: salin foto dari galeri/kamera, di-downscale & dikompres JPEG
 *   ke penyimpanan internal (filesDir/attachments) supaya ringan & siap dikirim ke AI.
 * - saveFileFromUri: salin file dokumen (PDF/invoice/nota) apa adanya ke folder yang
 *   sama — tidak dibaca AI, hanya dilampirkan & bisa dibuka.
 * - decodeImage: baca bitmap untuk ditampilkan di chat (dengan sampling biar hemat RAM).
 * - encodeBase64: enkode file jadi base64 untuk API AI vision (Gemini inline_data /
 *   OpenRouter image_url).
 */
object ImageFileUtil {

    // r1.7.0 (kompresi ala WA): 1280px / JPEG 82 — hampir sekecil WhatsApp
    // (1080px/80) tapi nota tetap tajam untuk parsing AI. Nilai sebelumnya
    // 1600px/85 (lebih besar dari yang dibutuhkan untuk tampilan & AI).
    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 82

    /**
     * M9 — lampiran di-namespace per workspace (PIN). Sebelumnya SEMUA lampiran
     * disimpan di satu folder global `filesDir/attachments` dan dihapus total saat
     * ganti workspace → kembali ke workspace lama, foto nota rusak padahal path
     * masih tersimpan di DB. Kini tiap PIN punya folder sendiri, dan ganti
     * workspace hanya menghapus folder milik workspace yang ditinggalkan.
     */
    private fun attachmentsDir(context: Context, workspace: String?): File {
        val base = File(context.filesDir, "attachments")
        if (workspace.isNullOrBlank()) return base
        val safe = workspace.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(base, safe).apply { mkdirs() }
    }

    /** Salin file dari URI (dokumen PDF/invoice) ke penyimpanan internal. */
    suspend fun saveFileFromUri(context: Context, uri: Uri, workspace: String? = null): SavedFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                val originalName = queryDisplayName(context, uri)
                    ?: "file_${System.currentTimeMillis()}"
                val safeName = originalName.substringAfterLast('/').take(80)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = attachmentsDir(context, workspace)
                val file = File(dir, "doc_${System.currentTimeMillis()}_$safeName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                SavedFile(file.absolutePath, safeName)
            }.getOrNull()
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()

    /**
     * Salin + downscale foto dari URI (galeri/kamera) ke penyimpanan internal.
     *
     * Audit performa (2026-08-12): decode TIDAK lagi membaca bitmap penuh ke
     * memori — foto kamera modern (48–108 MP) bisa memakan 100–400 MB bila
     * di-decode utuh dan berisiko OOM. Kini bounds dibaca dulu
     * (inJustDecodeBounds, murah) lalu inSampleSize dihitung agar sisi
     * terpanjang mendekati MAX_DIMENSION sebelum decode — sama seperti
     * [decodeImage]/[AvatarStore.decodeSampled]. Di bawah MAX_DIMENSION tetap
     * pakai [scaleDown] untuk ukuran persis.
     */
    suspend fun saveImageFromUri(context: Context, uri: Uri, workspace: String? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                // 1) Baca dimensi tanpa decode penuh (murah & aman memori).
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                // 2) Sample size: bagi 2 sampai sisi terpanjang mendekati MAX_DIMENSION.
                val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                } ?: return@withContext null

                // 3) Scale presisi ke MAX_DIMENSION & kompres JPEG.
                val scaled = scaleDown(bitmap, MAX_DIMENSION)
                val dir = attachmentsDir(context, workspace)
                val file = File(dir, "att_${System.currentTimeMillis()}.jpg")
                file.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                file.absolutePath
            }.getOrNull()
        }

    /** Baca bitmap untuk ditampilkan — disampling supaya hemat memori. */
    fun decodeImage(path: String, maxDim: Int = 1024): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        }.getOrNull()
    }

    /**
     * Enkode file gambar jadi base64 (untuk API AI vision).
     *
     * L5: metode ini membaca SELURUH file ke memori (File.readBytes), jadi batas
     * aman untuk foto nota yang sudah di-downscale adalah ≤ ~5 MB. Foto yang
     * melewati [saveImageFromUri] selalu berukuran kecil (max 1600px, JPEG 85);
     * dokumen PDF TIDAK dikirim ke AI. Untuk file besar di masa depan, ganti
     * dengan streaming (encode per-chunk) agar tidak memakan memori tinggi.
     */
    fun encodeBase64(imagePath: String): String? {
        return runCatching {
            val bytes = File(imagePath).readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * Hapus lampiran milik SATU workspace (folder per-PIN). Dipakai saat ganti
     * workspace supaya foto workspace lama tidak ikut terhapus (M9).
     */
    fun deleteWorkspaceAttachments(context: Context, workspace: String) {
        if (workspace.isBlank()) return
        val safe = workspace.replace(Regex("[^A-Za-z0-9_-]"), "_")
        runCatching {
            val dir = File(File(context.filesDir, "attachments"), safe)
            // Rekursif + hapus folder-nya juga (konsisten dengan deleteAllAttachments).
            dir.deleteRecursively()
        }
    }

    /**
     * Hapus semua file lampiran (foto nota & dokumen) di penyimpanan internal.
     *
     * Rekursif (audit remote/ 2026-08-13): lampiran kini di-namespace per
     * workspace (`attachments/<pin>/...`, M9) — `File.delete()` pada direktori
     * non-kosong gagal diam-diam, jadi clear data / logout sebelumnya
     * MENINGGALKAN semua foto workspace lama di disk (storage leak + privasi).
     */
    fun deleteAllAttachments(context: Context) {
        runCatching {
            val dir = File(context.filesDir, "attachments")
            dir.listFiles()?.forEach { file -> file.deleteRecursively() }
        }
    }

    /**
     * Sample size pangkat dua agar sisi terpanjang mendekati [maxDim] setelah
     * decode (dipakai [decodeImage] & [saveImageFromUri] — satu sumber kebenaran,
     * audit performa 2026-08-12). Nilai 0/negatif (bounds gagal) → 1 (tanpa
     * sampling, aman).
     *
     * P2 (audit performa 2026-08-12): dibuat `internal` supaya bisa di-cover
     * unit test murni JVM ([ImageFileUtilSamplingTest]) — logika ini paling
     * rawan bug (rasio ekstrem, bounds gagal, sample 0) dan dipakai 3 jalur
     * decode (chat media, avatar, simpan foto).
     */
    internal fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0 || maxDim <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxDim || height / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val max = maxOf(w, h)
        if (max <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / max
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * ratio).toInt().coerceAtLeast(1),
            (h * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
