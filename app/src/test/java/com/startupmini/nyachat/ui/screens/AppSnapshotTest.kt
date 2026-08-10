package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.SyncStatus
import com.startupmini.nyachat.ui.theme.CategoryColorsLight
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot UI dengan Roborazzi (P3-3) — mendeteksi regresi visual komponen
 * inti lewat render Robolectric (tanpa emulator). Baseline disimpan di
 * `app/src/test/snapshots/`; rekam ulang via
 * `./gradlew :app:recordRoborazziDebug`, verifikasi via
 * `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AppSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CoupleFinanceTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(8.dp)
                ) {
                    content()
                }
            }
        }
        // Path relatif terhadap direktori modul (app/) — baseline berada di
        // app/src/test/snapshots/ supaya ikut ter-commit sebagai golden file.
        composeRule.onRoot().captureRoboImage("src/test/snapshots/screens/$name.png")
    }

    @Test
    fun `chat bubble pengeluaran olehku dengan badge finansial`() {
        val msg = ChatMessage(
            sender = "Suami",
            messageText = "Beli nasi padang 50.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 50_000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = Constants.TransactionTypes.EXPENSE
        )
        capture("chat_bubble_expense") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami")
        }
    }

    @Test
    fun `chat bubble pemasukan pengirim lain dengan header`() {
        val msg = ChatMessage(
            sender = "Istri",
            messageText = "Gaji masuk 5.000.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 5_000_000.0,
            detectedCategory = "Gaji & Pemasukan",
            detectedType = Constants.TransactionTypes.INCOME
        )
        capture("chat_bubble_income_other") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami", showHeader = true)
        }
    }

    @Test
    fun `chat bubble membalas pesan`() {
        val msg = ChatMessage(
            sender = "Istri",
            messageText = "Oke, besok aku transfer ya",
            timestamp = 1_783_800_000_000L,
            replyToSender = "Suami",
            replyToText = "Bayar kontrakan 1.500.000",
            editedAt = 1_783_800_100_000L
        )
        capture("chat_bubble_reply") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami", showHeader = true)
        }
    }

    @Test
    fun `item transaksi rekap pengeluaran dan pemasukan`() {
        val txs = listOf(
            FinancialTransaction(
                type = Constants.TransactionTypes.EXPENSE,
                category = Constants.Categories.FOOD,
                amount = 50_000.0,
                description = "Beli nasi padang",
                loggedBy = "Suami",
                timestamp = 1_783_800_000_000L
            ),
            FinancialTransaction(
                type = Constants.TransactionTypes.INCOME,
                category = Constants.Categories.SALARY,
                amount = 5_000_000.0,
                description = "Gaji bulanan",
                loggedBy = "Istri",
                timestamp = 1_783_800_000_000L
            )
        )
        capture("rekap_transaction_items") {
            Column {
                txs.forEach { tx ->
                    TransactionItemCard(transaction = tx, onDelete = {}, onEdit = {})
                }
            }
        }
    }

    @Test
    fun `banner saldo tersinkron`() {
        capture("balance_banner_synced") {
            BalanceBannerCard(
                totalIncome = 5_000_000.0,
                totalExpense = 1_250_000.0,
                balance = 3_750_000.0,
                syncStatus = SyncStatus.SYNCED
            )
        }
    }

    @Test
    fun `banner saldo defisit dan offline`() {
        capture("balance_banner_offline") {
            BalanceBannerCard(
                totalIncome = 0.0,
                totalExpense = 250_000.0,
                balance = -250_000.0,
                syncStatus = SyncStatus.OFFLINE
            )
        }
    }

    @Test
    fun `donut chart kategori termasuk slice kecil`() {
        // P2-18: slice kecil (<0.5°) tidak boleh merusak posisi slice lain.
        val totals = listOf(
            "Makanan & Minuman" to 300_000.0,
            "Transportasi" to 150_000.0,
            "Tagihan & Utilitas" to 80_000.0,
            "Hiburan & Belanja" to 39_000.0,
            "Lain-lain" to 1_000.0 // ~0.17° — nyaris tak terlihat
        )
        capture("donut_chart") {
            DonutChart(
                categoryTotals = totals,
                totalExpense = 570_000.0,
                colors = CategoryColorsLight
            )
        }
    }

    @Test
    fun `saran cepat quick add`() {
        capture("quick_suggestions") {
            QuickSuggestionRow(
                suggestions = listOf(
                    "Makan siang 25.000",
                    "Bensin 20.000",
                    "Beli token listrik 50.000"
                ),
                onSuggestionClicked = {}
            )
        }
    }

    @Test
    fun `composer chat pill kosong`() {
        // Redesign composer (WhatsApp-style): floating pill + ikon (+) di dalam,
        // tombol Send circular terpisah — tanpa panel besar pembungkus.
        capture("chat_composer_pill") {
            ChatInputBar(
                value = "",
                onValueChange = {},
                isDark = false,
                canSend = false,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() }
            )
        }
    }

    @Test
    fun `composer chat pill terisi`() {
        // State aktif: teks terisi → tombol Send berwarna primary.
        capture("chat_composer_pill_active") {
            ChatInputBar(
                value = "Beli bakso 15.000",
                onValueChange = {},
                isDark = false,
                canSend = true,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() }
            )
        }
    }

    @Test
    fun `composer chat pill dengan reply quote`() {
        // Quote balasan menempel DI DALAM pill (gaya Telegram): garis aksen kiri,
        // nama pengirim tebal, snippet 1 baris, tombol ✕ — tombol Send tetap
        // sejajar dengan baris input.
        capture("chat_composer_reply_quote") {
            ChatInputBar(
                value = "Oke nanti sore",
                onValueChange = {},
                isDark = false,
                canSend = true,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() },
                replyTarget = ChatMessage(
                    sender = "ISTRI",
                    messageText = "Beli bakso 15.000 di pasar sore ini ya"
                ),
                onReplyDismiss = {}
            )
        }
    }
}
