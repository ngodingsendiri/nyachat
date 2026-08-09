@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.startupmini.nyachat.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.startupmini.nyachat.Constants
import androidx.lifecycle.viewModelScope
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.data.backup.BackupData
import com.startupmini.nyachat.data.backup.DataExporter
import com.startupmini.nyachat.data.analytics.WeeklyInsights
import com.startupmini.nyachat.data.local.AppDatabase
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.repository.FinanceRepository
import com.startupmini.nyachat.data.remote.FinanceAiService
import com.startupmini.nyachat.data.remote.GeminiService
import com.startupmini.nyachat.data.remote.ImageFileUtil
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@kotlinx.coroutines.FlowPreview
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Event one-shot: transaksi baru berhasil dicatat (manual atau hasil parse chat).
     * UI menampilkan Snackbar "Tercatat" dengan aksi Urungkan (hapus transaksi).
     */
    data class TransactionRecorded(val transaction: FinancialTransaction)

    companion object {
        /** Cooldown saran cepat — batasi panggilan AI agar tidak boros kuota BYOK (P2-8). */
        private const val QUICK_SUGGESTIONS_COOLDOWN_MS = 15 * 60 * 1000L

        // Satu sumber kebenaran saran statis: GeminiService.DEFAULT_SUGGESTIONS
        // (L6). Hindari duplikasi literal yang bisa melenceng antar file.
        private val DEFAULT_SUGGESTIONS = GeminiService.DEFAULT_SUGGESTIONS
    }

    private val repository: FinanceRepository

    val messages: StateFlow<List<ChatMessage>>
    val transactions: StateFlow<List<FinancialTransaction>>

    // Sender state
    private val _activeSender = MutableStateFlow("")
    val activeSender: StateFlow<String> = _activeSender.asStateFlow()

    private val _isAiThinking = MutableStateFlow(false)
    val isAiThinking: StateFlow<Boolean> = _isAiThinking.asStateFlow()

    private val _auditReport = MutableStateFlow<String?>(null)
    val auditReport: StateFlow<String?> = _auditReport.asStateFlow()

    private val _isAuditLoading = MutableStateFlow(false)
    val isAuditLoading: StateFlow<Boolean> = _isAuditLoading.asStateFlow()

    private val _monthlyReport = MutableStateFlow<String?>(null)
    val monthlyReport: StateFlow<String?> = _monthlyReport.asStateFlow()

    private val _isMonthlyLoading = MutableStateFlow(false)
    val isMonthlyLoading: StateFlow<Boolean> = _isMonthlyLoading.asStateFlow()

    val totalIncome: StateFlow<Double>
    val totalExpense: StateFlow<Double>

    /** Sprint-4: insight otomatis (tanpa AI) untuk kartu di layar Rekap. */
    val weeklyInsights: StateFlow<List<String>>

    private val _quickSuggestions = MutableStateFlow<List<String>>(emptyList())
    val quickSuggestions: StateFlow<List<String>> = _quickSuggestions.asStateFlow()

    private val _transactionRecorded = MutableSharedFlow<TransactionRecorded>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val transactionRecorded: SharedFlow<TransactionRecorded> = _transactionRecorded

    init {
        val db = AppDatabase.getDatabase(application)
        // P3-1: AI dipisah ke FinanceAiService — dependency injectable (bisa di-mock).
        repository = FinanceRepository(
            db.chatMessageDao(), db.transactionDao(), db.pendingOpDao(), FinanceAiService()
        )

        messages = repository.allMessages.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        transactions = repository.allTransactions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        totalIncome = transactions.map { list ->
            list.filter { it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)


        totalExpense = transactions.map { list ->
            list.filter { it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

        // Insight mingguan/bulanan dihitung lokal (murni, tanpa kuota AI) —
        // otomatis segar setiap kali daftar transaksi berubah.
        weeklyInsights = transactions
            .map { WeeklyInsights.generateInsights(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // P2-8: saran cepat dihitung maksimal sekali per interval cooldown —
        // memanggil AI (dengan rotasi 6 model) tiap perubahan transaksi, termasuk
        // perubahan dari listener perangkat lain, sangat boros kuota & lambat.
        viewModelScope.launch {
            var lastSuggestionsAt = 0L
            transactions
                .debounce(3000)
                .collect { list ->
                    if (list.isEmpty()) {
                        _quickSuggestions.value = DEFAULT_SUGGESTIONS
                        return@collect
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSuggestionsAt < QUICK_SUGGESTIONS_COOLDOWN_MS) {
                        return@collect
                    }
                    try {
                        val suggestions = repository.getFrequentTransactionSuggestions(list)
                        _quickSuggestions.value = suggestions
                        lastSuggestionsAt = now
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "Operasi gagal", e)
                    }
                }
        }

    }

    fun setSender(sender: String) {
        _activeSender.value = sender
    }

    fun startCloudSync(pin: String, role: String = Constants.Roles.MEMBER) {
        viewModelScope.launch {
            try {
                repository.startCloudSync(pin, role)
            } catch (e: Exception) {
                Log.w("MainViewModel", "Mulai cloud sync gagal", e)
            }
        }
    }

    fun stopCloudSync() {
        repository.stopCloudSync()
    }

    fun sendMessage(
        text: String,
        imagePath: String? = null,
        filePath: String? = null,
        fileName: String? = null,
        replyToSender: String? = null,
        replyToText: String? = null
    ) {
        if (text.isBlank() && imagePath == null && filePath == null) return
        val currentSender = _activeSender.value
        viewModelScope.launch {
            // Indikator "AI sedang berpikir" aktif SELAMA parsing (termasuk
            // kaskade AI) — tanpa ini user bisa mengirim pesan bertumpuk tanpa
            // tahu parse masih berjalan, dan bubble ketik tidak pernah muncul.
            _isAiThinking.value = true
            try {
                val created = repository.sendMessage(
                    currentSender, text.trim(), imagePath, filePath, fileName, replyToSender, replyToText
                )
                if (created != null) _transactionRecorded.tryEmit(TransactionRecorded(created))
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
            } finally {
                _isAiThinking.value = false
            }
        }
    }

    fun editMessage(messageId: Long, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch {
            try {
                repository.editMessage(messageId, newText.trim())
            } catch (e: Exception) {
                Log.w("MainViewModel", "Edit pesan gagal", e)
            }
        }
    }

    fun deleteChatMessage(messageId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteChatMessage(messageId)
            } catch (e: Exception) {
                Log.w("MainViewModel", "Hapus pesan gagal", e)
            }
        }
    }

    fun askAiInChat(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            _isAiThinking.value = true
            try {
                repository.askAiInChat(prompt.trim())
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
            } finally {
                _isAiThinking.value = false
            }
        }
    }

    fun addManualTransaction(
        type: String,
        category: String,
        amount: Double,
        description: String,
        loggedBy: String
    ) {
        viewModelScope.launch {
            val trans = FinancialTransaction(
                type = type,
                category = category,
                amount = amount,
                description = description,
                loggedBy = loggedBy
            )

            try {
                val inserted = repository.addManualTransaction(trans)
                _transactionRecorded.tryEmit(TransactionRecorded(inserted))
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
            }
        }
    }

    fun deleteTransaction(transaction: FinancialTransaction) {
        viewModelScope.launch {
            try {
                repository.deleteTransaction(transaction)
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
            }
        }
    }

    fun updateTransaction(transaction: FinancialTransaction) {
        viewModelScope.launch {
            try {
                repository.updateTransaction(transaction)
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
            }
        }
    }

    fun generateAiAuditReport() {
        viewModelScope.launch {
            _isAuditLoading.value = true
            try {
                val currentTrans = transactions.value
                val inc = totalIncome.value
                val exp = totalExpense.value
                val report = repository.generateAuditReport(currentTrans, inc, exp)
                _auditReport.value = report
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
                _auditReport.value = "Gagal memuat laporan, silakan coba lagi."
            } finally {
                _isAuditLoading.value = false
            }
        }
    }

    fun dismissAuditReport() {
        _auditReport.value = null
    }

    fun generateMonthlyAnalysis() {
        viewModelScope.launch {
            _isMonthlyLoading.value = true
            try {
                _monthlyReport.value = repository.generateMonthlyAnalysis(transactions.value)
            } catch (e: Exception) {
                Log.w("MainViewModel", "Analisis bulanan gagal", e)
                _monthlyReport.value = "Gagal memuat analisis, silakan coba lagi."
            } finally {
                _isMonthlyLoading.value = false
            }
        }
    }

    fun dismissMonthlyReport() {
        _monthlyReport.value = null
    }

    fun clearAllData() {
        viewModelScope.launch {
            try {
                ImageFileUtil.deleteAllAttachments(getApplication())
                repository.clearAllData()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Operasi gagal", e)
            }
        }
    }

    /** Hapus data lokal + lampiran saja (cloud dibiarkan) — saat pindah workspace. */
    fun clearLocalData() {
        viewModelScope.launch {
            try {
                ImageFileUtil.deleteAllAttachments(getApplication())
                repository.clearLocalData()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Bersihkan lokal gagal", e)
            }
        }
    }

    /**
     * Logout lengkap: hentikan sinkronisasi, hapus data lokal + lampiran + cloud,
     * lalu panggil [onComplete] (biasanya sign-out Google & reset UI) SETELAH semua
     * selesai. Urutan penting: cloud butuh auth masih aktif, jadi signOut dipanggil
     * di onComplete, bukan sebelum.
     */
    fun logoutAndDeleteAllData(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                // Urutan penting: cloud harus dibersihkan SELAGI familyId & auth masih
                // aktif; baru hentikan sync (yang me-reset familyId & matikan listener).
                ImageFileUtil.deleteAllAttachments(getApplication())
                repository.clearAllData()
                repository.stopCloudSync()
            } catch (e: Exception) {
                Log.w("MainViewModel", "Logout & hapus data gagal", e)
            } finally {
                onComplete()
            }
        }
    }

    // ---------- Export & Backup ----------

    /** CSV rekap keuangan (transaksi + riwayat chat) untuk diekspor. */
    fun exportRecapCsv(): String =
        DataExporter.buildRecapCsv(transactions.value, messages.value)

    /**
     * JSON backup lengkap untuk Google Drive. [familyId] = PIN workspace asal,
     * disimpan supaya restore lintas-workspace bisa dideteksi & dikonfirmasi (P1).
     */
    fun buildBackupJson(familyId: String? = null): String =
        DataExporter.buildBackupJson(transactions.value, messages.value, BuildConfig.VERSION_NAME, familyId)

    /** Parse backup (tanpa mengubah data). null kalau rusak / bukan Nyachat / format baru /
     *  backup terenkripsi tanpa passphrase yang benar. */
    fun parseRestore(json: String, passphrase: String? = null): BackupData? =
        DataExporter.parseBackupJson(json, passphrase)

    /** Terapkan hasil parse backup ke data lokal + cloud. Return true kalau berhasil. */
    suspend fun restoreParsedBackup(data: BackupData): Boolean =
        try {
            repository.restoreBackup(data.messages, data.transactions)
            true
        } catch (e: Exception) {
            Log.w("MainViewModel", "Restore gagal", e)
            false
        }
}
