package com.startupmini.nyachat.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.BitmapCache
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uji semantik gestur bubble chat (audit gestur 2026-08-13, permintaan user):
 *  - Bubble TEKS  : TAP = tidak ada aksi (bukan menu); TAHAN LAMA = menu (onLongPress).
 *  - Bubble GAMBAR: TAP = buka viewer (onOpenImage); TAHAN LAMA = menu (onLongPress).
 *  - GESER kanan  = balas (onReply).
 *  - Badge finansial tetap bisa di-tap (onOpenTransaction).
 *
 * Berjalan di JVM via Robolectric — siap untuk CI (tanpa emulator).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatBubbleGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var longPressed = false
    private var imageOpened = false
    private var replied = false
    private var txOpened = false

    @Before
    fun setup() {
        BitmapCache.clearForTest()
        longPressed = false
        imageOpened = false
        replied = false
        txOpened = false
    }

    private fun textMessage(id: Long = 1): ChatMessage = ChatMessage(
        id = id,
        sender = "Suami",
        messageText = "Beli nasi padang 50.000",
        timestamp = 1_783_800_000_000L,
        isFinancial = true,
        detectedAmount = 50_000.0,
        detectedCategory = "Makanan & Minuman",
        detectedType = Constants.TransactionTypes.EXPENSE
    )

    private fun imageMessage(id: Long = 2): ChatMessage = ChatMessage(
        id = id,
        sender = "Suami",
        messageText = "",
        timestamp = 1_783_800_000_000L,
        imagePath = "test_nota" // di-seed ke BitmapCache di setup()
    )

    /**
     * [wireOpenImage] = true hanya untuk bubble GAMBAR — persis wiring ChatScreen
     * (`onOpenImage` hanya dikirim saat message.imagePath != null); bubble teks
     * TIDAK punya callback ini sehingga tap-nya tidak melakukan apa pun.
     */
    private fun showBubble(msg: ChatMessage, wireOpenImage: Boolean = false) {
        if (msg.imagePath != null) {
            // Bubble men-decode media di maxDim 1100 — seed key yang sama.
            BitmapCache.putMediaForTest(
                msg.imagePath,
                1100,
                Bitmap.createBitmap(80, 110, Bitmap.Config.ARGB_8888)
            )
        }
        composeRule.setContent {
            CoupleFinanceTheme {
                ChatMessageBubble(
                    message = msg,
                    currentActiveSender = "Suami",
                    onLongPress = { longPressed = true },
                    onOpenImage = if (wireOpenImage) {
                        { imageOpened = true }
                    } else null,
                    onReply = { replied = true },
                    onOpenTransaction = { txOpened = true }
                )
            }
        }
        composeRule.waitForIdle()
        // Tunggu bubble ter-render (untuk pesan gambar: tunggu decode selesai).
        var found = false
        repeat(40) {
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithTag("chat_bubble_${msg.id}")
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                found = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue("bubble harus tampil", found)
        composeRule.waitForIdle()
    }

    private fun bubble(msg: ChatMessage) = composeRule.onNodeWithTag("chat_bubble_${msg.id}")

    @Test
    fun `tap bubble teks tidak membuka menu maupun viewer`() {
        showBubble(textMessage())
        bubble(textMessage()).performTouchInput { click() }
        composeRule.waitForIdle()
        assertFalse("tap tidak boleh memanggil onLongPress", longPressed)
        assertFalse("tap tidak boleh memanggil onOpenImage", imageOpened)
    }

    @Test
    fun `tahan lama bubble teks membuka menu`() {
        showBubble(textMessage())
        // Long-press di TEKS pesan (bukan pusat bubble) — r1.7.0 footer badge
        // + jam menyatu di pojok kanan-bawah; pusat bubble pesan pendek berbadge
        // lebar jatuh tepat di badge clickable (tap badge = buka transaksi,
        // perilaku disengaja). Gestur alami user: tahan lama pada teks pesan.
        bubble(textMessage()).performTouchInput { longClick(Offset(center.x, 12f)) }
        composeRule.waitForIdle()
        assertTrue("tahan lama harus memanggil onLongPress", longPressed)
        assertFalse("tahan lama tidak boleh memanggil onOpenImage", imageOpened)
    }

    @Test
    fun `tap bubble gambar membuka viewer`() {
        showBubble(imageMessage(), wireOpenImage = true)
        bubble(imageMessage()).performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue("tap bubble gambar harus memanggil onOpenImage", imageOpened)
        assertFalse("tap bubble gambar tidak boleh memanggil onLongPress", longPressed)
    }

    @Test
    fun `tahan lama bubble gambar membuka menu bukan viewer`() {
        showBubble(imageMessage())
        bubble(imageMessage()).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTrue("tahan lama harus memanggil onLongPress", longPressed)
        assertFalse("tahan lama tidak boleh memanggil onOpenImage", imageOpened)
    }

    @Test
    fun `geser kanan bubble teks membalas`() {
        showBubble(textMessage())
        bubble(textMessage()).performTouchInput {
            swipe(
                start = Offset(10f, center.y),
                end = Offset(visibleSize.width - 10f, center.y)
            )
        }
        composeRule.waitForIdle()
        assertTrue("geser kanan harus memanggil onReply", replied)
        assertFalse("geser tidak boleh memanggil onLongPress", longPressed)
    }

    @Test
    fun `tap badge finansial tetap membuka transaksi`() {
        showBubble(textMessage())
        composeRule.onNodeWithTag("financial_badge_${textMessage().id}")
            .performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue("tap badge harus memanggil onOpenTransaction", txOpened)
        assertFalse("tap badge tidak boleh memanggil onLongPress", longPressed)
    }
}
