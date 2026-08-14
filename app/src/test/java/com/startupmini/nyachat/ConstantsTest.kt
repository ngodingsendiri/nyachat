package com.startupmini.nyachat

import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.util.CustomClassMapper
import com.startupmini.nyachat.data.remote.CloudMessage
import com.startupmini.nyachat.data.remote.CloudTransaction
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kontrak nilai [Constants] (audit 2026-08-13).
 *
 * Nama koleksi/field Firestore, key backup JSON, key SharedPreferences, dan
 * nilai peran/pengirim adalah KONTRAK TERSIMPAN — data lintas perangkat,
 * file backup lama, dan pending op di disk bergantung pada nilai persisnya.
 * Mengubah salah satu nilainya secara diam-diam merusak data pengguna, jadi
 * nilai-nilai tersebut dikunci di sini. Mengubah kunci (nama konstanta) boleh;
 * mengubah NILAI harus lewat sini.
 */
class ConstantsTest {

    // ===== Link repo =====
    @Test
    fun `repo github satu sumber kebenaran untuk update checker dan kebijakan`() {
        // GitHubUpdateChecker memakai GITHUB_OWNER_REPO untuk API update — nilai
        // harus konsisten dengan REPO (path dari URL) supaya rename repo merambat
        // ke keduanya (audit remote/ 2026-08-13).
        assertTrue("REPO harus memuat GITHUB_OWNER_REPO", Constants.Links.REPO.endsWith("/" + Constants.Links.GITHUB_OWNER_REPO))
        assertTrue("PRIVACY_POLICY harus menunjuk ke repo", Constants.Links.PRIVACY_POLICY.startsWith(Constants.Links.REPO + "/"))
        assertTrue(Constants.Links.GITHUB_OWNER_REPO.contains("/"))
    }

    // ===== Firestore collection names =====
    @Test
    fun `collection names adalah kontrak Firestore yang stabil`() {
        assertEquals("families", Constants.Collections.FAMILIES)
        assertEquals("members", Constants.Collections.MEMBERS)
        assertEquals("joinRequests", Constants.Collections.JOIN_REQUESTS)
        assertEquals("messages", Constants.Collections.MESSAGES)
        assertEquals("transactions", Constants.Collections.TRANSACTIONS)
    }

    // ===== Firestore document field names =====
    // Semua nilai ini dipakai write map FirestoreSyncManager, JSON
    // backup/pending op (DataExporter), dan anotasi @PropertyName DTO
    // (CloudMessage/CloudTransaction) — TIDAK BOLEH berubah (kontrak cloud).
    @Test
    fun `field names adalah kontrak Firestore yang stabil`() {
        assertEquals("ownerId", Constants.Fields.OWNER_ID)
        assertEquals("createdAt", Constants.Fields.CREATED_AT)
        assertEquals("uid", Constants.Fields.UID)
        assertEquals("email", Constants.Fields.EMAIL)
        assertEquals("name", Constants.Fields.NAME)
        assertEquals("role", Constants.Fields.ROLE)
        assertEquals("label", Constants.Fields.LABEL)
        assertEquals("addedAt", Constants.Fields.ADDED_AT)
        assertEquals("requestedAt", Constants.Fields.REQUESTED_AT)
        assertEquals("cloudId", Constants.Fields.CLOUD_ID)
        assertEquals("sender", Constants.Fields.SENDER)
        assertEquals("messageText", Constants.Fields.MESSAGE_TEXT)
        assertEquals("timestamp", Constants.Fields.TIMESTAMP)
        assertEquals("isFinancial", Constants.Fields.IS_FINANCIAL)
        assertEquals("detectedAmount", Constants.Fields.DETECTED_AMOUNT)
        assertEquals("detectedCategory", Constants.Fields.DETECTED_CATEGORY)
        assertEquals("detectedType", Constants.Fields.DETECTED_TYPE)
        assertEquals("detectedCount", Constants.Fields.DETECTED_COUNT)
        assertEquals("replyToSender", Constants.Fields.REPLY_TO_SENDER)
        assertEquals("replyToText", Constants.Fields.REPLY_TO_TEXT)
        assertEquals("editedAt", Constants.Fields.EDITED_AT)
        assertEquals("type", Constants.Fields.TYPE)
        assertEquals("category", Constants.Fields.CATEGORY)
        assertEquals("amount", Constants.Fields.AMOUNT)
        assertEquals("description", Constants.Fields.DESCRIPTION)
        assertEquals("loggedBy", Constants.Fields.LOGGED_BY)
        assertEquals("chatMessageId", Constants.Fields.CHAT_MESSAGE_ID)
        assertEquals("fcmToken", Constants.Fields.FCM_TOKEN)
        assertEquals("avatarBytes", Constants.Fields.AVATAR_BYTES)
        assertEquals("avatarVersion", Constants.Fields.AVATAR_VERSION)
        assertEquals("photoUrl", Constants.Fields.PHOTO_URL)
        assertEquals("detectedBy", Constants.Fields.DETECTED_BY)
        assertEquals("serverUpdatedAt", Constants.Fields.SERVER_UPDATED_AT)
        assertEquals("sourceMessageCloudId", Constants.Fields.SOURCE_MESSAGE_CLOUD_ID)
    }

    @Test
    fun `field names tidak kosong dan unik`() {
        val values = listOf(
            Constants.Fields.OWNER_ID, Constants.Fields.CREATED_AT, Constants.Fields.UID,
            Constants.Fields.EMAIL, Constants.Fields.NAME, Constants.Fields.ROLE,
            Constants.Fields.LABEL, Constants.Fields.ADDED_AT, Constants.Fields.REQUESTED_AT,
            Constants.Fields.CLOUD_ID, Constants.Fields.SENDER, Constants.Fields.MESSAGE_TEXT,
            Constants.Fields.TIMESTAMP, Constants.Fields.IS_FINANCIAL,
            Constants.Fields.DETECTED_AMOUNT, Constants.Fields.DETECTED_CATEGORY,
            Constants.Fields.DETECTED_TYPE, Constants.Fields.DETECTED_COUNT,
            Constants.Fields.REPLY_TO_SENDER, Constants.Fields.REPLY_TO_TEXT,
            Constants.Fields.EDITED_AT, Constants.Fields.TYPE, Constants.Fields.CATEGORY,
            Constants.Fields.AMOUNT, Constants.Fields.DESCRIPTION,
            Constants.Fields.LOGGED_BY, Constants.Fields.CHAT_MESSAGE_ID,
            Constants.Fields.FCM_TOKEN, Constants.Fields.AVATAR_BYTES,
            Constants.Fields.AVATAR_VERSION, Constants.Fields.PHOTO_URL,
            Constants.Fields.DETECTED_BY, Constants.Fields.SERVER_UPDATED_AT,
            Constants.Fields.SOURCE_MESSAGE_CLOUD_ID
        )
        assertTrue("ada field blank", values.none { it.isBlank() })
        assertEquals("ada nama field duplikat", values.size, values.toSet().size)
    }

    // ===== Roles & sender =====
    @Test
    fun `nilai peran dan pengirim adalah kontrak tersimpan`() {
        assertEquals("owner", Constants.Roles.OWNER)
        assertEquals("member", Constants.Roles.MEMBER)
        assertEquals("AI", Constants.Sender.AI)
        assertEquals("Bendahara", Constants.Sender.BENDARAHA)
        assertEquals("Anggota", Constants.Sender.ANGGOTA)
        assertEquals("Ketua", Constants.Sender.KETUA)
    }

    // ===== Jenis transaksi (tersimpan di DB & cloud) =====
    @Test
    fun `jenis transaksi adalah kontrak tersimpan`() {
        assertEquals("PEMASUKAN", Constants.TransactionTypes.INCOME)
        assertEquals("PENGELUARAN", Constants.TransactionTypes.EXPENSE)
        assertEquals(listOf("PEMASUKAN", "PENGELUARAN"), Constants.TransactionTypes.ALL)
    }

    // ===== SharedPreferences keys (tersimpan di disk) =====
    @Test
    fun `pref keys tidak kosong dan unik`() {
        val keys = listOf(
            Constants.Prefs.IS_DARK_MODE, Constants.Prefs.WORKSPACE_PIN,
            Constants.Prefs.WORKSPACE_ROLE, Constants.Prefs.USER_NAME,
            Constants.Prefs.GEMINI_API_KEY, Constants.Prefs.OPENROUTER_API_KEY,
            Constants.Prefs.LAST_UPDATE_CHECK, Constants.Prefs.LAST_AUTO_BACKUP,
            Constants.Prefs.LAST_BACKUP_ENCRYPTED, Constants.Prefs.BACKUP_ENCRYPTED,
            Constants.Prefs.BACKUP_AUTO_PASSPHRASE, Constants.Prefs.CHAT_NOTIFICATIONS_ENABLED,
            Constants.Prefs.NOTIF_PERMISSION_ASKED, Constants.Prefs.AVATAR_SOURCE,
            Constants.Prefs.USER_EMAIL, Constants.Prefs.LAST_UPLOADED_AVATAR,
            Constants.Prefs.NAME_SYNCED
        )
        assertTrue("ada pref key blank", keys.none { it.isBlank() })
        assertEquals("ada pref key duplikat", keys.size, keys.toSet().size)
        // Key sensitif disimpan terenkripsi di SecureStorage, bukan prefs biasa.
        assertFalse(Constants.Prefs.WORKSPACE_PIN.startsWith("enc_"))
    }

    // ===== Kategori (tersimpan di DB & cloud) =====
    @Test
    fun `daftar kategori adalah kontrak tersimpan`() {
        // Kategori yang SUDAH tersimpan di DB/cloud tidak boleh diubah namanya —
        // hanya boleh menambah opsi baru. Uji pasangan inti.
        assertEquals("Groceries & Sembako", Constants.Categories.GROCERIES)
        assertEquals("Makanan & Minuman", Constants.Categories.FOOD)
        assertEquals("Gaji & Pemasukan", Constants.Categories.SALARY)
        assertEquals("Lain-lain", Constants.Categories.MISC)
        // ALL = pengeluaran dulu, lalu pemasukan (urutan UI).
        assertEquals(Constants.Categories.EXPENSE_ALL + Constants.Categories.INCOME_ALL,
            Constants.Categories.ALL)
        // Tidak boleh ada kategori duplikat di daftar mana pun.
        assertEquals(Constants.Categories.EXPENSE_ALL.size,
            Constants.Categories.EXPENSE_ALL.toSet().size)
        assertEquals(Constants.Categories.INCOME_ALL.size,
            Constants.Categories.INCOME_ALL.toSet().size)
        assertEquals(Constants.Categories.ALL.size, Constants.Categories.ALL.toSet().size)
    }

    @Test
    fun `setiap konstanta kategori tercakup di daftar ALL`() {
        // Paritas dua arah: konstanta baru yang lupa dimasukkan ke
        // EXPENSE_ALL/INCOME_ALL/ALL akan hilang dari dropdown UI tanpa
        // terdeteksi — test ini menutup celah itu (audit test 2026-08-14).
        val semuaKonstanta = listOf(
            Constants.Categories.GROCERIES, Constants.Categories.FOOD,
            Constants.Categories.UTILITIES, Constants.Categories.KIDS,
            Constants.Categories.TRANSPORT, Constants.Categories.HEALTH,
            Constants.Categories.ENTERTAINMENT, Constants.Categories.DEBT,
            Constants.Categories.EDUCATION, Constants.Categories.SOCIAL,
            Constants.Categories.INSURANCE, Constants.Categories.MISC,
            Constants.Categories.SALARY, Constants.Categories.BONUS,
            Constants.Categories.BUSINESS, Constants.Categories.INVESTMENT,
            Constants.Categories.GIFT, Constants.Categories.CASHBACK
        )
        assertEquals("ALL tidak memuat semua konstanta kategori",
            semuaKonstanta.toSet(), Constants.Categories.ALL.toSet())
        // Pengeluaran dan pemasukan tidak boleh saling tumpang tindih.
        assertTrue("kategori pengeluaran bocor ke pemasukan atau sebaliknya",
            Constants.Categories.EXPENSE_ALL.toSet()
                .intersect(Constants.Categories.INCOME_ALL.toSet()).isEmpty())
    }

    // ===== Konvensi PIN & sumber avatar =====
    @Test
    fun `konvensi PIN dan sumber avatar stabil`() {
        assertEquals(8, Constants.Defaults.PIN_LENGTH)
        assertEquals(6, Constants.Defaults.PIN_MIN_LEGACY_LENGTH)
        assertEquals("google", Constants.AvatarSources.GOOGLE)
        assertEquals("custom", Constants.AvatarSources.CUSTOM)
    }

    // ===== Link eksternal =====
    @Test
    fun `link kebijakan privasi dibangun dari repo`() {
        assertTrue(Constants.Links.PRIVACY_POLICY.startsWith("https://github.com/ngodingsendiri/nyachat"))
        assertTrue(Constants.Links.PRIVACY_POLICY.endsWith("PRIVACY_POLICY.md"))
    }

    // ===== Keselarasan Constants.Fields dengan DTO Firestore =====
    // Jalur tulis memakai Constants.Fields.* (write map FirestoreSyncManager &
    // JSON DataExporter); jalur baca memakai toObject() → nama field DTO
    // (anotasi @PropertyName / nama property). Keduanya HARUS identik — kalau
    // tidak, data yang ditulis tidak terbaca lintas perangkat. Diverifikasi
    // lewat CustomClassMapper (jalur serialisasi yang SAMA dengan toObject),
    // bukan sekadar membandingkan string.

    private val allFieldValues: Set<String> = setOf(
        Constants.Fields.OWNER_ID, Constants.Fields.CREATED_AT, Constants.Fields.UID,
        Constants.Fields.EMAIL, Constants.Fields.NAME, Constants.Fields.ROLE,
        Constants.Fields.LABEL, Constants.Fields.ADDED_AT, Constants.Fields.REQUESTED_AT,
        Constants.Fields.CLOUD_ID, Constants.Fields.SENDER, Constants.Fields.MESSAGE_TEXT,
        Constants.Fields.TIMESTAMP, Constants.Fields.IS_FINANCIAL,
        Constants.Fields.DETECTED_AMOUNT, Constants.Fields.DETECTED_CATEGORY,
        Constants.Fields.DETECTED_TYPE, Constants.Fields.DETECTED_COUNT,
        Constants.Fields.REPLY_TO_SENDER, Constants.Fields.REPLY_TO_TEXT,
        Constants.Fields.EDITED_AT, Constants.Fields.TYPE, Constants.Fields.CATEGORY,
        Constants.Fields.AMOUNT, Constants.Fields.DESCRIPTION,
        Constants.Fields.LOGGED_BY, Constants.Fields.CHAT_MESSAGE_ID,
        Constants.Fields.FCM_TOKEN, Constants.Fields.AVATAR_BYTES,
        Constants.Fields.AVATAR_VERSION, Constants.Fields.PHOTO_URL,
        Constants.Fields.DETECTED_BY,
        Constants.Fields.SERVER_UPDATED_AT, Constants.Fields.SOURCE_MESSAGE_CLOUD_ID
    )

    @Test
    fun `setiap field CloudMessage yang diserialisasi terwakili oleh Constants Fields`() {
        // Semua field non-null supaya CustomClassMapper menserialisasi semuanya
        // (Firestore mengabaikan null).
        val cloud = CloudMessage(
            cloudId = "c1",
            sender = "Ari",
            messageText = "beli bakso 15 ribu",
            timestamp = 1_000L,
            isFinancial = true,
            detectedAmount = 15_000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = "PENGELUARAN",
            replyToSender = "Budi",
            replyToText = "ok",
            editedAt = 2_000L,
            detectedBy = "AI",
            serverUpdatedAt = com.google.firebase.Timestamp(Date(3_000L))
        )
        val plain = CustomClassMapper.convertToPlainJavaTypes(cloud) as Map<*, *>
        assertTrue("CloudMessage tidak menserialisasi field apa pun", plain.isNotEmpty())
        for (key in plain.keys) {
            val name = key.toString()
            assertTrue(
                "Field cloud '$name' tidak punya konstanta Constants.Fields — " +
                    "tambahkan konstantanya atau jangan pakai literal di write map",
                allFieldValues.contains(name)
            )
        }
    }

    @Test
    fun `setiap field CloudTransaction yang diserialisasi terwakili oleh Constants Fields`() {
        val tx = CloudTransaction(
            cloudId = "t1",
            type = "PENGELUARAN",
            category = "Lain-lain",
            amount = 10_000.0,
            description = "jajan",
            loggedBy = "Ari",
            timestamp = 1_000L,
            chatMessageId = 7L,
            editedAt = 2_000L,
            sourceMessageCloudId = "c1",
            serverUpdatedAt = com.google.firebase.Timestamp(Date(3_000L))
        )
        val plain = CustomClassMapper.convertToPlainJavaTypes(tx) as Map<*, *>
        assertTrue("CloudTransaction tidak menserialisasi field apa pun", plain.isNotEmpty())
        for (key in plain.keys) {
            val name = key.toString()
            assertTrue(
                "Field cloud '$name' tidak punya konstanta Constants.Fields",
                allFieldValues.contains(name)
            )
        }
    }

    @Test
    fun `anotasi PropertyName isFinancial konsisten dengan Constants Fields`() {
        // Regresi BUG-1: tanpa anotasi ini, CustomClassMapper men-strip prefix
        // "is" → field cloud jadi "financial" dan badge finansial hilang.
        // Nilai anotasi harus SELALU sama dengan konstanta write map.
        val getter = CloudMessage::class.java.getMethod("isFinancial")
        assertEquals(
            Constants.Fields.IS_FINANCIAL,
            getter.getAnnotation(PropertyName::class.java)?.value
        )
    }
}
