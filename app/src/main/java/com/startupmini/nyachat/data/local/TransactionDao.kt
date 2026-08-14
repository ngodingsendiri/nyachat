package com.startupmini.nyachat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM financial_transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<FinancialTransaction>>

    // REPLACE (bukan ABORT): dengan index unik cloudId, merge dari snapshot
    // listener yang balapan harus KONVERGEN, bukan crash. Aman karena semua
    // pemanggil upsert resolve baris lewat getByCloudId dulu (id lokal dijaga);
    // restore menimpa tabel kosong, jadi primary key tidak pernah berubah diam-diam.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
