package com.startupmini.nyachat.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Indikator "AI memproses" (r1.4.0 — permintaan user): tiga titik kecil yang
 * MENYATU di footer bubble pesan milik user yang sedang diproses AI —
 * menggantikan bubble "AI sedang memproses..." yang terpisah di sisi chat.
 *
 * - isAiThinking=true  → titik (label a11y "AI sedang memproses...") tampil
 *   di bubble pesan TERAKHIR milik activeSender.
 * - Pesan anggota lain yang tiba belakangan TIDAK diindikasi (aman lintas
 *   perangkat) — total indikator tetap satu.
 * - isAiThinking=false → tidak ada titik sama sekali.
 *
 * Berjalan di JVM via Robolectric — siap CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AiProcessingIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun myMsg(id: Long, text: String = "Beli nasi padang 50.000") = ChatMessage(
        id = id,
        sender = "Suami",
        messageText = text,
        timestamp = 1_783_800_000_000L
    )

    private fun otherMsg(id: Long, text: String = "Pesan dari istri") = ChatMessage(
        id = id,
        sender = "Istri",
        messageText = text,
        timestamp = 1_783_800_000_000L
    )

    // Sinkron dengan strings.xml chat_ai_thinking (audit clean-code 2026-08-14:
    // ellipsis unicode — jangan ubah salah satu tanpa mengubah yang lain).
    private val thinkingLabel = "AI sedang memproses…"

    private fun showChat(messages: List<ChatMessage>, isAiThinking: Boolean) {
        composeRule.setContent {
            CoupleFinanceTheme {
                ChatScreen(
                    messages = messages,
                    activeSender = "Suami",
                    isAiThinking = isAiThinking,
                    quickSuggestions = emptyList(),
                    onSendMessage = { _, _, _, _, _, _ -> },
                    onEditMessage = { _, _ -> },
                    onAskAiClicked = {},
                    onDeleteMessage = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Bubble pesan bersifat clickable (combinedClickable) → Compose MENGGABUNGKAN
     * semantics keturunan (termasuk label a11y titik) ke node bubble itu sendiri.
     * Helper ini membaca label yang ter-merge dari node bubble bertag [tag].
     */
    private fun bubbleHasThinkingLabel(tag: String): Boolean {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it == thinkingLabel } == true
    }

    @Test
    fun `titik memproses tampil di bubble pesan user terakhir saat isAiThinking`() {
        showChat(listOf(myMsg(1)), isAiThinking = true)
        composeRule.onNodeWithTag("chat_bubble_1").assertIsDisplayed()
        assertTrue("bubble pesan user membawa label memproses", bubbleHasThinkingLabel("chat_bubble_1"))
    }

    @Test
    fun `titik hanya di bubble milik user - bukan pesan anggota lain yang datang belakangan`() {
        // Pesan "Istri" tiba SETELAH pesan "Suami" — indikator tetap di bubble
        // Suami (pesan yang sedang diproses), bukan di pesan Istri.
        showChat(listOf(myMsg(1), otherMsg(2)), isAiThinking = true)
        assertTrue("bubble Suami membawa label", bubbleHasThinkingLabel("chat_bubble_1"))
        assertFalse("bubble Istri TIDAK membawa label", bubbleHasThinkingLabel("chat_bubble_2"))
    }

    @Test
    fun `tanpa isAiThinking tidak ada titik sama sekali`() {
        showChat(listOf(myMsg(1)), isAiThinking = false)
        assertFalse("indikator tidak boleh muncul", bubbleHasThinkingLabel("chat_bubble_1"))
    }
}
