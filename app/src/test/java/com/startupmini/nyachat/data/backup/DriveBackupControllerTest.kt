package com.startupmini.nyachat.data.backup

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P4.5: alur backup/restore Google Drive diuji dengan DriveBackupApi palsu —
 * tanpa jaringan nyata. Mencakup: backup sukses (upload + prune), konsen OAuth
 * (aksi diulang setelah disetujui), restore lintas-workspace (konfirmasi
 * eksplisit), restore workspace sama (langsung diterapkan), tombol Batal, dan
 * silent backup harian.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DriveBackupControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Fake API Drive — mencatat panggilan & bisa diset untuk menggantung. */
    private class FakeDriveApi : DriveBackupApi {
        var accessTokenResult: BackupResult<String> = BackupResult.Success("token")
        var uploadResult: BackupResult<Unit> = BackupResult.Success(Unit)
        var listResult: BackupResult<List<DriveBackupFile>> = BackupResult.Success(emptyList())
        var downloadResult: BackupResult<String> = BackupResult.Success("""{"app":"Nyachat"}""")
        var uploadCalls = 0
        var pruneCalls = 0
        var downloadCalls = 0
        var lastToken: String? = null
        var lastFileName: String? = null
        var lastJson: String? = null
        /** true → uploadBackup menggantung sampai coroutine dibatalkan (tes Batal). */
        var hangUpload = false

        override suspend fun getAccessToken(context: Context, email: String): BackupResult<String> =
            accessTokenResult

        override suspend fun uploadBackup(
            context: Context,
            token: String,
            fileName: String,
            json: String
        ): BackupResult<Unit> {
            uploadCalls++
            lastToken = token
            lastFileName = fileName
            lastJson = json
            if (hangUpload) awaitCancellation()
            return uploadResult
        }

        override suspend fun listBackups(
            context: Context,
            token: String
        ): BackupResult<List<DriveBackupFile>> = listResult

        override suspend fun downloadBackup(
            context: Context,
            token: String,
            fileId: String
        ): BackupResult<String> {
            downloadCalls++
            return downloadResult
        }

        override suspend fun pruneOldBackups(
            context: Context,
            token: String,
            keep: Int
        ): BackupResult<Unit> {
            pruneCalls++
            return BackupResult.Success(Unit)
        }
    }

    private fun newController(api: FakeDriveApi, scope: kotlinx.coroutines.CoroutineScope): DriveBackupController {
        // UnconfinedTestDispatcher: operasi controller (yang aslinya berjalan di
        // Dispatchers.IO) dieksekusi langsung di scheduler test tanpa kabur ke
        // thread lain — advanceUntilIdle tetap deterministik.
        val c = DriveBackupController(scope, context, api, UnconfinedTestDispatcher())
        c.currentEmail = { "test@example.com" }
        c.getWorkspacePin = { "11111111" }
        c.buildBackupJson = { """{"app":"Nyachat"}""" }
        c.parseRestore = { _, _ -> null }
        c.restoreParsedBackup = { true }
        c.getEncryptionEnabled = { false }
        c.onSuccessfulBackup = { }
        return c
    }

    @Test
    fun backupBerhasilMenguploadDanPrune() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)

        controller.startBackup()
        advanceUntilIdle()

        assertEquals(1, api.uploadCalls)
        assertEquals(1, api.pruneCalls)
        assertEquals("token", api.lastToken)
        assertTrue(api.lastFileName!!.startsWith("Nyachat-backup-"))
        // Backup plain → nama tanpa penanda .enc.json (tidak ada badge 🔒).
        assertFalse(api.lastFileName!!.endsWith(DriveBackupManager.ENCRYPTED_NAME_SUFFIX))
        assertFalse(controller.busy.value)
        assertTrue(controller.message.value!!.contains("Backup berhasil"))
    }

    @Test
    fun konsenOAuthMemunculkanIntentDanAksiDiulangSetelahDisetujui() = runTest {
        val api = FakeDriveApi()
        api.accessTokenResult = BackupResult.ConsentRequired(Intent(Intent.ACTION_VIEW))
        val controller = newController(api, this)

        controller.startBackup()
        advanceUntilIdle()

        // Modal konsen muncul, belum ada upload.
        assertNotNull(controller.consentIntent.value)
        assertEquals(0, api.uploadCalls)

        // User menyetujui → aksi (startBackup) diulang otomatis.
        api.accessTokenResult = BackupResult.Success("token")
        controller.onConsentResult(true)
        advanceUntilIdle()

        assertEquals(1, api.uploadCalls)
        assertEquals("token", api.lastToken)
        assertTrue(controller.message.value!!.contains("Backup berhasil"))
    }

    @Test
    fun konsenDibatalkanTidakMengulangAksi() = runTest {
        val api = FakeDriveApi()
        api.accessTokenResult = BackupResult.ConsentRequired(Intent(Intent.ACTION_VIEW))
        val controller = newController(api, this)

        controller.startBackup()
        advanceUntilIdle()
        controller.onConsentResult(false)
        advanceUntilIdle()

        assertEquals(0, api.uploadCalls)
        assertTrue(controller.message.value!!.contains("dibatalkan"))
    }

    @Test
    fun restoreWorkspaceSamaLangsungDiterapkan() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        api.downloadResult = BackupResult.Success("""{"app":"Nyachat","format":1}""")
        var applied = false
        controller.parseRestore = { _, _ -> BackupData(emptyList(), emptyList(), familyId = "11111111") }
        controller.restoreParsedBackup = { applied = true; true }

        controller.confirmRestore(DriveBackupFile("id1", "backup.json", "2026-01-01"))
        advanceUntilIdle()

        assertTrue(applied)
        assertTrue(controller.message.value!!.contains("Backup berhasil dipulihkan"))
        assertFalse(controller.busy.value)
    }

    @Test
    fun restoreLintasWorkspaceButuhKonfirmasiEksplisit() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        api.downloadResult = BackupResult.Success("""{"app":"Nyachat","format":1}""")
        controller.parseRestore = { _, _ -> BackupData(emptyList(), emptyList(), familyId = "99999999") }

        controller.confirmRestore(DriveBackupFile("id1", "backup.json", "2026-01-01"))
        advanceUntilIdle()

        // Backup milik workspace lain → dialog konfirmasi tampil, restore belum jalan.
        assertNotNull(controller.crossFamilyRestore.value)
        assertEquals("99999999", controller.crossFamilyRestore.value!!.familyId)
        assertNull(controller.backups.value)
        assertNull(controller.restoreTarget.value)
        assertFalse(controller.busy.value)

        // Konfirmasi → restore diterapkan.
        controller.proceedCrossFamilyRestore()
        advanceUntilIdle()
        assertNull(controller.crossFamilyRestore.value)
        assertTrue(controller.message.value!!.contains("Backup berhasil dipulihkan"))
    }

    @Test
    fun batalMenghentikanOperasiDanMenutupModal() = runTest {
        val api = FakeDriveApi()
        api.hangUpload = true
        val controller = newController(api, this)

        controller.startBackup()
        advanceUntilIdle()

        assertTrue(controller.busy.value) // upload menggantung → modal tampil
        controller.cancelActiveOperation()
        advanceUntilIdle()

        assertFalse(controller.busy.value)
        assertNull(controller.message.value)
        assertNull(controller.backups.value)
    }

    @Test
    fun silentBackupMengembalikanTrueDanMenandaiSukses() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        var successStamped = false
        var lastBackupEncryptedFlag: Boolean? = null
        controller.onSuccessfulBackup = { encrypted ->
            successStamped = true
            lastBackupEncryptedFlag = encrypted
        }

        assertTrue(controller.silentBackup())
        assertEquals(1, api.uploadCalls)
        assertEquals(1, api.pruneCalls)
        assertTrue(successStamped)
        // Backup plain → flag enkripsi file AKTUAL = false (bukan setting toggle).
        assertEquals(false, lastBackupEncryptedFlag)
    }

    @Test
    fun silentBackupTanpaAkunTidakMelakukanApaApa() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        controller.currentEmail = { null }

        assertFalse(controller.silentBackup())
        assertEquals(0, api.uploadCalls)
    }

    // ---- Sprint-2: backup terenkripsi ----

    @Test
    fun backupTerenkripsiMintaPassphraseDuluLaluUploadAmplop() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        controller.getEncryptionEnabled = { true }

        controller.startBackup()
        advanceUntilIdle()

        // Belum ada upload — menunggu passphrase.
        assertTrue(controller.passphrasePrompt.value is DriveBackupController.PassphrasePrompt.Backup)
        assertEquals(0, api.uploadCalls)

        controller.submitPassphrase("rahasia123")
        advanceUntilIdle()

        assertEquals(1, api.uploadCalls)
        // Yang di-upload amplop terenkripsi, BUKAN JSON plaintext.
        assertTrue(BackupCrypto.isEncryptedEnvelope(api.lastJson!!))
        assertFalse(api.lastJson!!.contains("\"app\":\"Nyachat\",\"format\""))
        // File backup terenkripsi diberi penanda .enc.json → badge 🔒 di picker.
        assertTrue(api.lastFileName!!.endsWith(DriveBackupManager.ENCRYPTED_NAME_SUFFIX))
        // Isi amplop bisa dibuka kembali dengan passphrase yang sama.
        assertEquals(
            """{"app":"Nyachat"}""",
            BackupCrypto.decryptEnvelope(api.lastJson!!, "rahasia123")
        )
        assertTrue(controller.message.value!!.contains("Backup berhasil"))
    }

    @Test
    fun batalPassphraseMembatalkanBackup() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        controller.getEncryptionEnabled = { true }

        controller.startBackup()
        controller.cancelPassphrase()
        advanceUntilIdle()

        assertNull(controller.passphrasePrompt.value)
        assertEquals(0, api.uploadCalls)
    }

    @Test
    fun silentBackupTerenkripsiDipakaiAutoPassphrase() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        controller.getEncryptionEnabled = { true }
        // M5: auto-passphrase dari Keystore dipakai — auto-backup TETAP jalan
        // walau enkripsi aktif (sebelumnya dilewati → backup 24 jam hilang).
        controller.getAutoPassphrase = { "auto-passphrase-keystore" }
        controller.buildBackupJson = { """{"app":"Nyachat","format":1}""" }
        var lastBackupEncryptedFlag: Boolean? = null
        controller.onSuccessfulBackup = { encrypted -> lastBackupEncryptedFlag = encrypted }

        assertTrue(controller.silentBackup())
        assertEquals(1, api.uploadCalls)
        // Isi yang diupload harus berupa envelope terenkripsi (bukan plaintext).
        assertTrue(api.lastJson != null && BackupCrypto.isEncryptedEnvelope(api.lastJson!!))
        // File backup terenkripsi → flag enkripsi AKTUAL = true + penanda nama.
        assertEquals(true, lastBackupEncryptedFlag)
        assertTrue(api.lastFileName!!.endsWith(DriveBackupManager.ENCRYPTED_NAME_SUFFIX))
    }

    @Test
    fun silentBackupTanpaAutoPassphraseDilewati() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        controller.getEncryptionEnabled = { true }
        // Tidak ada auto-passphrase (SecureStorage gagal) → tidak ada backup.
        controller.getAutoPassphrase = { null }

        assertFalse(controller.silentBackup())
        assertEquals(0, api.uploadCalls)
    }

    // ---- Temuan #4: badge 🔒 di picker restore ----

    @Test
    fun parseEncryptionStatusMenanganiPenandaNamaDanAppProperties() {
        // Penanda nama `.enc.json` → true walau tanpa appProperties.
        assertEquals(true, parseEncryptionStatus("Nyachat-backup-20260809-184131.enc.json", null))
        // appProperties eksplisit (semua backup baru): true / false.
        assertEquals(
            true,
            parseEncryptionStatus("Nyachat-backup-20260809-184131.json", JSONObject().put("encrypted", "true"))
        )
        assertEquals(
            false,
            parseEncryptionStatus("Nyachat-backup-20260809-183501.json", JSONObject().put("encrypted", "false"))
        )
        // Backup lama: tanpa penanda & tanpa appProperties → null (perlu probe).
        assertNull(parseEncryptionStatus("Nyachat-backup-20260809-183501.json", null))
        // appProperties tanpa key encrypted → null.
        assertNull(parseEncryptionStatus("backup.json", JSONObject()))
    }

    @Test
    fun restorePickerMenandaiFileTerenkripsiDariPenandaNama() = runTest {
        val api = FakeDriveApi()
        api.listResult = BackupResult.Success(
            listOf(
                // Status sudah diketahui dari metadata Drive (manager sudah
                // mem-parsing appProperties/penanda nama) → tanpa unduh isi.
                DriveBackupFile("id1", "Nyachat-backup-20260809-184131.enc.json", "2026-08-09T18:41:31.000Z", encrypted = true),
                DriveBackupFile("id2", "Nyachat-backup-20260809-183501.json", "2026-08-09T18:35:01.000Z", encrypted = false)
            )
        )
        val controller = newController(api, this)

        controller.startRestore()
        advanceUntilIdle()

        val files = controller.backups.value!!
        // Backup baru: badge 🔒 tanpa perlu unduh isi — termasuk yang plain
        // (statusnya sudah diketahui dari appProperties).
        assertTrue(files[0].encrypted == true)
        assertFalse(files[1].encrypted == true)
        assertEquals(0, api.downloadCalls)
    }

    @Test
    fun restorePickerProbeFileLamaTerenkripsiUntukBadge() = runTest {
        val api = FakeDriveApi()
        api.listResult = BackupResult.Success(
            listOf(
                // Backup lama: tanpa metadata Drive → encrypted = null.
                DriveBackupFile("id1", "Nyachat-backup-20260809-184131.json", "2026-08-09T18:41:31.000Z")
            )
        )
        // Isinya amplop terenkripsi → probe saat listing mendeteksi & badge 🔒.
        api.downloadResult = BackupResult.Success(
            BackupCrypto.encryptToEnvelope("""{"app":"Nyachat"}""", "rahasia123", iterations = 1_000)
        )
        val controller = newController(api, this)

        controller.startRestore()
        advanceUntilIdle()

        assertTrue(controller.backups.value!!.first().encrypted == true)
        assertEquals(1, api.downloadCalls)
    }

    @Test
    fun restorePickerProbeFileLamaPlainTetapTanpaBadge() = runTest {
        val api = FakeDriveApi()
        api.listResult = BackupResult.Success(
            listOf(
                DriveBackupFile("id1", "Nyachat-backup-20260809-183501.json", "2026-08-09T18:35:01.000Z")
            )
        )
        api.downloadResult = BackupResult.Success("""{"app":"Nyachat","format":1}""")
        val controller = newController(api, this)

        controller.startRestore()
        advanceUntilIdle()

        // Isi plain → probe selesai, hasil disimpan false → tanpa badge 🔒.
        assertFalse(controller.backups.value!!.first().encrypted == true)
        assertEquals(1, api.downloadCalls)
    }

    @Test
    fun restoreBackupTerenkripsiMintaPassphraseDanTolakPassphraseSalah() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        val envelope = BackupCrypto.encryptToEnvelope(
            """{"app":"Nyachat","format":1}""", "benar123", iterations = 1_000
        )
        api.downloadResult = BackupResult.Success(envelope)
        controller.parseRestore = { _, passphrase ->
            if (passphrase == "benar123") BackupData(emptyList(), emptyList(), familyId = "11111111")
            else null
        }

        controller.confirmRestore(DriveBackupFile("id1", "backup.json", "2026-01-01"))
        advanceUntilIdle()

        // Prompt passphrase muncul, restore belum jalan.
        assertTrue(controller.passphrasePrompt.value is DriveBackupController.PassphrasePrompt.Restore)

        // Passphrase salah → error lewat saluran SNACKBAR khusus (passphraseError),
        // modal progres sudah ditutup (busy=false) & tidak diterapkan.
        var applied = false
        controller.restoreParsedBackup = { applied = true; true }
        controller.submitPassphrase("salah999")
        advanceUntilIdle()
        assertFalse(applied)
        assertFalse(controller.busy.value)
        assertTrue(controller.passphraseError.value!!.contains("Passphrase salah"))
        assertNull(controller.message.value)

        // dismiss → saluran bersih (snackbar sudah tampil).
        controller.dismissPassphraseError()
        assertNull(controller.passphraseError.value)

        // Batal juga membersihkan saluran error: jalankan ulang alur restore
        // (download → prompt passphrase) lalu submit salah & batalkan.
        controller.confirmRestore(DriveBackupFile("id1", "backup.json", "2026-01-01"))
        advanceUntilIdle()
        controller.submitPassphrase("salah999")
        advanceUntilIdle()
        assertNotNull(controller.passphraseError.value)
        controller.cancelActiveOperation()
        assertNull(controller.passphraseError.value)
    }

    @Test
    fun restoreBackupTerenkripsiDenganPassphraseBenarDiterapkan() = runTest {
        val api = FakeDriveApi()
        val controller = newController(api, this)
        val envelope = BackupCrypto.encryptToEnvelope(
            """{"app":"Nyachat","format":1}""", "benar123", iterations = 1_000
        )
        api.downloadResult = BackupResult.Success(envelope)
        var receivedPassphrase: String? = null
        controller.parseRestore = { _, passphrase ->
            receivedPassphrase = passphrase
            BackupData(emptyList(), emptyList(), familyId = "11111111")
        }
        var applied = false
        controller.restoreParsedBackup = { applied = true; true }

        controller.confirmRestore(DriveBackupFile("id1", "backup.json", "2026-01-01"))
        advanceUntilIdle()
        controller.submitPassphrase("benar123")
        advanceUntilIdle()

        assertEquals("benar123", receivedPassphrase)
        assertTrue(applied)
        assertTrue(controller.message.value!!.contains("Backup berhasil dipulihkan"))
    }
}
