package com.startupmini.nyachat.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUDIT MOTION (2026-08-12): reduced-motion harus menegakkan snap — semua
 * tween 0ms (bukan durasi normal), spring dipersingkat ke settle instan,
 * stagger tanpa delay. Memastikan fitur aksesibilitas "Hapus animasi" sistem
 * benar-benar mematikan motion, bukan sekadar mengubah flag.
 */
class MotionReducedMotionTest {

    @After
    fun tearDown() {
        // Selalu reset — jangan bocor ke test lain.
        Motion.reducedMotion = false
    }

    @Test
    fun `default - reducedMotion mati - durasi normal`() {
        assertFalse(Motion.reducedMotion)
        assertEquals(Motion.QUICK_MS, Motion.quick<Float>().durationMillis)
        assertEquals(Motion.FAST_MS, Motion.fast<Float>().durationMillis)
        assertEquals(Motion.BASE_MS, Motion.base<Float>().durationMillis)
        assertEquals(Motion.NAV_MS, Motion.nav<Float>().durationMillis)
    }

    @Test
    fun `reducedMotion aktif - semua tween snap 0ms`() {
        Motion.reducedMotion = true
        assertEquals(0, Motion.quick<Float>().durationMillis)
        assertEquals(0, Motion.fast<Float>().durationMillis)
        assertEquals(0, Motion.base<Float>().durationMillis)
        assertEquals(0, Motion.nav<Float>().durationMillis)
    }

    @Test
    fun `reducedMotion aktif - stagger tanpa delay dan durasi`() {
        Motion.reducedMotion = true
        assertEquals(0, Motion.stagger<Float>(0).durationMillis)
        assertEquals(0, Motion.stagger<Float>(5).durationMillis)
        assertEquals(0, Motion.stagger<Float>(5).delay)
    }

    @Test
    fun `reducedMotion mati - stagger punya durasi base dan delay bertingkat`() {
        assertEquals(Motion.BASE_MS, Motion.stagger<Float>(0).durationMillis)
        assertEquals(Motion.BASE_MS, Motion.stagger<Float>(3).durationMillis)
        assertEquals(0, Motion.stagger<Float>(0).delay)
        assertEquals(135, Motion.stagger<Float>(3).delay) // 3 * 45
    }

    @Test
    fun `reducedMotion aktif - springOrSnap mengembalikan tween 0ms bukan spring`() {
        Motion.reducedMotion = true
        val spec = Motion.springOrSnap(
            spring<Float>(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
        assertTrue(
            "harus tween (bukan spring)",
            spec is TweenSpec<*>
        )
        val tweenSpec = spec as TweenSpec<*>
        assertEquals(0, tweenSpec.durationMillis)
    }

    @Test
    fun `reducedMotion mati - springOrSnap tetap mengembalikan spring asli`() {
        val springSpec = spring<Float>(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
        val spec: FiniteAnimationSpec<Float> = Motion.springOrSnap(springSpec)
        assertEquals(springSpec, spec)
    }
}
