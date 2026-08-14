package com.startupmini.nyachat.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regresi bug pembersihan lampiran (audit remote/ 2026-08-13): lampiran kini
 * di-namespace per workspace (`attachments/<pin>/...`, M9), tapi
 * `deleteAllAttachments` lama hanya mengiterasi level ATAS — `File.delete()`
 * pada direktori non-kosong gagal diam-diam, jadi clear data / logout
 * MENINGGALKAN semua foto workspace lama di disk (storage leak + privasi).
 *
 * Kini rekursif (`deleteRecursively`) — test ini mengunci perilaku itu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageFileUtilAttachmentCleanupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deleteAllAttachmentsMenghapusFileDiDalamFolderPerWorkspace() {
        // Struktur nyata: lampiran per-workspace + file longgar level atas (jalur lama).
        val wsDir = File(context.filesDir, "attachments/pin_123").apply { mkdirs() }
        File(wsDir, "att_1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(wsDir, "doc_2.pdf").writeBytes(byteArrayOf(4, 5))
        val legacy = File(context.filesDir, "attachments/att_legacy.jpg")
        legacy.writeBytes(byteArrayOf(7))

        ImageFileUtil.deleteAllAttachments(context)

        assertFalse("folder workspace harus terhapus", wsDir.exists())
        assertFalse("file longgar level atas harus terhapus", legacy.exists())
        val attachmentsRoot = File(context.filesDir, "attachments")
        assertTrue("folder akar tetap ada (dibuat ulang saat simpan berikutnya)", attachmentsRoot.exists())
        assertTrue(
            "tidak boleh ada isi tersisa di attachments/",
            attachmentsRoot.listFiles().isNullOrEmpty()
        )
    }

    @Test
    fun deleteWorkspaceAttachmentsMenghapusFolderWorkspaceSaja() {
        val wsA = File(context.filesDir, "attachments/pin_a").apply { mkdirs() }
        File(wsA, "att_1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val wsB = File(context.filesDir, "attachments/pin_b").apply { mkdirs() }
        val keep = File(wsB, "keep.jpg")
        keep.writeBytes(byteArrayOf(9))

        ImageFileUtil.deleteWorkspaceAttachments(context, "pin_a")

        assertFalse("folder workspace yang ditinggalkan harus terhapus", wsA.exists())
        assertTrue("folder workspace lain tidak boleh tersentuh", keep.exists())
    }

    @Test
    fun deleteAllAttachmentsAmanSaatTidakAdaLampiran() {
        // Pastikan folder attachments TIDAK ADA sama sekali.
        val root = File(context.filesDir, "attachments")
        root.deleteRecursively()
        assertFalse(root.exists())

        // Tidak boleh crash & tidak boleh membuat folder baru di disk.
        ImageFileUtil.deleteAllAttachments(context)
        ImageFileUtil.deleteWorkspaceAttachments(context, "pin_x")

        assertFalse(
            "delete tanpa lampiran tidak boleh membuat folder attachments baru",
            root.exists()
        )
    }

    @Test
    fun deleteWorkspaceAttachmentsAmanSaatWorkspaceKosong() {
        val root = File(context.filesDir, "attachments").apply { mkdirs() }

        // workspace blank → early return, tanpa crash & tanpa efek samping.
        ImageFileUtil.deleteWorkspaceAttachments(context, "")
        ImageFileUtil.deleteWorkspaceAttachments(context, "   ")

        assertTrue(root.exists())
        assertTrue(
            "workspace blank tidak boleh menghapus/membuat isi apa pun",
            root.listFiles().isNullOrEmpty()
        )
    }
}
