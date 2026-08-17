package com.startupmini.nyachat

/**
 * Konstanta global aplikasi.
 * Tujuan: menghindari typo string literal berulang di seluruh kode (pref key,
 * nama koleksi/field Firestore, role, dsb.).
 */
object Constants {

    // ===== SharedPreferences keys =====
    object Prefs {
        const val IS_DARK_MODE = "is_dark_mode"
        const val WORKSPACE_PIN = "workspace_pin"
        const val WORKSPACE_ROLE = "workspace_role"
        const val USER_NAME = "user_name"
        const val GEMINI_API_KEY = "gemini_api_key"
        const val OPENROUTER_API_KEY = "openrouter_api_key"
        const val LAST_UPDATE_CHECK = "last_update_check"
        const val LAST_AUTO_BACKUP = "last_auto_backup"
        // Status enkripsi FILE backup terakhir yang berhasil dibuat (bukan setting
        // toggle) — supaya label "Backup terakhir … Terenkripsi" mencerminkan
        // isi file di Drive, bukan toggle yang berubah setelahnya.
        const val LAST_BACKUP_ENCRYPTED = "last_backup_encrypted"
        const val BACKUP_ENCRYPTED = "backup_encrypted"
        // M5: passphrase otomatis untuk backup terenkripsi diam-diam (auto-backup
        // 24 jam). Disimpan di SecureStorage (Android Keystore), bukan prefs biasa
        // — passphrase yang dipakai user manual (dialog) TETAP dipakai untuk
        // backup manual; kunci ini khusus jalur silentBackup.
        const val BACKUP_AUTO_PASSPHRASE = "backup_auto_passphrase"
        // 3.7: toggle notifikasi chat (default ON).
        const val CHAT_NOTIFICATIONS_ENABLED = "chat_notifications_enabled"
        // 3.7: penanda dialog izin notifikasi sudah pernah ditanya (jangan spam).
        const val NOTIF_PERMISSION_ASKED = "notif_permission_asked"
        // Profil & Akun (r1.2.1): sumber avatar user (null/google/custom).
        // Path avatar TIDAK disimpan — selalu diturunkan dari sumbernya
        // (custom → AvatarStore.getCustomAvatarPath(), google → cache lokal
        // google_<uid>.jpg). Foto Google hanya di-cache lokal, akun Google
        // tidak pernah diubah. null = auto (google bila ada, else inisial).
        const val AVATAR_SOURCE = "avatar_source"
        // Email akun Google yang dipakai login — untuk ditampilkan di Profil
        // & Akun (FirebaseAuth bisa null setelah logout, jadi di-snapshot).
        const val USER_EMAIL = "user_email"
        // r1.2.3 (P1): path avatar terakhir yang sudah di-upload ke Firestore —
        // supaya tidak upload ulang foto yang sama di setiap buka app.
        const val LAST_UPLOADED_AVATAR = "last_uploaded_avatar"
        // Audit keanggotaan: nama user sudah pernah disinkronkan ke member doc
        // Firestore — supaya SyncLifecycle tidak menulis ulang di SETIAP buka app
        // (biaya write). Di-reset saat logout (clear) & diset ulang saat rename/
        // connect berikutnya.
        const val NAME_SYNCED = "name_synced"
        // r1.7.0 (E2EE): public key perangkat kita sudah pernah ditulis ke member
        // doc Firestore — supaya tidak menulis ulang di SETIAP buka app. Di-reset
        // saat logout/ganti perangkat/workspace.
        const val E2EE_PUBKEY_SYNCED = "e2ee_pubkey_synced"
    }

    // ===== Link eksternal =====
    object Links {
        /** Path repo GitHub (owner/repo) — SATU sumber kebenaran; dipakai
         *  update checker (API GitHub) & kebijakan privasi. Sebelumnya
         *  GitHubUpdateChecker menduplikasi literal ini (audit remote/ 2026-08-13)
         *  — rename repo tidak merambat ke API update → 404 diam-diam. */
        const val GITHUB_OWNER_REPO = "ngodingsendiri/nyachat"
        /** Repo publik aplikasi (update & kebijakan). */
        const val REPO = "https://github.com/$GITHUB_OWNER_REPO"
        /** Kebijakan privasi — dibuka dari Pengaturan → Tentang. */
        const val PRIVACY_POLICY = "$REPO/blob/main/PRIVACY_POLICY.md"
    }

    // ===== Sumber foto profil (nilai AVATAR_SOURCE) =====
    object AvatarSources {
        const val GOOGLE = "google"
        const val CUSTOM = "custom"
    }

    // ===== Firestore collection names =====
    object Collections {
        const val FAMILIES = "families"
        const val MEMBERS = "members"
        const val JOIN_REQUESTS = "joinRequests"
        const val MESSAGES = "messages"
        const val TRANSACTIONS = "transactions"
        // r1.7.0 (E2EE): marker aktivasi E2EE — doc `families/{PIN}/e2ee` yang
        // dibuat oleh device OWNER (migrasi deterministik: kapan mulai enkripsi).
        const val E2EE = "e2ee"
        // r1.7.0 (E2EE): doc `families/{PIN}/e2eeKeys/{uid}` berisi grup key
        // workspace yang di-wrap ke perangkat {uid} (EciesWrap).
        const val E2EE_KEYS = "e2eeKeys"
        // r1.7.0 (chat ephemeral): subkoleksi ACK per penerima —
        // `families/{PIN}/messages/{cloudId}/deliveries/{uid}`. Sebuah pesan
        // dihapus dari server begitu semua member menulis ACK (model WA).
        const val DELIVERIES = "deliveries"
    }

    // ===== Firestore document field names =====
    // Nama field pesan/transaksi adalah KONTRAK CLOUD — dipakai write map
    // FirestoreSyncManager, JSON backup/pending op (DataExporter), dan anotasi
    // @PropertyName DTO (CloudMessage/CloudTransaction). Nilai TIDAK boleh
    // berubah (data lintas perangkat & backup lama bergantung padanya) —
    // dijaga ConstantsTest.
    object Fields {
        const val OWNER_ID = "ownerId"
        const val CREATED_AT = "createdAt"
        const val UID = "uid"
        const val EMAIL = "email"
        const val NAME = "name"
        const val ROLE = "role"
        const val LABEL = "label"
        const val ADDED_AT = "addedAt"
        const val REQUESTED_AT = "requestedAt"
        const val CLOUD_ID = "cloudId"
        const val SENDER = "sender"
        // r1.6.1 (audit pesan): uid penulis pesan — dipakai (1) rules Firestore
        // mengikat penulis (anggota tidak bisa mengatasnamakan anggota lain),
        // dan (2) FCM self-skip presisi per-uid (bukan per-nama yang bisa
        // bertabrakan).
        const val SENDER_UID = "senderUid"
        const val MESSAGE_TEXT = "messageText"
        // r1.6.1 (audit pesan): path file foto di Firebase Storage
        // (families/{PIN}/messages/{cloudId}.jpg). Penerima mengunduh & menyimpan
        // ke penyimpanan lokal sebelum menampilkan bubble.
        const val IMAGE_URL = "imageUrl"
        const val TIMESTAMP = "timestamp"
        const val IS_FINANCIAL = "isFinancial"
        const val DETECTED_AMOUNT = "detectedAmount"
        const val DETECTED_CATEGORY = "detectedCategory"
        const val DETECTED_TYPE = "detectedType"
        // r1.4.0 (audit Finance AI): jumlah transaksi yang direkap dari satu pesan
        // (badge multi-transaksi tanpa netting).
        const val DETECTED_COUNT = "detectedCount"
        // r1.4.0 (badge campuran): true jika pesan berisi PEMASUKAN DAN
        // PENGELUARAN sekaligus — badge menampilkan warna paduan pelangi.
        const val HAS_MIXED_TYPES = "hasMixedTypes"
        const val REPLY_TO_SENDER = "replyToSender"
        const val REPLY_TO_TEXT = "replyToText"
        const val EDITED_AT = "editedAt"
        const val TYPE = "type"
        const val CATEGORY = "category"
        const val AMOUNT = "amount"
        const val DESCRIPTION = "description"
        const val LOGGED_BY = "loggedBy"
        const val CHAT_MESSAGE_ID = "chatMessageId"
        const val FCM_TOKEN = "fcmToken"
        // r1.2.3 (P1): avatar foto member — bytes JPEG kecil (Blob Firestore) +
        // version untuk cache invalidation antar perangkat.
        const val AVATAR_BYTES = "avatarBytes"
        const val AVATAR_VERSION = "avatarVersion"
        // r1.4.0 (avatar foto): URL foto Google anggota — fallback avatar di
        // device lain saat avatarBytes belum pernah di-sync (URL foto Google
        // publik, bisa diunduh langsung).
        const val PHOTO_URL = "photoUrl"
        // M7: asal deteksi transaksi ("AI" | "HEURISTIK") di pesan chat.
        const val DETECTED_BY = "detectedBy"
        // M4: penanda waktu server Firestore — resolusi konflik deterministik.
        const val SERVER_UPDATED_AT = "serverUpdatedAt"
        // r1.2.4: relasi lintas perangkat transaksi → pesan (lookup via cloudId pesan).
        const val SOURCE_MESSAGE_CLOUD_ID = "sourceMessageCloudId"
        // r1.6.0: nama custom workspace & plan langganan di doc keluarga.
        const val PLAN = "plan"
        // r1.6.0: penanda keputusan pada join request (rejected) sebelum doc
        // dihapus — dibaca cloud function handleJoinRequest untuk notifikasi hasil.
        // Approve TIDAK memakai penanda: transaksi Firestore tidak bisa 2x menulis
        // doc yang sama, jadi status approve diturunkan dari keberadaan member doc
        // (ditulis atomik bersama delete request).
        const val JOIN_REQUEST_STATUS = "status"
        const val JOIN_STATUS_REJECTED = "rejected"
        // r1.6.0 (presence): penanda waktu aktivitas terakhir anggota — di-update
        // berkala (heartbeat) selama app di foreground. Menentukan siapa yang
        // "online" di topbar (lihat Constants.Presence.ONLINE_WINDOW_MS).
        const val LAST_ACTIVE_AT = "lastActiveAt"
        // r1.7.0 (E2EE): hasil enkripsi AES-GCM konten pesan/transaksi —
        // format `ivB64.ctB64` (lihat WorkspaceCrypto). Ada TANPA messageText/
        // amount dsb. saat msgVersion=1 (server hanya melihat ciphertext).
        const val ENC = "enc"
        // r1.7.0 (E2EE): versi format pesan — 0 = plaintext (legacy), 1 =
        // terenkripsi. Dipakai rules Firestore & app untuk memilih jalur
        // enkripsi/dekripsi.
        const val MSG_VERSION = "msgVersion"
        // r1.7.0 (E2EE): public key EC P-256 perangkat (Base64 SPKI) di member
        // doc — dipakai untuk meng-wrap grup key ke perangkat itu.
        const val E2EE_PUB_KEY = "e2eePubKey"
        // r1.7.0 (E2EE): versi publik key — naik tiap regenerasi (ganti
        // perangkat). Dipakai self-heal untuk mendeteksi perangkat lama.
        const val E2EE_KEY_VERSION = "e2eeKeyVersion"
        // r1.7.0 (E2EE): grup key workspace yang di-wrap (EciesWrap) —
        // isi doc `families/{PIN}/e2eeKeys/{uid}`.
        const val E2EE_KEY_BYTES = "e2eeKeyBytes"
        // r1.7.0 (E2EE): timestamp aktivasi di doc marker `families/{PIN}/e2ee`.
        const val E2EE_ACTIVATED = "activated"
        // r1.7.0 (chat ephemeral): timestamp ACK di `deliveries/{uid}` doc —
        // pesan dihapus server saat semua member punya ACK lebih muda dari doc.
        const val DELIVERED_AT = "deliveredAt"
    }

    // ===== Peran workspace (wajib sama dengan rules) =====
    object Roles {
        const val OWNER = "owner"
        const val MEMBER = "member"
    }

    // ===== Langganan workspace (r1.6.0) =====
    // Plan menentukan kapasitas anggota: free = 2, pro = 6 (lihat Limits).
    // Nilai tersimpan di doc keluarga (field `plan`) — JANGAN diubah.
    object Plans {
        const val FREE = "free"
        const val PRO = "pro"
    }

    // ===== Batas jumlah anggota per workspace (r1.6.0) =====
    // Enforce di sisi app (approveJoin) — Firestore rules tidak bisa COUNT.
    // Catatan produksi: saat billing Play aktif, tambahkan enforce server-side
    // (cloud function) sebagai lapisan otoritatif.
    object Limits {
        /** Kapasitas maksimum anggota (termasuk owner) untuk plan free. */
        const val FREE_MAX_MEMBERS = 2
        /** Kapasitas maksimum anggota (termasuk owner) untuk plan pro. */
        const val PRO_MAX_MEMBERS = 6
    }

    // ===== Presence (r1.6.0) =====
    // Anggota dianggap "online" bila `lastActiveAt` di member doc masih dalam
    // jendela [ONLINE_WINDOW_MS] dari sekarang. Heartbeat meng-update field itu
    // tiap [HEARTBEAT_INTERVAL_MS] selama app di foreground (bukan background —
    // presence = sedang MEMAKAI app, bukan sekadar terpasang).
    object Presence {
        /** Jeda pembaruan `lastActiveAt` oleh device yang aktif. */
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        /** Jendela online: `lastActiveAt` lebih muda dari ini → dianggap online. */
        const val ONLINE_WINDOW_MS = 3 * 60_000L
        /** Jeda UI topbar menyegarkan status online (lebih jarang dari heartbeat). */
        const val UI_REFRESH_MS = 30_000L
    }

    // ===== Pengirim pesan khusus =====
    object Sender {
        /** Pesan balasan AI (nilai tersimpan di DB & cloud — JANGAN diubah). */
        const val AI = "AI"
        /** Label peran lama yang tersimpan sebagai sender pesan (kompatibilitas). */
        const val BENDARAHA = "Bendahara"
        const val ANGGOTA = "Anggota"
        const val KETUA = "Ketua"
    }

    // ===== Versi format pesan cloud (r1.7.0 — E2EE) =====
    // Nilai field `msgVersion` — JANGAN diubah maknanya. Pesan plaintext lama
    // (0) tetap terbaca selamanya; hanya pesan baru yang terenkripsi (1).
    object MsgVersion {
        /** Pesan lama/legacy — field terbaca polos (messageText, amount, …). */
        const val LEGACY = 0
        /** Pesan terenkripsi — hanya field `enc` + metadata (senderUid, dsb.). */
        const val ENCRYPTED = 1
    }

    // ===== Default values =====
    object Defaults {
        const val ROLE = Roles.MEMBER
        const val LABEL = "Anggota"
        /** Panjang PIN untuk workspace BARU (8 digit = ruang kunci 10^8). */
        const val PIN_LENGTH = 8
        /** Panjang minimal PIN yang diterima saat join (kompatibilitas PIN 6 digit lama). */
        const val PIN_MIN_LEGACY_LENGTH = 6
        /** Nama default workspace bila doc keluarga belum punya field `name`. */
        const val FAMILY_NAME = "Keuangan Bersama"
    }

    // ===== Jenis transaksi (nilai tersimpan di DB & cloud — JANGAN diubah) =====
    object TransactionTypes {
        const val INCOME = "PEMASUKAN"
        const val EXPENSE = "PENGELUARAN"
        val ALL = listOf(INCOME, EXPENSE)
    }

    // ===== Skala radius sudut (audit P2.6 — hindari radius ad-hoc) =====
    object Ui {
        /** dp, elemen kecil (chip, segmen kontrol, icon container). */
        const val CORNER_S = 8
        /** dp, tombol & field. */
        const val CORNER_M = 12
        /** dp, kartu standar & FAB. */
        const val CORNER_L = 16
        /** dp, kartu banner/hero. */
        const val CORNER_XL = 24
    }

    // ===== Kategori transaksi default (satu-satunya sumber kebenaran literal) =====
    // Pemasukan & pengeluaran dipisah (r1.2.2): dulu hanya ada 1 kategori
    // pemasukan ("Gaji & Pemasukan") sehingga dividen/arisan/jualan tercampur.
    // Nama kategori yang SUDAH TERSIMPAN di DB/cloud TIDAK diubah — hanya
    // menambah opsi baru supaya data lama tetap kompatibel.
    object Categories {
        // ---- Pengeluaran ----
        const val GROCERIES = "Groceries & Sembako"
        const val FOOD = "Makanan & Minuman"
        const val UTILITIES = "Tagihan & Utilitas"
        const val KIDS = "Kebutuhan Anak"
        const val TRANSPORT = "Transportasi"
        const val HEALTH = "Kesehatan & Skincare"
        const val ENTERTAINMENT = "Hiburan & Belanja"
        const val DEBT = "Cicilan & Pinjaman"
        const val EDUCATION = "Pendidikan"
        const val SOCIAL = "Sosial & Donasi"
        const val INSURANCE = "Asuransi & Pajak"
        const val MISC = "Lain-lain"

        // ---- Pemasukan ----
        const val SALARY = "Gaji & Pemasukan"
        const val BONUS = "Bonus & Komisi"
        const val BUSINESS = "Usaha & Jualan"
        const val INVESTMENT = "Investasi & Dividen"
        const val GIFT = "Hadiah & Arisan"
        const val CASHBACK = "Cashback & Refund"

        /** Kategori yang relevan untuk transaksi PENGELUARAN. */
        val EXPENSE_ALL = listOf(
            GROCERIES, FOOD, UTILITIES, KIDS, TRANSPORT, HEALTH,
            ENTERTAINMENT, DEBT, EDUCATION, SOCIAL, INSURANCE, MISC
        )

        /** Kategori yang relevan untuk transaksi PEMASUKAN. */
        val INCOME_ALL = listOf(SALARY, BONUS, BUSINESS, INVESTMENT, GIFT, CASHBACK)

        /** Seluruh kategori (urutan: pengeluaran dulu, lalu pemasukan). */
        val ALL = EXPENSE_ALL + INCOME_ALL
    }
}