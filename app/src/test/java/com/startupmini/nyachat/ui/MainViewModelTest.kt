package com.startupmini.nyachat.ui

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.AppDatabase
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.AiChatParseResult
import com.startupmini.nyachat.data.remote.FinanceAiService
import com.startupmini.nyachat.data.remote.FirestoreSyncManager
import com.startupmini.nyachat.data.repository.FinanceRepository
import java.lang.reflect.Field
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit test `MainViewModel` (state machine UI — audit 2026-08-13):
 *  - Undo hapus: delete → event DeleteUndo → undoDelete mengembalikan data.
 *  - Clear data: clearAllData / clearLocalData mengosongkan Room + antrian pending.
 *  - AI report: generate/dismiss audit & bulanan (loading + error state benar).
 *
 * Strategi (tanpa mengubah kode produksi):
 *  - Robolectric menyediakan Application & main looper.
 *  - DB Room in-memory di-inject ke singleton `AppDatabase.INSTANCE` via refleksi —
 *    `MainViewModel` memakai `getDatabase()` yang mengembalikan INSTANCE bila ada,
 *    jadi test mendapat DB segar per metode tanpa file disk.
 *  - Flow `stateIn(WhileSubscribed(5000))` butuh subscriber — kolektor keep-alive
 *    di background menjaga upstream tetap aktif.
 *  - Lanjutan coroutine di `Dispatchers.Main` dieksekusi dengan meng-idle-kan main
 *    looper (pola sama dengan test Robolectric lain di repo).
 *  - Kaskade AI jatuh cepat ke heuristik offline (tanpa API key; relay mati sendiri
 *    karena FirebaseApp tidak ter-init di unit test) — tidak ada network call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: MainViewModel

    /** Menjaga flow WhileSubscribed tetap ter-subscribe supaya StateFlow ter-update dari Room. */
    private val keepAlive = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        setDatabaseInstance(db)

        viewModel = MainViewModel(context)
        viewModel.setSender("Suami")

        // stateIn(WhileSubscribed(5000)): tanpa subscriber, upstream Room tidak
        // pernah dikoleksi sehingga `.value` tetap emptyList — aktifkan dulu.
        keepAlive.launch { viewModel.messages.collect { } }
        keepAlive.launch { viewModel.transactions.collect { } }
        keepAlive.launch { viewModel.totalIncome.collect { } }
        keepAlive.launch { viewModel.totalExpense.collect { } }
    }

    @After
    fun tearDown() {
        keepAlive.cancel()
        setDatabaseInstance(null)
    }

    private fun setDatabaseInstance(instance: AppDatabase?) {
        val field: Field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, instance)
    }

    /** Tunggu kondisi sambil meng-idle-kan main looper (lanjutan coroutine Main). */
    private fun awaitTrue(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        fail("Tidak tercapai dalam $timeoutMs ms: $what")
    }

    private fun addExpense(amount: Double, description: String) {
        viewModel.addManualTransaction(
            type = Constants.TransactionTypes.EXPENSE,
            category = Constants.Categories.FOOD,
            amount = amount,
            description = description,
            loggedBy = "Suami"
        )
    }

    // ---------- Indikator AI berpikir (audit ui/ 2026-08-14) ----------

    @Test
    fun `editMessage menyalakan indikator AI berpikir dan mematikannya setelah selesai`() {
        val vm = DelayedAiViewModel(ApplicationProvider.getApplicationContext())
        vm.setSender("Suami")
        val k = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        k.launch { vm.messages.collect { } }
        k.launch { vm.transactions.collect { } }

        vm.sendMessage("halo", null, null, null, null, null)
        awaitTrue("pesan terkirim") { vm.messages.value.isNotEmpty() }
        // Pesan tampil SEBELUM parse AI selesai (insert mendahului parse di
        // repository) — tunggu indikator benar-benar mati dulu sebelum edit.
        awaitTrue("indikator mati setelah kirim") { !vm.isAiThinking.value }
        val id = vm.messages.value.first().id

        assertFalse("indikator harus mati sebelum edit", vm.isAiThinking.value)
        vm.editMessage(id, "beli kopi 20000")
        // Biarkan coroutine mulai: setAiThinking(true) lalu masuk delay AI.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("indikator menyala saat edit berjalan", vm.isAiThinking.value)

        // Edit selesai → indikator harus kembali mati (finally, tidak stuck).
        awaitTrue("indikator mati setelah edit selesai") { !vm.isAiThinking.value }
        k.cancel()
    }

    // ---------- Undo hapus (state machine) ----------

    @Test
    fun `deleteTransaction memicu event undo dan undoDelete mengembalikan transaksi`() {
        addExpense(20_000.0, "beli kopi 20rb")
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }
        val original = viewModel.transactions.value.first()
        assertNotNull("transaksi harus punya cloudId", original.cloudId)

        val undoEvents = mutableListOf<MainViewModel.DeleteUndo>()
        val job = keepAlive.launch { viewModel.deleteUndoEvents.collect { undoEvents.add(it) } }

        viewModel.deleteTransaction(original)
        awaitTrue("transaksi hilang dari daftar") { viewModel.transactions.value.isEmpty() }
        awaitTrue("event undo dikirim") { undoEvents.isNotEmpty() }
        assertEquals(1, undoEvents[0].transactions.size)

        // Undo → transaksi kembali dengan cloudId SAMA (tanpa duplikat di cloud).
        viewModel.undoDelete(undoEvents[0])
        awaitTrue("transaksi dipulihkan") { viewModel.transactions.value.size == 1 }
        val restored = viewModel.transactions.value.first()
        assertEquals("cloudId harus dipertahankan saat undo", original.cloudId, restored.cloudId)
        assertEquals(20_000.0, restored.amount, 0.001)
        job.cancel()
    }

    @Test
    fun `deleteTransaction dengan emitUndo false tidak mengirim event undo`() {
        addExpense(15_000.0, "beli bakso")
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }
        val original = viewModel.transactions.value.first()

        val undoEvents = mutableListOf<MainViewModel.DeleteUndo>()
        val job = keepAlive.launch { viewModel.deleteUndoEvents.collect { undoEvents.add(it) } }

        // emitUndo=false dipakai pemanggil yang sudah tahu konsekuensinya (mis.
        // aksi "Urungkan" dari snackbar Tercatat) — tidak boleh ada snackbar ganda.
        viewModel.deleteTransaction(original, emitUndo = false)
        awaitTrue("transaksi hilang") { viewModel.transactions.value.isEmpty() }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("tidak boleh ada event undo", undoEvents.isEmpty())
        job.cancel()
    }

    @Test
    fun `hapus pesan finansial mengembalikan pesan dan transaksi terkait untuk undo`() {
        viewModel.sendMessage("beli bakso 15 ribu")
        awaitTrue("pesan terkirim") { viewModel.messages.value.isNotEmpty() }
        // Heuristik offline mengenali transaksi → 1 transaksi dibuat. Badge pesan
        // di-update SETELAH insert transaksi (jalur sendMessage), jadi tunggu
        // sampai badge mendarat sebelum membaca pesan (anti-race — pernah flaky).
        awaitTrue("transaksi hasil parse tercatat") { viewModel.transactions.value.size == 1 }
        awaitTrue("badge finansial mendarat di pesan") {
            viewModel.messages.value.firstOrNull()?.isFinancial == true
        }
        val msg = viewModel.messages.value.first()
        assertTrue("pesan finansial harus ber-badge", msg.isFinancial)
        val msgCloudId = msg.cloudId
        assertNotNull(msgCloudId)

        val undoEvents = mutableListOf<MainViewModel.DeleteUndo>()
        val job = keepAlive.launch { viewModel.deleteUndoEvents.collect { undoEvents.add(it) } }

        viewModel.deleteChatMessage(msg.id)
        awaitTrue("pesan hilang") { viewModel.messages.value.isEmpty() }
        awaitTrue("transaksi terkait ikut hilang") { viewModel.transactions.value.isEmpty() }
        awaitTrue("event undo dikirim") { undoEvents.isNotEmpty() }

        val undo = undoEvents[0]
        assertNotNull("payload undo harus berisi pesan", undo.message)
        assertEquals(1, undo.transactions.size)
        assertEquals("transaksi harus punya relasi ke pesan", msg.id, undo.transactions[0].chatMessageId)

        // Undo memulihkan pesan + transaksi dengan cloudId sama & relasi dijaga ulang.
        viewModel.undoDelete(undo)
        awaitTrue("pesan dipulihkan") { viewModel.messages.value.size == 1 }
        awaitTrue("transaksi dipulihkan") { viewModel.transactions.value.size == 1 }
        val restoredMsg = viewModel.messages.value.first()
        assertEquals("beli bakso 15 ribu", restoredMsg.messageText)
        assertEquals("cloudId pesan dipertahankan", msgCloudId, restoredMsg.cloudId)
        assertTrue("badge finansial dihitung ulang", restoredMsg.isFinancial)
        val restoredTx = viewModel.transactions.value.first()
        assertEquals("relasi pesan->transaksi dijaga ulang", restoredMsg.id, restoredTx.chatMessageId)
        job.cancel()
    }

    @Test
    fun `addManualTransaction memicu event Tercatat untuk snackbar`() {
        val recorded = mutableListOf<MainViewModel.TransactionRecorded>()
        val job = keepAlive.launch { viewModel.transactionRecorded.collect { recorded.add(it) } }

        addExpense(50_000.0, "bensin")
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }
        awaitTrue("event Tercatat diterima") { recorded.isNotEmpty() }
        assertEquals(50_000.0, recorded[0].transaction.amount, 0.001)
        job.cancel()
    }

    // ---------- Clear data ----------

    @Test
    fun `clearAllData mengosongkan pesan transaksi dan antrian pending`() {
        viewModel.sendMessage("beli bakso 15 ribu")
        awaitTrue("pesan terkirim") { viewModel.messages.value.isNotEmpty() }
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }
        // Belum login/sync → syncMessage di-antri ke pending_ops.
        awaitTrue("operasi pending tersimpan") {
            runBlocking { db.pendingOpDao().count() } > 0
        }

        viewModel.clearAllData()
        awaitTrue("pesan bersih") { viewModel.messages.value.isEmpty() }
        awaitTrue("transaksi bersih") { viewModel.transactions.value.isEmpty() }
        // Op sync lama dibuang; yang tersisa HANYA op pembersihan cloud —
        // clearFamilyData() meng-enqueue CLEAR_FAMILY saat offline supaya data
        // cloud ikut dibersihkan begitu perangkat online kembali (desain).
        val remaining = runBlocking { db.pendingOpDao().getAll() }
        assertEquals("hanya op CLEAR_FAMILY yang tersisa", 1, remaining.size)
        assertEquals(FirestoreSyncManager.OP_CLEAR_FAMILY, remaining[0].opType)
    }

    @Test
    fun `clearLocalData mengosongkan data lokal saja`() {
        viewModel.sendMessage("beli bakso 15 ribu")
        awaitTrue("pesan terkirim") { viewModel.messages.value.isNotEmpty() }
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }

        viewModel.clearLocalData()
        awaitTrue("pesan bersih") { viewModel.messages.value.isEmpty() }
        awaitTrue("transaksi bersih") { viewModel.transactions.value.isEmpty() }
        assertEquals("antrian pending ikut dibuang (anti-replay workspace)", 0, runBlocking { db.pendingOpDao().count() })
    }

    // ---------- AI report ----------

    @Test
    fun `generateAiAuditReport menghasilkan laporan dan mengelola loading dengan benar`() {
        addExpense(100_000.0, "belanja")
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }

        viewModel.generateAiAuditReport()
        // Loading di-set sinkron di awal launch (Main.immediate).
        assertTrue(viewModel.isAuditLoading.value)
        awaitTrue("laporan audit tersedia") { viewModel.auditReport.value != null }
        assertFalse("loading harus selesai", viewModel.isAuditLoading.value)
        assertFalse("laporan tidak boleh kosong", viewModel.auditReport.value!!.isBlank())
    }

    @Test
    fun `dismissAuditReport mengosongkan laporan`() {
        viewModel.generateAiAuditReport()
        awaitTrue("laporan audit tersedia") { viewModel.auditReport.value != null }

        viewModel.dismissAuditReport()
        assertNull(viewModel.auditReport.value)
    }

    @Test
    fun `generateMonthlyAnalysis menghasilkan laporan dan mengelola loading dengan benar`() {
        addExpense(30_000.0, "kopi")
        awaitTrue("transaksi tercatat") { viewModel.transactions.value.size == 1 }

        viewModel.generateMonthlyAnalysis()
        assertTrue(viewModel.isMonthlyLoading.value)
        awaitTrue("laporan bulanan tersedia") { viewModel.monthlyReport.value != null }
        assertFalse("loading harus selesai", viewModel.isMonthlyLoading.value)
        assertFalse("laporan tidak boleh kosong", viewModel.monthlyReport.value!!.isBlank())
    }

    @Test
    fun `dismissMonthlyReport mengosongkan laporan`() {
        viewModel.generateMonthlyAnalysis()
        awaitTrue("laporan bulanan tersedia") { viewModel.monthlyReport.value != null }

        viewModel.dismissMonthlyReport()
        assertNull(viewModel.monthlyReport.value)
    }

    // ---------- Jalur error AI report ----------

    @Test
    fun `generateAiAuditReport gagal menandai error dan menampilkan teks error`() {
        val failingVm = FailingReportViewModel(ApplicationProvider.getApplicationContext())

        failingVm.generateAiAuditReport()
        // Error dilempar sinkron (tanpa suspensi) → state akhir langsung tercapai.
        awaitTrue("laporan error tampil") { failingVm.auditReport.value != null }
        assertTrue("harus ditandai error", failingVm.isAuditError.value)
        assertFalse("loading harus selesai", failingVm.isAuditLoading.value)
        assertEquals(
            "teks error harus dari resource rekap_ai_failed",
            ApplicationProvider.getApplicationContext<Application>().getString(R.string.rekap_ai_failed),
            failingVm.auditReport.value
        )

        // Dismiss tetap berfungsi setelah error (dialog bisa ditutup).
        failingVm.dismissAuditReport()
        assertNull(failingVm.auditReport.value)
    }

    @Test
    fun `generateMonthlyAnalysis gagal menandai error dan menampilkan teks error`() {
        val failingVm = FailingReportViewModel(ApplicationProvider.getApplicationContext())

        failingVm.generateMonthlyAnalysis()
        awaitTrue("laporan bulanan error tampil") { failingVm.monthlyReport.value != null }
        assertTrue("harus ditandai error", failingVm.isMonthlyError.value)
        assertFalse("loading harus selesai", failingVm.isMonthlyLoading.value)
        assertEquals(
            "teks error harus dari resource rekap_monthly_failed",
            ApplicationProvider.getApplicationContext<Application>().getString(R.string.rekap_monthly_failed),
            failingVm.monthlyReport.value
        )

        failingVm.dismissMonthlyReport()
        assertNull(failingVm.monthlyReport.value)
    }

    /**
     * AI service dengan parse lambat (delay 800ms) — menguji bahwa indikator
     * "AI berpikir" menyala SELAMA operasi (edit/send) dan mati setelah selesai.
     */
    private class DelayedAiService : FinanceAiService() {
        override suspend fun parseMessage(
            messageText: String,
            sender: String,
            recentContext: List<ChatMessage>,
            imagePath: String?
        ): AiChatParseResult {
            kotlinx.coroutines.delay(800)
            return super.parseMessage(messageText, sender, recentContext, imagePath)
        }
    }

    /** ViewModel dengan AI service yang lambat (factory overridable). */
    private class DelayedAiViewModel(application: Application) : MainViewModel(application) {
        override fun createRepository(application: Application): FinanceRepository {
            val db = AppDatabase.getDatabase(application)
            return FinanceRepository(
                db, db.chatMessageDao(), db.transactionDao(), db.pendingOpDao(), DelayedAiService()
            )
        }
    }

    /**
     * AI service tiruan: laporan audit & bulanan SELALU gagal — menguji jalur
     * error MainViewModel (is*Error = true + teks error dari resource).
     */
    private class FailingAiService : FinanceAiService() {
        override suspend fun auditReport(
            transactions: List<FinancialTransaction>,
            income: Double,
            expense: Double
        ): String = throw IllegalStateException("AI mati (uji jalur error)")

        override suspend fun monthlyAnalysis(transactions: List<FinancialTransaction>): String =
            throw IllegalStateException("AI mati (uji jalur error)")
    }

    /** ViewModel dengan repository ber-AI gagal (lewat factory yang bisa di-override). */
    private class FailingReportViewModel(application: Application) : MainViewModel(application) {
        override fun createRepository(application: Application): FinanceRepository {
            val db = AppDatabase.getDatabase(application)
            return FinanceRepository(
                db, db.chatMessageDao(), db.transactionDao(), db.pendingOpDao(), FailingAiService()
            )
        }
    }
}
