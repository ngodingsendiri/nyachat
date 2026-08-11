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
    }

    // ===== Firestore document field names =====
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
        const val MESSAGE_TEXT = "messageText"
        const val TIMESTAMP = "timestamp"
        const val IS_FINANCIAL = "isFinancial"
        const val DETECTED_AMOUNT = "detectedAmount"
        const val DETECTED_CATEGORY = "detectedCategory"
        const val DETECTED_TYPE = "detectedType"
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
    }

    // ===== Peran workspace (wajib sama dengan rules) =====
    object Roles {
        const val OWNER = "owner"
        const val MEMBER = "member"
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

    // ===== Default values =====
    object Defaults {
        const val ROLE = Roles.MEMBER
        const val LABEL = "Anggota"
        /** Panjang PIN untuk workspace BARU (8 digit = ruang kunci 10^8). */
        const val PIN_LENGTH = 8
        /** Panjang minimal PIN yang diterima saat join (kompatibilitas PIN 6 digit lama). */
        const val PIN_MIN_LEGACY_LENGTH = 6
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