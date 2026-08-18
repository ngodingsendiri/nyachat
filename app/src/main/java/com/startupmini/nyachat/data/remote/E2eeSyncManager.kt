package com.startupmini.nyachat.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.backup.BackupCrypto
import com.startupmini.nyachat.data.crypto.WorkspaceCrypto
import com.startupmini.nyachat.data.local.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * r1.7.0 — Siklus hidup kunci E2EE per workspace.
 *
 * Model (terinspirasi Signal, disederhanakan untuk keluarga):
 *  - Setiap perangkat punya keypair EC P-256 (private key di SecureStorage /
 *    Android Keystore; public key ditulis ke member doc Firestore).
 *  - Workspace punya SATU grup key AES-256 (dibuat device OWNER saat marker
 *    `families/{PIN}/e2ee/e2ee` pertama kali dibuat). Grup key di-wrap
 *    (EciesWrap) ke tiap perangkat & disimpan di `families/{PIN}/e2eeKeys/{uid}`.
 *  - Pemilik kunci = siapapun yang punya grup key → bisa meng-wrap untuk member
 *    baru yang sudah mempublikasikan public key-nya (self-heal berkala).
 *
 * Alur aktivasi (migrasi deterministic):
 *  1. Owner membuka app → keypair dibuat, public key di-sync ke member doc.
 *  2. Owner melihat marker belum ada → generate grup key + tulis marker +
 *     wrap untuk semua member yang sudah punya public key (termasuk dirinya).
 *  3. Member membuka app → keypair + public key di-sync → marker sudah ada →
 *     ambil wrap-nya sendiri (`e2eeKeys/{uid}`) → unwrap dengan private key.
* 4. Setelah ini, FirestoreSyncManager mengenkripsi semua tulis baru
 *     (messages & transactions) dan mendekripsi saat merge (Room tetap polos).
 *
 * Pemulihan perangkat (r1.7.1): wrap `e2eeKeys/{uid}` membawa `e2eeKeyVersion`
 * (versi pubkey saat di-wrap). Self-heal me-REWRAP setiap kali versi pubkey
 * member lebih baru dari versi di wrap — menutup kasus reinstall/perangkat
 * baru yang membuat pubkey baru (member doc versi naik → perangkat mana pun
 * yang memegang grup key me-rewrap; perangkat yang belum punya kunci retry
 * ambil wrap-nya tiap self-heal berkala). Bila unwrap gagal (pubkey member doc
 * milik perangkat lain), republish pubkey sendiri untuk memicu rewrap.
 *
 * Tidak ada kunci = pesan lama plaintext tetap terbaca; hanya pesan BARU
 * setelah aktivasi yang terenkripsi & ephemeral (lihat Constants.MsgVersion).
 *
 * BATASAN (dokumentasi): SATU slot wrap per member → hanya perangkat terbaru
 * yang bisa re-fetch wrap (perangkat lain memakai grup key dari cache lokal).
 * Kasus fatal: SEMUA perangkat ter-wipe → grup key hilang permanen (butuh
 * fitur rotate key, di luar scope r1.7.x).
 */
object E2eeSyncManager {

    private const val TAG = "E2eeSync"
    private const val MARKER_DOC = "e2ee"
    /** Jeda self-heal: cek member baru/public key baru untuk di-wrap. */
    private const val HEAL_INTERVAL_MS = 60_000L

    @Volatile private var familyId: String = ""
    @Volatile private var appContext: Context? = null
    @Volatile private var activeRole: String = Constants.Roles.MEMBER
    @Volatile private var markerActive = false
    @Volatile private var groupKeyBytes: ByteArray? = null
    @Volatile private var markerListener: ListenerRegistration? = null

    /** r1.7.1: status E2EE untuk UI (banner "mempersiapkan enkripsi" & gating
     *  kirim). Dipublikasikan tiap perubahan marker/kunci (start/heal/stop). */
    data class E2eeStatus(val active: Boolean = false, val ready: Boolean = false)
    private val _status = MutableStateFlow(E2eeStatus())
    val status: StateFlow<E2eeStatus> = _status.asStateFlow()

    @Volatile private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ======================= Status publik =======================

    /** Grup key workspace aktif (null sebelum aktivasi / belum bisa di-unwrap). */
    fun currentGroupKey(): ByteArray? = groupKeyBytes

    /** Marker E2EE sudah ada di server (workspace sudah "terenkripsi"). */
    fun isActive(): Boolean = markerActive

    /** Siap enkripsi/dekripsi: marker aktif DAN grup key tersedia lokal. */
    fun isReady(): Boolean = markerActive && groupKeyBytes != null

    // ======================= Start / stop =======================

    /** Mulai siklus kunci untuk workspace [pin]. Dipanggil SyncLifecycle. */
    suspend fun start(context: Context, pin: String, role: String) {
        stop()
        familyId = pin
        activeRole = role
        appContext = context.applicationContext
        runCatching {
            // 1) Keypair perangkat (dibuat bila belum ada) & sync public key.
            val pub = ensureKeyPair()
            syncPubKeyToMemberDoc(pub)
            // 2) Grup key lokal (kalau sudah pernah disimpan).
            loadGroupKey()
            // 3) Marker: aktifkan bila OWNER; kalau sudah aktif → ambil kunci.
            ensureActivation()
            if (markerActive && groupKeyBytes == null) fetchAndUnwrapGroupKey()
            // 4) Wrap untuk member yang belum punya (termasuk diri sendiri bila baru).
            selfHealWraps()
            notifyReady()
            publishStatus()
            // 5) Pemantau realtime + self-heal berkala.
            listenForActivation()
            startPeriodicHeal()
        }.onFailure { Log.w(TAG, "E2EE start gagal: ${it.message}") }
    }

    /** Hentikan semua aktivitas E2EE (saat logout / ganti workspace). */
    fun stop() {
        markerListener?.remove(); markerListener = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        familyId = ""
        activeRole = Constants.Roles.MEMBER
        appContext = null
        markerActive = false
        groupKeyBytes = null
        _status.value = E2eeStatus()
    }

    /**
     * Self-heal sekali jalan (dipanggil dari start & marker listener): pastikan
     * setiap member yang sudah punya public key punya wrap grup key, dan jika
     * grup key belum ada tapi marker sudah aktif → ambil dari wrap sendiri.
     */
    suspend fun heal() {
        if (familyId.isEmpty()) return
        runCatching {
            val uid = uid() ?: return@runCatching
            ensureActivation()
            if (markerActive && groupKeyBytes == null) fetchAndUnwrapGroupKey()
            selfHealWraps()
            notifyReady()
            publishStatus()
        }.onFailure { Log.w(TAG, "E2EE heal gagal: ${it.message}") }
    }

    /** Wrap grup key untuk member baru (dipanggil MembershipManager.approveJoin). */
    suspend fun wrapForNewMember(uid: String) {
        if (familyId.isEmpty() || !markerActive || groupKeyBytes == null) return
        runCatching {
            val memberDoc = membersRef().document(uid).get().await()
            val pub = memberDoc.getString(Constants.Fields.E2EE_PUB_KEY) ?: return
            val memberVersion = memberDoc.getLong(Constants.Fields.E2EE_KEY_VERSION)
            val wrapSnap = e2eeKeysRef().document(uid).get().await()
            if (wrapSnap.exists() && !needsRewrap(
                    wrapSnap.getLong(Constants.Fields.E2EE_KEY_VERSION), memberVersion
                )
            ) return
            e2eeKeysRef().document(uid)
                .set(mapOf(
                    Constants.Fields.E2EE_KEY_BYTES to WorkspaceCrypto.wrapGroupKey(groupKeyBytes!!, pub),
                    Constants.Fields.E2EE_KEY_VERSION to (memberVersion ?: 0L)
                ))
                .await()
        }.onFailure { Log.w(TAG, "wrapForNewMember gagal: ${it.message}") }
    }

    // ======================= Aktivasi & pengambilan kunci =======================

    /** Kalau marker sudah ada di server (dibuat owner) → tandai aktif. Semua
     *  peran membaca marker (tidak hanya owner) supaya keputusan "workspace
     *  terenkripsi" segera akurat — menutup race di start: member tidak boleh
     *  mengirim plaintext hanya karena marker listener belum menembak. */
    private suspend fun ensureActivation() {
        if (markerActive) return
        if (markerRef().get().await().exists()) {
            markerActive = true
            return
        }
        if (activeRole != Constants.Roles.OWNER) return
        activateNow()
    }

    /** OWNER membuat marker + grup key + wrap awal (hanya sekali, deterministic). */
    private suspend fun activateNow() {
        val groupKey = WorkspaceCrypto.generateGroupKey()
        storeGroupKey(groupKey)
        markerRef().set(mapOf(Constants.Fields.E2EE_ACTIVATED to FieldValue.serverTimestamp())).await()
        markerActive = true
        Log.d(TAG, "E2EE diaktifkan untuk workspace (marker dibuat owner)")
    }

    /** Ambil wrap grup key milik perangkat ini (`e2eeKeys/{uid}`) lalu unwrap. */
    private suspend fun fetchAndUnwrapGroupKey() {
        val uid = uid() ?: return
        val wrapped = e2eeKeysRef().document(uid).get().await()
            .getString(Constants.Fields.E2EE_KEY_BYTES) ?: return
        val priv = loadPrivateKey() ?: return
        val key = runCatching { WorkspaceCrypto.unwrapGroupKey(wrapped, priv) }.getOrNull()
        if (key == null) {
            // Wrap tidak bisa dibuka (biasanya karena pubkey member doc milik
            // perangkat lain, atau wrap lama dari keypair yang dihapus). Re-publish
            // pubkey sendiri (naikkan versi) → perangkat pemegang grup key
            // me-rewrap pada self-heal berikutnya.
            Log.w(TAG, "Grup key gagal dibuka — republish pubkey untuk memicu rewrap")
            syncPubKeyToMemberDoc(ensureKeyPair())
            return
        }
        storeGroupKey(key)
        Log.d(TAG, "Grup key workspace diterima & dibuka")
    }

    /**
     * Pastikan SEMUA member yang sudah punya public key memiliki wrap grup key
     * yang sesuai dengan pubkey-nya. Re-wrap bila: wrap belum ada, ATAU versi
     * pubkey member lebih baru dari versi yang tersimpan di wrap (reinstall /
     * perangkat baru → pubkey berubah). Idempoten.
     */
    private suspend fun selfHealWraps() {
        val groupKey = groupKeyBytes ?: return
        if (familyId.isEmpty()) return
        val docs = membersRef().get().await()
        for (doc in docs.documents) {
            val pub = doc.getString(Constants.Fields.E2EE_PUB_KEY) ?: continue
            val memberUid = doc.id
            val memberVersion = doc.getLong(Constants.Fields.E2EE_KEY_VERSION)
            val wrapRef = e2eeKeysRef().document(memberUid)
            val wrapSnap = wrapRef.get().await()
            if (wrapSnap.exists() && !needsRewrap(
                    wrapSnap.getLong(Constants.Fields.E2EE_KEY_VERSION), memberVersion
                )
            ) continue
            val wrapped = runCatching { WorkspaceCrypto.wrapGroupKey(groupKey, pub) }.getOrNull() ?: continue
            wrapRef.set(mapOf(
                Constants.Fields.E2EE_KEY_BYTES to wrapped,
                Constants.Fields.E2EE_KEY_VERSION to (memberVersion ?: 0L)
            )).await()
        }
    }

    /** true bila wrap perlu ditulis ulang: versi pubkey di wrap != versi pubkey
     *  member saat ini (wrap legacy tanpa versi dianggap 0 → di-rewrap). */
    internal fun needsRewrap(wrapVersion: Long?, memberVersion: Long?): Boolean =
        (wrapVersion ?: 0L) != (memberVersion ?: 0L)

    private suspend fun ensureKeyPair(): String {
        val ctx = appContext ?: return ""
        val existingPub = SecureStorage.getSecretAsync(ctx, pubKeyKey())
        if (!existingPub.isNullOrBlank()) return existingPub
        val kp = WorkspaceCrypto.generateKeyPair()
        val pub = WorkspaceCrypto.publicKeyBase64(kp.public)
        SecureStorage.putSecretAsync(ctx, privKeyKey(), BackupCrypto.encodeBase64(kp.private.encoded))
        SecureStorage.putSecretAsync(ctx, pubKeyKey(), pub)
        return pub
    }

    private suspend fun syncPubKeyToMemberDoc(pub: String) {
        val uid = uid() ?: return
        val memberRef = membersRef().document(uid)
        val snap = memberRef.get().await()
        if (!snap.exists()) return // belum jadi anggota (masih pending) — tunggu
        if (snap.getString(Constants.Fields.E2EE_PUB_KEY) == pub) return
        memberRef.update(
            mapOf(
                Constants.Fields.E2EE_PUB_KEY to pub,
                // Naik tiap regenerasi kunci (ganti perangkat / reset) —
                // penanda "kunci baru" untuk self-heal & debugging.
                Constants.Fields.E2EE_KEY_VERSION to FieldValue.increment(1)
            )
        ).await()
    }

    private suspend fun loadGroupKey() {
        if (groupKeyBytes != null) return
        val ctx = appContext ?: return
        val b64 = SecureStorage.getSecretAsync(ctx, groupKeyKey()) ?: return
        groupKeyBytes = runCatching { BackupCrypto.decodeBase64(b64) }.getOrNull()
    }

    private suspend fun storeGroupKey(key: ByteArray) {
        groupKeyBytes = key
        val ctx = appContext ?: return
        SecureStorage.putSecretAsync(ctx, groupKeyKey(), BackupCrypto.encodeBase64(key))
    }

    private suspend fun loadPrivateKey(): PrivateKey? {
        val ctx = appContext ?: return null
        val privB64 = SecureStorage.getSecretAsync(ctx, privKeyKey()) ?: return null
        return runCatching {
            KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(BackupCrypto.decodeBase64(privB64)))
        }.getOrNull()
    }

    // ======================= Real-time & berkala =======================

    /** Pantau marker: begitu owner mengaktifkan, langsung ambil kunci. */
    private fun listenForActivation() {
        markerListener?.remove()
        markerListener = markerRef().addSnapshotListener { snap, err ->
            if (err != null || familyId.isEmpty()) return@addSnapshotListener
            if (snap != null && snap.exists() && !markerActive) {
                markerActive = true
                scope.launch {
                    if (groupKeyBytes == null) fetchAndUnwrapGroupKey()
                    selfHealWraps()
                    notifyReady()
                    publishStatus()
                }
            }
        }
    }

    /** Self-heal berkala: tangkap member/public-key baru, me-rewrap bila versi
     *  berubah, DAN retry ambil kunci bila perangkat ini belum punya grup key
     *  (kunci bisa datang belakangan — mis. setelah perangkat lain me-rewrap
     *  pasca-reinstall). */
    private fun startPeriodicHeal() {
        scope.launch {
            while (familyId.isNotEmpty()) {
                delay(HEAL_INTERVAL_MS)
                heal()
            }
        }
    }

    /** Beri tahu FirestoreSyncManager bahwa kunci siap → proses ulang yang tertunda. */
    private fun notifyReady() {
        if (isReady()) FirestoreSyncManager.onE2eeKeyReady()
    }

    /** Publikasikan status E2EE untuk UI (banner & gating kirim). */
    private fun publishStatus() {
        _status.value = E2eeStatus(active = markerActive, ready = isReady())
    }

    // ======================= Referensi & util =======================

    private fun db() = FirebaseFirestore.getInstance()
    private fun familyRef() = db().collection(Constants.Collections.FAMILIES).document(familyId)
    private fun membersRef() = familyRef().collection(Constants.Collections.MEMBERS)
    private fun e2eeKeysRef() = familyRef().collection(Constants.Collections.E2EE_KEYS)
    private fun markerRef() = familyRef().collection(Constants.Collections.E2EE).document(MARKER_DOC)

    private fun uid(): String? =
        runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private fun safePin(): String = familyId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun privKeyKey() = "e2ee_priv_${safePin()}"
    private fun pubKeyKey() = "e2ee_pub_${safePin()}"
    private fun groupKeyKey() = "e2ee_group_${safePin()}"
}
