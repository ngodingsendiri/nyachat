package com.startupmini.nyachat.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M12 — migrasi Room diuji dengan skema historis yang diekspor (`app/schemas`).
 *
 * Setiap `MIGRATION_x_y` harus membawa database dari skema lama bertemu skema
 * baru tanpa kehilangan data (audit: migrasi yang salah baru terdeteksi di
 * produksi karena tidak ada migration test). Test ini memakai
 * `MigrationTestHelper` + skema JSON historis untuk memverifikasi jalur
 * v8→v9 (kolom `sourceMessageCloudId` di financial_transactions & chat_messages).
 *
 * Skema historis dibaca dari aset test — lihat `sourceSets.test.assets` di
 * `app/build.gradle.kts` yang memetakan direktori `app/schemas` (nama folder =
 * nama kelas database, dipakai MigrationTestHelper sebagai lokasi file JSON).
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    companion object {
        private const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * v8→v9→v10: kolom sourceMessageCloudId (transaksi + chat) & index
     * lookup financial_transactions(sourceMessageCloudId) harus ada, dan data
     * lama tidak boleh hilang.
     *
     * Jalur v8→v10 meniru upgrade riil: schema v9 asli (committed) TIDAK
     * mendeklarasikan index sourceMessageColumnId, padahal MIGRATION_8_9
     * membuatnya — inkonsistensi yang membuat Room gagal verifikasi identitas
     * untuk DB v9 yang sudah ter-install. MIGRATION_9_10 menyinkronkannya
     * (IF NOT EXISTS → aman untuk DB yang sudah ber-index & yang belum).
     */
    @Test
    fun migrate8To10_addsSourceMessageColumnsAndIndex_keepsData() {
        // 1. Buat database versi 8 sesuai skema historis (8.json).
        helper.createDatabase(TEST_DB, 8).apply {
            // Masukkan data nyata supaya terverifikasi tidak hilang setelah migrasi.
            execSQL(
                "INSERT INTO chat_messages (id, sender, messageText, timestamp, isFinancial) " +
                    "VALUES (1, 'Suami', 'Beli bensin 50.000', 1752000000000, 1)"
            )
            execSQL(
                "INSERT INTO financial_transactions " +
                    "(id, type, category, amount, description, loggedBy, timestamp, chatMessageId, cloudId) " +
                    "VALUES (1, 'EXPENSE', 'Transportasi', 50000.0, 'Beli bensin', 'Suami', " +
                    "1752000000000, 1, 'tx-cloud-1')"
            )
            close()
        }

        // 2. Jalankan migrasi v8→v9→v10 & validasi skema FINAL sesuai 10.json.
        helper.runMigrationsAndValidate(
            TEST_DB, 10, true, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10
        ).use { db ->
            // 3. Data lama tetap ada.
            val cs = db.query("SELECT messageText FROM chat_messages WHERE id = 1")
            assertTrue(cs.moveToFirst())
            assertEquals("Beli bensin 50.000", cs.getString(0))
            cs.close()

            // 4. Kolom & index baru muncul (dipakai lookup cross-device).
            val msgCols = db.query("PRAGMA table_info(chat_messages)")
            var hasSourceCol = false
            while (msgCols.moveToNext()) {
                if (msgCols.getString(1) == "sourceMessageCloudId") hasSourceCol = true
            }
            msgCols.close()
            assertTrue("chat_messages.sourceMessageCloudId hilang setelah migrasi", hasSourceCol)

            val txCols = db.query("PRAGMA table_info(financial_transactions)")
            var hasTxSourceCol = false
            while (txCols.moveToNext()) {
                if (txCols.getString(1) == "sourceMessageCloudId") hasTxSourceCol = true
            }
            txCols.close()
            assertTrue("financial_transactions.sourceMessageCloudId hilang setelah migrasi", hasTxSourceCol)

            // Index lookup sourceMessageCloudId harus ada (lihat FinancialTransaction @Entity).
            val idx = db.query("PRAGMA index_list(financial_transactions)")
            var hasSourceIdx = false
            while (idx.moveToNext()) {
                if (idx.getString(1) == "index_financial_transactions_sourceMessageCloudId") hasSourceIdx = true
            }
            idx.close()
            assertTrue("index financial_transactions(sourceMessageCloudId) hilang", hasSourceIdx)
        }
    }

    /** v9 (committed, tanpa deklarasi index) → v10: index ditambahkan via MIGRATION_9_10. */
    @Test
    fun migrate9To10_addsMissingSourceIndex() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                "INSERT INTO chat_messages (id, sender, messageText, timestamp, isFinancial) " +
                    "VALUES (1, 'Suami', 'Beli bensin 50.000', 1752000000000, 1)"
            )
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 10, true, AppDatabase.MIGRATION_9_10
        ).use { db ->
            val idx = db.query("PRAGMA index_list(financial_transactions)")
            var hasSourceIdx = false
            while (idx.moveToNext()) {
                if (idx.getString(1) == "index_financial_transactions_sourceMessageCloudId") hasSourceIdx = true
            }
            idx.close()
            assertTrue("index financial_transactions(sourceMessageCloudId) tidak ditambahkan v9→v10", hasSourceIdx)
        }
    }

    /**
     * v10→v11 (M4+M7): kolom serverUpdatedAt (chat + transaksi) & detectedBy
     * (chat) harus ada, nilai lama tidak hilang, dan kolom baru bernilai NULL
     * untuk data lama (default).
     */
    @Test
    fun migrate10To11_addsServerTimestampAndDetectionSource() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                "INSERT INTO chat_messages (id, sender, messageText, timestamp, isFinancial) " +
                    "VALUES (1, 'Suami', 'Beli bensin 50.000', 1752000000000, 1)"
            )
            execSQL(
                "INSERT INTO financial_transactions " +
                    "(id, type, category, amount, description, loggedBy, timestamp, chatMessageId, cloudId) " +
                    "VALUES (1, 'EXPENSE', 'Transportasi', 50000.0, 'Beli bensin', 'Suami', " +
                    "1752000000000, 1, 'tx-cloud-1')"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB, 11, true, AppDatabase.MIGRATION_10_11
        ).use { db ->
            // Kolom baru ada & data lama tetap.
            val cols = db.query("PRAGMA table_info(chat_messages)")
            val msgColNames = mutableSetOf<String>()
            while (cols.moveToNext()) { msgColNames.add(cols.getString(1)) }
            cols.close()
            assertTrue("chat_messages.serverUpdatedAt hilang", msgColNames.contains("serverUpdatedAt"))
            assertTrue("chat_messages.detectedBy hilang", msgColNames.contains("detectedBy"))

            val txCols = db.query("PRAGMA table_info(financial_transactions)")
            val txColNames = mutableSetOf<String>()
            while (txCols.moveToNext()) { txColNames.add(txCols.getString(1)) }
            txCols.close()
            assertTrue("financial_transactions.serverUpdatedAt hilang", txColNames.contains("serverUpdatedAt"))

            val rows = db.query("SELECT messageText, serverUpdatedAt, detectedBy FROM chat_messages WHERE id = 1")
            assertTrue(rows.moveToFirst())
            assertEquals("Beli bensin 50.000", rows.getString(0))
            assertTrue("serverUpdatedAt lama harus NULL", rows.isNull(1))
            assertTrue("detectedBy lama harus NULL", rows.isNull(2))
            rows.close()
        }
    }
}