package com.startupmini.nyachat.ui

import java.util.concurrent.atomic.AtomicInteger

/**
 * Penghitung operasi AI aktif — dipakai indikator "AI sedang berpikir".
 *
 * Audit ketahanan (2026-08-11): sebelumnya indikator memakai boolean yang
 * di-set false di `finally` tiap operasi. Mengirim 2 pesan beruntun membuat
 * indikator mati lebih awal (pesan pertama selesai) padahal pesan kedua masih
 * diproses. Counter ini menjamin indikator hanya off saat SEMUA operasi
 * selesai (kembali ke 0).
 *
 * Thread-safe (AtomicInteger) — operasi start/finish bisa datang dari
 * coroutine berbeda. [finish] di-clamp ke 0 supaya tidak negatif bila alur
 * tak seimbang (defensif, tidak menimbulkan efek samping).
 */
class AiThinkingCounter {

    private val count = AtomicInteger(0)

    /** Tandai satu operasi AI mulai. Return true bila indikator harus ON. */
    fun start(): Boolean = count.incrementAndGet() > 0

    /** Tandai satu operasi AI selesai. Return true bila indikator tetap ON.
     *  updateAndGet: nilai INTERNAL di-clamp ke 0 (bukan hanya nilai kembalian)
     *  supaya `current()` tidak pernah negatif saat alur tak seimbang. */
    fun finish(): Boolean = count.updateAndGet { (it - 1).coerceAtLeast(0) } > 0

    /** Nilai saat ini — untuk pengujian/observabilitas. */
    fun current(): Int = count.get()
}
