package com.startupmini.nyachat.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["timestamp"]), Index(value = ["cloudId"], unique = true)]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String, // "ISTRI", "SUAMI", "AI"
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFinancial: Boolean = false,
    val detectedAmount: Double? = null,
    val detectedCategory: String? = null,
    val detectedType: String? = null, // "PENGELUARAN" or "PEMASUKAN"
    // r1.4.0 (audit Finance AI): jumlah transaksi yang direkap dari pesan ini.
    // null/1 = pesan transaksi tunggal (badge menampilkan +/- nominal & kategori);
    // >1 = pesan multi-transaksi (badge menampilkan "N transaksi · total" tanpa
    // tanda — tidak men-netting pemasukan & pengeluaran).
    val detectedCount: Int? = null,
    // r1.4.0 (badge campuran): true jika pesan berisi PEMASUKAN DAN PENGELUARAN
    // sekaligus (detectedCount >= 2 dengan tipe berbeda). Badge menampilkan
    // warna paduan pelangi sebagai penanda campuran; null/false = single-type
    // (badge hijau pemasukan / merah pengeluaran seperti biasa).
    val hasMixedTypes: Boolean? = null,
    val imagePath: String? = null, // path file foto lampiran (nota belanja) di penyimpanan internal
    // r1.6.1 (audit pesan): path file foto di Firebase Storage — ada nilainya
    // untuk pesan yang mengirimkan foto. Di perangkat PENGIRIM selalu null
    // (file lokal via imagePath); di perangkat PENERIMA menjadi acuan unduhan
    // sebelum imagePath diisi dari file hasil download.
    val imageUrl: String? = null,
    val filePath: String? = null, // path file dokumen (PDF/invoice/nota) di penyimpanan internal
    val fileName: String? = null, // nama asli file dokumen untuk ditampilkan di bubble
    val replyToSender: String? = null, // snapshot pengirim pesan yang dibalas (balasan via swipe)
    val replyToText: String? = null, // snapshot isi pesan yang dibalas
    val editedAt: Long? = null, // timestamp terakhir diedit (null = belum pernah diedit)
    // M7: asal deteksi transaksi — "AI" (Gemini/OpenRouter) atau "HEURISTIK"
    // (fallback offline). Tampil sebagai label kecil di badge finansial supaya
    // pengguna tahu nilai itu tidak divalidasi AI (indikator heuristik/offline).
    val detectedBy: String? = null,
    // M4: timestamp SERVER (FieldValue.serverTimestamp()) saat dokumen ini
    // terakhir ditulis ke Firestore — basis resolusi konflik yang imun terhadap
    // selisih jam antar-perangkat (jenis LWW deterministik).
    val serverUpdatedAt: Long? = null,
    val cloudId: String? = null, // ID dokumen Firestore (unik lintas perangkat)
    // r1.6.1 (audit pesan): uid Firebase penulis — FCM self-skip presisi per-uid
    // & binding rules Firestore (anggota tidak bisa menulis atas nama anggota
    // lain). null untuk data lama / pesan hasil restore yang belum di-sync ulang.
    val senderUid: String? = null,
    // Audit data/ (2026-08-14): kolom ini SENGAJA tidak diisi di runtime untuk
    // pesan — cross-device lookup memakai field di TRANSaksi (lihat
    // FinancialTransaction.sourceMessageCloudId). Dipertahankan di schema
    // (migrasi v8→v9 + backup JSON DataExporter) demi kompatibilitas format
    // backup lama; nilainya selalu null. Jangan dihapus tanpa migrasi v12.
    val sourceMessageCloudId: String? = null
)
