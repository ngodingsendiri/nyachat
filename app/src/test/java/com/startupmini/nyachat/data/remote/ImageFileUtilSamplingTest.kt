package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 (audit performa 2026-08-12) — unit test logika sampling [ImageFileUtil],
 * yang PALING rawan bug: rasio ekstrem, bounds gagal, sample 0. Murni JVM
 * (tanpa Android) karena hanya menguji fungsi `computeSampleSize` — murah &
 * deterministik. Logika ini dipakai 3 jalur decode (chat media 1100px, avatar
 * 128px, simpan foto 1600px) + sampling avatar [AvatarStore].
 */
class ImageFileUtilSamplingTest {

    // ===== Rasio normal =====

    @Test
    fun smallImage_noSampling() {
        // 800px dengan maxDim 1024 → sisi terpanjang sudah < maxDim → sample 1.
        assertEquals(1, ImageFileUtil.computeSampleSize(800, 600, 1024))
        // Persis di bawah batas → tetap 1.
        assertEquals(1, ImageFileUtil.computeSampleSize(1023, 600, 1024))
    }

    @Test
    fun exactlyAtBoundary_staysOne() {
        // 1024px == maxDim: 1024/(1*2)=512 < 1024 → sample 1 (decode penuh OK).
        assertEquals(1, ImageFileUtil.computeSampleSize(1024, 1024, 1024))
    }

    @Test
    fun portraitAndLandscape_symmetric() {
        // Sisi TERPANJANG yang jadi patokan — orientasi tidak mengubah hasil.
        assertEquals(
            ImageFileUtil.computeSampleSize(4000, 3000, 1024),
            ImageFileUtil.computeSampleSize(3000, 4000, 1024)
        )
    }

    // ===== Rasio ekstrem (bug && lama di AvatarStore) =====

    @Test
    fun extremeWide_samplingFollowsLongestSide() {
        // Panorama 10000×100, maxDim 256: sisi terpanjang harus disampling sampai
        // mendekati 256 — BUKAN berhenti karena sisi pendek sudah kecil (loop &&
        // lama menghasilkan sample=1 → decode penuh 10000px!).
        val sample = ImageFileUtil.computeSampleSize(10000, 100, 256)
        assertTrue("sample=$sample harus besar untuk rasio ekstrem", sample >= 32)
        // Sisi terpanjang hasil decode harus ≤ ~2× maxDim (pangkat dua paling dekat).
        assertTrue("decode width masih kelewat besar", 10000 / sample <= 256 * 2)
    }

    @Test
    fun extremeTall_samplingFollowsLongestSide() {
        val sample = ImageFileUtil.computeSampleSize(100, 10000, 256)
        assertTrue("sample=$sample harus besar untuk rasio ekstrem", sample >= 32)
        assertTrue("decode height masih kelewat besar", 10000 / sample <= 256 * 2)
    }

    // ===== Bounds gagal / input invalid =====

    @Test
    fun zeroOrNegativeBounds_returnOne() {
        // bounds gagal dibaca (0/negatif) → tanpa sampling (aman, decodeFile
        // mengembalikan null sendiri).
        assertEquals(1, ImageFileUtil.computeSampleSize(0, 600, 1024))
        assertEquals(1, ImageFileUtil.computeSampleSize(800, 0, 1024))
        assertEquals(1, ImageFileUtil.computeSampleSize(-1, 600, 1024))
        assertEquals(1, ImageFileUtil.computeSampleSize(800, -5, 1024))
        assertEquals(1, ImageFileUtil.computeSampleSize(0, 0, 1024))
    }

    @Test
    fun invalidMaxDim_returnOne() {
        assertEquals(1, ImageFileUtil.computeSampleSize(4000, 3000, 0))
        assertEquals(1, ImageFileUtil.computeSampleSize(4000, 3000, -1))
    }

    // ===== Sample size selalu pangkat dua =====

    @Test
    fun sampleIsAlwaysPowerOfTwo() {
        val cases = listOf(
            Triple(10000, 100, 256),
            Triple(8000, 4000, 1024),
            Triple(6000, 2000, 1600),
            Triple(3200, 3200, 512),
            Triple(5000, 7000, 640)
        )
        for ((w, h, maxDim) in cases) {
            val s = ImageFileUtil.computeSampleSize(w, h, maxDim)
            assertTrue("sample $s untuk ${w}x$h bukan pangkat dua", s > 0 && (s and (s - 1)) == 0)
        }
    }

    /** Kontrak: hasil decode (ukuran/sample) harus ≤ 2× maxDim (pangkat dua terdekat). */
    @Test
    fun decodedSizeStaysCloseToMaxDim() {
        val cases = listOf(
            Triple(4000, 3000, 1600), // foto kamera biasa
            Triple(12000, 9000, 1100), // bubble media
            Triple(4096, 3072, 128),  // avatar kecil
            Triple(8000, 8000, 1024)  // persegi besar
        )
        for ((w, h, maxDim) in cases) {
            val s = ImageFileUtil.computeSampleSize(w, h, maxDim)
            val decodedW = w / s
            val decodedH = h / s
            assertTrue(
                "decode ${decodedW}x$decodedH dari ${w}x$h (maxDim $maxDim, sample $s) terlalu besar",
                maxOf(decodedW, decodedH) <= maxDim * 2
            )
        }
    }
}
