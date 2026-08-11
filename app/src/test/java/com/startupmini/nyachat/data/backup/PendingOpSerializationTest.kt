package com.startupmini.nyachat.data.backup

import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip serialisasi entitas → JSON → entitas. Dasar dari antrian pending op
 * (retry offline): payload op harus bisa diubah kembali menjadi objek yang sama
 * sebelum dieksekusi ulang ke Firestore.
 */
class PendingOpSerializationTest {

    @Test
    fun `round-trip pesan mempertahankan semua field cloud`() {
        val msg = ChatMessage(
            id = 42L,
            sender = "Suami",
            messageText = "beli bakso 25000",
            timestamp = 1700000000000L,
            isFinancial = true,
            detectedAmount = 25000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = "PENGELUARAN",
            imagePath = "/data/user/0/x/attachments/att_1.jpg",
            replyToSender = "Istri",
            replyToText = "siap",
            editedAt = 1700000001000L,
            cloudId = "msg-1"
        )

        val json = DataExporter.messageToJson(msg).toString()
        val restored = DataExporter.messageFromJson(JSONObject(json))

        assertEquals("msg-1", restored.cloudId)
        assertEquals("Suami", restored.sender)
        assertEquals("beli bakso 25000", restored.messageText)
        assertEquals(1700000000000L, restored.timestamp)
        assertTrue(restored.isFinancial)
        assertEquals(25000.0, restored.detectedAmount!!, 0.001)
        assertEquals("Makanan & Minuman", restored.detectedCategory)
        assertEquals("PENGELUARAN", restored.detectedType)
        assertEquals("Istri", restored.replyToSender)
        assertEquals(1700000001000L, restored.editedAt)
    }

    @Test
    fun `round-trip transaksi mempertahankan semua field`() {
        val tx = FinancialTransaction(
            id = 7L,
            type = "PEMASUKAN",
            category = "Gaji & Pemasukan",
            amount = 5000000.0,
            description = "gaji bulanan",
            loggedBy = "Istri",
            timestamp = 1700086400000L,
            editedAt = 1700086405000L,
            chatMessageId = 42L,
            cloudId = "tx-1",
            sourceMessageCloudId = "msg-1"
        )

        val json = DataExporter.transactionToJson(tx).toString()
        val restored = DataExporter.transactionFromJson(JSONObject(json))

        assertEquals("tx-1", restored.cloudId)
        assertEquals("PEMASUKAN", restored.type)
        assertEquals("Gaji & Pemasukan", restored.category)
        assertEquals(5000000.0, restored.amount, 0.001)
        assertEquals("gaji bulanan", restored.description)
        assertEquals("Istri", restored.loggedBy)
        assertEquals(1700086400000L, restored.timestamp)
        assertEquals(1700086405000L, restored.editedAt)
        assertEquals(42L, restored.chatMessageId)
        // Relasi cross-device harus bertahan lewat pending-op (retry offline).
        assertEquals("msg-1", restored.sourceMessageCloudId)
    }

    @Test
    fun `transaksi lama tanpa editedAt tetap bisa diparse`() {
        // Payload pending-op / backup lama tidak punya field editedAt —
        // harus tetap kompatibel (null).
        val legacy = JSONObject(
            "{\"type\":\"PENGELUARAN\",\"category\":\"Lain-lain\",\"amount\":10000," +
                "\"description\":\"kopi\",\"loggedBy\":\"Suami\",\"timestamp\":1700000000000," +
                "\"cloudId\":\"tx-lama\"}"
        )
        val restored = DataExporter.transactionFromJson(legacy)
        assertEquals("tx-lama", restored.cloudId)
        assertEquals(null, restored.editedAt)
    }

    @Test
    fun `pesan tanpa cloudId menghasilkan JSON tetap bisa di-round-trip`() {
        val msg = ChatMessage(sender = "AI", messageText = "Mantap!", timestamp = 1L)
        val restored = DataExporter.messageFromJson(JSONObject(DataExporter.messageToJson(msg).toString()))
        assertEquals("AI", restored.sender)
        assertEquals(null, restored.cloudId)
        assertEquals(false, restored.isFinancial)
    }
}
