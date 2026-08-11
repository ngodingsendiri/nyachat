package com.startupmini.nyachat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatMessage::class, FinancialTransaction::class, PendingOp::class],
    version = 11,
    // Skema diekspor ke app/schemas (room.schemaLocation di build.gradle.kts)
    // supaya sejarah migrasi bisa direview di code review.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun transactionDao(): TransactionDao
    abstract fun pendingOpDao(): PendingOpDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1 -> v2: tambah index timestamp di chat_messages (performa query urut waktu)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
            }
        }

        // v2 -> v3: kolom cloudId (ID dokumen Firestore) untuk sinkronisasi antar perangkat
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN cloudId TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_cloudId ON chat_messages(cloudId)")
                db.execSQL("ALTER TABLE financial_transactions ADD COLUMN cloudId TEXT")
            }
        }

        // v3 -> v4: kolom imagePath (path foto lampiran nota belanja di chat)
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imagePath TEXT")
            }
        }

        // v4 -> v5: balasan (reply), file dokumen (PDF), dan penanda pesan diedit
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN replyToSender TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN replyToText TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN filePath TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN fileName TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN editedAt INTEGER")
            }
        }

        // v5 -> v6: antrian operasi cloud yang belum tersinkron (retry offline)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_ops (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "opType TEXT NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        // v6 -> v7: kolom editedAt di transaksi — resolusi konflik sync
        // last-writer-by-time saat dua perangkat mengedit transaksi yang sama.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE financial_transactions ADD COLUMN editedAt INTEGER")
            }
        }

        // v7 -> v8: index unik cloudId di financial_transactions (paritas dengan
        // chat_messages). Duplikat yang terlanjur ada dibuang dulu — dijaga baris
        // dengan id lokal terbesar per cloudId — supaya CREATE UNIQUE INDEX tidak
        // gagal di perangkat yang sudah punya data duplikat.
        //
        // L1: baris duplikat yang DIHAPUS di-backup dulu ke tabel staging
        // (financial_transactions_duplicates_backup) sebelum di-delete — migrasi
        // ini destruktif & permanen; backup memberi jalur pemulihan manual bila
        // ternyata ada data yang masih dibutuhkan. Tabel staging dibuat satu kali
        // (IF NOT EXISTS) dan tidak dihapus.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS financial_transactions_duplicates_backup (" +
                        "id INTEGER, type TEXT, category TEXT, amount REAL, description TEXT, " +
                        "loggedBy TEXT, timestamp INTEGER, chatMessageId INTEGER, cloudId TEXT, " +
                        "editedAt INTEGER, " +
                        "backedUpAt INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "INSERT INTO financial_transactions_duplicates_backup " +
                        "(id, type, category, amount, description, loggedBy, timestamp, chatMessageId, cloudId, editedAt, backedUpAt) " +
                        "SELECT id, type, category, amount, description, loggedBy, timestamp, chatMessageId, cloudId, editedAt, " +
                        "(strftime('%s','now') * 1000) FROM financial_transactions " +
                        "WHERE cloudId IS NOT NULL AND id NOT IN (" +
                        "SELECT MAX(id) FROM financial_transactions WHERE cloudId IS NOT NULL GROUP BY cloudId)"
                )
                db.execSQL(
                    "DELETE FROM financial_transactions WHERE cloudId IS NOT NULL AND id NOT IN (" +
                        "SELECT MAX(id) FROM financial_transactions WHERE cloudId IS NOT NULL GROUP BY cloudId)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_financial_transactions_cloudId ON financial_transactions(cloudId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_financial_transactions_timestamp ON financial_transactions(timestamp)")
            }
        }

        // v8 -> v9: tambah sourceMessageCloudId untuk cross-device relasi
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE financial_transactions ADD COLUMN sourceMessageCloudId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_financial_transactions_sourceMessageCloudId ON financial_transactions(sourceMessageCloudId)")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sourceMessageCloudId TEXT")
            }
        }

        // v9 -> v10: sinkronkan index lookup sourceMessageCloudId.
        // MIGRATION_8_9 lama membuat index di financial_transactions tapi entity
        // v9 belum mendeklarasikannya — DB fresh (onCreate) & DB hasil migrasi
        // jadi tidak konsisten dan Room gagal verifikasi identitas. Index kini
        // dideklarasikan di @Entity FinancialTransaction; migrasi ini menjamin
        // instalasi lama yang sudah ada di v9 juga punya index yang sama.
        // IF NOT EXISTS → aman untuk DB yang sudah ter-index lewat jalur v8→v9.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_financial_transactions_sourceMessageCloudId ON financial_transactions(sourceMessageCloudId)")
            }
        }

        // v10 -> v11 (M4 + M7):
        // - M4: serverUpdatedAt (Long, millis waktu server Firestore) di pesan &
        //   transaksi → resolusi konflik sync deterministik (imun selisih jam).
        // - M7: detectedBy ("AI"|"HEURISTIK") di pesan → indikator di badge bahwa
        //   deteksi finansial berasal dari AI atau fallback heuristik offline.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN serverUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN detectedBy TEXT")
                db.execSQL("ALTER TABLE financial_transactions ADD COLUMN serverUpdatedAt INTEGER")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "keuangan_pasutri_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
