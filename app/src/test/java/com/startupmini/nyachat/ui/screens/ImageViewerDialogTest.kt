package com.startupmini.nyachat.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.IntSize
import com.startupmini.nyachat.data.remote.BitmapCache
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uji gestur ImageViewerContent / ImageViewerDialog (audit gestur 2026-08-13):
 *  - TAP            → tutup viewer (onDismiss).
 *  - DOUBLE-TAP     → zoom 2.5× (stateDescription = "Perbesaran 250 persen"),
 *                     double-tap lagi → kembali 1×.
 *  - PINCH (2 jari) → zoom masuk (stateDescription > 100 persen).
 *  - Logika zoom/pan [applyZoomPan] diuji murni: clamp skala, batas pan,
 *    titik di bawah jari stabil, offset kembali ke pusat saat 1×.
 *
 * Zoom diverifikasi lewat stateDescription (juga dibaca TalkBack), bukan
 * analisis piksel — captureToImage/captureRoboImage tidak andal menulis file
 * di setup Robolectric ini.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ImageViewerDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var dismissed = false

    @Before
    fun setup() {
        BitmapCache.clearForTest()
        dismissed = false
        val bmp = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AndroidColor.rgb(255, 252, 245))
        val paint = Paint()
        paint.color = AndroidColor.rgb(30, 90, 160)
        canvas.drawRect(0f, 0f, 800f, 140f, paint)
        paint.color = AndroidColor.rgb(200, 30, 30)
        paint.textSize = 40f
        canvas.drawText("TOTAL Rp 162.000", 60f, 620f, paint)
        // Seed langsung ke BitmapCache (key viewer = 2200|path) — tanpa IO disk.
        BitmapCache.putMediaForTest("test_nota", 2200, bmp)
    }

    private fun showViewer() {
        composeRule.setContent {
            CoupleFinanceTheme {
                ImageViewerContent(imagePath = "test_nota", onDismiss = { dismissed = true })
            }
        }
        composeRule.waitForIdle()
        // Tunggu produceState (Dispatchers.IO) selesai dengan sleep nyata —
        // waitUntil tidak andal memompa komposisi di Robolectric.
        var found = false
        repeat(40) {
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithTag("image_viewer_image")
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                found = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue("gambar viewer harus tampil", found)
        composeRule.waitForIdle()
    }

    /** Persentase zoom dari stateDescription Image; -1 jika tidak ada. */
    private fun zoomPercent(): Int {
        val desc = composeRule.onNodeWithTag("image_viewer_image")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        return desc?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: -1
    }

    @Test
    fun `tap menutup viewer`() {
        showViewer()
        composeRule.onRoot().performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertTrue("tap harus memanggil onDismiss", dismissed)
    }

    @Test
    fun `double-tap zoom 250 persen lalu double-tap lagi kembali 100`() {
        showViewer()
        assertEquals("awal harus 1x", 100, zoomPercent())
        composeRule.onRoot().performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertEquals("double-tap harus zoom 2.5x", 250, zoomPercent())
        assertTrue("double-tap bukan tutup", !dismissed)
        composeRule.onRoot().performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertEquals("double-tap kedua harus reset 1x", 100, zoomPercent())
    }

    @Test
    fun `pinch dua jari memperbesar dan tidak menutup`() {
        showViewer()
        composeRule.onRoot().performTouchInput {
            pinch(
                start0 = center - Offset(80f, 0f),
                end0 = center - Offset(180f, 0f),
                start1 = center + Offset(80f, 0f),
                end1 = center + Offset(180f, 0f),
                durationMillis = 300
            )
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
        assertTrue(
            "pinch-out harus zoom masuk (sekarang ${zoomPercent()}%)",
            zoomPercent() > 100
        )
        assertTrue("pinch bukan tutup", !dismissed)
    }

    // ===== Unit test logika transform (applyZoomPan) =====

    private val screen = IntSize(1080, 2400)
    private val center = Offset(540f, 1200f)

    @Test
    fun `pinch zoom in menaikkan skala`() {
        val r = applyZoomPan(1f, Offset.Zero, screen, center, Offset.Zero, 1.5f)
        assertEquals("zoom in 1.5x", 1.5f, r.scale, 0.001f)
        assertEquals("pinch di tengah tidak menggeser", Offset.Zero, r.offset)
    }

    @Test
    fun `zoom tidak melebihi batas maksimum`() {
        val r = applyZoomPan(1f, Offset.Zero, screen, center, Offset.Zero, 20f)
        assertEquals("clamp ke MAX_ZOOM", MAX_ZOOM, r.scale, 0.001f)
    }

    @Test
    fun `zoom tidak turun di bawah 1x`() {
        val r = applyZoomPan(2f, Offset.Zero, screen, center, Offset.Zero, 0.1f)
        assertEquals("clamp ke 1x", 1f, r.scale, 0.001f)
    }

    @Test
    fun `pan saat skala 1x tidak menggeser gambar`() {
        val r = applyZoomPan(1f, Offset.Zero, screen, center, Offset(200f, 100f), 1f)
        assertEquals("offset harus 0 (batas pan = 0)", Offset.Zero, r.offset)
    }

    @Test
    fun `pan ter-clamp ke tepi konten saat zoom`() {
        val r = applyZoomPan(2.5f, Offset.Zero, screen, center, Offset(5000f, 0f), 1f)
        val maxX = (screen.width * 2.5f - screen.width) / 2f
        assertEquals("offset-x di-clamp ke tepi", maxX, r.offset.x, 0.001f)
    }

    @Test
    fun `titik di bawah jari tetap stabil saat zoom`() {
        val oldScale = 1.4f
        val oldOffset = Offset(120f, -80f)
        val centroid = Offset(700f, 300f)
        val zoom = 1.8f
        val r = applyZoomPan(oldScale, oldOffset, screen, centroid, Offset.Zero, zoom)
        // Titik konten di bawah jari sebelum == sesudah:
        // (c − offset_lama) / skala_lama == (c + pan − offset_baru) / skala_baru
        val c = centroid - center
        val before = (c - oldOffset) / oldScale
        val after = (c + Offset.Zero - r.offset) / r.scale
        assertEquals("titik fokus harus tetap", before.x, after.x, 1f)
        assertEquals("titik fokus harus tetap", before.y, after.y, 1f)
    }

    @Test
    fun `offset kembali ke pusat saat zoom reset 1x`() {
        val r = applyZoomPan(1.01f, Offset(300f, 200f), screen, center, Offset.Zero, 0.5f)
        assertEquals("skala clamp 1x", 1f, r.scale, 0.001f)
        assertEquals("offset kembali 0", Offset.Zero, r.offset)
    }
}
