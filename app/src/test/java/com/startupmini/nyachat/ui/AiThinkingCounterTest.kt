package com.startupmini.nyachat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Regression test audit ketahanan (2026-08-11):
 * indikator "AI berpikir" harus tetap ON selama masih ada operasi AI berjalan
 * — 2 pesan beruntun tidak boleh mematikan indikator lebih awal.
 */
class AiThinkingCounterTest {

    @Test
    fun `satu operasi - start on finish off`() {
        val c = AiThinkingCounter()
        assertTrue(c.start())   // operasi 1 mulai → ON
        assertFalse(c.finish()) // operasi 1 selesai → OFF
        assertTrue(c.current() == 0)
    }

    @Test
    fun `dua operasi beruntun - indikator tetap on sampai keduanya selesai`() {
        val c = AiThinkingCounter()
        assertTrue(c.start())   // pesan 1 mulai → ON
        assertTrue(c.start())   // pesan 2 mulai (sebelum 1 selesai) → tetap ON
        assertTrue(c.finish())  // pesan 1 selesai → MASIH ON (pesan 2 jalan)
        assertTrue(c.current() == 1)
        assertFalse(c.finish()) // pesan 2 selesai → OFF
        assertTrue(c.current() == 0)
    }

    @Test
    fun `tiga operasi - off hanya saat counter kembali ke nol`() {
        val c = AiThinkingCounter()
        c.start(); c.start(); c.start()
        assertTrue(c.finish())
        assertTrue(c.finish())
        assertFalse(c.finish())
        assertTrue(c.current() == 0)
    }

    @Test
    fun `finish tanpa start - di-clamp ke nol dan tidak negatif`() {
        val c = AiThinkingCounter()
        assertFalse(c.finish()) // defensif: alur tak seimbang tidak boleh negatif
        assertFalse(c.finish())
        assertTrue(c.current() == 0)
    }

    @Test
    fun `aman dipakai dari banyak thread sekaligus`() {
        val c = AiThinkingCounter()
        val threads = (1..8).map {
            thread(start = true) {
                repeat(100) {
                    c.start()
                    c.finish()
                }
            }
        }
        threads.forEach { it.join() }
        assertTrue("counter harus kembali ke 0 setelah 800 siklus seimbang", c.current() == 0)
    }
}
