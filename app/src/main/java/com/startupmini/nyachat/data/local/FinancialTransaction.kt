package com.startupmini.nyachat.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Paritas dengan chat_messages: index unik cloudId mencegah duplikasi transaksi
// permanen di lokal (race restore + snapshot listener). Index timestamp menjaga
// performa query Rekap (ORDER BY timestamp DESC).
@Entity(
    tableName = "financial_transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["cloudId"], unique = true),
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
    val category: String, // "Groceries & Sembako", "Makanan & Minuman", "Tagihan & Utilitas", "Kebutuhan Anak", "Transportasi", "Kesehatan & Skincare", "Hiburan & Belanja", "Lain-lain", "Gaji & Pemasukan"
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
