package com.startupmini.nyachat.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * MOTION LANGUAGE aplikasi (audit animasi 2026-08-11) — satu sumber kebenaran
 * untuk durasi & easing seluruh animasi tween.
 *
 * Karakter: ringan, natural, konsisten. Semua tween memakai easing
 * FastOutSlowIn (mulai cepat, melandai lembut) — TIDAK ada bounce/overshoot
 * di elemen layout. Spring hanya dipakai untuk GESTURE yang memang harus
 * elastis: FAB jump-to-bottom & geser chips (LowBouncy stiffness 600f, sesuai
 * permintaan user agar kemunculan lembut) dan swipe-reply bubble.
 *
 * Konteks penggunaan:
 *  - [quick]  (150ms): state mikro — fade kecil, elemen yang HILANG (dismiss
 *    cepat & responsif).
 *  - [fast]   (200ms): elemen naik-turun composer (reply quote, pratinjau),
 *    toggle warna, expand kecil.
 *  - [base]   (250ms): elemen muncul/hilang umum (chip saldo, filter, navbar,
 *    snackbar snap-back).
 *  - [nav]    (300ms): navigasi antar tab — smooth & ringan.
 */
object Motion {
    const val QUICK_MS = 150
    const val FAST_MS = 200
    const val BASE_MS = 250
    const val NAV_MS = 300

    val STANDARD: Easing = FastOutSlowInEasing

    /** 150ms — micro state / elemen menghilang (responsif). */
    fun <T> quick(): TweenSpec<T> = tween(QUICK_MS, easing = STANDARD)

    /** 200ms — composer elements, toggle warna, expand kecil. */
    fun <T> fast(): TweenSpec<T> = tween(FAST_MS, easing = STANDARD)

    /** 250ms — elemen muncul/hilang umum. */
    fun <T> base(): TweenSpec<T> = tween(BASE_MS, easing = STANDARD)

    /** 300ms — navigasi antar layar/tab. */
    fun <T> nav(): TweenSpec<T> = tween(NAV_MS, easing = STANDARD)
}
