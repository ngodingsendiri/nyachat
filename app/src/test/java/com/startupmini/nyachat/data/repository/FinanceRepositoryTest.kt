package com.startupmini.nyachat.data.repository

import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-5: konsistensi badge finansial pesan ↔ transaksi.
 *
 * Helper murni yang dipakai `FinanceRepository` saat transaksi diedit/dihapus dari
 * layar Rekap — diuji langsung tanpa Room/Firestore supaya regresi mudah terdeteksi:
 * - `rebuildBadge`: recompute badge dari SEMUA transaksi pesan (edit/hapus/restore).
 * - `clearFinancialBadge`: hapus transaksi → badge pesan dicabut (tidak ada badge hantu).
 */
class FinanceRepositoryTest {

    private val message = ChatMessage(
        id = 1L,
        sender = "Suami",
        messageText = "beli kopi 20rb",
        timestamp = 1L
    )

    private val transaction = FinancialTransaction(
        type = Constants.TransactionTypes.EXPENSE,
        category = Constants.Categories.FOOD,
        amount = 20000.0,
        description = "beli kopi 20rb",
        loggedBy = "Suami",
        timestamp = 1L
    )

    @Test
    fun rebuildBadgeMenyinkronkanNilaiTransaksiKePesan() {
        val updated = message.rebuildBadge(listOf(transaction))

        assertTrue(updated.isFinancial)
        assertEquals(20000.0, updated.detectedAmount!!, 0.001)
        assertEquals(Constants.Categories.FOOD, updated.detectedCategory)
        assertEquals(Constants.TransactionTypes.EXPENSE, updated.detectedType)
        assertEquals(1, updated.detectedCount)
        assertFalse(updated.hasMixedTypes!!)
        // Field lain pesan tidak berubah.
        assertEquals("beli kopi 20rb", updated.messageText)
        assertEquals("Suami", updated.sender)
        assertEquals(1L, updated.id)
    }

    @Test
    fun clearFinancialBadgeMencabutStatusKeuanganTapiMenyimpanPesan() {
        val financial = message.copy(
            isFinancial = true,
            detectedAmount = 20000.0,
            detectedCategory = Constants.Categories.FOOD,
            detectedType = Constants.TransactionTypes.EXPENSE
        )
        val cleared = financial.clearFinancialBadge()

        assertFalse(cleared.isFinancial)
        assertNull(cleared.detectedAmount)
        assertNull(cleared.detectedCategory)
        assertNull(cleared.detectedType)
        // Pesan tetap ada — hanya badge yang hilang.
        assertEquals("beli kopi 20rb", cleared.messageText)
        assertEquals(1L, cleared.id)
    }

    @Test
    fun rebuildBadgeDenganDaftarKosongMencabutBadge() {
        val financial = message.copy(
            isFinancial = true,
            detectedAmount = 20000.0,
            detectedCount = 2,
            hasMixedTypes = true
        )
        val cleared = financial.rebuildBadge(emptyList())

        assertFalse(cleared.isFinancial)
        assertNull(cleared.detectedAmount)
        assertNull(cleared.detectedCount)
        assertNull(cleared.hasMixedTypes)
    }

    @Test
    fun badgePemasukanIkutTersinkronDenganTipeDanKategori() {
        val income = transaction.copy(
            type = Constants.TransactionTypes.INCOME,
            category = Constants.Categories.SALARY,
            amount = 5000000.0
        )
        val updated = message.rebuildBadge(listOf(income))

        assertTrue(updated.isFinancial)
        assertEquals(Constants.TransactionTypes.INCOME, updated.detectedType)
        assertEquals(Constants.Categories.SALARY, updated.detectedCategory)
        assertEquals(5000000.0, updated.detectedAmount!!, 0.001)
    }

    // ---- Sprint-3: guard dedup bubble (satu cloudId = satu bubble) ----

    @Test
    fun tanpaDuplikatDaftarTidakBerubah() {
        val a = message.copy(id = 1, cloudId = "c-1")
        val b = message.copy(id = 2, messageText = "kedua", cloudId = "c-2")
        val result = listOf(a, b).dedupeByCloudId()
        assertEquals(listOf(a, b), result)
    }

    @Test
    fun duplikatCloudIdDimenangkanVersiEditTerbaru() {
        val lama = message.copy(id = 1, messageText = "versi lama", timestamp = 100L, cloudId = "c-1")
        val baru = message.copy(
            id = 2, messageText = "versi edit", timestamp = 100L, editedAt = 200L, cloudId = "c-1"
        )
        // Urutan input dibalik — pemenang tetap versi edit, bukan yang datang duluan.
        val result = listOf(lama, baru).dedupeByCloudId()
        assertEquals(1, result.size)
        assertEquals("versi edit", result[0].messageText)
    }

    @Test
    fun duplikatWaktuSeriDimenangkanIdLokalTerbesar() {
        val pertama = message.copy(id = 5, messageText = "pertama", timestamp = 100L, cloudId = "c-1")
        val kedua = message.copy(id = 9, messageText = "kedua", timestamp = 100L, cloudId = "c-1")
        val result = listOf(pertama, kedua).dedupeByCloudId()
        assertEquals(1, result.size)
        assertEquals("kedua", result[0].messageText)
    }

    @Test
    fun pesanTanpaCloudIdSelaluDipertahankan() {
        // Pesan lokal murni (belum tersinkron) tidak boleh ikut ter-dedup.
        val lokal1 = message.copy(id = 1, cloudId = null)
        val lokal2 = message.copy(id = 2, messageText = "sama", timestamp = 1L, cloudId = null)
        val dup = message.copy(id = 3, cloudId = "c-1")
        val dupLagi = message.copy(id = 4, editedAt = 50L, messageText = "pemenang", cloudId = "c-1")
        val result = listOf(lokal1, lokal2, dup, dupLagi).dedupeByCloudId()
        assertEquals(3, result.size)
        assertEquals("pemenang", result[2].messageText)
    }

    @Test
    fun dedupMenjagaUrutanAsliPesan() {
        val a = message.copy(id = 1, messageText = "A", timestamp = 1L, cloudId = "c-1")
        val b = message.copy(id = 2, messageText = "B", timestamp = 2L, cloudId = "c-2")
        val aDup = message.copy(id = 3, messageText = "A-lama", timestamp = 0L, cloudId = "c-1")
        val result = listOf(a, b, aDup).dedupeByCloudId()
        assertEquals(listOf("A", "B"), result.map { it.messageText })
    }

    @Test
    fun duplikatServerUpdatedAtLebihBaruMenangWalauWaktuLokalLebihTua() {
        // Paritas cloudIsNewer (audit r1.6.0): dua baris cloudId sama — yang
        // punya serverUpdatedAt lebih baru menang walau editedAt lokal-nya lebih
        // tua. Sebelumnya dedupe hanya membandingkan waktu efektif sehingga bisa
        // memilih pemenang BERBEDA dari keputusan merge listener.
        val cloudBaru = message.copy(
            id = 2, messageText = "dari cloud", timestamp = 100L, editedAt = 100L,
            serverUpdatedAt = 2000L, cloudId = "c-1"
        )
        val lokalEdit = message.copy(
            id = 1, messageText = "edit lokal", timestamp = 200L, editedAt = 200L,
            serverUpdatedAt = 1000L, cloudId = "c-1"
        )
        // Urutan input dibalik — pemenang tetap ditentukan serverUpdatedAt.
        val result = listOf(lokalEdit, cloudBaru).dedupeByCloudId()
        assertEquals(1, result.size)
        assertEquals("dari cloud", result[0].messageText)
    }

    // ---- Paritas dedupe transaksi: satu cloudId = satu baris di Rekap ----

    @Test
    fun transaksiTanpaDuplikatTidakBerubah() {
        val a = transaction.copy(id = 1, cloudId = "t-1")
        val b = transaction.copy(id = 2, description = "kedua", cloudId = "t-2")
        val result = listOf(a, b).dedupeByCloudId()
        assertEquals(listOf(a, b), result)
    }

    @Test
    fun duplikatTransaksiDimenangkanVersiEditTerbaru() {
        val lama = transaction.copy(id = 1, amount = 10000.0, timestamp = 100L, cloudId = "t-1")
        val baru = transaction.copy(id = 2, amount = 15000.0, timestamp = 100L, editedAt = 200L, cloudId = "t-1")
        val result = listOf(lama, baru).dedupeByCloudId()
        assertEquals(1, result.size)
        assertEquals(15000.0, result[0].amount, 0.001)
    }

    @Test
    fun duplikatTransaksiWaktuSeriDimenangkanIdTerbesar() {
        val pertama = transaction.copy(id = 5, amount = 1000.0, timestamp = 100L, cloudId = "t-1")
        val kedua = transaction.copy(id = 9, amount = 2000.0, timestamp = 100L, cloudId = "t-1")
        val result = listOf(pertama, kedua).dedupeByCloudId()
        assertEquals(1, result.size)
        assertEquals(2000.0, result[0].amount, 0.001)
    }

    @Test
    fun transaksiLokalTanpaCloudIdSelaluDipertahankan() {
        val lokal = transaction.copy(id = 1, cloudId = null)
        val dup = transaction.copy(id = 2, amount = 1000.0, cloudId = "t-1")
        val dupBaru = transaction.copy(id = 3, amount = 9000.0, editedAt = 50L, cloudId = "t-1")
        val result = listOf(lokal, dup, dupBaru).dedupeByCloudId()
        assertEquals(2, result.size)
        assertEquals(9000.0, result[1].amount, 0.001)
    }

    @Test
    fun duplikatTransaksiServerUpdatedAtLebihBaruMenang() {
        // Paritas cloudIsNewer (audit r1.6.0) untuk transaksi Rekap.
        val cloudBaru = transaction.copy(
            id = 2, amount = 9000.0, timestamp = 100L, editedAt = 100L,
            serverUpdatedAt = 2000L, cloudId = "t-1"
        )
        val lokalEdit = transaction.copy(
            id = 1, amount = 15000.0, timestamp = 200L, editedAt = 200L,
            serverUpdatedAt = 1000L, cloudId = "t-1"
        )
        val result = listOf(lokalEdit, cloudBaru).dedupeByCloudId()
        assertEquals(1, result.size)
        assertEquals(9000.0, result[0].amount, 0.001)
    }
}
