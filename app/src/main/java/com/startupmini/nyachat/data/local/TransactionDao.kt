package com.startupmini.nyachat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM financial_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<FinancialTransaction>>

    // @Upsert (bukan INSERT REPLACE, audit DB r1.6.0): sama dengan chat_messages —
// konflik index unik (cloudId ATAU id) menghasilkan DO UPDATE yang menjaga
// primary key baris lama; REPLACE bisa menghapus baris lain diam-diam bila id
// eksplisit bentrok. Semua pemanggil upsert resolve baris lewat getByCloudId
// dulu (id lokal dijaga); @Upsert menjadikan id LOKAL tidak pernah berubah
// bahkan bila jalur itu terlewat.
    @Upsert
    suspend fun insertTransaction(transaction: FinancialTransaction): Long

    @Update
    suspend fun updateTransaction(transaction: FinancialTransaction)

    @Delete
    suspend fun deleteTransaction(transaction: FinancialTransaction)

    @Query("SELECT * FROM financial_transactions WHERE cloudId = :cloudId LIMIT 1")
    suspend fun getByCloudId(cloudId: String): FinancialTransaction?

    // r1.2.4 (tuning AI): SATU pesan bisa memuat BANYAK transaksi (multi-transaksi)
    // — dibutuhkan untuk rebuild transaksi saat pesan diedit.
    @Query("SELECT * FROM financial_transactions WHERE chatMessageId = :chatMessageId")
    suspend fun getAllByChatMessageId(chatMessageId: Long): List<FinancialTransaction>

    @Query("DELETE FROM financial_transactions WHERE cloudId = :cloudId")
    suspend fun deleteByCloudId(cloudId: String)

    // r1.2.4: hapus semua transaksi milik satu pesan (rebuild saat edit).
    @Query("DELETE FROM financial_transactions WHERE chatMessageId = :chatMessageId")
    suspend fun deleteByChatMessageId(chatMessageId: Long)

    @Query("DELETE FROM financial_transactions")
    suspend fun deleteAllTransactions()
}
