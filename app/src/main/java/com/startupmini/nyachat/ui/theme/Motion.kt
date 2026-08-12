package com.startupmini.nyachat.ui.theme

import android.content.Context
import android.provider.Settings
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
 * elastis, semua berkarakter LowBouncy (tanpa overshoot berlebihan):
 *  - FAB jump-to-bottom & geser chips: LowBouncy stiffness 1600f (audit motion
 *    2026-08-12: diturunkan dari 600f ±1s ke ±600ms — tetap soft tapi
 *    responsif; sinkron satu sama lain).
 *  - Swipe-reply bubble: LowBouncy + StiffnessMediumLow (elastis halus,
 *    responsif — menggantikan MediumBouncy/StiffnessMedium yang terlontar).
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

    /**
     * REDUCED MOTION (audit aksesibilitas 2026-08-12): saat sistem mematikan
     * animasi (Settings > Aksesibilitas > Hapus animasi, ANIMATOR_DURATION_SCALE=0),
     * seluruh tween snap ke 0ms supaya tidak ada motion yang wajib dipahami
     * user. Di-set sekali dari MainActivity.onCreate via [applySystemSetting].
     * Spring tidak dihapus sepenuhnya (gesture butuh feedback fisik), tapi
     * pemanggil yang peduli reduced-motion memakai [springOrSnap] untuk
     * menegakkan settle cepat.
     */
    @Volatile
    var reducedMotion: Boolean = false

    /** 150ms — micro state / elemen menghilang (responsif). */
    fun <T> quick(): TweenSpec<T> = if (reducedMotion) tween(0) else tween(QUICK_MS, easing = STANDARD)

    /** 200ms — composer elements, toggle warna, expand kecil. */
    fun <T> fast(): TweenSpec<T> = if (reducedMotion) tween(0) else tween(FAST_MS, easing = STANDARD)

    /** 250ms — elemen muncul/hilang umum. */
    fun <T> base(): TweenSpec<T> = if (reducedMotion) tween(0) else tween(BASE_MS, easing = STANDARD)

    /** 300ms — navigasi antar layar/tab. */
    fun <T> nav(): TweenSpec<T> = if (reducedMotion) tween(0) else tween(NAV_MS, easing = STANDARD)

    /**
     * Stagger base (250ms) untuk elemen berurutan (mis. chip saran masuk satu
     * per satu seperti kereta, delay [index] * 45ms). Reduced-motion: durasi &
     * delay keduanya 0 → semua chip tampil sekaligus tanpa motion.
     */
    fun <T> stagger(index: Int): TweenSpec<T> =
        if (reducedMotion) tween(0) else tween(BASE_MS, delayMillis = index * 45, easing = STANDARD)

    /**
     * Baca ANIMATOR_DURATION_SCALE sistem (1f = normal, 0f = animasi mati)
     * dan set [reducedMotion]. Dipanggil dari MainActivity.onCreate (bukan
     * composable — cukup sekali, nilai tidak berubah saat runtime).
     */
    fun applySystemSetting(context: Context) {
        // getFloat(cr, name, default) tidak pernah melempar saat setting tidak
        // ada — default dipakai langsung (tidak perlu runCatching).
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        reducedMotion = scale == 0f
    }

    /**
     * Spring yang menegakkan reduced-motion: jika [reducedMotion] aktif,
     * kembalikan spec tween 0ms (settle instan) alih-alih spring berelastis.
     */
    fun <T> springOrSnap(springSpec: androidx.compose.animation.core.SpringSpec<T>): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reducedMotion) tween(0) else springSpec
}
