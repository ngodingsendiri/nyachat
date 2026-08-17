package com.startupmini.nyachat.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    /**
     * N pesan TERAKHIR (konteks untuk AI/heuristik) — audit performa 2026-08-12:
     * tidak lagi memuat SELURUH riwayat ke memori hanya untuk takeLast(10).
     * Urutan DESC, pemanggil membalik bila butuh kronologis.
     */
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    // @Upsert (bukan INSERT REPLACE, audit DB r1.6.0): pada konflik index unik
// (cloudId ATAU id), SQLite menjalankan DO UPDATE yang MEMPERTAHANKAN primary
// key baris yang sudah ada. REPLACE lama menghapus baris konflik lalu insert
// baru — kalau entity membawa id eksplisit yang bentrok dengan id baris lain,
// baris itu ikut terhapus diam-diam. @Upsert aman: id lokal tidak pernah
// berganti di luar sepengetahuan pemanggil.
@Upsert
    suspend fun insertMessage(message: ChatMessage): Long

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE cloudId = :cloudId LIMIT 1")
    suspend fun getByCloudId(cloudId: String): ChatMessage?

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM chat_messages WHERE cloudId = :cloudId")
    suspend fun deleteByCloudId(cloudId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()
}
