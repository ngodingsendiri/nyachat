package com.startupmini.nyachat.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.startupmini.nyachat.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Anggota workspace (dokumen di subkoleksi `families/{pin}/members/{uid}`).
 * `role` = "owner" | "member"; `label` adalah label tampilan orang
 * (mis. "Suami", "Istri", "Bendahara", "Anggota", nama, dsb.).
 */
data class FamilyMember(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val role: String = "member",
    val label: String = "",
    val addedAt: Long = 0L
) {
    val isOwner: Boolean get() = role == Constants.Roles.OWNER
}

/** Permintaan bergabung (dokumen di subkoleksi `families/{pin}/joinRequests/{uid}`). */
data class JoinRequest(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val requestedAt: Long = 0L
)

/** Status keanggotaan untuk alur masuk workspace (PIN perlu persetujuan owner). */
enum class MembershipStatus { FAMILY_NOT_FOUND, MEMBER, PENDING, NOT_REQUESTED, FAILED, TIMED_OUT }

/** Hasil pengiriman permintaan bergabung (membedakan "PIN tidak ada" vs error). */
enum class JoinRequestResult { SUCCESS, NOT_FOUND, FAILED }

/** Hasil penyiapan workspace pemilik. */
enum class OwnerSetupResult { SUCCESS, ALREADY_OWNED, FAILED }

/**
 * Keanggotaan workspace (P5 — hasil dekomposisi `FirestoreSyncManager`).
 *
 * Sebelumnya seluruh alur keanggotaan (daftar anggota, permintaan bergabung,
 * persetujuan owner) hidup di dalam singleton FirestoreSyncManager (±950 baris)
 * sehingga mencampur 4 tanggung jawab. Modul ini memegang SATU tanggung jawab:
 * siapa saja anggota workspace & bagaimana alur masuk/kelola anggota berjalan.
 *
 * - [start] memasang listener realtime daftar anggota + permintaan bergabung
 *   (joinRequests hanya untuk owner — anggota tidak punya izin baca).
 * - Alur PIN: [ensureOwnerWorkspace] (owner), [checkMembership], [requestJoin],
 *   [waitForJoinRequestDecision] (anggota menunggu persetujuan).
 * - Kelola: [approveJoin], [rejectJoin], [removeMember], [setMemberRole].
 */
object MembershipManager {

    private const val TAG = "MembershipManager"

    /** Peran pemilik workspace — satu-satunya yang mencatat ownerId di dokumen keluarga. */
    const val ROLE_OWNER = "owner"
    const val ROLE_MEMBER = "member"

    /**
     * Batas tunggu keputusan owner pada satu panggilan waitForJoinRequestDecision.
     * Kalau lewat, gate akan menampilkan error timeout (dan user bisa Coba Lagi).
     * Owner yang sibuk lebih baik dilaporkan gagal timeout daripada gate
     * menggantung selamanya. (P2-10)
     */
    private const val JOIN_DECISION_TIMEOUT_MS = 10 * 60 * 1000L

    // Daftar anggota & permintaan bergabung — dikonsumsi UI (layar Kelola Anggota).
    private val _members = MutableStateFlow<List<FamilyMember>>(emptyList())
    val members: StateFlow<List<FamilyMember>> = _members.asStateFlow()
    private val _joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val joinRequests: StateFlow<List<JoinRequest>> = _joinRequests.asStateFlow()

    /**
     * Detail kegagalan terakhir (alur PIN & keanggotaan). Disimpan supaya layar
     * gate bisa menampilkan PENYEBAB asli kegagalan — bukan hanya "gagal terhubung
     * ke server" yang menyesatkan (semua non-kasus-khusus tadi direduksi ke pesan
     * itu). Dipakai untuk diagnosa cepat di perangkat tanpa butuh Logcat.
     */
    private val _lastFailure = MutableStateFlow<MembershipFailure?>(null)
    val lastFailure: StateFlow<MembershipFailure?> = _lastFailure.asStateFlow()

    /** Ringkasan kegagalan untuk ditampilkan ke user. */
    data class MembershipFailure(val code: String, val message: String) {
        val summary: String get() = "$code: $message".ifBlank { code }
    }

    @Volatile private var membersListener: ListenerRegistration? = null
    @Volatile private var joinRequestsListener: ListenerRegistration? = null

    /** PIN & peran workspace aktif (untuk pasang-ulang listener saat resume). */
    @Volatile private var currentPin: String = ""
    @Volatile private var activeRole: String = ROLE_MEMBER

    /** true saat app background & listener keanggotaan untuk sementara diputus (M2). */
    @Volatile private var paused = false

    /** UID pengguna yang login sekarang. */
    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun db() = FirebaseFirestore.getInstance()

    /**
     * Mulai mendengarkan daftar anggota & permintaan bergabung untuk UI
     * "Kelola Anggota". Listener joinRequests hanya dipasang untuk owner
     * (anggota tidak punya izin baca, jadi jangan sampai kena PERMISSION_DENIED).
     */
    fun start(pin: String, role: String) {
        // stop() di sini (selain yang dipicu FirestoreSyncManager.start) bersifat
        // idempoten — dipanggil dua kali saat startup (L12) tapi tidak berbahaya:
        // membersListener/joinRequestsListener null-safe & state di-reset bersih.
        stop()
        currentPin = pin
        activeRole = role
        attachListeners(pin, role)
    }

    /** Pasang listener keanggotaan (dipisah agar bisa dipasang ulang di resume, M2). */
    private fun attachListeners(pin: String, role: String) {
        val famRef = db().collection(Constants.Collections.FAMILIES).document(pin)
        membersListener = famRef.collection(Constants.Collections.MEMBERS).addSnapshotListener { snap, err ->
            if (err != null) {
                if (err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    membersListener?.remove(); membersListener = null
                } else Log.w(TAG, "Listen members gagal: ${err.message}")
                return@addSnapshotListener
            }
            snap ?: return@addSnapshotListener
            _members.value = snap.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                FamilyMember(
                    uid = doc.id,
                    email = d[Constants.Fields.EMAIL] as? String ?: "",
                    name = d[Constants.Fields.NAME] as? String ?: "",
                    role = d[Constants.Fields.ROLE] as? String ?: Constants.Roles.MEMBER,
                    label = d[Constants.Fields.LABEL] as? String ?: "",
                    addedAt = (d[Constants.Fields.ADDED_AT] as? Number)?.toLong() ?: 0L
                )
            }
        }
        if (role != Constants.Roles.OWNER) return
        joinRequestsListener = famRef.collection(Constants.Collections.JOIN_REQUESTS).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "Listen joinRequests gagal: ${err.message}")
                return@addSnapshotListener
            }
            snap ?: return@addSnapshotListener
            _joinRequests.value = snap.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                JoinRequest(
                    uid = doc.id,
                    email = d[Constants.Fields.EMAIL] as? String ?: "",
                    name = d[Constants.Fields.NAME] as? String ?: "",
                    requestedAt = (d[Constants.Fields.REQUESTED_AT] as? Number)?.toLong() ?: 0L
                )
            }
        }
    }

    /**
     * Jeda listener keanggotaan saat app background (M2). Listener realtime
     * diputus supaya tidak boros kuota/baterai & tidak memicu komposisi ulang
     * daftar anggota di background. Daftar terakhir tetap dipertahankan — list
     * tidak di-reset; saat resume, listener dipasang ulang & snapshot baru datang.
     */
    fun pauseListeners() {
        if (paused) return
        paused = true
        membersListener?.remove(); membersListener = null
        joinRequestsListener?.remove(); joinRequestsListener = null
    }

    /** Pasang ulang listener saat app kembali ke foreground. */
    fun resumeListeners() {
        if (!paused) return
        paused = false
        if (currentPin.isEmpty()) return
        attachListeners(currentPin, activeRole)
    }

    /** Hentikan listener & reset state (dipanggil juga dari FirestoreSyncManager.stop). */
    fun stop() {
        paused = false
        currentPin = ""
        activeRole = ROLE_MEMBER
        membersListener?.remove(); membersListener = null
        joinRequestsListener?.remove(); joinRequestsListener = null
        // Jangan biarkan daftar workspace lama menempel (P4-1): login ke workspace
        // berbeda berikutnya harus menampilkan anggota baru, bukan yang lama.
        _members.value = emptyList()
        _joinRequests.value = emptyList()
        _lastFailure.value = null
    }

    /** Bangun map Firestore tanpa kunci bernilai null (null membuat set() error). */
    private fun nonNullMap(vararg pairs: Pair<String, Any?>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        pairs.forEach { (k, v) -> if (v != null) result[k] = v }
        return result
    }

    /** Simpan detail kegagalan Firestore agar layar gate bisa menampilkan penyebab asli. */
    private fun recordFailure(e: Throwable) {
        val msg = if (e is com.google.firebase.firestore.FirebaseFirestoreException) {
            MembershipFailure(e.code?.name ?: "UNKNOWN", e.message ?: "")
        } else {
            MembershipFailure("UNKNOWN", e.message ?: e.javaClass.simpleName)
        }
        _lastFailure.value = msg
    }

    /** Tulis member doc diri sendiri bila belum ada (bootstrap & migrasi workspace lama). */
    private suspend fun ensureSelfMemberDoc(
        famRef: com.google.firebase.firestore.DocumentReference,
        uid: String,
        role: String
    ) {
        val selfRef = famRef.collection(Constants.Collections.MEMBERS).document(uid)
        if (selfRef.get().await().exists()) return
        val user = FirebaseAuth.getInstance().currentUser
        selfRef.set(
            nonNullMap(
                Constants.Fields.UID to uid,
                Constants.Fields.EMAIL to user?.email,
                Constants.Fields.NAME to user?.displayName,
                Constants.Fields.ROLE to role,
                Constants.Fields.LABEL to (user?.displayName ?: Constants.Defaults.LABEL),
                Constants.Fields.ADDED_AT to System.currentTimeMillis()
            )
        ).await()
    }

    /**
     * Siapkan workspace baru untuk pemilik (PIN baru): pastikan dokumen
     * keluarga memuat ownerId dan member doc diri sendiri ada. Dipanggil oleh
     * layar gate sebelum masuk.
     *
     * Menyesuaikan rules: dokumen keluarga HANYA bisa dibaca oleh anggota/owner.
     * Pemilik yang baru pertama kali membuat workspace belum punya member doc,
     * jadi baca-nya ditolak (PERMISSION_DENIED) — alur menangani ini dengan
     * mencoba CREATE langsung (rules mengizinkan create dengan
     * ownerId == auth.uid). Kalau create juga ditolak, berarti PIN sudah dimiliki
     * orang lain → ALREADY_OWNED.
     */
    suspend fun ensureOwnerWorkspace(pin: String): OwnerSetupResult {
        val uid = currentUid() ?: return OwnerSetupResult.FAILED
        _lastFailure.value = null
        val famRef = db().collection(Constants.Collections.FAMILIES).document(pin)
        return runCatching {
            var ownedByMe = false
            try {
                val fam = famRef.get().await()
                ownedByMe = fam.exists() && fam.getString(Constants.Fields.OWNER_ID) == uid
                if (!fam.exists()) {
                    famRef.set(
                        mapOf(
                            Constants.Fields.OWNER_ID to uid,
                            Constants.Fields.CREATED_AT to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                    ownedByMe = true
                }
            } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                // Belum jadi anggota → dokumen tidak bisa dibaca (rules).
                if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                try {
                    famRef.set(
                        mapOf(
                            Constants.Fields.OWNER_ID to uid,
                            Constants.Fields.CREATED_AT to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                    ownedByMe = true
                } catch (e2: com.google.firebase.firestore.FirebaseFirestoreException) {
                    // CREATE ditolak → dokumen sudah ada (milik orang lain).
                    // Error lain (mis. jaringan) bukan berarti PIN dimiliki — biarkan
                    // menjadi FAILED, bukan ALREADY_OWNED.
                    if (e2.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        return OwnerSetupResult.ALREADY_OWNED
                    }
                    throw e2
                }
            }
            if (!ownedByMe) return OwnerSetupResult.ALREADY_OWNED
            ensureSelfMemberDoc(famRef, uid, Constants.Roles.OWNER)
            OwnerSetupResult.SUCCESS
        }.onFailure { Log.w(TAG, "ensureOwnerWorkspace gagal: ${it.message}") }
            .getOrDefault(OwnerSetupResult.FAILED)
    }

    /**
     * Cek status keanggotaan pengguna saat ini di sebuah workspace (PIN).
     * Sesuai rules: dokumen keluarga TIDAK dibaca non-anggota, jadi
     * FAMILY_NOT_FOUND tidak bisa dideteksi di sini — keberadaan PIN ditentukan
     * lewat hasil [requestJoin] (create ditolak rules bila keluarga tidak ada).
     */
    suspend fun checkMembership(pin: String): MembershipStatus {
        val uid = currentUid() ?: return MembershipStatus.FAILED
        _lastFailure.value = null
        val famRef = db().collection(Constants.Collections.FAMILIES).document(pin)
        return runCatching {
            // Dokumen member & joinRequest milik sendiri selalu boleh dibaca oleh
            // rules (request.auth.uid == uid), jadi ini tidak kena PERMISSION_DENIED.
            if (famRef.collection(Constants.Collections.MEMBERS).document(uid).get().await().exists())
                return MembershipStatus.MEMBER
            if (famRef.collection(Constants.Collections.JOIN_REQUESTS).document(uid).get().await().exists())
                return MembershipStatus.PENDING
            MembershipStatus.NOT_REQUESTED
        }.onFailure {
            Log.w(TAG, "checkMembership gagal: ${it.message}")
            recordFailure(it)
        }
            .getOrDefault(MembershipStatus.FAILED)
    }

    /**
     * Kirim permintaan bergabung sebagai diri sendiri.
     * Rules menolak create (PERMISSION_DENIED) kalau keluarga tidak ada →
     * dikembalikan sebagai NOT_FOUND supaya UI bisa menampilkan "PIN tidak
     * ditemukan" tanpa membocorkan keberadaan PIN lewat read dokumen keluarga.
     */
    suspend fun requestJoin(pin: String): JoinRequestResult {
        val uid = currentUid() ?: return JoinRequestResult.FAILED
        _lastFailure.value = null
        val user = FirebaseAuth.getInstance().currentUser
        return try {
            db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.JOIN_REQUESTS).document(uid)
                .set(
                    nonNullMap(
                        Constants.Fields.UID to uid,
                        Constants.Fields.EMAIL to user?.email,
                        Constants.Fields.NAME to user?.displayName,
                        Constants.Fields.REQUESTED_AT to System.currentTimeMillis()
                    )
                ).await()
            JoinRequestResult.SUCCESS
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            Log.w(TAG, "requestJoin gagal: ${e.code}: ${e.message}")
            recordFailure(e)
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                JoinRequestResult.NOT_FOUND
            } else {
                JoinRequestResult.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestJoin gagal: ${e.message}")
            recordFailure(e)
            JoinRequestResult.FAILED
        }
    }

    /**
     * Tunggu keputusan owner (setujui/tolak) pada permintaan bergabung.
     * Menggunakan listener realtime pada dokumen joinRequests/{uid}:
     * - Jika dokumen dihapus & user jadi member → return MEMBER
     * - Jika dokumen dihapus & user BUKAN member → return NOT_REQUESTED (ditolak)
     * - Jika error listener → return FAILED
     */
    suspend fun waitForJoinRequestDecision(pin: String): MembershipStatus {
        val uid = currentUid() ?: return MembershipStatus.FAILED
        val requestRef = db().collection(Constants.Collections.FAMILIES).document(pin)
            .collection(Constants.Collections.JOIN_REQUESTS).document(uid)
        val deferred = CompletableDeferred<MembershipStatus>()
        val listener = requestRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                recordFailure(error)
                deferred.complete(MembershipStatus.FAILED)
                return@addSnapshotListener
            }
            // Dokumen dihapus (owner setujui/tolak) → cek status keanggotaan
            if (snapshot == null || !snapshot.exists()) {
                // Owner sudah aksi: cek apakah jadi member
                val famRef = db().collection(Constants.Collections.FAMILIES).document(pin)
                famRef.collection(Constants.Collections.MEMBERS).document(uid).get()
                    .addOnSuccessListener { memberSnap ->
                        deferred.complete(
                            if (memberSnap.exists()) MembershipStatus.MEMBER
                            else MembershipStatus.NOT_REQUESTED
                        )
                    }
                    .addOnFailureListener { _ ->
                        deferred.complete(MembershipStatus.FAILED)
                    }
            }
            // Dokumen masih ada (PENDING) → tidak complete, tunggu event berikutnya
        }
        try {
            // Timeout agar gate tidak menggantung selamanya kalau owner tidak
            // membalas (P2-10). Dikembalikan sebagai TIMED_OUT (BUKAN FAILED)
            // supaya gate bisa menampilkan pesan timeout yang tepat, bukan
            // "gagal terhubung ke server".
            return withTimeoutOrNull(JOIN_DECISION_TIMEOUT_MS) { deferred.await() }
                ?: MembershipStatus.TIMED_OUT
        } finally {
            listener.remove()
        }
    }

    /** Owner menyetujui permintaan → jadikan anggota & hapus permintaan (atomik). */
    suspend fun approveJoin(pin: String, request: JoinRequest) {
        val famRef = db().collection(Constants.Collections.FAMILIES).document(pin)
        val memberRef = famRef.collection(Constants.Collections.MEMBERS).document(request.uid)
        val requestRef = famRef.collection(Constants.Collections.JOIN_REQUESTS).document(request.uid)
        runCatching {
            // Satu transaksi: baca ulang permintaan (untuk deteksi konflik), lalu
            // tulis member + hapus permintaan secara atomik. Kalau permintaan sudah
            // diproses device lain, transaksi tidak menulis apa-apa.
            db().runTransaction { txn ->
                if (txn.get(requestRef).exists()) {
                    txn.set(
                        memberRef,
                        nonNullMap(
                            Constants.Fields.UID to request.uid,
                            Constants.Fields.EMAIL to request.email,
                            Constants.Fields.NAME to request.name,
                            Constants.Fields.ROLE to Constants.Roles.MEMBER,
                            Constants.Fields.LABEL to request.name.ifBlank { Constants.Defaults.LABEL },
                            Constants.Fields.ADDED_AT to System.currentTimeMillis()
                        )
                    )
                    txn.delete(requestRef)
                }
            }.await()
        }.onFailure { Log.w(TAG, "approveJoin gagal: ${it.message}") }
    }

    /** Owner menolak permintaan bergabung. */
    suspend fun rejectJoin(pin: String, uid: String) {
        runCatching {
            db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.JOIN_REQUESTS).document(uid).delete().await()
        }.onFailure { Log.w(TAG, "rejectJoin gagal: ${it.message}") }
    }

    /** Owner menghapus anggota dari workspace (tidak untuk diri sendiri). */
    suspend fun removeMember(pin: String, uid: String) {
        if (uid == currentUid()) return
        runCatching {
            db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.MEMBERS).document(uid).delete().await()
        }.onFailure { Log.w(TAG, "removeMember gagal: ${it.message}") }
    }

    /** Owner mengubah peran (owner/member) dan/atau label tampilan anggota. */
    suspend fun setMemberRole(pin: String, uid: String, role: String, label: String? = null) {
        runCatching {
            val updates = mutableMapOf<String, Any>(Constants.Fields.ROLE to role)
            if (label != null) updates[Constants.Fields.LABEL] = label
            db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.MEMBERS).document(uid)
                .update(updates).await()
        }.onFailure { Log.w(TAG, "setMemberRole gagal: ${it.message}") }
    }
}
