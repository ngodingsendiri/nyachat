package com.startupmini.nyachat.data.remote

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P2 (audit performa 2026-08-12) — guard sederhana: 1 decode per path per sesi.
 *
 * Sebelum ada [BitmapCache], `AvatarImage`/bubble media men-decode ulang dari
 * disk setiap item muncul di layar (scroll bolak-balik = decode berulang =
 * skipped frames). Cache tidak men-decode ulang bila item sudah pernah dimuat —
 * dibuktikan lewat counter hit/miss & identitas instance bitmap.
 */
@RunWith(RobolectricTestRunner::class)
class BitmapCacheTest {

    @Before
    fun setUp() {
        BitmapCache.clearForTest()
    }

    private fun bitmapOf(): Bitmap =
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

    /** Item yang sudah di-cache TIDAK menambah miss — di-return dari cache (hit). */
    @Test
    fun cachedAvatar_secondDecode_isHit_notMiss() {
        val bmp = bitmapOf()
        BitmapCache.putAvatarForTest("/avatars/a.jpg", bmp)

        val first = BitmapCache.decodeAvatar("/avatars/a.jpg")
        val second = BitmapCache.decodeAvatar("/avatars/a.jpg")

        // Instance SAMA → tidak ada decode ulang dari disk.
        assertSame(first, second)
        assertEquals("miss harus 0 (tidak decode ulang)", 0L, BitmapCache.misses)
        assertEquals("dua akses → dua hit", 2L, BitmapCache.hits)
    }

    /**
     * Guard utama (P2): 1 decode per path per sesi. Decoder di-inject dan
     * menghitung pemanggilannya — setelah item di-cache, akses berikutnya TIDAK
     * memanggil decoder (tidak decode ulang dari disk).
     */
    @Test
    fun decodedOnce_thenCached_neverDecodesAgain() {
        var decodeCalls = 0
        BitmapCache.testDecoder = { _, _ -> decodeCalls++; bitmapOf() }

        val first = BitmapCache.decodeAvatar("/avatars/a.jpg")
        val second = BitmapCache.decodeAvatar("/avatars/a.jpg")
        val third = BitmapCache.decodeAvatar("/avatars/a.jpg")

        assertSame("instance sama → di-return dari cache", first, second)
        assertSame(first, third)
        assertEquals("decode hanya 1× untuk 3 akses", 1, decodeCalls)
        assertEquals("1 miss (decode pertama) + 2 hit", 1L, BitmapCache.misses)
        assertEquals(2L, BitmapCache.hits)
    }

    /** Path yang belum pernah dimuat & decoder gagal → null + miss tercatat. */
    @Test
    fun missingPath_isMiss_returnsNull() {
        BitmapCache.testDecoder = { _, _ -> null } // simulasi file tidak ada / decode gagal
        val result = BitmapCache.decodeAvatar("/avatars/tidak-ada.jpg")
        assertNull(result)
        assertEquals("path hilang harus dihitung miss", 1L, BitmapCache.misses)
        assertEquals(0L, BitmapCache.hits)
    }

    /** null path tidak menyentuh cache sama sekali (guard NPE). */
    @Test
    fun nullPath_noCacheAccess() {
        assertNull(BitmapCache.decodeAvatar(null))
        assertNull(BitmapCache.decodeMedia(null, 1100))
        assertEquals(0L, BitmapCache.misses)
        assertEquals(0L, BitmapCache.hits)
    }

    /** Key cache memuat maxDim: preview 640px & bubble 1100px dari FILE yang sama di-cache terpisah. */
    @Test
    fun sameFileDifferentMaxDim_cachedSeparately() {
        val preview = bitmapOf()
        val bubble = bitmapOf()
        BitmapCache.putMediaForTest("/att/x.jpg", 640, preview)
        BitmapCache.putMediaForTest("/att/x.jpg", 1100, bubble)

        assertSame(preview, BitmapCache.decodeMedia("/att/x.jpg", 640))
        assertSame(bubble, BitmapCache.decodeMedia("/att/x.jpg", 1100))
        // Keduanya hit — preview 640 tidak menimpa bubble 1100 (key terpisah).
        assertEquals(2L, BitmapCache.hits)
        assertEquals(0L, BitmapCache.misses)
    }

    /** Cache avatar & media terpisah — item di cache yang satu tak bocor ke yang lain. */
    @Test
    fun avatarAndMediaCaches_areIsolated() {
        BitmapCache.testDecoder = { _, _ -> null } // media tidak ter-cache → miss
        val avatar = bitmapOf()
        BitmapCache.putAvatarForTest("/x.jpg", avatar)

        // Path sama tapi cache berbeda → media tidak melihat avatar.
        assertNull(BitmapCache.decodeMedia("/x.jpg", 640))
        assertEquals(1L, BitmapCache.misses)
        assertSame(avatar, BitmapCache.decodeAvatar("/x.jpg"))
        assertEquals(1L, BitmapCache.hits)
    }

    /** Decode gagal (null) TIDAK di-cache — akses berikutnya tetap miss (bukan hit basi). */
    @Test
    fun failedDecode_isNotCached() {
        BitmapCache.testDecoder = { _, _ -> null }
        assertNull(BitmapCache.decodeAvatar("/x.jpg"))
        assertNull(BitmapCache.decodeAvatar("/x.jpg"))
        assertEquals("dua percobaan gagal → dua miss", 2L, BitmapCache.misses)
        assertEquals(0L, BitmapCache.hits)
    }
}
