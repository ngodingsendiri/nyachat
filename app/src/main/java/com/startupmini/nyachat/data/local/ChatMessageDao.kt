package com.startupmini.nyachat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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

    // REPLACE (bukan ABORT): dengan index unik cloudId, merge dari snapshot
    // listener yang balapan harus KONVERGEN, bukan crash. Aman karena upsert di
    // FirestoreSyncManager resolve baris lewat getByCloudId dulu (id lokal dijaga)
    // dan sendMessage selalu menyertakan id lokal pada insert kedua.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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
