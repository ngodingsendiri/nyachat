package com.startupmini.nyachat.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.BitmapCache
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Uji integrasi gestur di ChatScreen (audit gestur 2026-08-13, permintaan user):
 *  - TAHAN LAMA bubble teks  → DropdownMenu muncul (Balas / Salin / Hapus Pesan).
 *  - TAP bubble teks         → menu TIDAK muncul.
 *  - TAP bubble gambar       → viewer foto terbuka (dialog full-screen).
 *  - TAHAN LAMA bubble gambar → menu muncul, viewer TIDAK terbuka.
 *
 * Berjalan di JVM via Robolectric — siap CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatScreenGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setup() {
        BitmapCache.clearForTest()
        // Bubble men-decode media di 1100; viewer (ImageViewerDialog) di 2200.
        val bmp = Bitmap.createBitmap(80, 110, Bitmap.Config.ARGB_8888)
        BitmapCache.putMediaForTest("test_nota", 1100, bmp)
        BitmapCache.putMediaForTest("test_nota", 2200, bmp)
    }

    private val textMsg = ChatMessage(
        id = 1,
        sender = "Suami",
        messageText = "Beli nasi padang 50.000",
        timestamp = 1_783_800_000_000L,
        isFinancial = true,
        detectedAmount = 50_000.0,
        detectedCategory = "Makanan & Minuman",
        detectedType = Constants.TransactionTypes.EXPENSE
    )

    private val imageMsg = ChatMessage(
        id = 2,
        sender = "Suami",
        messageText = "",
        timestamp = 1_783_800_000_000L,
        imagePath = "test_nota"
    )

    private fun showChat() {
        composeRule.setContent {
            CoupleFinanceTheme {
                ChatScreen(
                    messages = listOf(textMsg, imageMsg),
                    activeSender = "Suami",
                    isAiThinking = false,
                    quickSuggestions = emptyList(),
                    onSendMessage = { _, _, _, _, _, _ -> },
                    onEditMessage = { _, _ -> },
                    onAskAiClicked = {},
                    onDeleteMessage = {}
                )
            }
        }
        composeRule.waitForIdle()
        // Tunggu kedua bubble tampil (decode gambar bisa async).
        var found = false
        repeat(40) {
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithTag("chat_bubble_2")
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                found = true
                return@repeat
            }
            Thread.sleep(100)
        }
        org.junit.Assert.assertTrue("bubble gambar harus tampil", found)
        composeRule.waitForIdle()
    }

    private val menuItems = listOf("Balas", "Salin", "Hapus Pesan")

    @Test
    fun `tahan lama bubble teks memunculkan menu`() {
        showChat()
        // Long-press di TEKS pesan — lihat ChatBubbleGestureTest untuk alasan
        // (pusat bubble pesan pendek berbadge jatuh di badge clickable).
        composeRule.onNodeWithTag("chat_bubble_1").performTouchInput { longClick(Offset(center.x, 12f)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Balas").assertIsDisplayed()
        composeRule.onNodeWithText("Salin").assertIsDisplayed()
        composeRule.onNodeWithText("Hapus Pesan").assertIsDisplayed()
    }

    @Test
    fun `tap bubble teks tidak memunculkan menu`() {
        showChat()
        composeRule.onNodeWithTag("chat_bubble_1").performTouchInput { click() }
        composeRule.waitForIdle()
        menuItems.forEach { item ->
            composeRule.onAllNodesWithText(item).assertCountEquals(0)
        }
    }

    @Test
    fun `tap bubble gambar membuka viewer`() {
        showChat()
        composeRule.onNodeWithTag("chat_bubble_2").performTouchInput { click() }
        composeRule.waitForIdle()
        // Tunggu viewer (Dialog) tampil dengan poll nyata — waitUntil tidak andal
        // memompa komposisi di Robolectric (pola sama dengan showChat).
        var viewerVisible = false
        repeat(40) {
            composeRule.waitForIdle()
            if (composeRule.onAllNodesWithTag("image_viewer_image")
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                viewerVisible = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue("viewer harus terbuka", viewerVisible)
        composeRule.onNodeWithTag("image_viewer_image").assertIsDisplayed()
        // Tidak boleh ada menu bubble.
        composeRule.onAllNodesWithText("Balas").assertCountEquals(0)
    }

    @Test
    fun `tahan lama bubble gambar memunculkan menu bukan viewer`() {
        showChat()
        composeRule.onNodeWithTag("chat_bubble_2").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Balas").assertIsDisplayed()
        composeRule.onAllNodesWithTag("image_viewer_image").assertCountEquals(0)
    }
}
