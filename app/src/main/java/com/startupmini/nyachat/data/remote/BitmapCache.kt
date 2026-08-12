package com.startupmini.nyachat.data.remote

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Cache bitmap SESI untuk menghilangkan decode ulang dari disk (P1 — audit
 * performa 2026-08-12). Sebelumnya `AvatarImage` (128px) & bubble media
 * (1100px) di-decode ulang SETIAP item muncul di layar — scroll bolak-balik =
 * decode berulang → skipped frames (jank).
 *
 * Dua cache terpisah dengan ukuran berbasis byte:
 * - [avatarCache] 14 MB — avatar kecil (128px, header chat/topbar/profil).
 * - [mediaCache] 32 MB — thumbnail media per sesi (640px preview composer /
 *   1100px bubble chat); ~10 gambar 1100px sudah penuh — batas aman low-RAM
 *   (2GB) tanpa mengorbankan scroll mulus untuk sesi normal.
 *
 * Key = `"maxDim|path"` supaya preview 640px & bubble 1100px dari FILE yang
 * sama di-cache terpisah (tidak saling menimpa).
 *
 * Catatan race (bukan bug): dua coroutine IO yang sama-sama miss path yang
 * sama bisa men-decode dobel sebelum salah satunya `put` — `LruCache.put`
 * atomik, jadi tidak ada korupsi; hanya satu decode terbuang. Tidak perlu
 * serialisasi (justru memperlambat scroll).
 */
object BitmapCache {

    private const val AVATAR_MAX_BYTES = 14 * 1024 * 1024 // 14 MB
    private const val MEDIA_MAX_BYTES = 32 * 1024 * 1024  // 32 MB

    // sizeOf override (bukan konstruktor SizeOf API 31) — kompatibel minSdk 24:
    // bobot cache dihitung dari byte bitmap, bukan jumlah item.
    private val avatarCache = object : LruCache<String, Bitmap>(AVATAR_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val mediaCache = object : LruCache<String, Bitmap>(MEDIA_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Statistik hit/miss — dipakai unit test guard performa (jumlah decode per scroll). */
    var hits: Long = 0
        private set
    var misses: Long = 0
        private set

    /** Dekode foto avatar (sampling 128px) dengan cache sesi. Null → null. */
    fun decodeAvatar(path: String?): Bitmap? =
        path?.let { decodeCached(avatarCache, it, 128) }

    /**
     * Dekode media/thumbnail dengan cache sesi. [maxDim] dipakai sampling
     * (640 preview / 1100 bubble) DAN sebagai bagian key cache.
     */
    fun decodeMedia(path: String?, maxDim: Int): Bitmap? =
        path?.let { decodeCached(mediaCache, it, maxDim) }

    private fun decodeCached(
        cache: LruCache<String, Bitmap>,
        path: String,
        maxDim: Int
    ): Bitmap? {
        val key = key(maxDim, path)
        cache.get(key)?.let { hit ->
            hits++
            return hit
        }
        misses++
        // Seam test (P2): inject decoder supaya guard hit/miss bisa diuji
        // deterministik tanpa file nyata. Saat null → decode asli dari disk.
        val decoder = testDecoder
        val bmp = if (decoder != null) decoder(path, maxDim) else ImageFileUtil.decodeImage(path, maxDim)
        if (bmp == null) return null
        cache.put(key, bmp)
        return bmp
    }

    /** Test-only: inject decoder (path, maxDim) → Bitmap. Null = pakai decode asli. */
    internal var testDecoder: ((String, Int) -> Bitmap?)? = null

    private fun key(maxDim: Int, path: String): String = "$maxDim|$path"

    /**
     * Kosongkan cache + reset statistik — dipanggil saat logout / hapus data /
     * pindah workspace: file avatar & lampiran DIHAPUS dari disk, jadi bitmap
     * hasil decode tidak boleh menggantung di memori sampai sesi berakhir
     * (reviewer, audit performa 2026-08-12).
     */
    fun clear() {
        avatarCache.evictAll()
        mediaCache.evictAll()
        hits = 0
        misses = 0
    }

    // ===== Test-only (guard performa) =====
    // Simulasikan hasil decode tanpa file nyata supaya hit/miss & instance
    // identity bisa diverifikasi deterministik di unit test Robolectric.

    internal fun putAvatarForTest(path: String, bmp: Bitmap) =
        avatarCache.put(key(128, path), bmp)

    internal fun putMediaForTest(path: String, maxDim: Int, bmp: Bitmap) =
        mediaCache.put(key(maxDim, path), bmp)

    internal fun clearForTest() {
        clear()
        testDecoder = null
    }
}
