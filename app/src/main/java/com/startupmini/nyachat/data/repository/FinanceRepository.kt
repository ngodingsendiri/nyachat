package com.startupmini.nyachat.data.repository

import android.util.Log
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.ChatMessageDao
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.local.PendingOpDao
import com.startupmini.nyachat.data.local.TransactionDao
import com.startupmini.nyachat.data.remote.FinanceAiService
import com.startupmini.nyachat.data.remote.FirestoreSyncManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository utama data chat + keuangan.
 *
 * P3-1: AI tidak lagi dipanggil langsung ke [GeminiService] — semua panggilan
 * lewat [FinanceAiService] (dependency injectable untuk unit test). Repository
 * tetap menjadi orkestrator: konsistensi 2 arah antara pesan chat & transaksi
 * (badge finansial) sengaja dipertahankan di sini karena relasinya lintas-entitas.
 */
class FinanceRepository(
    private val chatMessageDao: ChatMessageDao,
    private val transactionDao: TransactionDao,
    private val pendingOpDao: PendingOpDao,
    private val aiService: FinanceAiService = FinanceAiService()
) {

    init {
        // Pasang DAO antrian pending sedini mungkin — menutup celah "kirim pesan
        // sebelum startCloudSync selesai" agar op tidak pernah hilang.
        FirestoreSyncManager.setPendingOpDao(pendingOpDao)
    }

    companion object {
        private const val TAG = "FinanceRepository"
    }

    val allMessages: Flow<List<ChatMessage>> =
        chatMessageDao.getAllMessages().map { it.dedupeByCloudId() }
    // Guard dedupe transaksi (paritas dengan pesan) — lapisan pertahanan kedua di
    // atas index unik cloudId, supaya UI tidak pernah menampilkan angka ganda.
    val allTransactions: Flow<List<FinancialTransaction>> =
        transactionDao.getAllTransactions().map { it.dedupeByCloudId() }

    /** Aktifkan sinkronisasi cloud (wajib sudah login Google + listener realtime). */
    suspend fun startCloudSync(pin: String, role: String = Constants.Roles.MEMBER) {
        if (FirestoreSyncManager.isSignedIn()) {
            FirestoreSyncManager.start(pin, role, chatMessageDao, transactionDao, pendingOpDao)
        } else {
            Log.w(TAG, "Cloud sync dilewati: belum login dengan akun Google")
        }
    }

    fun stopCloudSync() {
        FirestoreSyncManager.stop()
    }

    suspend fun sendMessage(
        sender: String,
        messageText: String,
        imagePath: String? = null,
        filePath: String? = null,
        fileName: String? = null,
        replyToSender: String? = null,
        replyToText: String? = null
    ): FinancialTransaction? {
        return withContext(Dispatchers.IO) {
            // Satu sumber waktu untuk pesan & transaksi (L3) — chat dan Rekap
            // memakai timestamp yang sama supaya urutan tidak melompat-lompat.
            val now = System.currentTimeMillis()
            // 1. Insert user chat message (cloudId unik lintas perangkat; imagePath = foto
            //    nota lokal; filePath/fileName = dokumen; replyTo* = pesan yang dibalas)
            val initialMsg = ChatMessage(
                sender = sender,
                messageText = messageText,
                timestamp = now,
                imagePath = imagePath,
                filePath = filePath,
                fileName = fileName,
                replyToSender = replyToSender,
                replyToText = replyToText,
                cloudId = UUID.randomUUID().toString()
            )
            val msgId = chatMessageDao.insertMessage(initialMsg)

            // Get recent context (audit performa: query LIMIT 10, bukan muat
            // seluruh riwayat lalu takeLast — hemat memori & I/O tiap kirim).
            val recentList = chatMessageDao.getRecentMessages(10).asReversed()

            // 2. Process message with AI Parser silently in background (foto nota ikut
            //    dibaca AI; file PDF hanya dilampirkan — teks caption tetap diparse)
            val aiResult = aiService.parseMessage(messageText, sender, recentList, imagePath)

            var finalMsg = initialMsg.copy(id = msgId)
            var createdTx: FinancialTransaction? = null

            // r1.2.4 (tuning AI): SATU pesan bisa memuat BANYAK transaksi — semua
            // hasil parse disimpan (Rekap menampilkan lengkap); badge pesan memakai
            // ringkasan (total nominal + kategori transaksi pertama).
            if (aiResult.containsTransaction) {
                val txs = aiResult.all
                if (txs.isNotEmpty()) {
                    val insertedList = txs.map { tx ->
                        val trans = FinancialTransaction(
                            type = tx.type,
                            category = tx.category,
                            amount = tx.amount,
                            description = tx.description.ifBlank { messageText },
                            loggedBy = sender,
                            // Timestamp eksplisit (tanggal disebut di pesan) atau waktu pesan.
                            timestamp = tx.timestamp ?: now,
                            chatMessageId = msgId,
                            cloudId = UUID.randomUUID().toString(),
                            sourceMessageCloudId = finalMsg.cloudId // Cross-device lookup key
                        )
                        val txId = transactionDao.insertTransaction(trans)
                        trans.copy(id = txId)
                    }
                    createdTx = insertedList.first()
                    val total = insertedList.sumOf { it.amount }
                    val first = insertedList.first()

                    // Update user message with financial badge tags on the message itself
                    finalMsg = initialMsg.copy(
                        id = msgId,
                        isFinancial = true,
                        detectedAmount = total,
                        detectedCategory = first.category,
                        detectedType = first.type,
                        // M7: catat asal deteksi (AI atau heuristik offline) untuk
                        // indikator transparansi di badge financisial.
                        detectedBy = aiResult.detectedBy
                    )
                    chatMessageDao.insertMessage(finalMsg)

                    // Sync transaksi ke cloud supaya pasangan/keluarga di perangkat lain ikut melihat
                    insertedList.forEach { FirestoreSyncManager.syncTransaction(it) }
                }
            } else if (com.startupmini.nyachat.data.remote.GeminiService.isFinancialQuestion(messageText)) {
                // r1.2.4 (tuning AI): pertanyaan keuangan di chat dijawab BERBASIS
                // data DB yang sebenarnya (bukan mengarang) — "hari ini sudah keluar
                // berapa?" mendapat jawaban nyata, bukan "tercatat dalam obrolan".
                answerFinancialQuestion(messageText)
            }

            // Push ke cloud supaya pasangan/keluarga di perangkat lain ikut melihat
            FirestoreSyncManager.syncMessage(finalMsg)

            // NO AUTOMATIC AI CHAT BUBBLE HERE! Chat stays clean between Husband & Wife.
            createdTx
        }
    }

    /**
     * Edit isi pesan yang sudah terkirim. Teks baru diparse ulang oleh AI dan:
     * - kalau sekarang jadi transaksi → buat transaksi baru,
     * - kalau tetap transaksi → perbarui transaksi lama (Rekap ikut berubah),
     * - kalau sudah bukan transaksi lagi → transaksi terkait dihapus.
     */
    suspend fun editMessage(messageId: Long, newText: String) {
        withContext(Dispatchers.IO) {
            val existing = chatMessageDao.getById(messageId) ?: return@withContext
            val recentList = chatMessageDao.getRecentMessages(10).asReversed()

            // Parse ulang dengan AI (foto nota tetap ikut dibaca kalau ada)
            val aiResult = aiService.parseMessage(
                newText, existing.sender, recentList, existing.imagePath
            )
            val txs = if (aiResult.containsTransaction) aiResult.all else emptyList()
            val isFinancial = txs.isNotEmpty()

            val updated = existing.copy(
                messageText = newText,
                editedAt = System.currentTimeMillis(),
                isFinancial = isFinancial,
                detectedAmount = if (isFinancial) txs.sumOf { it.amount } else null,
                detectedCategory = if (isFinancial) txs.first().category else null,
                detectedType = if (isFinancial) txs.first().type else null,
                // M7: perbarui asal deteksi juga saat edit.
                detectedBy = if (isFinancial) aiResult.detectedBy else null
            )
            chatMessageDao.updateMessage(updated)

            // r1.2.4: REBUILD semua transaksi pesan ini (bukan update 1 baris) —
            // konsisten untuk pesan single maupun multi-transaksi: hapus yang lama
            // (lokal + cloud), lalu tulis ulang dari hasil parse terbaru.
            val oldTxs = transactionDao.getAllByChatMessageId(existing.id)
            oldTxs.forEach { tx -> tx.cloudId?.let { FirestoreSyncManager.deleteTransaction(it) } }
            transactionDao.deleteByChatMessageId(existing.id)

            if (isFinancial) {
                txs.forEach { tx ->
                    val trans = FinancialTransaction(
                        type = tx.type,
                        category = tx.category,
                        amount = tx.amount,
                        description = tx.description.ifBlank { newText },
                        loggedBy = existing.sender,
                        timestamp = tx.timestamp ?: existing.timestamp,
                        chatMessageId = messageId,
                        cloudId = UUID.randomUUID().toString(),
                        sourceMessageCloudId = existing.cloudId // Cross-device lookup key
                    )
                    val txId = transactionDao.insertTransaction(trans)
                    FirestoreSyncManager.syncTransaction(trans.copy(id = txId))
                }
            }

            FirestoreSyncManager.syncMessage(updated)
        }
    }

    /**
     * Jawab pertanyaan keuangan di chat BERBASIS data DB (tuning AI r1.2.4):
     * ringkasan nyata (hari ini / bulan ini / transaksi terakhir) disisipkan ke
     * prompt AI; offline → jawaban lokal dari data yang sama (tetap nyata,
     * bukan mengarang). Jawaban disimpan sebagai pesan AI di chat.
     */
    private suspend fun answerFinancialQuestion(question: String) {
        val all = transactionDao.getAllTransactions().first()
        val summary = buildFinancialSummaryText(all)
        // Audit r1.2.4: bila jalur AI tersedia tapi PANGGILAN gagal — baik exception
        // MAUPUN timeout (askInChat menelan timeout menjadi balasan generik tanpa
        // data; deteksi lewat ketiadaan "Rp" / frasa khas template offline) — jatuh
        // ke jawaban offline BERBASIS DATA, jangan balas generik tanpa angka.
        val reply = if (com.startupmini.nyachat.data.remote.GeminiService.isAiAvailable()) {
            val ai = runCatching {
                aiService.askInChat(
                    "Ini data riil keuangan pengguna saat ini (jangan berikan angka lain):\n$summary\n\nPertanyaan: $question"
                )
            }.getOrNull()
            if (ai.isNullOrBlank() || !ai.contains("Rp") || ai.contains("mode AI sedang offline")) {
                buildOfflineFinancialAnswer(question, all)
            } else {
                ai
            }
        } else {
            buildOfflineFinancialAnswer(question, all)
        }
        val aiMsg = ChatMessage(
            sender = Constants.Sender.AI,
            messageText = reply,
            timestamp = System.currentTimeMillis(),
            cloudId = UUID.randomUUID().toString()
        )
        chatMessageDao.insertMessage(aiMsg)
        FirestoreSyncManager.syncMessage(aiMsg)
    }

    /** Ringkasan angka nyata dari DB untuk prompt jawaban pertanyaan finansial. */
    private fun buildFinancialSummaryText(all: List<FinancialTransaction>): String {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        // Setiap batas dihitung dari Calendar SEGAR (review r1.2.4) — jangan
        // mutasi objek yang sama karena startOfWeek bisa salah (berbasis tanggal
        // 1, bukan hari ini, atau jatuh di bulan sebelumnya).
        val startOfWeek = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        }.timeInMillis
        val startOfMonth = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        fun sum(period: Long): Pair<Double, Double> {
            val inPeriod = all.filter { it.timestamp >= period }
            return inPeriod.filter { it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount } to
                inPeriod.filter { it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }
        }

        val (todayExp, todayInc) = sum(startOfDay)
        val (weekExp, weekInc) = sum(startOfWeek)
        val (monthExp, monthInc) = sum(startOfMonth)
        val last3 = all.take(3).joinToString("\n") {
            "- ${it.type} ${it.category}: Rp ${it.amount.toLong()} (${it.description})"
        }

        return buildString {
            appendLine("Total hari ini: keluar Rp ${todayExp.toLong()}, masuk Rp ${todayInc.toLong()}")
            appendLine("Total minggu ini: keluar Rp ${weekExp.toLong()}, masuk Rp ${weekInc.toLong()}")
            appendLine("Total bulan ini: keluar Rp ${monthExp.toLong()}, masuk Rp ${monthInc.toLong()}")
            appendLine("Transaksi terakhir:")
            appendLine(last3.ifBlank { "(belum ada transaksi)" })
        }
    }

    /** Jawaban offline berbasis data nyata (tanpa AI). */
    private fun buildOfflineFinancialAnswer(question: String, all: List<FinancialTransaction>): String {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val startOfMonth = cal.apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        val lower = question.lowercase()

        val todayExp = all.filter { it.timestamp >= startOfDay && it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }
        val todayInc = all.filter { it.timestamp >= startOfDay && it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }
        val monthExp = all.filter { it.timestamp >= startOfMonth && it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }
        val monthInc = all.filter { it.timestamp >= startOfMonth && it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }

        val sb = StringBuilder()
        when {
            lower.contains("hari ini") -> {
                sb.appendLine("📊 Hari ini: keluar **Rp ${todayExp.toLong()}**, masuk **Rp ${todayInc.toLong()}**.")
            }
            lower.contains("bulan ini") -> {
                sb.appendLine("📊 Bulan ini: keluar **Rp ${monthExp.toLong()}**, masuk **Rp ${monthInc.toLong()}** (saldo ${(monthInc - monthExp).toLong()}).")
            }
            else -> {
                sb.appendLine("📊 Hari ini keluar Rp ${todayExp.toLong()}, bulan ini keluar Rp ${monthExp.toLong()}.")
            }
        }
        val last3 = all.take(3)
        if (last3.isNotEmpty()) {
            sb.appendLine("Transaksi terakhir:")
            last3.forEach { sb.appendLine("  • ${it.description} — Rp ${it.amount.toLong()} (${it.category})") }
        }
        sb.append("Aku dalam mode offline, jadi jawaban ini dari data tersimpan. 😊")
        return sb.toString()
    }

    suspend fun askAiInChat(prompt: String): String {
        return withContext(Dispatchers.IO) {
            // Jawaban AI bebas (bukan parser transaksi) saat user menekan tombol ✨ Tanya AI
            val reply = aiService.askInChat(prompt)

            val aiMsg = ChatMessage(
                sender = Constants.Sender.AI,
                messageText = reply,
                timestamp = System.currentTimeMillis(),
                cloudId = UUID.randomUUID().toString()
            )
            chatMessageDao.insertMessage(aiMsg)
            FirestoreSyncManager.syncMessage(aiMsg)

            reply
        }
    }

    suspend fun addManualTransaction(transaction: FinancialTransaction): FinancialTransaction {
        return withContext(Dispatchers.IO) {
            val withCloud = transaction.copy(cloudId = transaction.cloudId ?: UUID.randomUUID().toString())
            val txId = transactionDao.insertTransaction(withCloud)
            val inserted = withCloud.copy(id = txId)
            FirestoreSyncManager.syncTransaction(inserted)
            inserted
        }
    }

    /**
     * Perbarui transaksi (edit) lalu sinkronkan ke cloud.
     * Konsistensi 2 arah: kalau transaksi ini dibuat dari pesan chat
     * (chatMessageId != null), badge finansial pada pesan ikut diperbarui
     * (nominal/kategori/tipe) + disinkronkan — supaya Rekap & chat selalu sama
     * setelah user mengedit transaksi dari layar Rekap.
     */
    suspend fun updateTransaction(transaction: FinancialTransaction) {
        withContext(Dispatchers.IO) {
            // Cap waktu edit — dipakai resolusi konflik sync (last-writer-by-time)
            // supaya edit bersamaan di dua perangkat tidak saling menindas acak.
            val edited = transaction.copy(editedAt = System.currentTimeMillis())
            transactionDao.updateTransaction(edited)

            edited.chatMessageId?.let { messageId ->
                chatMessageDao.getById(messageId)?.let { msg ->
                    val updatedMsg = edited.applyFinancialBadgeTo(msg)
                    chatMessageDao.updateMessage(updatedMsg)
                    FirestoreSyncManager.syncMessage(updatedMsg)
                }
            }

            edited.cloudId?.let { FirestoreSyncManager.syncTransaction(edited) }
        }
    }

    suspend fun deleteChatMessage(messageId: Long) {
        withContext(Dispatchers.IO) {
            val msg = chatMessageDao.getById(messageId) ?: return@withContext
            // Konsistensi 1 arah: hapus transaksi terkait pesan ini agar tidak jadi
            // orphan di Rekap (dan dihapus juga dari cloud).
            transactionDao.getByChatMessageId(messageId)?.let { tx ->
                transactionDao.deleteTransaction(tx)
                tx.cloudId?.let { FirestoreSyncManager.deleteTransaction(it) }
            }
            chatMessageDao.deleteMessage(messageId)
            msg.cloudId?.let { FirestoreSyncManager.deleteMessage(it) }
        }
    }

    suspend fun deleteTransaction(transaction: FinancialTransaction) {
        withContext(Dispatchers.IO) {
            // Hapus DULU, lalu query sisa — tanpa filter id (review r1.2.4): filter
            // `id != transaction.id` rapuh saat id lokal 0 (baris dari cloud yang
            // belum pernah dapat id lokal).
            transactionDao.deleteTransaction(transaction)
            transaction.cloudId?.let { FirestoreSyncManager.deleteTransaction(it) }

            // Konsistensi 2 arah: kalau transaksi ini dibuat dari pesan chat, perbarui
            // badge pesan + sinkron, agar Rekap & chat sinkron dan re-parse tidak
            // menciptakan transaksi ulang.
            // Audit r1.2.4: pesan MULTI-transaksi — menghapus SATU transaksi tidak
            // boleh menghilangkan badge total (sebelumnya clearFinancialBadge()
            // menghapus SEMUA badge padahal transaksi lain masih ada).
            transaction.chatMessageId?.let { messageId ->
                chatMessageDao.getById(messageId)?.let { msg ->
                    val remaining = transactionDao.getAllByChatMessageId(messageId)
                    val updated = if (remaining.isEmpty()) {
                        msg.clearFinancialBadge()
                    } else {
                        msg.copy(
                            isFinancial = true,
                            detectedAmount = remaining.sumOf { it.amount },
                            detectedCategory = remaining.first().category,
                            detectedType = remaining.first().type
                        )
                    }
                    chatMessageDao.updateMessage(updated)
                    FirestoreSyncManager.syncMessage(updated)
                }
            }
        }
    }

    suspend fun clearAllData() {
        withContext(Dispatchers.IO) {
            chatMessageDao.deleteAllMessages()
            transactionDao.deleteAllTransactions()
            // Ops yang belum tersinkron ikut dihapus — data lokal sudah hilang,
            // mengeksekusinya lagi hanya akan mem-push data basi ke cloud.
            pendingOpDao.deleteAll()
            FirestoreSyncManager.clearFamilyData()
        }
    }

    /**
     * Hapus hanya data LOKAL (Room) — tanpa menyentuh cloud. Dipakai saat ganti
     * workspace (PIN berbeda) supaya data antar-workspace tidak tercampur.
     * Antrian pending ikut dihapus: op yang tersisa milik workspace LAMA dan
     * dilarang ter-replay ke workspace baru.
     */
    suspend fun clearLocalData() {
        withContext(Dispatchers.IO) {
            chatMessageDao.deleteAllMessages()
            transactionDao.deleteAllTransactions()
            pendingOpDao.deleteAll()
        }
    }

    /**
     * Restore: ganti seluruh data lokal + cloud dengan isi backup.
     * Id lokal dibuat ulang; relasi transaksi -> pesan chat dijaga lewat
     * pemetaan id lama -> id baru. Semua record lalu di-push ke Firestore
     * supaya perangkat lain di workspace ikut menerima hasil restore.
     */
    suspend fun restoreBackup(messages: List<ChatMessage>, transactions: List<FinancialTransaction>) {
        withContext(Dispatchers.IO) {
            chatMessageDao.deleteAllMessages()
            transactionDao.deleteAllTransactions()
            // Ops lama milik data yang ditimpa — buang supaya tidak push data basi.
            pendingOpDao.deleteAll()

            val idMap = mutableMapOf<Long, Long>()
            messages.forEach { m ->
                val newId = chatMessageDao.insertMessage(m.copy(id = 0))
                idMap[m.id] = newId
            }

            transactions.forEach { t ->
                transactionDao.insertTransaction(
                    t.copy(id = 0, chatMessageId = t.chatMessageId?.let { idMap[it] })
                )
            }

            // Hapus dulu dokumen cloud yang TIDAK ada di backup — tanpa ini,
            // dokumen lama bertahan di cloud dan muncul lagi di perangkat lain
            // walau restore seharusnya mengganti seluruh data lokal + cloud.
            FirestoreSyncManager.deleteAbsentFromBackup(
                keptMessageCloudIds = messages.mapNotNull { it.cloudId }.toSet(),
                keptTransactionCloudIds = transactions.mapNotNull { it.cloudId }.toSet()
            )

            transactions.forEach { t ->
                t.cloudId?.let {
                    FirestoreSyncManager.syncTransaction(
                        t.copy(chatMessageId = t.chatMessageId?.let { oldId -> idMap[oldId] })
                    )
                }
            }
            messages.forEach { m ->
                m.cloudId?.let {
                    FirestoreSyncManager.syncMessage(m.copy(id = idMap[m.id] ?: m.id))
                }
            }
        }
    }

    suspend fun getFrequentTransactionSuggestions(transactions: List<FinancialTransaction>): List<String> =
        aiService.frequentSuggestions(transactions)

    suspend fun generateAuditReport(
        transactions: List<FinancialTransaction>,
        income: Double,
        expense: Double
    ): String = aiService.auditReport(transactions, income, expense)

    /** Analisis bulanan: rekap per bulan + tren + rekomendasi (AI berlapis + fallback offline). */
    suspend fun generateMonthlyAnalysis(transactions: List<FinancialTransaction>): String =
        aiService.monthlyAnalysis(transactions)
}

/**
 * Terapkan nilai transaksi terbaru ke badge finansial pesan chat terkait
 * (dipakai saat edit transaksi dari Rekap). Murni — mudah di-unit-test.
 */
internal fun FinancialTransaction.applyFinancialBadgeTo(message: ChatMessage): ChatMessage =
    message.copy(
        isFinancial = true,
        detectedAmount = amount,
        detectedCategory = category,
        detectedType = type
    )

/**
 * Cabut badge finansial dari pesan chat (dipakai saat transaksi dihapus dari Rekap).
 * Pesan tetap ada — hanya status keuangan yang hilang. Murni — mudah di-unit-test.
 */
internal fun ChatMessage.clearFinancialBadge(): ChatMessage =
    copy(
        isFinancial = false,
        detectedAmount = null,
        detectedCategory = null,
        detectedType = null,
        detectedBy = null
    )

/**
 * Guard dedup bubble (Sprint-3): satu cloudId harus tampil sebagai SATU bubble.
 * Baris duplikat bisa muncul dari race restore + snapshot listener atau backup
 * lama yang berisi id ganda. Pemenang = versi dengan waktu efektif (editedAt
 * ?: timestamp) terbaru; seri → id lokal terbesar. Pesan tanpa cloudId selalu
 * dipertahankan, dan urutan asli tidak diubah. Murni — mudah di-unit-test.
 */
internal fun List<ChatMessage>.dedupeByCloudId(): List<ChatMessage> {
    val winners = HashMap<String, ChatMessage>()
    for (m in this) {
        val cid = m.cloudId ?: continue
        val cur = winners[cid]
        if (cur == null || m.effectiveTime() > cur.effectiveTime() ||
            (m.effectiveTime() == cur.effectiveTime() && m.id > cur.id)
        ) {
            winners[cid] = m
        }
    }
    if (winners.size == count { it.cloudId != null }) return this // fast path: tanpa duplikat
    return filter { m -> m.cloudId == null || winners[m.cloudId] === m }
}

private fun ChatMessage.effectiveTime(): Long = editedAt ?: timestamp

/**
 * Guard dedupe transaksi (paritas dengan [ChatMessage] di atas): satu cloudId
 * harus tampil sebagai SATU transaksi di Rekap. Baris duplikat bisa muncul dari
 * race restore + snapshot listener atau backup lama yang berisi id ganda.
 * Pemenang = versi dengan waktu efektif (editedAt ?: timestamp) terbaru;
 * seri → id lokal terbesar. Transaksi tanpa cloudId selalu dipertahankan, dan
 * urutan asli tidak diubah. Murni — mudah di-unit-test.
 */
@JvmName("dedupeTransactionsByCloudId") // hindari tabrakan tanda tangan JVM dengan versi ChatMessage
internal fun List<FinancialTransaction>.dedupeByCloudId(): List<FinancialTransaction> {
    val winners = HashMap<String, FinancialTransaction>()
    for (t in this) {
        val cid = t.cloudId ?: continue
        val cur = winners[cid]
        if (cur == null || t.effectiveTime() > cur.effectiveTime() ||
            (t.effectiveTime() == cur.effectiveTime() && t.id > cur.id)
        ) {
            winners[cid] = t
        }
    }
    if (winners.size == count { it.cloudId != null }) return this // fast path: tanpa duplikat
    return filter { t -> t.cloudId == null || winners[t.cloudId] === t }
}

private fun FinancialTransaction.effectiveTime(): Long = editedAt ?: timestamp
