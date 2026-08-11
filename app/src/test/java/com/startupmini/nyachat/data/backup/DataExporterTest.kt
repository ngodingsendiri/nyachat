package com.startupmini.nyachat.data.backup

import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test backup JSON (Google Drive): round-trip export → parse harus
 * mengembalikan data yang sama, dan JSON rusak/bukan Nyachat ditolak.
 */
class DataExporterTest {

    private val transactions = listOf(
        FinancialTransaction(
            type = "PENGELUARAN",
            category = "Makanan & Minuman",
            amount = 25000.0,
            description = "beli bakso",
            loggedBy = "Suami",
            timestamp = 1700000000000L,
            chatMessageId = 1L,
            cloudId = "tx-1",
            sourceMessageCloudId = "msg-1"
        ),
        FinancialTransaction(
            type = "PEMASUKAN",
            category = "Gaji & Pemasukan",
            amount = 5000000.0,
            description = "gaji bulanan",
            loggedBy = "Istri",
            timestamp = 1700086400000L,
            cloudId = "tx-2"
        )
    )

    private val messages = listOf(
        ChatMessage(
            id = 1L,
            sender = "Suami",
            messageText = "beli bakso 25000",
            timestamp = 1700000000000L,
            isFinancial = true,
            detectedAmount = 25000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = "PENGELUARAN",
            replyToSender = null,
            cloudId = "msg-1"
        ),
        ChatMessage(
            id = 2L,
            sender = "AI",
            messageText = "Mantap!",
            timestamp = 1700000001000L
        )
    )

    @Test
    fun roundTripBackupJsonMempertahankanData() {
        val json = DataExporter.buildBackupJson(transactions, messages, "2.0.0")
        val parsed = DataExporter.parseBackupJson(json)

        assertNotNull(parsed)
        val backup = parsed!!
        assertEquals(2, backup.transactions.size)
        assertEquals(2, backup.messages.size)

        // Transaksi
        val first = backup.transactions[0]
        assertEquals("PENGELUARAN", first.type)
        assertEquals(25000.0, first.amount, 0.001)
        assertEquals("tx-1", first.cloudId)
        assertEquals(1L, first.chatMessageId)
        // Relasi cross-device (sourceMessageCloudId) harus bertahan di backup.
        assertEquals("msg-1", first.sourceMessageCloudId)

        // Pesan
        val msg = backup.messages[0]
        assertTrue(msg.isFinancial)
        assertEquals(25000.0, msg.detectedAmount!!, 0.001)
        assertEquals("msg-1", msg.cloudId)
    }

    @Test
    fun jsonBukanNyachatDitolak() {
        assertNull(DataExporter.parseBackupJson("""{"app":"OtherApp","messages":[]}"""))
    }

    @Test
    fun backupMarkerLamaMoneyChatTetapDiterima() {
        // Backup yang dibuat sebelum rebrand (marker "MoneyChat") tetap bisa
        // di-restore di Nyachat (migrasi lintas-rebrand).
        val base = DataExporter.buildBackupJson(transactions, messages, "1.0.0")
        val legacy = base.replace("\"app\":\"Nyachat\"", "\"app\":\"MoneyChat\"")
        assertNotNull(DataExporter.parseBackupJson(legacy))
    }

    @Test
    fun jsonRusakDitolak() {
        assertNull(DataExporter.parseBackupJson("this is not valid json {"))
    }

    @Test
    fun jsonKosongDitolak() {
        assertNull(DataExporter.parseBackupJson(""))
    }

    @Test
    fun backupMenyimpanFamilyIdUntukDeteksiLintasWorkspace() {
        val json = DataExporter.buildBackupJson(transactions, messages, "1.4.0", familyId = "12345678")
        val parsed = DataExporter.parseBackupJson(json)

        assertNotNull(parsed)
        assertEquals("12345678", parsed!!.familyId)
    }

    @Test
    fun backupTanpaFamilyIdDianggapNull() {
        val json = DataExporter.buildBackupJson(transactions, messages, "1.4.0")
        assertNull(DataExporter.parseBackupJson(json)!!.familyId)
    }

    @Test
    fun formatBackupMasaDepanDitolak() {
        // Backup dari versi app yang lebih baru (format > 1) harus ditolak,
        // bukan di-parse salah lalu merusak data (P1).
        val base = DataExporter.buildBackupJson(transactions, messages, "99.0.0", "12345678")
        val future = base.replace("\"format\":1", "\"format\":99")
        assertNull(DataExporter.parseBackupJson(future))
    }

    @Test
    fun backupTanpaFieldFormatTetapDiterima() {
        // Backup lama (sebelum field format ada) dianggap format 1.
        val base = DataExporter.buildBackupJson(transactions, messages, "1.0.0")
        val legacy = base.replace("\"format\":1,", "") // hapus field format
        val parsed = DataExporter.parseBackupJson(legacy)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.formatVersion)
    }

    // ---------- Backup terenkripsi (Sprint-2) ----------

    @Test
    fun backupTerenkripsiBisaDiparseDenganPassphraseBenar() {
        val plain = DataExporter.buildBackupJson(transactions, messages, "2.0.0", familyId = "12345678")
        val envelope = BackupCrypto.encryptToEnvelope(plain, "rahasia123", iterations = 1_000)

        val parsed = DataExporter.parseBackupJson(envelope, "rahasia123")
        assertNotNull(parsed)
        assertEquals(2, parsed!!.transactions.size)
        assertEquals("12345678", parsed.familyId)
    }

    @Test
    fun backupTerenkripsiTanpaPassphraseDitolak() {
        val plain = DataExporter.buildBackupJson(transactions, messages, "2.0.0")
        val envelope = BackupCrypto.encryptToEnvelope(plain, "rahasia123", iterations = 1_000)
        // Tanpa passphrase (atau passphrase salah) amplop tidak bisa dibuka.
        assertNull(DataExporter.parseBackupJson(envelope))
        assertNull(DataExporter.parseBackupJson(envelope, "salah"))
    }

    @Test
    fun backupPlaintextTetapBisaDiparseTanpaPassphrase() {
        // Backward-compat: backup lama tanpa amplop tetap jalan di jalur baru.
        val plain = DataExporter.buildBackupJson(transactions, messages, "2.0.0")
        assertNotNull(DataExporter.parseBackupJson(plain, null))
        assertNotNull(DataExporter.parseBackupJson(plain, "passphrase-tak-dipakai"))
    }

    // ---------- CSV (P4-5) ----------

    @Test
    fun csvMengEscapePemisahDanKutipGanda() {
        val tx = FinancialTransaction(
            type = "PENGELUARAN",
            category = "Hiburan & Belanja",
            amount = 1000.0,
            description = "beli \"alat\"; cuci gudang",
            loggedBy = "Suami",
            timestamp = 1L
        )
        val csv = DataExporter.buildRecapCsv(listOf(tx), emptyList())

        // Deskripsi mengandung ; dan " → sel dikutip & kutip ganda di-escape
        // (supaya Excel tidak salah membelah kolom).
        assertTrue(csv.contains("\"beli \"\"alat\"\"; cuci gudang\""))
        // Nominal TIDAK dikutip (biar dibaca angka) dan memakai desimal koma.
        assertTrue(csv.contains("1000,00"))
    }

    @Test
    fun csvTidakMengutipNominalAgarTerbacaAngka() {
        val tx = FinancialTransaction(
            type = "PEMASUKAN",
            category = "Gaji & Pemasukan",
            amount = 1500000.0,
            description = "gaji",
            loggedBy = "Istri",
            timestamp = 1L
        )
        val csv = DataExporter.buildRecapCsv(listOf(tx), emptyList())
        // Nominal ditulis dengan desimal koma TANPA pemisah ribuan dan tanpa
        // kutip di sekelilingnya — supaya Excel/Google Sheets membacanya angka.
        assertTrue(csv.contains("1500000,00"))
        assertFalse(csv.contains("\"1500000,00\""))
    }

    // ---------- Lampiran di backup (P4-5) ----------

    @Test
    fun pesanDenganLampiranFileMasihAdaDipertahankan() {
        val tmp = File.createTempFile("foto_nota", ".jpg")
        try {
            val msg = ChatMessage(
                sender = "Suami",
                messageText = "nota",
                timestamp = 1L,
                imagePath = tmp.absolutePath,
                fileName = "nota.pdf"
            )
            val json = DataExporter.buildBackupJson(emptyList(), listOf(msg), "1.4.0")
            val parsed = DataExporter.parseBackupJson(json)
            assertNotNull(parsed)
            assertEquals(tmp.absolutePath, parsed!!.messages[0].imagePath)
            assertEquals("nota.pdf", parsed.messages[0].fileName)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun pesanDenganLampiranFileHilangDibuang() {
        val msg = ChatMessage(
            sender = "Suami",
            messageText = "nota",
            timestamp = 1L,
            imagePath = "/tidak/ada/foto.jpg"
        )
        val json = DataExporter.buildBackupJson(emptyList(), listOf(msg), "1.4.0")
        val parsed = DataExporter.parseBackupJson(json)
        assertNotNull(parsed)
        // Referensi lampiran yang file-nya sudah hilang dibuang supaya tidak
        // muncul bubble rusak setelah restore.
        assertNull(parsed!!.messages[0].imagePath)
    }
}