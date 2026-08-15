package com.startupmini.nyachat.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.SetOptions
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.backup.DataExporter
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.ChatMessageDao
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.local.PendingOp
import com.startupmini.nyachat.data.local.PendingOpDao
import com.startupmini.nyachat.data.local.TransactionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Representasi dokumen Firestore (data tanpa id lokal Room). */
data class CloudMessage(
    val cloudId: String = "",
    val sender: String = "",
    val messageText: String = "",
    val timestamp: Long = 0L,
    // BUG-1 (P0): property Kotlin Boolean berawalan "is" menghasilkan getter JVM
    // `isFinancial()` — CustomClassMapper Firestore menurunkan nama field jadi
    // "financial" (strip prefix "is"), sehingga field cloud "isFinancial" TIDAK
    // pernah terbaca → toObject() selalu memberi false walau cloud menyimpan true.
    // Dampak: badge finansial hilang dari bubble ~5-10 detik setelah kirim pesan
    // (snapshot listener me-merge ulang dari cloud dengan isFinancial=false;
    // detectedAmount tetap tersimpan karena field-nya tidak kena masalah ini).
    // @get:PropertyName memaksa nama field eksplisit agar round-trip benar.
    @get:PropertyName("isFinancial")
    val isFinancial: Boolean = false,
    val detectedAmount: Double? = null,
    val detectedCategory: String? = null,
    val detectedType: String? = null,
    // r1.4.0 (audit Finance AI): jumlah transaksi dari pesan ini (badge
    // multi-transaksi tanpa netting).
    val detectedCount: Int? = null,
    // r1.4.0 (badge campuran): true jika pesan berisi PEMASUKAN DAN
    // PENGELUARAN sekaligus — badge pelangi di chat.
    val hasMixedTypes: Boolean? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val editedAt: Long? = null,
    // M7: asal deteksi — "AI" atau "HEURISTIK" (fallback offline).
    val detectedBy: String? = null,
    // M4: waktu terakhir ditulis di server Firestore. Tipe Timestamp (bukan Long)
    // karena serverTimestamp() tersimpan sebagai com.google.firebase.Timestamp di
    // cloud — Long? membuat toObject() crash dengan "Could not deserialize object".
    // Dikonversi ke millis (toMillis) saat disimpan ke Room.
    val serverUpdatedAt: com.google.firebase.Timestamp? = null
)

/** Representasi dokumen Firestore untuk transaksi. */
data class CloudTransaction(
    val cloudId: String = "",
    val type: String = "",
    val category: String = "",
    val amount: Double = 0.0,
    val description: String = "",
    val loggedBy: String = "",
    val timestamp: Long = 0L,
    val chatMessageId: Long? = null,
    val editedAt: Long? = null,
    val sourceMessageCloudId: String? = null, // Cross-device lookup key
    // M4: lihat CloudMessage — tipe Timestamp agar toObject() tidak crash;
    // dikonversi ke millis saat disimpan ke Room.
    val serverUpdatedAt: com.google.firebase.Timestamp? = null
)

/** Status sinkronisasi nyata untuk indikator UI (P2-16). */
enum class SyncStatus { SYNCED, SYNCING, OFFLINE, ERROR }

// P5: FamilyMember, JoinRequest, MembershipStatus, JoinRequestResult,
// OwnerSetupResult & seluruh alur keanggotaan pindah ke MembershipManager.kt.

/**
 * Sinkronisasi dua arah dengan Firestore:
 * - Tulis: setiap pesan/transaksi baru (dan hapus/clear) dipush ke cloud.
 * - Baca: snapshot listener realtime — perubahan dari perangkat lain langsung
 *   dimerge ke Room lokal (offline-first tetap jalan kalau gak ada internet).
 * Workspace diidentifikasi oleh PIN keluarga (document id di koleksi "families").
 */
object FirestoreSyncManager {

    private const val TAG = "FirestoreSync"
    private const val COLLECTION_FAMILIES = Constants.Collections.FAMILIES
    // Konstanta peran memakai MembershipManager.ROLE_* (satu sumber kebenaran — P4.5).
    /** Jenis operasi antrian pending (retry offline). */
    const val OP_SYNC_MESSAGE = "SYNC_MESSAGE"
    const val OP_DELETE_MESSAGE = "DELETE_MESSAGE"
    const val OP_SYNC_TRANSACTION = "SYNC_TRANSACTION"
    const val OP_DELETE_TRANSACTION = "DELETE_TRANSACTION"
    const val OP_CLEAR_FAMILY = "CLEAR_FAMILY"
    /** Delay awal untuk retry — berlipat dua setiap percobaan (max 32 detik). */
    private const val MIN_RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRY_DELAY_MS = 32_000L
    @Volatile private var familyId: String = ""
    @Volatile private var role: String = MembershipManager.ROLE_MEMBER
    @Volatile private var chatDao: ChatMessageDao? = null
    @Volatile private var transDao: TransactionDao? = null
    @Volatile private var pendingDao: PendingOpDao? = null
    @Volatile private var messagesListener: ListenerRegistration? = null
    @Volatile private var transactionsListener: ListenerRegistration? = null
    // BUG-06 lanjutan (P0): status jaringan dari NetworkMonitor. null = belum
    // diketahui (app baru mulai / callback belum menembak) → perilaku lama.
    @Volatile private var networkAvailable: Boolean? = null
    /** Status sinkronisasi yang jujur untuk indikator UI (P2-16). */
    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    /** Waktu terakhir sinkron berhasil (FASE 3 item 3.8) — banner Rekap menampilkan
     *  "Tersinkron · HH:mm". null = belum pernah sinkron di sesi ini. */
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()
    /** Event sekali-jalan saat koneksi pulih (OFFLINE/ERROR → SYNCED) — UI
     *  menampilkan Snackbar singkat supaya user tahu status merah/kuning sudah
     *  selesai dan data kembali sinkron (audit #6). */
    private val _recoveryEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recoveryEvents: SharedFlow<String> = _recoveryEvents.asSharedFlow()
    /** Sinyal "ada op baru di antrian" — drain tidur di sini (bukan polling 2s). */
    private val opsSignal = Channel<Unit>(Channel.CONFLATED)
    /** Listener dijeda saat app di background (P2-12) — hemat baterai/kuota. */
    @Volatile private var paused = false
    /** Scope tunggal utk retry & merge hasil listener — dibatalkan di stop(). */
    @Volatile private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Cek apakah sudah login dengan akun Google (wajib sebelum sync).
     * Login Google dilakukan dari UI (PinConnectScreen) via Credential Manager —
     * di sini kita tidak pernah login anonim lagi.
     */
    fun isSignedIn(): Boolean = FirebaseAuth.getInstance().currentUser != null

    /**
     * Peran berubah di tengah sesi (P1-1 audit keanggotaan) — dipanggil saat
     * snapshot members mendeteksi role diri sendiri berubah (di-demote/promote
     * oleh owner lain). Menjaga keputusan owner-only konsisten dengan peran baru.
     */
    fun setRole(role: String) {
        this.role = role
    }

    /**
     * Pasang DAO antrian pending sejak awal (dipanggil saat repository dibuat),
     * supaya operasi yang dikirim SEBELUM start() selesai (familyId masih kosong)
     * tetap bisa di-antri — tidak hilang.
     */
    fun setPendingOpDao(dao: PendingOpDao) {
        pendingDao = dao
    }

    /** Aktifkan sinkronisasi untuk workspace PIN tertentu. */
    fun start(pin: String, role: String = MembershipManager.ROLE_MEMBER, chatMessageDao: ChatMessageDao, transactionDao: TransactionDao, pendingOpDao: PendingOpDao) {
        stop()
        paused = false
        familyId = pin
        this.role = role
        chatDao = chatMessageDao
        transDao = transactionDao
        pendingDao = pendingOpDao
        ensureFamilyDoc()
        listenMessages()
        listenTransactions()
        startPendingDrain()
        // P4-1: PIN adalah password bersama workspace — jangan pernah dicatat
        // apa adanya ke log (meski Log.d sudah di-strip R8 di build release).
        Log.d(TAG, "Cloud sync aktif untuk keluarga: ${redactSecret(pin)} (role=$role)")
    }

    /** Hentikan semua listener + batalkan retry/merge + reset state (saat logout). */
    fun stop() {
        paused = false
        // Listener & state keanggotaan ditangani MembershipManager (P5) — reset
        // daftar anggota di sana supaya login ke workspace berbeda tidak
        // menampilkan anggota workspace lama (P4-1).
        MembershipManager.stop()
        messagesListener?.remove(); messagesListener = null
        transactionsListener?.remove(); transactionsListener = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        familyId = ""
        role = MembershipManager.ROLE_MEMBER
        chatDao = null
        transDao = null
        pendingDao = null
        // Jangan biarkan status lama (ERROR/OFFLINE) menempel di singleton setelah
        // logout — kalau tidak, workspace berikutnya sempat menampilkan indikator
        // sinkronisasi yang salah (P2-16).
        _syncStatus.value = SyncStatus.SYNCED
        _lastSyncedAt.value = null
    }

    /**
     * Jeda listener realtime saat app masuk background (P2-12) — data tetap utuh
     * di cache lokal; saat resume, listener dipasang ulang & menerima snapshot baru.
     */
    fun pauseListeners() {
        if (paused) return
        paused = true
        messagesListener?.remove(); messagesListener = null
        transactionsListener?.remove(); transactionsListener = null
    }

    /** Aktifkan kembali listener saat app kembali ke foreground. */
    fun resumeListeners() {
        if (!paused) return
        paused = false
        if (familyId.isEmpty() || chatDao == null) return
        listenMessages()
        listenTransactions()
    }

    /**
     * Catat kepemilikan workspace di dokumen keluarga (id = PIN) dan pastikan
     * member doc diri sendiri ada (bootstrap & migrasi workspace lama).
     * Hanya PEMILIK (yang membuat PIN) yang menulis ownerId — anggota tidak
     * pernah menimpa. Fondasi model otorisasi: dokumen mencatat siapa pembuat
     * workspace, dan keanggotaan di-enforce oleh rules Firestore.
     */
    private fun ensureFamilyDoc() {
        if (role != MembershipManager.ROLE_OWNER) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        scope.launch {
            runCatching {
                val famRef = db().collection(COLLECTION_FAMILIES).document(familyId)
                famRef.set(
                    mapOf(
                        Constants.Fields.OWNER_ID to uid,
                        Constants.Fields.CREATED_AT to FieldValue.serverTimestamp(),
                        // r1.6.0: default nama & plan workspace baru. SetOptions.merge()
                        // → workspace lama tidak tertimpa.
                        Constants.Fields.NAME to Constants.Defaults.FAMILY_NAME,
                        Constants.Fields.PLAN to Constants.Plans.FREE
                    ),
                    SetOptions.merge()
                ).await()
                // Satu implementasi member doc (deduplikasi P3 audit keanggotaan) —
                // hidup di MembershipManager.
                MembershipManager.ensureSelfMemberDoc(famRef, uid, MembershipManager.ROLE_OWNER)
            }.onFailure { Log.w(TAG, "Catat ownerId/member gagal: ${it.message}") }
        }
    }

    /**
     * Masking nilai rahasia untuk log — tampilkan 2 karakter pertama + terakhir
     * saja supaya jejak tetap bisa dibedakan tanpa membocorkan nilai penuh.
     */
    private fun redactSecret(secret: String): String =
        if (secret.length <= 4) "****" else secret.take(2) + "••••" + secret.takeLast(2)

    private fun db() = FirebaseFirestore.getInstance()

    /** True bila error = PERMISSION_DENIED (member dihapus / kehilangan akses
     *  workspace) — operasi tidak akan pernah sukses lagi (P2-2 audit keanggotaan). */
    private fun isPermissionDenied(e: Throwable): Boolean =
        e is com.google.firebase.firestore.FirebaseFirestoreException &&
            e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED

    private fun messagesRef() =
        db().collection(Constants.Collections.FAMILIES).document(familyId).collection(Constants.Collections.MESSAGES)

    private fun transactionsRef() =
        db().collection(Constants.Collections.FAMILIES).document(familyId).collection(Constants.Collections.TRANSACTIONS)

    // ---------- Baca: snapshot listener realtime ----------

    private fun listenMessages(retryDelayMs: Long = MIN_RETRY_DELAY_MS) {
        messagesListener = messagesRef().addSnapshotListener { snapshot, error ->
            if (paused) return@addSnapshotListener // app di background
            if (error != null) {
                Log.w(TAG, "Listen messages gagal: ${error.message}. Retry dalam ${retryDelayMs / 1000}s...")
                // Belum jadi anggota → ditolak rules; jangan retry selamanya.
                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w(TAG, "Listen messages: PERMISSION_DENIED (bukan anggota) — berhenti.")
                    messagesListener?.remove(); messagesListener = null
                    return@addSnapshotListener
                }
                onSyncFailure(error)
                messagesListener?.remove()
                messagesListener = null
                scope.launch {
                    retryWithBackoff(
                        label = "messages",
                        delayMs = retryDelayMs,
                        action = { listenMessages((retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)) }
                    )
                }
                return@addSnapshotListener
            }
            snapshot ?: return@addSnapshotListener
            markSynced()
            scope.launch {
                val dao = chatDao ?: return@launch
                for (change in snapshot.documentChanges) {
                    try {
                        // M4: serverUpdatedAt tersimpan sebagai Timestamp di cloud —
                        // toObject langsung memetakannya (tipe DTO = Timestamp).
                        // toObject di dalam try/catch: skema yang tak dikenal dari
                        // data lama/backup tidak boleh mematikan proses (crash).
                        val cloud = change.document.toObject(CloudMessage::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED ->
                                upsertMessage(dao, cloud)
                            DocumentChange.Type.REMOVED -> dao.deleteByCloudId(cloud.cloudId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Merge pesan gagal: ${e.message}")
                    }
                }
            }
        }
    }

    private fun listenTransactions(retryDelayMs: Long = MIN_RETRY_DELAY_MS) {
        transactionsListener = transactionsRef().addSnapshotListener { snapshot, error ->
            if (paused) return@addSnapshotListener // app di background
            if (error != null) {
                Log.w(TAG, "Listen transactions gagal: ${error.message}. Retry dalam ${retryDelayMs / 1000}s...")
                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w(TAG, "Listen transactions: PERMISSION_DENIED (bukan anggota) — berhenti.")
                    transactionsListener?.remove(); transactionsListener = null
                    return@addSnapshotListener
                }
                onSyncFailure(error)
                transactionsListener?.remove()
                transactionsListener = null
                scope.launch {
                    retryWithBackoff(
                        label = "transactions",
                        delayMs = retryDelayMs,
                        action = { listenTransactions((retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)) }
                    )
                }
                return@addSnapshotListener
            }
            snapshot ?: return@addSnapshotListener
            markSynced()
            scope.launch {
                val dao = transDao ?: return@launch
                for (change in snapshot.documentChanges) {
                    try {
                        // M4: serverUpdatedAt tersimpan sebagai Timestamp di cloud —
                        // toObject langsung memetakannya (tipe DTO = Timestamp).
                        // toObject di dalam try/catch: skema tak dikenal tidak boleh
                        // mematikan proses (crash) — log & lanjutkan.
                        val cloud = change.document.toObject(CloudTransaction::class.java)
                        when (change.type) {
                            DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED ->
                                upsertTransaction(dao, cloud)
                            DocumentChange.Type.REMOVED -> dao.deleteByCloudId(cloud.cloudId)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Merge transaksi gagal: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Menunggu dengan exponential backoff sebelum memanggil ulang [action].
     * Setiap pemanggil meneruskan delayMs yang sudah berlipat dua, sehingga
     * urutan delay: 1s → 2s → 4s → 8s → 16s → 32s (cap).
     * Berhenti otomatis jika familyId kosong (listener sudah di-stop via logout).
     */
    private suspend fun retryWithBackoff(label: String, delayMs: Long = MIN_RETRY_DELAY_MS, action: () -> Unit) {
        if (familyId.isEmpty() || paused) return // logout / app di background
        Log.d(TAG, "[$label] Retry dalam ${delayMs / 1000}s (next: ${(delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS) / 1000}s)...")
        delay(delayMs)
        if (familyId.isEmpty() || paused) return
        action()
    }

    /**
     * Waktu efektif untuk resolusi konflik: saat terakhir diedit, atau waktu
     * dibuat kalau belum pernah diedit. Dipakai upsert supaya edit bersamaan
     * di dua perangkat dimenangkan penulis terakhir berbasis WAKTU — bukan
     * urutan tibanya snapshot listener.
     */
    internal fun effectiveSortTime(editedAt: Long?, timestamp: Long): Long = editedAt ?: timestamp

    /**
     * M4 — tie-break deterministik berbasis waktu SERVER.
     *
     * Klien menulis `serverUpdatedAt = FieldValue.serverTimestamp()` setiap sync,
     * sehingga perbandingan konflik tidak lagi bergantung murni pada jam lokal
     * (yang bisa selisih antar-perangkat → edit "baru" bisa tampak "tua").
     * Aturan:
     *  1) Dua-duanya punya serverUpdatedAt  → bandingkan waktu server (menang yang
     *     akhir ditulis ke server; sama-sama → terima cloud agar konvergen).
     *  2) Minimal satu sisi belum punya (data lama / belum pernah sync) → fallback
     *     ke waktu lokal (perilaku lama, kompatibel dengan data migrasi v10-).
     */
    internal fun cloudIsNewer(
        existingEditedAt: Long?,
        existingTimestamp: Long,
        existingServerUpdatedAt: Long?,
        cloudEditedAt: Long?,
        cloudTimestamp: Long,
        cloudServerUpdatedAt: Long?
    ): Boolean {
        val localServer = existingServerUpdatedAt
        val cloudServer = cloudServerUpdatedAt
        if (localServer != null && cloudServer != null) {
            if (cloudServer < localServer) return false
            if (cloudServer > localServer) return true
            // Server time sama → pemutus deterministik ke waktu efektif (terima
            // cloud bila tidak lebih tua) supaya dua perangkat berakhir konsisten.
            return effectiveSortTime(cloudEditedAt, cloudTimestamp) >=
                effectiveSortTime(existingEditedAt, existingTimestamp)
        }
        return effectiveSortTime(cloudEditedAt, cloudTimestamp) >=
            effectiveSortTime(existingEditedAt, existingTimestamp)
    }

    internal fun cloudIsNewer(existing: ChatMessage, c: CloudMessage): Boolean =
        cloudIsNewer(
            existing.editedAt, existing.timestamp, existing.serverUpdatedAt,
            c.editedAt, c.timestamp, c.serverUpdatedAt.toMillis()
        )

    internal fun cloudIsNewer(existing: FinancialTransaction, c: CloudTransaction): Boolean =
        cloudIsNewer(
            existing.editedAt, existing.timestamp, existing.serverUpdatedAt,
            c.editedAt, c.timestamp, c.serverUpdatedAt.toMillis()
        )

    /** Konversi Timestamp Firestore → millis epoch (null aman). */
    internal fun com.google.firebase.Timestamp?.toMillis(): Long? = this?.toDate()?.time

    internal suspend fun upsertMessage(dao: ChatMessageDao, c: CloudMessage) {
        val existing = dao.getByCloudId(c.cloudId)
        if (existing != null) {
            // Last-writer-by-time: snapshot listener bisa tiba dalam urutan apa
            // pun, jadi versi cloud yang lebih tua tidak boleh menimpa edit
            // lokal yang lebih baru. M4: kalau kedua sisi sudah punya waktu SERVER
            // (serverUpdatedAt), bandingkan itu dulu — imun terhadap selisih jam
            // antar-perangkat; kalau belum (data lama), jatuh ke waktu lokal.
            if (!cloudIsNewer(existing, c)) return
        }
val local = if (existing != null) {
            ChatMessage(
                id = existing.id,
                sender = c.sender,
                messageText = c.messageText,
                timestamp = c.timestamp,
                isFinancial = c.isFinancial,
                detectedAmount = c.detectedAmount,
                detectedCategory = c.detectedCategory,
                detectedType = c.detectedType,
                detectedCount = c.detectedCount,
                hasMixedTypes = c.hasMixedTypes,
                replyToSender = c.replyToSender,
                replyToText = c.replyToText,
                editedAt = c.editedAt,
                detectedBy = c.detectedBy,
                serverUpdatedAt = c.serverUpdatedAt.toMillis(),
                // Lampiran (imagePath/filePath/fileName) TIDAK dikirim ke cloud —
                // file hanya ada di perangkat yang mengirimnya. Pertahankan path
                // lokal supaya bubble foto nota/dokumen tidak hilang saat listener
                // Firestore mem-merge dokumen yang sama (mis. setelah restore).
                imagePath = existing.imagePath,
                filePath = existing.filePath,
                fileName = existing.fileName,
                cloudId = c.cloudId
            )
        } else {
            ChatMessage(
                sender = c.sender,
                messageText = c.messageText,
                timestamp = c.timestamp,
                isFinancial = c.isFinancial,
                detectedAmount = c.detectedAmount,
                detectedCategory = c.detectedCategory,
                detectedType = c.detectedType,
                detectedCount = c.detectedCount,
                hasMixedTypes = c.hasMixedTypes,
                replyToSender = c.replyToSender,
                replyToText = c.replyToText,
                editedAt = c.editedAt,
                detectedBy = c.detectedBy,
                serverUpdatedAt = c.serverUpdatedAt.toMillis(),
                cloudId = c.cloudId
            )
        }
        dao.insertMessage(local)
    }

    internal suspend fun upsertTransaction(dao: TransactionDao, c: CloudTransaction) {
        val existing = dao.getByCloudId(c.cloudId)
        if (existing != null) {
            // Sama dengan upsertMessage — konflik edit transaksi dua perangkat
            // diselesaikan berbasis waktu edit, bukan urutan listener. M4: waktu
            // server (serverUpdatedAt) diprioritaskan bila tersedia di dua sisi.
            if (!cloudIsNewer(existing, c)) return
        }
        val local = if (existing != null) {
            FinancialTransaction(
                id = existing.id,
                type = c.type,
                category = c.category,
                amount = c.amount,
                description = c.description,
                loggedBy = c.loggedBy,
                timestamp = c.timestamp,
                editedAt = c.editedAt,
                chatMessageId = c.chatMessageId,
                cloudId = c.cloudId,
                sourceMessageCloudId = c.sourceMessageCloudId,
                serverUpdatedAt = c.serverUpdatedAt.toMillis()
            )
        } else {
            FinancialTransaction(
                type = c.type,
                category = c.category,
                amount = c.amount,
                description = c.description,
                loggedBy = c.loggedBy,
                timestamp = c.timestamp,
                editedAt = c.editedAt,
                chatMessageId = c.chatMessageId,
                cloudId = c.cloudId,
                sourceMessageCloudId = c.sourceMessageCloudId,
                serverUpdatedAt = c.serverUpdatedAt.toMillis()
            )
        }
        dao.insertTransaction(local)
    }

    // ---------- Tulis: push perubahan lokal ke cloud ----------

    /** Guard: jangan push/hapus apapun setelah logout (familyId kosong) atau tak login. */
    private fun canSync(): Boolean = familyId.isNotEmpty() && isSignedIn()

    // ---------- Tulis: push perubahan lokal ke cloud (dengan antrian retry) ----------
    //
    // Semua metode *public* di bawah ini bersifat "queue-aware": mencoba kirim
    // langsung, dan kalau gagal / workspace belum siap, operasi disimpan sebagai
    // pending op di Room. Antrian dikuras (drain) oleh startPendingDrain() dengan
    // exponential backoff selama app hidup, dan diproses lagi saat workspace yang
    // sama diaktifkan berikutnya — sehingga pesan/transaksi TIDAK hilang walau
    // app ditutup saat offline.

    suspend fun syncMessage(message: ChatMessage) {
        val cid = message.cloudId?.takeIf { it.isNotBlank() } ?: return
        if (canSync()) {
            val sent = try {
                syncMessageNow(message)
            } catch (e: Exception) {
                // P2-2: bukan anggota lagi (di-kick) — op tidak akan pernah sukses;
                // buang supaya tidak di-retry selamanya. Non-PD sudah ditelan
                // syncMessageNow (kembali false → di-antri).
                if (isPermissionDenied(e)) {
                    Log.w(TAG, "Sync pesan dibuang: PERMISSION_DENIED (bukan anggota lagi?)")
                    return
                }
                Log.w(TAG, "Sync pesan gagal: ${e.message}")
                onSyncFailure(e)
                false
            }
            if (sent) return
        }
        enqueueOp(OP_SYNC_MESSAGE, DataExporter.messageToJson(message).toString())
    }

    private suspend fun syncMessageNow(message: ChatMessage): Boolean {
        val cid = message.cloudId?.takeIf { it.isNotBlank() } ?: return false
        return try {
            // Firestore menolak nilai null di dalam map set() — filter dulu.
            // Nama field memakai Constants.Fields.* (kontrak cloud — nilai TIDAK
            // boleh berubah: data lintas perangkat & backup lama bergantung padanya;
            // dijaga ConstantsTest).
            messagesRef().document(cid).set(
                nonNullMap(
                    Constants.Fields.CLOUD_ID to cid,
                    Constants.Fields.SENDER to message.sender,
                    Constants.Fields.MESSAGE_TEXT to message.messageText,
                    Constants.Fields.TIMESTAMP to message.timestamp,
                    Constants.Fields.IS_FINANCIAL to message.isFinancial,
                    Constants.Fields.DETECTED_AMOUNT to message.detectedAmount,
                    Constants.Fields.DETECTED_CATEGORY to message.detectedCategory,
                    Constants.Fields.DETECTED_TYPE to message.detectedType,
                    Constants.Fields.DETECTED_COUNT to message.detectedCount,
                    Constants.Fields.HAS_MIXED_TYPES to message.hasMixedTypes,
                    Constants.Fields.REPLY_TO_SENDER to message.replyToSender,
                    Constants.Fields.REPLY_TO_TEXT to message.replyToText,
                    Constants.Fields.EDITED_AT to message.editedAt,
                    Constants.Fields.DETECTED_BY to message.detectedBy,
                    // M4: penanda waktu SERVER — sampai di sini setiap perubahan
                    // di-push, sehingga konflik di-resolve pakai jam Firestore
                    // (deterministik) bukan jam perangkat.
                    Constants.Fields.SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                )
            ).await()
            true
        } catch (e: Exception) {
            // P2-2: biarkan PERMISSION_DENIED mengalir ke pemanggil (dibuang di
            // syncMessage) — jangan diantri & di-retry tanpa batas.
            if (isPermissionDenied(e)) throw e
            Log.w(TAG, "Sync pesan gagal: ${e.message}")
            onSyncFailure(e)
            false
        }
    }

    suspend fun deleteMessage(cloudId: String) {
        if (cloudId.isBlank()) return
        if (canSync()) {
            val done = try {
                deleteMessageNow(cloudId)
            } catch (e: Exception) {
                if (isPermissionDenied(e)) {
                    Log.w(TAG, "Hapus pesan cloud dibuang: PERMISSION_DENIED (bukan anggota lagi?)")
                    return
                }
                Log.w(TAG, "Hapus pesan cloud gagal: ${e.message}")
                onSyncFailure(e)
                false
            }
            if (done) return
        }
        enqueueOp(OP_DELETE_MESSAGE, JSONObject().put(Constants.Fields.CLOUD_ID, cloudId).toString())
    }

    private suspend fun deleteMessageNow(cloudId: String): Boolean = try {
        messagesRef().document(cloudId).delete().await()
        true
    } catch (e: Exception) {
        if (isPermissionDenied(e)) throw e
        Log.w(TAG, "Hapus pesan cloud gagal: ${e.message}")
        onSyncFailure(e)
        false
    }

    suspend fun syncTransaction(transaction: FinancialTransaction) {
        val cid = transaction.cloudId?.takeIf { it.isNotBlank() } ?: return
        if (canSync()) {
            val sent = try {
                syncTransactionNow(transaction)
            } catch (e: Exception) {
                if (isPermissionDenied(e)) {
                    Log.w(TAG, "Sync transaksi dibuang: PERMISSION_DENIED (bukan anggota lagi?)")
                    return
                }
                Log.w(TAG, "Sync transaksi gagal: ${e.message}")
                onSyncFailure(e)
                false
            }
            if (sent) return
        }
        enqueueOp(OP_SYNC_TRANSACTION, DataExporter.transactionToJson(transaction).toString())
    }

    private suspend fun syncTransactionNow(transaction: FinancialTransaction): Boolean {
        val cid = transaction.cloudId?.takeIf { it.isNotBlank() } ?: return false
        return try {
            transactionsRef().document(cid).set(
                nonNullMap(
                    Constants.Fields.CLOUD_ID to cid,
                    Constants.Fields.TYPE to transaction.type,
                    Constants.Fields.CATEGORY to transaction.category,
                    Constants.Fields.AMOUNT to transaction.amount,
                    Constants.Fields.DESCRIPTION to transaction.description,
                    Constants.Fields.LOGGED_BY to transaction.loggedBy,
                    Constants.Fields.TIMESTAMP to transaction.timestamp,
                    Constants.Fields.EDITED_AT to transaction.editedAt,
                    Constants.Fields.CHAT_MESSAGE_ID to transaction.chatMessageId,
                    Constants.Fields.SOURCE_MESSAGE_CLOUD_ID to transaction.sourceMessageCloudId,
                    // M4: penanda waktu SERVER — resolusi konflik deterministik lintas
                    // perangkat tanpa bergantung kalibrasi jam lokal.
                    Constants.Fields.SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                )
            ).await()
            true
        } catch (e: Exception) {
            if (isPermissionDenied(e)) throw e
            Log.w(TAG, "Sync transaksi gagal: ${e.message}")
            onSyncFailure(e)
            false
        }
    }

    /** Bangun map Firestore tanpa kunci bernilai null (null membuat set() error). */
    private fun nonNullMap(vararg pairs: Pair<String, Any?>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        pairs.forEach { (k, v) -> if (v != null) result[k] = v }
        return result
    }

    suspend fun deleteTransaction(cloudId: String) {
        if (cloudId.isBlank()) return
        if (canSync()) {
            val done = try {
                deleteTransactionNow(cloudId)
            } catch (e: Exception) {
                if (isPermissionDenied(e)) {
                    Log.w(TAG, "Hapus transaksi cloud dibuang: PERMISSION_DENIED (bukan anggota lagi?)")
                    return
                }
                Log.w(TAG, "Hapus transaksi cloud gagal: ${e.message}")
                onSyncFailure(e)
                false
            }
            if (done) return
        }
        enqueueOp(OP_DELETE_TRANSACTION, JSONObject().put(Constants.Fields.CLOUD_ID, cloudId).toString())
    }

    private suspend fun deleteTransactionNow(cloudId: String): Boolean = try {
        transactionsRef().document(cloudId).delete().await()
        true
    } catch (e: Exception) {
        if (isPermissionDenied(e)) throw e
        Log.w(TAG, "Hapus transaksi cloud gagal: ${e.message}")
        onSyncFailure(e)
        false
    }

    /** Hapus semua data workspace keluarga dari cloud. */
    suspend fun clearFamilyData() {
        if (canSync()) {
            val done = try {
                clearFamilyDataNow()
            } catch (e: Exception) {
                if (isPermissionDenied(e)) {
                    Log.w(TAG, "Bersihkan cloud dibuang: PERMISSION_DENIED (bukan anggota lagi?)")
                    return
                }
                Log.w(TAG, "Bersihkan cloud gagal: ${e.message}")
                onSyncFailure(e)
                false
            }
            if (done) return
        }
        enqueueOp(OP_CLEAR_FAMILY, JSONObject().toString())
    }

    /**
     * Hapus seluruh data cloud pakai WriteBatch (P2-9) — jauh lebih cepat & andal
     * daripada delete satu-per-satu (yang bisa berhenti di tengah & kena rate limit).
     */
    private suspend fun clearFamilyDataNow(): Boolean = try {
        deleteCollectionInBatches(messagesRef())
        deleteCollectionInBatches(transactionsRef())
        true
    } catch (e: Exception) {
        if (isPermissionDenied(e)) throw e
        Log.w(TAG, "Bersihkan cloud gagal: ${e.message}")
        onSyncFailure(e)
        false
    }

    /**
     * Hapus semua dokumen sebuah koleksi, per batch maks 400 (batas WriteBatch).
     * Query dibatasi per halaman ([com.google.firebase.firestore.Query.limit])
     * dan diulang sampai koleksi kosong — get() tunggal tanpa pagination bisa
     * terpangkas batas dokumen sehingga koleksi besar tidak terhapus semua.
     * Guard [maxPages] mencegah loop tak berujung bila commit terus gagal.
     */
    private suspend fun deleteCollectionInBatches(
        ref: com.google.firebase.firestore.CollectionReference,
        maxPages: Int = 1_000
    ) {
        var pages = 0
        while (pages < maxPages) {
            val docs = ref.limit(500).get().await().documents
            if (docs.isEmpty()) break
            docs.chunked(400).forEach { chunk ->
                val batch = db().batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            pages++
        }
        if (pages >= maxPages) {
            Log.w(TAG, "Hapus koleksi ${ref.path} berhenti di batas halaman ($maxPages) — mungkin belum bersih semua")
        }
    }

    /**
     * Hapus dokumen cloud yang TIDAK ada di backup — dipanggil restoreBackup
     * SEBELUM push isi backup. Tanpa ini, dokumen lama bertahan di cloud dan
     * muncul lagi di perangkat lain walau restore seharusnya mengganti seluruh
     * data lokal + cloud.
     */
    suspend fun deleteAbsentFromBackup(
        keptMessageCloudIds: Set<String>,
        keptTransactionCloudIds: Set<String>
    ) {
        if (!canSync()) return
        try {
            deleteDocsAbsentFrom(messagesRef(), keptMessageCloudIds)
            deleteDocsAbsentFrom(transactionsRef(), keptTransactionCloudIds)
        } catch (e: Exception) {
            Log.w(TAG, "Bersihkan dokumen cloud di luar backup gagal: ${e.message}")
            onSyncFailure(e)
        }
    }

    /** Hapus dokumen sebuah koleksi yang id-nya tidak termasuk isi backup, per batch 400. */
    private suspend fun deleteDocsAbsentFrom(
        ref: com.google.firebase.firestore.CollectionReference,
        keptCloudIds: Set<String>
    ) {
        val docIds = ref.get().await().documents.map { it.id }
        idsAbsentFromBackup(docIds, keptCloudIds).chunked(400).forEach { chunk ->
            val batch = db().batch()
            chunk.forEach { id -> batch.delete(ref.document(id)) }
            batch.commit().await()
        }
    }

    /** Id dokumen cloud yang tidak termasuk isi backup — murni, mudah di-unit-test. */
    internal fun idsAbsentFromBackup(cloudDocIds: Collection<String>, keptCloudIds: Set<String>): List<String> =
        cloudDocIds.filterNot { it in keptCloudIds }

    // ---------- Antrian pending: simpan & kuras operasi yang gagal ----------

    private suspend fun enqueueOp(opType: String, payload: String) {
        val opDao = pendingDao ?: return
        runCatching { opDao.insert(PendingOp(opType = opType, payload = payload)) }
            .onFailure { Log.w(TAG, "Simpan pending op gagal: ${it.message}") }
        // Bangunkan drain yang sedang tidur (P2-12: tanpa polling tiap 2 detik).
        opsSignal.trySend(Unit)
    }

    /**
     * Klasifikasi kegagalan sinkronisasi untuk indikator UI (BUG-06, audit UX):
     * OFFLINE saat koneksi putus, ERROR untuk kegagalan lainnya. Sebelumnya error
     * offline di lapisan bawah (socket/IO, "Failed to resolve", timeout) jatuh ke
     * ERROR sehingga user offline melihat label merah "Gagal sinkron" yang
     * menakutkan padahal itu kondisi normal.
     *
     * Prioritas (error nyata tidak boleh dikira offline):
     *  1) Kode Firestore EKSPLISIT non-jaringan — PERMISSION_DENIED, UNAUTHENTICATED,
     *     RESOURCE_EXHAUSTED (kuota), ABORTED, dll. → ERROR. Cek kode dulu supaya
     *     isi teks pesan yang kebetulan mirip (mis. "network") tidak menyesatkan.
     *  2) UNAVAILABLE / DEADLINE_EXCEEDED (timeout) → OFFLINE.
     *  3) Tanpa kode (exception lapisan bawah): teks offline + penyebab IOException
     *     (termasuk rantai nested cause) → OFFLINE; selain itu ERROR.
     */
    internal fun classifySyncFailure(e: Throwable): SyncStatus {
        val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
        return classifySyncFailure(
            networkCode = code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE ||
                code == com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            hasExplicitCode = code != null,
            message = e.message,
            cause = e.cause
        )
    }

    /**
     * Overload murni — keputusan tanpa enum/objek Firebase. Unit test JVM tidak
     * bisa mereferensikan [FirebaseFirestoreException.Code] karena static-init-nya
     * memakai android.util.SparseArray (tidak di-mock di luar Robolectric), jadi
     * jalur test memasok hasil ekstraksi [code] sebagai boolean.
     *
     * @param networkCode true bila kode Firestore UNAVAILABLE/DEADLINE_EXCEEDED
     *   (koneksi putus / timeout).
     * @param hasExplicitCode true bila Firestore memberi kode error eksplisit.
     *   Kode non-jaringan (PERMISSION_DENIED, kuota, dll.) → ERROR, apapun teksnya.
     */
    internal fun classifySyncFailure(
        networkCode: Boolean,
        hasExplicitCode: Boolean,
        message: String?,
        cause: Throwable?
    ): SyncStatus {
        // Kode non-jaringan eksplisit → ERROR: error nyata tidak boleh dikira
        // offline walau isi teks pesan kebetulan mirip (mis. "network").
        if (hasExplicitCode && !networkCode) return SyncStatus.ERROR

        val msg = message?.lowercase() ?: ""
        if (msg.contains("offline") ||
            msg.contains("failed to resolve") ||
            msg.contains("unable to resolve") ||
            msg.contains("timed out") ||
            msg.contains("timeout") ||
            msg.contains("network") ||
            msg.contains("unreachable")
        ) {
            return SyncStatus.OFFLINE
        }

        // Firestore membungkus kegagalan socket/SSL sebagai cause — telusuri rantainya.
        var c: Throwable? = cause
        while (c != null) {
            if (c is java.io.IOException) return SyncStatus.OFFLINE
            c = c.cause
        }
        return if (networkCode) SyncStatus.OFFLINE else SyncStatus.ERROR
    }

    /** Status error untuk indikator UI: OFFLINE saat koneksi putus, ERROR lainnya. */
    private fun onSyncFailure(e: Exception) {
        _syncStatus.value = classifySyncFailure(e)
    }

    /**
     * BUG-06 lanjutan (P0): status jaringan berubah (dipanggil NetworkMonitor).
     * Jaringan hilang → OFFLINE (walau cache Firestore masih bisa memenuhi
     * snapshot — markSynced tidak boleh mengembalikan ke SYNCED). Jaringan pulih
     * → SYNCING sampai snapshot nyata mengonfirmasi (markSynced → SYNCED + event
     * pemulihan). null → perilaku lama (tanpa deteksi jaringan).
     */
    fun setNetworkAvailable(available: Boolean) {
        networkAvailable = available
        _syncStatus.value = resolveStatusOnNetworkChange(_syncStatus.value, available)
    }

    /**
     * Status indikator saat jaringan berubah (BUG-06 lanjutan) — MURNI untuk
     * unit test JVM.
     *  - Jaringan hilang → OFFLINE, apa pun status saat ini.
     *  - Jaringan pulih dari OFFLINE → SYNCING (menunggu konfirmasi snapshot).
     *  - Selain itu biarkan status berjalan (ERROR/SYNCING tidak ditimpa).
     */
    internal fun resolveStatusOnNetworkChange(current: SyncStatus, networkAvailable: Boolean): SyncStatus =
        when {
            !networkAvailable -> SyncStatus.OFFLINE
            current == SyncStatus.OFFLINE -> SyncStatus.SYNCING
            else -> current
        }

    /**
     * Status setelah snapshot listener BERHASIL — murni untuk unit test.
     * SYNCED hanya bila jaringan diketahui AKTIF; jaringan jelas mati (false)
     * → OFFLINE (snapshot dari offline cache tidak menyesatkan); null (belum
     * diketahui) → SYNCED (perilaku lama, tidak mengubah apa pun).
     */
    internal fun resolveStatusOnSyncSuccess(networkAvailable: Boolean?): SyncStatus =
        if (networkAvailable == false) SyncStatus.OFFLINE else SyncStatus.SYNCED

    /**
     * Status saat drain antrian pending AKTIF (ada op untuk di-retry) — murni
     * untuk unit test. Jaringan jelas mati → OFFLINE (retry tidak mungkin
     * sukses; menimpa SYNCING yang menyesatkan); selain itu SYNCING.
     */
    internal fun resolveStatusOnDraining(networkAvailable: Boolean?): SyncStatus =
        if (networkAvailable == false) SyncStatus.OFFLINE else SyncStatus.SYNCING

    /** Set status SYNCED + emit event pemulihan bila sebelumnya error/offline
     *  (audit #6: indikator jujur + pemberitahuan saat koneksi pulih).
     *  BUG-06 lanjutan: offline murni + cache — snapshot sukses dari cache tidak
     *  boleh menyesatkan indikator menjadi "Tersinkron". */
    private fun markSynced() {
        if (resolveStatusOnSyncSuccess(networkAvailable) == SyncStatus.OFFLINE) {
            _syncStatus.value = SyncStatus.OFFLINE
            return
        }
        if (_syncStatus.value != SyncStatus.SYNCED) {
            _recoveryEvents.tryEmit("Sinkron tersambung kembali.")
        }
        _syncStatus.value = SyncStatus.SYNCED
        // 3.8: setiap snapshot sukses = sinkron aktif → catat waktu terakhir.
        _lastSyncedAt.value = System.currentTimeMillis()
    }

    /** Eksekusi satu op antrian. Return true kalau berhasil (op bisa dihapus). */
    private suspend fun executeOp(op: PendingOp): Boolean {
        return try {
            val payload = JSONObject(op.payload)
            when (op.opType) {
                OP_SYNC_MESSAGE -> syncMessageNow(DataExporter.messageFromJson(payload))
                OP_DELETE_MESSAGE -> deleteMessageNow(payload.optString(Constants.Fields.CLOUD_ID))
                OP_SYNC_TRANSACTION -> syncTransactionNow(DataExporter.transactionFromJson(payload))
                OP_DELETE_TRANSACTION -> deleteTransactionNow(payload.optString(Constants.Fields.CLOUD_ID))
                OP_CLEAR_FAMILY -> clearFamilyDataNow()
                else -> true // tipe tak dikenal → buang agar tidak macet
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // jangan telan pembatalan coroutine (logout/stop) sebagai kegagalan op
        } catch (e: Exception) {
            // P2-2 (audit keanggotaan): member dihapus/di-kick — PERMISSION_DENIED
            // tidak akan pernah sukses; buang op agar drain tidak retry selamanya.
            if (isPermissionDenied(e)) {
                Log.w(TAG, "Pending op dibuang: PERMISSION_DENIED (bukan anggota lagi?)")
                true
            } else {
                Log.w(TAG, "Eksekusi pending op gagal: ${e.message}")
                false
            }
        }
    }

    /**
     * Kuras antrian pending selama workspace aktif. Backoff eksponensial saat ada
     * op gagal (1s → 2s → ... → 32s). Saat antrian kosong, drain TIDAK polling —
     * ia tidur di [opsSignal] sampai ada op baru (atau scope dibatalkan saat logout).
     * Berhenti otomatis saat logout (familyId kosong).
     */
    private fun startPendingDrain() {
        scope.launch {
            var backoffMs = MIN_RETRY_DELAY_MS
            while (familyId.isNotEmpty()) {
                val opDao = pendingDao ?: return@launch
                val ops = opDao.getAll()
                if (ops.isEmpty()) {
                    backoffMs = MIN_RETRY_DELAY_MS
                    // BUG-06 lanjutan: antrian kosong ≠ sinkron — kalau jaringan
                    // jelas mati, indikator tetap OFFLINE (bukan SYNCED palsu).
                    _syncStatus.value = resolveStatusOnSyncSuccess(networkAvailable)
                    opsSignal.receive()
                    continue
                }                    // BUG-06 lanjutan: dengan jaringan jelas mati, retry tidak
                    // akan sukses — tampilkan OFFLINE, bukan SYNCING yang
                    // menyesatkan (reviewer).
                    _syncStatus.value = resolveStatusOnDraining(networkAvailable)
                    var failed = false
                    for (op in ops) {
                    if (familyId.isEmpty()) return@launch
                    if (executeOp(op)) {
                        opDao.deleteById(op.id)
                    } else {
                        failed = true
                        break
                    }
                }
                if (failed) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                } else {
                    backoffMs = MIN_RETRY_DELAY_MS
                }
            }
        }
    }

}
