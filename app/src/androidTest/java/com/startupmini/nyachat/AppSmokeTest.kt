package com.startupmini.nyachat

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SMOKE TEST perangkat nyata (P2#3 — audit 2026-08-14): satu-satunya test yang
 * berjalan di emulator sungguhan (CI job device-smoke). Robolectric tidak
 * mensimulasikan runtime Android penuh (Keystore, Firebase, layout, IME) —
 * test ini memastikan APK bisa LAUNCH tanpa crash dan merender konten.
 *
 * Sengaja minimal & toleran: tanpa akun Google emulator menampilkan layar
 * connect/PIN — yang penting app hidup, tidak FATAL, dan ada konten di layar.
 * TIDAK memakai waitForIdle Compose: animasi infinite (indikator aiSpark di
 * ChatBubbles) membuat Compose tidak pernah idle → test bisa menggantung.
 * Sleep bounded + cek Activity masih hidup adalah pendekatan yang stabil.
 *
 * Test alur lengkap (kirim pesan → badge → Rekap) memakai Robolectric unit
 * test + verifikasi manual di emulator (lihat docs/DEVELOPER.md).
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun app_launches_and_renders_content_without_crash() {
        // Beri waktu startup phase (Firestore auto-connect / layar connect).
        Thread.sleep(6_000)
        // Masih ada view yang dirender → app tidak blank & tidak crash.
        onView(isRoot()).check(matches(isDisplayed()))
        // Activity masih hidup (onActivity melempar kalau sudah di-destroy).
        scenario.onActivity { activity -> assertNotNull(activity) }
    }
}
