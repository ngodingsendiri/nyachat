package com.startupmini.nyachat.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.AvatarStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val addedAt: Long = 0L,
    // r1.2.3 (P1): versi foto avatar member (0 = belum punya foto). Bytes foto
    // tidak dibawa ke UI — di-cache ke disk via AvatarStore (lihat [cacheAvatarOf]).
    val avatarVersion: Long = 0L
) {
    val isOwner: Boolean get() = role == Constants.Roles.OWNER
}

/** Permintaan bergabung (dokumen di subkoleksi `families/{pin}/joinRequests/{uid}`). */
data class JoinRequest(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    // r1.4.0 (avatar foto): URL foto Google pemohon — disalin ke member doc
    // saat disetujui supaya device lain bisa menampilkan foto sebelum
    // avatarBytes di-sync.
    val photoUrl: String = "",
    val requestedAt: Long = 0L
)

/**
 * Workspace milik akun (r1.4.0 — auto-connect): hasil query collectionGroup
 * `members` by uid. `pin` = id dokumen keluarga (parent dari doc member),
 * `role` = peran akun di workspace itu.
 */
// r1.4.0 (auto-connect): name = nama tampilan member di workspace (dari doc
// members/{uid}.name) — dipakai auto-connect untuk langsung mengisi userName
// tanpa meminta ulang nama di layar PIN.
data class MyWorkspace(val pin: String, val role: String, val name: String = "")

/**
 * Keputusan auto-connect (r1.4.0) dari [MembershipManager.resolveAutoConnect] —
 * logika MURNI (tanpa I/O) supaya bisa di-unit-test, dipakai LaunchedEffect di
 * MainActivity setelah Google login.
 */
sealed class AutoConnectDecision {
    /** Sambungkan ke workspace ini (isi ulang PIN + role + nama). */
    data class Connect(val ws: MyWorkspace) : AutoConnectDecision()

    /** Akun terikat >1 workspace & tidak ada workspace aktif yang jelas → pilih. */
    data object ShowPicker : AutoConnectDecision()

    /** PIN lokal basi (akun tidak terikat workspace mana pun) → bersihkan. */
    data object ClearStalePin : AutoConnectDecision()

    /** Sudah tersambung / tidak ada yang perlu dilakukan. */
    data object Noop : AutoConnectDecision()
}

/**
 * Putuskan aksi auto-connect setelah `discoverMyWorkspaces` (r1.4.0).
 *
 * Aturan:
 * - 0 workspace → PIN lokal basi (di-kick/sudah keluar) → [AutoConnectDecision.ClearStalePin]
 *   hanya bila ada PIN lokal; kalau tidak ada, [AutoConnectDecision.Noop].
 * - 1 workspace → sambungkan bila PIN aktif berbeda ATAU identitas belum terisi
 *   (`userName == null` — kasus logout biasa: PIN pulih dari Keystore tapi
 *   userName di-reset, tanpa kondisi ini user nyangkut di layar PIN).
 * - >1 workspace (user lama) → resume workspace aktif bila PIN-nya ada di daftar
 *   (isi ulang userName bila kosong); selain itu tampilkan pemilih.
 */
fun resolveAutoConnect(
    discovered: List<MyWorkspace>,
    workspacePin: String?,
    userName: String?
): AutoConnectDecision = when {
    discovered.isEmpty() ->
        if (workspacePin != null) AutoConnectDecision.ClearStalePin else AutoConnectDecision.Noop

    discovered.size == 1 -> {
        val ws = discovered[0]
        if (workspacePin != ws.pin || userName == null) AutoConnectDecision.Connect(ws)
        else AutoConnectDecision.Noop
    }

    else -> {
        val active = discovered.firstOrNull { it.pin == workspacePin }
        when {
            active != null && userName != null -> AutoConnectDecision.Noop
            active != null -> AutoConnectDecision.Connect(active)
            else -> AutoConnectDecision.ShowPicker
        }
    }
}

/**
 * Apakah photoUrl di member doc perlu di-refresh (r1.4.0): true bila photoUrl
 * Google baru tidak kosong dan berbeda dari yang tersimpan — dipakai
 * [MembershipManager.ensureSelfMemberDoc] untuk member lama (doc dibuat sebelum
 * fitur avatar foto) saat connect.
 */
fun shouldRefreshPhotoUrl(currentPhoto: String?, newPhoto: String?): Boolean =
    !newPhoto.isNullOrBlank() && newPhoto != currentPhoto

enum class AvatarSourceDecision { USE_BYTES, DOWNLOAD_PHOTO_URL, SKIP }

/**
 * Putuskan sumber avatar untuk satu member (r1.4.0):
 * - [AvatarSourceDecision.USE_BYTES] — ada versi avatarBytes lebih baru dari
 *   yang sudah di-publish → cache/decode bytes (foto ter-upload menang).
 * - [AvatarSourceDecision.DOWNLOAD_PHOTO_URL] — bytes tidak baru (atau belum
 *   pernah sync), tapi URL foto Google berbeda dari yang sudah di-publish →
 *   unduh URL (fallback: anggota belum pernah upload avatarBytes).
 * - [AvatarSourceDecision.SKIP] — tidak ada sumber baru (hindari unduh ulang).
 */
fun decideAvatarSource(
    avatarVersion: Long,
    publishedVersion: Long,
    photoUrl: String?,
    publishedPhotoUrl: String?
): AvatarSourceDecision = when {
    avatarVersion > 0L && avatarVersion > publishedVersion -> AvatarSourceDecision.USE_BYTES
    !photoUrl.isNullOrBlank() && photoUrl != publishedPhotoUrl -> AvatarSourceDecision.DOWNLOAD_PHOTO_URL
    else -> AvatarSourceDecision.SKIP
}

/** Status keanggotaan untuk alur masuk workspace (PIN perlu persetujuan owner). */
enum class MembershipStatus { MEMBER, PENDING, NOT_REQUESTED, FAILED, TIMED_OUT }

/** Hasil pengiriman permintaan bergabung (membedakan "PIN tidak ada" vs error). */
enum class JoinRequestResult { SUCCESS, NOT_FOUND, FAILED }

/** Hasil penyiapan workspace pemilik. */
enum class OwnerSetupResult { SUCCESS, ALREADY_OWNED, OWNED_ELSEWHERE, FAILED }

/** Hasil keluar dari workspace (r1.4.0). */
enum class LeaveWorkspaceResult { LEFT, NEED_OWNER_TRANSFER, FAILED }

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
     * Keputusan murni: kapan listener members yang error dianggap "di-kick".
     * PERMISSION_DENIED SAJA belum cukup — kalau listener belum pernah menerima
     * snapshot SUKSES (bootstrap/race saat member doc baru dibuat), tolak tidak
     * berarti di-kick. Diekstrak supaya bisa di-unit-test (pola MembershipGateLogic).
     *
     * @param hadSnapshot true jika listener pernah menerima snapshot sukses
     *   (artinya sempat jadi anggota & punya akses baca).
     * @param permissionDenied true jika error listener = PERMISSION_DENIED.
     */
    internal fun shouldTriggerKick(hadSnapshot: Boolean, permissionDenied: Boolean): Boolean =
        hadSnapshot && permissionDenied

    /**
     * Keputusan murni (r1.4.0): bolehkah user keluar dari workspace?
     * Owner TIDAK boleh keluar kalau tidak ada owner lain — workspace yatim.
     * Member bebas keluar. Diekstrak supaya bisa di-unit-test.
     */
    internal fun canLeaveWorkspace(myRole: String, otherOwnerCount: Int): Boolean =
        myRole != ROLE_OWNER || otherOwnerCount >= 1

    /**
     * Keputusan murni (audit workspace 2026-08-14): apakah akun sudah jadi
     * OWNER di workspace LAIN (selain [currentPin])? Menegakkan aturan
     * "1 akun = 1 workspace" — kalau true, pembuatan workspace baru diblokir
     * sampai workspace lama DIHAPUS atau kepemilikan DIWARISKAN (promote
     * anggota lain jadi owner). Anggota biasa (bukan owner) di workspace lain
     * TIDAK terblokir — mereka bebas bikin workspace sendiri.
     *
     * Member docs = pasangan (pin, role). Diekstrak supaya bisa di-unit-test
     * (query Firestore-nya ada di [ensureOwnerWorkspace]).
     */
    internal fun ownsWorkspaceElsewhere(
        memberDocs: List<Pair<String, String?>>,
        currentPin: String
    ): Boolean = memberDocs.any { (pin, role) -> pin != currentPin && role == ROLE_OWNER }


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

    // r1.2.3 (P1): konteks aplikasi untuk cache avatar anggota lain ke disk.
    // Di-set saat start() — snapshot listener Firestore butuh konteks untuk
    // menulis file cache (bukan komposisi, jadi tidak bisa pakai LocalContext).
    @Volatile private var appContext: Context? = null

    // r1.2.3 (P1): scope khusus untuk I/O cache avatar anggota lain. Snapshot
    // listener Firestore berjalan di main thread — menulis file di dalamnya
    // memicu jank (review P1). Semua operasi disk avatar di-pindahkan ke sini;
    // dibatalkan di stop() agar tidak ada task menggantung setelah logout.
    private val avatarScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** true saat app background & listener keanggotaan untuk sementara diputus (M2). */
    @Volatile private var paused = false

    /**
     * Sinyal "di-kick / tidak lagi anggota" — event counter yang naik tiap kali
     * listener daftar anggota menerima PERMISSION_DENIED saat workspace aktif
     * (audit workspace 2026-08-12). Sebelumnya kick hanya terdeteksi saat app
     * RESUME (SyncLifecycle A3) — user yang di-kick saat app terbuka tetap bisa
     * melihat & mencoba mengirim pesan yang gagal sampai app dibackground-kan.
     * MainActivity meng-collect counter ini → langsung kembali ke layar PIN.
     *
     * Kick hanya dipicu jika listener pernah menerima snapshot SUKSES lebih dulu
     * ([hadMembersSnapshot]) — PERMISSION_DENIED langsung di awal bootstrap
     * (mis. race saat member doc baru dibuat) bukan berarti di-kick.
     */
    private val _kickedEvents = MutableStateFlow(0)
    val kickedEvents: StateFlow<Int> = _kickedEvents.asStateFlow()

    /** true setelah listener members pernah menerima snapshot sukses (guard kick). */
    @Volatile private var hadMembersSnapshot = false

    /** UID pengguna yang login sekarang. */
    fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun db() = FirebaseFirestore.getInstance()

    /**
     * Mulai mendengarkan daftar anggota & permintaan bergabung untuk UI
     * "Kelola Anggota". Listener joinRequests hanya dipasang untuk owner
     * (anggota tidak punya izin baca, jadi jangan sampai kena PERMISSION_DENIED).
     */
    fun start(pin: String, role: String, context: Context? = null) {
        // stop() di sini (selain yang dipicu FirestoreSyncManager.start) bersifat
        // idempoten — dipanggil dua kali saat startup (L12) tapi tidak berbahaya:
        // membersListener/joinRequestsListener null-safe & state di-reset bersih.
        stop()
        currentPin = pin
        activeRole = role
        appContext = context?.applicationContext
        attachListeners(pin, role)
    }

    /** Pasang listener keanggotaan (dipisah agar bisa dipasang ulang di resume, M2). */
    // r1.2.3 (P1): foto avatar anggota lain yang sudah di-cache ke disk,
    // dikunci uid → path file. Dibangun saat snapshot members datang (lihat
    // [cacheAvatarOf]); UI header chat/topbar memakainya untuk AvatarImage.
    private val _memberAvatarPaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val memberAvatarPaths: StateFlow<Map<String, String>> = _memberAvatarPaths.asStateFlow()

    // Versi avatar per uid yang sudah di-publish (review P1): mencegah task
    // avatarScope yang lebih TUA menimpa map dengan versi lebih baru saat dua
    // snapshot beruntun (mis. member ganti foto tepat saat snapshot pertama
    // masih menulis cache). ConcurrentHashMap aman diakses lintas thread.
    private val publishedAvatarVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // r1.4.0 (avatar foto): URL photoUrl per uid yang sudah di-publish ke
    // [memberAvatarPaths] — mencegah mengunduh foto Google berulang kali di
    // tiap snapshot. URL berubah → diunduh ulang.
    private val publishedPhotoUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun attachListeners(pin: String, role: String) {
        val famRef = db().collection(Constants.Collections.FAMILIES).document(pin)
        membersListener = famRef.collection(Constants.Collections.MEMBERS).addSnapshotListener { snap, err ->
            if (err != null) {
                if (err.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    membersListener?.remove(); membersListener = null
                    // Audit workspace: PERMISSION_DENIED SETELAH pernah punya akses
                    // (snapshot sukses) = doc member sendiri dihapus (di-kick owner)
                    // → beri sinyal ke UI agar langsung kembali ke layar PIN.
                    if (shouldTriggerKick(hadMembersSnapshot, true)) _kickedEvents.value++
                } else Log.w(TAG, "Listen members gagal: ${err.message}")
                return@addSnapshotListener
            }
            snap ?: return@addSnapshotListener
            // Guard kick (audit workspace): snapshot sukses = sempat jadi anggota.
            hadMembersSnapshot = true
            // r1.4.0 (avatar foto): backfill photoUrl di member doc SENDIRI bila
            // belum tersimpan (member lama, doc dibuat sebelum fitur) atau foto
            // Google berubah. Satu update ter-target, konvergen — snapshot
            // berikutnya tidak menulis lagi. Dijalankan tiap connect supaya
            // device lain punya sumber foto (fallback) untuk avatar.
            val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (myUid != null) {
                val myDoc = snap.documents.firstOrNull { it.id == myUid }
                if (myDoc != null) {
                    val currentPhoto = myDoc.data?.get(Constants.Fields.PHOTO_URL) as? String
                    val googlePhoto =
                        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.photoUrl?.toString()
                    if (shouldRefreshPhotoUrl(currentPhoto, googlePhoto)) {
                        myDoc.reference.update(Constants.Fields.PHOTO_URL, googlePhoto)
                            .addOnFailureListener {
                                Log.w(TAG, "refresh photoUrl member doc gagal: ${it.message}")
                            }
                    }
                }
            }
            val list = snap.documents.mapNotNull { doc ->
                val d = doc.data ?: return@mapNotNull null
                FamilyMember(
                    uid = doc.id,
                    email = d[Constants.Fields.EMAIL] as? String ?: "",
                    name = d[Constants.Fields.NAME] as? String ?: "",
                    role = d[Constants.Fields.ROLE] as? String ?: Constants.Roles.MEMBER,
                    label = d[Constants.Fields.LABEL] as? String ?: "",
                    // P3 (audit keanggotaan): addedAt bisa bertipe Timestamp
                    // (serverTimestamp) atau Number (jam klien lama) — tangani keduanya.
                    addedAt = (d[Constants.Fields.ADDED_AT] as? Number)?.toLong()
                        ?: (d[Constants.Fields.ADDED_AT] as? com.google.firebase.Timestamp)?.toDate()?.time
                        ?: 0L,
                    avatarVersion = (d[Constants.Fields.AVATAR_VERSION] as? Number)?.toLong() ?: 0L
                )
            }
            _members.value = list
            // P1: cache foto avatar anggota lain ke disk (Blob Firestore → file),
            // lalu publish map uid→path untuk UI. Dijalankan di avatarScope
            // (Dispatchers.IO) — snapshot listener main thread TIDAK boleh
            // menulis file (jank). Bytes foto tidak disimpan di memori — hanya
            // dibaca saat snapshot & langsung ditulis ke disk.
            val ctx = appContext ?: return@addSnapshotListener
            if (list.isNotEmpty()) {
                val docs = snap.documents
                avatarScope.launch {
                    val paths = mutableMapOf<String, String>()
                    docs.forEach { doc ->
                        val uid = doc.id
                        // Prioritas: avatarBytes (foto ter-upload, versi bernomor) —
                        // fallback: photoUrl Google dari member doc (r1.4.0).
                        // Keputusan sumber diekstrak ke fungsi murni [decideAvatarSource]
                        // (di-unit-test) — loop ini HANYA mengeksekusi hasilnya.
                        val version = (doc.data?.get(Constants.Fields.AVATAR_VERSION) as? Number)?.toLong() ?: 0L
                        val prev = publishedAvatarVersions[uid] ?: 0L
                        val photoUrl = (doc.data?.get(Constants.Fields.PHOTO_URL) as? String)
                            ?.takeIf { it.isNotBlank() }
                        val decision = decideAvatarSource(version, prev, photoUrl, publishedPhotoUrls[uid])
                        var havePath = false
                        if (decision == AvatarSourceDecision.USE_BYTES) {
                            val existing = AvatarStore.getMemberAvatarPath(ctx, uid, version)
                            if (existing != null) {
                                paths[uid] = existing
                                publishedAvatarVersions[uid] = version
                                havePath = true
                            } else {
                                val bytes = (doc.data?.get(Constants.Fields.AVATAR_BYTES) as? com.google.firebase.firestore.Blob)?.toBytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    AvatarStore.cacheMemberAvatar(ctx, uid, version, bytes)
                                        ?.let {
                                            paths[uid] = it
                                            publishedAvatarVersions[uid] = version
                                            havePath = true
                                        }
                                }
                            }
                        }
                        // Fallback (r1.4.0): bytes tidak tersedia/tidak baru — pakai
                        // URL foto Google dari member doc (join/connect). Guard
                        // per-uid: URL sama → tidak unduh ulang (SKIP).
                        if (!havePath && photoUrl != null && publishedPhotoUrls[uid] != photoUrl) {
                            AvatarStore.cacheGooglePhoto(ctx, photoUrl, uid)?.let {
                                paths[uid] = it
                                publishedPhotoUrls[uid] = photoUrl
                            }
                        }
                    }
                    // StateFlow thread-safe; merge (bukan replace) supaya task yang
                    // selesai belakangan untuk member berbeda tidak menghapus path
                    // milik member lain yang sudah ter-publish lebih dulu.
                    if (paths.isNotEmpty()) {
                        _memberAvatarPaths.value = _memberAvatarPaths.value + paths
                    }
                }
            }
        }
        if (role != Constants.Roles.OWNER) return
        listenJoinRequests(famRef)
    }

    /** Listener realtime permintaan bergabung (hanya owner) — dipasang saat start
     *  dan dipasang ulang bila peran di-promote ke owner (P1-1 audit keanggotaan). */
    private fun listenJoinRequests(famRef: com.google.firebase.firestore.DocumentReference) {
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
     * Peran berubah di tengah sesi (di-promote/di-demote oleh owner lain).
     * P1-1 (audit keanggotaan): sebelumnya role hanya di-set saat start() — owner
     * yang di-demote tetap punya listener joinRequests & UI owner sampai restart.
     * Fungsi ini menyinkronkan listener dengan peran terbaru (dipanggil saat
     * snapshot members mendeteksi perbedaan role sendiri).
     */
    fun updateRole(role: String) {
        if (role == activeRole) return
        activeRole = role
        if (currentPin.isEmpty()) return
        val famRef = db().collection(Constants.Collections.FAMILIES).document(currentPin)
        if (role == ROLE_OWNER) {
            listenJoinRequests(famRef)
        } else {
            joinRequestsListener?.remove(); joinRequestsListener = null
            _joinRequests.value = emptyList()
        }
    }

    /**
     * Sinkronkan nama tampilan diri sendiri ke member doc Firestore (audit
     * keanggotaan — identitas koheren lintas perangkat): nama yang dipilih user
     * (onboarding/ganti nama) harus sama dengan nama di doc member supaya device
     * lain menampilkan nama yang benar & map avatar berfungsi. Best-effort —
     * gagal offline tidak fatal, dicoba lagi saat rename/connect berikutnya.
     */
    suspend fun updateMyIdentity(pin: String, name: String): Boolean {
        val uid = currentUid() ?: return false
        if (name.isBlank()) return false
        return runCatching {
            val selfRef = db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.MEMBERS).document(uid)
            val doc = selfRef.get().await()
            if (!doc.exists()) return@runCatching false
            val currentName = doc.getString(Constants.Fields.NAME)
            val currentLabel = doc.getString(Constants.Fields.LABEL)
            val updates = mutableMapOf<String, Any>(Constants.Fields.NAME to name)
            // Sinkron label juga bila masih DEFAULT (kosong / == nama lama) supaya
            // device lain tidak menampilkan nama lama di daftar anggota. Label yang
            // dikustomisasi owner (mis. "Bendahara") TIDAK ditimpa.
            if (currentLabel.isNullOrBlank() || currentLabel == currentName) {
                updates[Constants.Fields.LABEL] = name
            }
            // r1.4.0 (avatar foto): segarkan URL foto Google di member doc saat
            // connect/rename — device lain selalu punya sumber foto terbaru.
            FirebaseAuth.getInstance().currentUser?.photoUrl?.toString()?.let {
                updates[Constants.Fields.PHOTO_URL] = it
            }
            selfRef.update(updates).await()
            true
        }.onFailure { Log.w(TAG, "updateMyIdentity gagal: ${it.message}") }
            .getOrDefault(false)
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
        appContext = null
        membersListener?.remove(); membersListener = null
        joinRequestsListener?.remove(); joinRequestsListener = null
        // Batalkan task I/O avatar yang mungkin masih jalan (review P1) —
        // jangan biarkan menulis cache setelah stop().
        avatarScope.coroutineContext.cancelChildren()
        publishedAvatarVersions.clear()
        // Jangan biarkan daftar workspace lama menempel (P4-1): login ke workspace
        // berbeda berikutnya harus menampilkan anggota baru, bukan yang lama.
        _members.value = emptyList()
        _joinRequests.value = emptyList()
        _memberAvatarPaths.value = emptyMap()
        _lastFailure.value = null
        _kickedEvents.value = 0
        hadMembersSnapshot = false
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

    /**
     * Tulis member doc diri sendiri bila belum ada (bootstrap & migrasi workspace
     * lama). internal (bukan private): dipakai juga FirestoreSyncManager.ensureFamilyDoc
     * — SATU implementasi (deduplikasi P3 audit keanggotaan).
     */
    internal suspend fun ensureSelfMemberDoc(
        famRef: com.google.firebase.firestore.DocumentReference,
        uid: String,
        role: String
    ) {
        val selfRef = famRef.collection(Constants.Collections.MEMBERS).document(uid)
        val user = FirebaseAuth.getInstance().currentUser
        val existing = selfRef.get().await()
        if (existing.exists()) {
            // r1.4.0 (avatar foto): refresh photoUrl untuk member LAMA (doc dibuat
            // sebelum fitur ini) — update ter-target satu field saat connect, bukan
            // per-launch. Rules mengizinkan self-update photoUrl.
            val currentPhoto = existing.data?.get(Constants.Fields.PHOTO_URL) as? String
            val newPhoto = user?.photoUrl?.toString()
            if (shouldRefreshPhotoUrl(currentPhoto, newPhoto)) {
                selfRef.update(Constants.Fields.PHOTO_URL, newPhoto).await()
            }
            return
        }
        selfRef.set(
            nonNullMap(
                Constants.Fields.UID to uid,
                Constants.Fields.EMAIL to user?.email,
                Constants.Fields.NAME to user?.displayName,
                Constants.Fields.ROLE to role,
                Constants.Fields.LABEL to (user?.displayName ?: Constants.Defaults.LABEL),
                // r1.4.0 (avatar foto): URL foto Google disimpan di member doc
                // sejak awal — device lain bisa menampilkan foto via URL
                // fallback sebelum avatarBytes di-sync.
                Constants.Fields.PHOTO_URL to user?.photoUrl?.toString(),
                // P3 (audit keanggotaan): jam SERVER (bukan jam klien) — konsisten
                // dengan createdAt di dokumen keluarga.
                Constants.Fields.ADDED_AT to FieldValue.serverTimestamp()
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
        // Audit workspace (2026-08-14): aturan "1 akun = 1 workspace". Rules
        // Firestore tidak bisa query collectionGroup, jadi guard di sisi app:
        // sebelum membuat workspace BARU, pastikan akun belum jadi OWNER di
        // workspace lain — kalau sudah, blokir (harus hapus/wariskan dulu).
        // Ini mencegah penumpukan workspace sampah (semua akun tes di DB punya
        // 2–7 workspace karena dulu tidak ada guard ini).
        val memberDocs = try {
            db().collectionGroup(Constants.Collections.MEMBERS)
                .whereEqualTo(Constants.Fields.UID, uid)
                .get().await()
                .documents.mapNotNull { d ->
                    val otherPin = d.reference.parent.parent?.id
                    if (otherPin.isNullOrBlank()) null else otherPin to d.getString(Constants.Fields.ROLE)
                }
        } catch (e: Exception) {
            Log.w(TAG, "ensureOwnerWorkspace: cek kepemilikan gagal: ${e.message}")
            return OwnerSetupResult.FAILED
        }
        if (ownsWorkspaceElsewhere(memberDocs, pin)) {
            return OwnerSetupResult.OWNED_ELSEWHERE
        }
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
     * Sesuai rules: dokumen keluarga TIDAK dibaca non-anggota, jadi keberadaan
     * PIN ditentukan lewat hasil [requestJoin] (create ditolak rules bila
     * keluarga tidak ada) — bukan di sini.
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
                        // r1.4.0 (avatar foto): URL foto Google pemohon dibawa ke
                        // join request → disalin ke member doc saat disetujui.
                        Constants.Fields.PHOTO_URL to user?.photoUrl?.toString(),
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
                            // r1.4.0 (avatar foto): URL foto Google pemohon disalin
                            // ke member doc saat persetujuan. Blank → null →
                            // di-drop nonNullMap (hindari string kosong di doc).
                            Constants.Fields.PHOTO_URL to request.photoUrl.takeIf { it.isNotBlank() },
                            // P3 (audit keanggotaan): jam server, bukan jam klien
                            // (selisih jam antar perangkat tidak memengaruhi addedAt).
                            Constants.Fields.ADDED_AT to FieldValue.serverTimestamp()
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

    /**
     * Temukan workspace milik akun (r1.4.0 — auto-connect).
     *
     * Query collectionGroup `members` by uid → setiap doc member milik akun;
     * PIN workspace = id dokumen KELUARGA (parent dari doc member). Dengan
     * model 1 akun = 1 workspace aktif, hasil biasanya 0 atau 1 — tapi user
     * lama bisa punya >1 (sebelum ada fitur keluar), jadi pemanggil harus
     * menangani daftar.
     *
     * Return null kalau query GAGAL (offline/error) — pemanggil HARUS
     * memperlakukan null sebagai "tidak tahu", BUKAN "tidak terikat":
     * fallback ke PIN lokal supaya alur offline-first tidak putus.
     */
    suspend fun discoverMyWorkspaces(uid: String? = null): List<MyWorkspace>? {
        val myUid = uid ?: currentUid() ?: return null
        return try {
            db().collectionGroup(Constants.Collections.MEMBERS)
                .whereEqualTo(Constants.Fields.UID, myUid)
                .get().await()
                .documents.mapNotNull { doc ->
                    val pin = doc.reference.parent.parent?.id
                    if (pin.isNullOrBlank()) return@mapNotNull null
                    val role = doc.data?.get(Constants.Fields.ROLE) as? String ?: ROLE_MEMBER
                    val name = doc.data?.get(Constants.Fields.NAME) as? String ?: ""
                    MyWorkspace(pin = pin, role = role, name = name)
                }
        } catch (e: Exception) {
            Log.w(TAG, "discoverMyWorkspaces gagal: ${e.message}")
            null
        }
    }

    /**
     * Keluar dari workspace (r1.4.0): hapus doc members/{uid} diri sendiri.
     *
     * Guard anti-yatim (keputusan r1.4.0): owner TIDAK boleh keluar kalau dia
     * satu-satunya owner — wajib promote anggota lain jadi owner DULU
     * (dikembalikan sebagai [LeaveWorkspaceResult.NEED_OWNER_TRANSFER]). Guard
     * ini di sisi app karena rules Firestore tidak bisa query daftar member.
     *
     * Catatan: setelah keluar, data lokal masih ada sampai pemanggil
     * membersihkannya ([MainViewModel.clearLocalData] + hapus PIN lokal).
     */
    suspend fun leaveWorkspace(pin: String): LeaveWorkspaceResult {
        val uid = currentUid() ?: return LeaveWorkspaceResult.FAILED
        return try {
            val membersRef = db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.MEMBERS)
            val selfRef = membersRef.document(uid)
            val self = selfRef.get().await()
            if (!self.exists()) return LeaveWorkspaceResult.FAILED
            val myRole = self.getString(Constants.Fields.ROLE) ?: ROLE_MEMBER
            // Owner: hitung owner LAIN (bukan diri sendiri) untuk guard anti-yatim.
            val otherOwners = if (myRole == ROLE_OWNER) {
                membersRef.get().await().documents.count { d ->
                    d.id != uid && d.getString(Constants.Fields.ROLE) == ROLE_OWNER
                }
            } else 0
            if (!canLeaveWorkspace(myRole, otherOwners)) {
                return LeaveWorkspaceResult.NEED_OWNER_TRANSFER
            }
            selfRef.delete().await()
            LeaveWorkspaceResult.LEFT
        } catch (e: Exception) {
            Log.w(TAG, "leaveWorkspace gagal: ${e.message}")
            LeaveWorkspaceResult.FAILED
        }
    }

    /**
     * Upload foto avatar Diri Sendiri ke Firestore (r1.2.3 — P1): bytes JPEG
     * kecil (sudah dikompresi via [AvatarStore.compressAvatarForCloud]) + versi
     * timestamp supaya perangkat lain tahu cache lokalnya kedaluwarsa.
     *
     * Rules mengizinkan tiap anggota meng-update field avatarBytes/avatarVersion
     * miliknya sendiri (lihat firestore.rules). [bytes] null → reset avatar
     * (hapus foto dari cloud, member lain kembali ke inisial berwarna).
     *
     * Return true hanya bila upload BENAR-BENAR berhasil — pemanggil
     * (MainActivity) memakai ini untuk menandai pref "terakhir di-upload".
     * Tanpa return ini, upload yang gagal (mis. offline) ikut ditandai sukses
     * dan tidak pernah dicoba ulang sampai user mengganti foto lagi (review P1).
     */
    suspend fun uploadMyAvatar(pin: String, bytes: ByteArray?): Boolean {
        val uid = currentUid() ?: return false
        return runCatching {
            val updates = mutableMapOf<String, Any>()
            if (bytes != null) {
                updates[Constants.Fields.AVATAR_BYTES] = com.google.firebase.firestore.Blob.fromBytes(bytes)
                updates[Constants.Fields.AVATAR_VERSION] = System.currentTimeMillis()
            } else {
                // Reset: hapus field avatar (Firestore menerima FieldValue.delete).
                updates[Constants.Fields.AVATAR_BYTES] = FieldValue.delete()
                updates[Constants.Fields.AVATAR_VERSION] = FieldValue.delete()
            }
            db().collection(Constants.Collections.FAMILIES).document(pin)
                .collection(Constants.Collections.MEMBERS).document(uid)
                .update(updates).await()
            true
        }.onFailure { Log.w(TAG, "uploadMyAvatar gagal: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Build map nama-tampilan → path foto avatar (r1.2.3 — P1) untuk header
     * chat & topbar. Kunci = nama & label member (sender pesan bisa salah satu),
     * plus nama user lokal sendiri bila punya foto. Murni (tanpa baca state
     * internal) supaya bisa di-remember reaktif oleh UI layer.
     */
    fun buildAvatarNameMap(
        members: List<FamilyMember>,
        memberAvatarPaths: Map<String, String>,
        myName: String?,
        myAvatarPath: String?
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        members.forEach { member ->
            val path = memberAvatarPaths[member.uid] ?: return@forEach
            if (member.name.isNotBlank()) map[member.name] = path
            if (member.label.isNotBlank()) map[member.label] = path
        }
        if (myName != null && myAvatarPath != null) map[myName] = myAvatarPath
        return map
    }
}
