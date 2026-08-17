package com.startupmini.nyachat.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.roundToLong

// Paritas dengan chat_messages: index unik cloudId mencegah duplikasi transaksi
// permanen di lokal (race restore + snapshot listener). Index timestamp menjaga
// performa query Rekap (ORDER BY timestamp DESC).
@Entity(
    tableName = "financial_transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["cloudId"], unique = true),
        // Audit DB r1.6.0: index lookup per-pesan — dipakai tiap edit/hapus pesan
        // (getAllByChatMessageId/deleteByChatMessageId) & rebuild badge; tanpa
        // index = full table scan. Konsisten dengan MIGRATION_13_14 yang membuatnya
        // untuk instalasi lama.
        Index(value = ["chatMessageId"]),
        // Konsisten dengan MIGRATION_8_9 yang membuat index ini saat upgrade:
        // DB fresh (onCreate) & DB hasil migrasi harus punya index yang sama,
        // kalau tidak Room gagal verifikasi schema (M12 migration test).
        Index(value = ["sourceMessageCloudId"])
    ]
)
data class FinancialTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String, // "PENGELUARAN" or "PEMASUKAN"
    val category: String, // lihat Constants.Categories (pengeluaran: Groceries & Sembako, Makanan & Minuman, Tagihan & Utilitas, Kebutuhan Anak, Transportasi, Kesehatan & Skincare, Hiburan & Belanja, Cicilan & Pinjaman, Pendidikan, Sosial & Donasi, Asuransi & Pajak, Lain-lain; pemasukan: Gaji & Pemasukan, Bonus & Komisi, Usaha & Jualan, Investasi & Dividen, Hadiah & Arisan, Cashback & Refund)
    val amount: Double,
    val description: String,
    val loggedBy: String, // "ISTRI", "SUAMI", "AI"
    val timestamp: Long = System.currentTimeMillis(),
    val editedAt: Long? = null, // timestamp terakhir diedit (null = belum pernah); dasar resolusi konflik sync berbasis waktu
    val chatMessageId: Long? = null,
    // M4: timestamp server (FieldValue.serverTimestamp()) — dasar resolusi konflik
    // lintas perangkat yang deterministik (tidak peka selisih jam lokal).
    val serverUpdatedAt: Long? = null,
    val cloudId: String? = null, // ID dokumen Firestore (unik lintas perangkat)
    val sourceMessageCloudId: String? = null // Cloud ID pesan chat asal (untuk cross-device lookup)
)

/**
 * Asuransi presisi uang (audit 2026-08-14): rupiah selalu bilangan bulat, jadi
 * semua nominal di-snap ke rupiah penuh di SETIAP batas persist — hasil parse
 * AI/heuristik (FinanceRepository), parse backup/restore & pending-op
 * (DataExporter.transactionFromJson), dan merge cloud (FirestoreSyncManager
 * .upsertTransaction). Double aman untuk integer < 2^53 (±9 kuadriliun), tapi
 * snap ini mencegah pecahan kecil masuk diam-diam ke DB (mis. AI salah format
 * atau file backup berisi nominal desimal). Murni & deterministik.
 */
internal fun normalizeAmount(amount: Double): Double = amount.roundToLong().toDouble()
