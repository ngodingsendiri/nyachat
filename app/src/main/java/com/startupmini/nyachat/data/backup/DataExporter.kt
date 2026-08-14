package com.startupmini.nyachat.data.backup

import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hasil parsing file backup untuk restore. */
data class BackupData(
    val messages: List<ChatMessage>,
    val transactions: List<FinancialTransaction>,
    /** PIN/workspace asal backup (null = backup lama tanpa info workspace). */
    val familyId: String? = null,
    /** Versi format backup; dipakai untuk menolak format masa depan. */
    val formatVersion: Int = DataExporter.FORMAT_VERSION
)

/**
 * Export data lokal ke:
 * 1) CSV rekapan keuangan — dibuka di Excel/Google Sheets langsung jadi tabel
 *    (ringkasan, rekap per kategori, riwayat transaksi, riwayat chat).
 * 2) JSON backup lengkap untuk cadangan Google Drive + restore.
 */
object DataExporter {

    internal const val FORMAT_VERSION = 1

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val numberFmt = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID"))

    fun formatDate(timestamp: Long): String =
        runCatching { dateFmt.format(Date(timestamp)) }.getOrDefault("")

    /** Angka ringkasan: "Rp 1.234.567". */
    fun formatIdr(amount: Double): String = "Rp ${numberFmt.format(amount)}"

    // ---------- CSV rekap keuangan ----------

    /**
     * CSV memakai pemisah titik-koma (;) — cocok dengan pengaturan regional
     * Excel Indonesia, jadi pas dibuka langsung jadi tabel rapi. Kolom yang
     * mengandung karakter khusus dibungkus kutip ganda (escaped).
     */
    fun buildRecapCsv(
        transactions: List<FinancialTransaction>,
        messages: List<ChatMessage>
    ): String {
        val sb = StringBuilder()
        val income = transactions.filter { it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }
        val expense = transactions.filter { it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }

        sb.appendLine(csvRow("Rekapan Keuangan Nyachat"))
        sb.appendLine(csvRow("Dibuat", formatDate(System.currentTimeMillis())))
        sb.appendLine(csvRow("Total Saldo", formatIdr(income - expense)))
        sb.appendLine(csvRow("Total Pemasukan", formatIdr(income)))
        sb.appendLine(csvRow("Total Pengeluaran", formatIdr(expense)))
        sb.appendLine(csvRow("Jumlah Transaksi", transactions.size.toString()))
        sb.appendLine(csvRow("Jumlah Pesan Chat", messages.size.toString()))
        sb.appendLine()

        sb.appendLine(csvRow("REKAP PER KATEGORI"))
        sb.appendLine(csvRow("Kategori", "Tipe", "Total"))
        transactions
            .groupBy { it.category to it.type }
            .entries
            .sortedByDescending { it.value.sumOf { t -> t.amount } }
            .forEach { (key, list) ->
                sb.appendLine(csvRow(key.first, key.second, formatIdr(list.sumOf { t -> t.amount })))
            }
        sb.appendLine()

        sb.appendLine(csvRow("RIWAYAT TRANSAKSI"))
        sb.appendLine(csvRow("No", "Tanggal", "Tipe", "Kategori", "Deskripsi", "Dicatat oleh", "Jumlah"))
        transactions
            .sortedByDescending { it.timestamp }
            .forEachIndexed { index, t ->
                sb.appendLine(
                    csvRow(
                        (index + 1).toString(),
                        formatDate(t.timestamp),
                        t.type,
                        t.category,
                        t.description,
                        t.loggedBy,
                        num(t.amount)
                    )
                )
            }
        sb.appendLine()

        sb.appendLine(csvRow("RIWAYAT CHAT"))
        sb.appendLine(csvRow("No", "Tanggal", "Pengirim", "Pesan", "Transaksi terdeteksi", "Tipe", "Kategori", "Jumlah"))
        messages
            .sortedBy { it.timestamp }
            .forEachIndexed { index, m ->
                sb.appendLine(
                    csvRow(
                        (index + 1).toString(),
                        formatDate(m.timestamp),
                        m.sender,
                        m.messageText,
                        if (m.isFinancial) "Ya" else "Tidak",
                        m.detectedType ?: "",
                        m.detectedCategory ?: "",
                        m.detectedAmount?.let { num(it) } ?: ""
                    )
                )
            }
        return sb.toString()
    }

    /**
     * Satu sel CSV: kutip kalau mengandung pemisah, kutip ganda, atau baris baru.
     * Angka (format desimal koma) TIDAK dikutip supaya Excel/Google Sheets tetap
     * membacanya sebagai angka yang bisa dijumlahkan, bukan teks.
     */
    private fun csvCell(value: Any?): String {
        val s = value?.toString() ?: ""
        val numeric = s.matches(Regex("^-?\\d[\\d.,]*$"))
        val needQuote = !numeric && (s.contains(';') || s.contains(',') || s.contains('\"') || s.contains('\n'))
        return if (needQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun csvRow(vararg cells: Any?): String = cells.joinToString(";") { csvCell(it) }

    /** Angka kolom transaksi pakai desimal koma (format Excel Indonesia) supaya bisa dijumlahkan. */
    private fun num(value: Double): String =
        String.format(Locale.US, "%.2f", value).replace('.', ',')

    // ---------- Backup JSON (Google Drive) ----------

    fun buildBackupJson(
        transactions: List<FinancialTransaction>,
        messages: List<ChatMessage>,
        versionName: String,
        familyId: String? = null
    ): String {
        val root = JSONObject()
        root.put("app", "Nyachat")
        root.put("format", FORMAT_VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("versionName", versionName)
        root.putOpt("familyId", familyId)
        root.put(
            "transactions",
            JSONArray().apply { transactions.forEach { put(transactionToJson(it)) } }
        )
        root.put(
            "messages",
            JSONArray().apply { messages.forEach { put(messageToJson(it)) } }
        )
        return root.toString()
    }

    /**
     * Parse file backup. Return null kalau rusak / bukan backup Nyachat /
     * format backup lebih baru dari yang dipahami app (P1: tolak format masa
     * depan supaya restore tidak salah-parse lalu merusak data).
     *
     * Backup terenkripsi (amplop [BackupCrypto]) WAJIB diberi [passphrase];
     * tanpa passphrase atau passphrase salah → null.
     */
    fun parseBackupJson(json: String, passphrase: String? = null): BackupData? = runCatching {
        if (BackupCrypto.isEncryptedEnvelope(json)) {
            val p = passphrase ?: return null
            val plain = BackupCrypto.decryptEnvelope(json, p) ?: return null
            return parseBackupJson(plain)
        }
        val root = JSONObject(json)
        val appTag = root.optString("app")
        // Backup lama ber-marker "MoneyChat" (sebelum rebrand) tetap diterima
        // supaya restore lintas-rebrand tetap jalan.
        if (appTag != "Nyachat" && appTag != "MoneyChat") return null
        val format = root.optInt("format", 1)
        if (format > FORMAT_VERSION) return null

        val transactions = mutableListOf<FinancialTransaction>()
        val txArr = root.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until txArr.length()) {
            transactions.add(transactionFromJson(txArr.getJSONObject(i)))
        }

        val messages = mutableListOf<ChatMessage>()
        val msgArr = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgArr.length()) {
            messages.add(messageFromJson(msgArr.getJSONObject(i)))
        }

        BackupData(
            messages = messages,
            transactions = transactions,
            familyId = root.optNullableString("familyId"),
            formatVersion = format
        )
    }.getOrNull()

    /** Serialisasi transaksi → JSON (dipakai untuk backup & antrian sync). */
    // Nama field JSON memakai Constants.Fields.* (kontrak backup/payload — nilai
    // TIDAK boleh berubah: file backup lama & pending op tersimpan bergantung
    // padanya; dijaga ConstantsTest). Kunci lampiran lokal (imagePath/filePath/
    // fileName) sengaja literal — itu bukan field Firestore.
    internal fun transactionToJson(t: FinancialTransaction): JSONObject =
        JSONObject()
            .put(Constants.Fields.TYPE, t.type)
            .put(Constants.Fields.CATEGORY, t.category)
            .put(Constants.Fields.AMOUNT, t.amount)
            .put(Constants.Fields.DESCRIPTION, t.description)
            .put(Constants.Fields.LOGGED_BY, t.loggedBy)
            .put(Constants.Fields.TIMESTAMP, t.timestamp)
            .putOpt(Constants.Fields.EDITED_AT, t.editedAt)
            .putOpt(Constants.Fields.CHAT_MESSAGE_ID, t.chatMessageId)
            .putOpt(Constants.Fields.CLOUD_ID, t.cloudId)
            .putOpt(Constants.Fields.SOURCE_MESSAGE_CLOUD_ID, t.sourceMessageCloudId)
            // M4: server timestamp ikut dibackup agar tie-break mrgl tidak hilang.
            .putOpt(Constants.Fields.SERVER_UPDATED_AT, t.serverUpdatedAt)

    /** Serialisasi pesan → JSON (dipakai untuk backup & antrian sync). */
    internal fun messageToJson(m: ChatMessage): JSONObject =
        JSONObject()
            .put(Constants.Fields.SENDER, m.sender)
            .put(Constants.Fields.MESSAGE_TEXT, m.messageText)
            .put(Constants.Fields.TIMESTAMP, m.timestamp)
            .put(Constants.Fields.IS_FINANCIAL, m.isFinancial)
            .putOpt(Constants.Fields.DETECTED_AMOUNT, m.detectedAmount)
            .putOpt(Constants.Fields.DETECTED_CATEGORY, m.detectedCategory)
            .putOpt(Constants.Fields.DETECTED_TYPE, m.detectedType)
            .putOpt(Constants.Fields.DETECTED_COUNT, m.detectedCount)
            .putOpt(Constants.Fields.HAS_MIXED_TYPES, m.hasMixedTypes)
            .putOpt("imagePath", m.imagePath)
            .putOpt("filePath", m.filePath)
            .putOpt("fileName", m.fileName)
            .putOpt(Constants.Fields.REPLY_TO_SENDER, m.replyToSender)
            .putOpt(Constants.Fields.REPLY_TO_TEXT, m.replyToText)
            .putOpt(Constants.Fields.EDITED_AT, m.editedAt)
            .putOpt(Constants.Fields.CLOUD_ID, m.cloudId)
            .putOpt(Constants.Fields.SOURCE_MESSAGE_CLOUD_ID, m.sourceMessageCloudId)
            // M4/M7: kolom baru ikut dibackup agar restore tidak kehilangan
            // penanda asal deteksi & tie-break server.
            .putOpt(Constants.Fields.DETECTED_BY, m.detectedBy)
            .putOpt(Constants.Fields.SERVER_UPDATED_AT, m.serverUpdatedAt)

    /** Parse transaksi dari JSON. */
    internal fun transactionFromJson(o: JSONObject): FinancialTransaction =
        FinancialTransaction(
            type = o.optString(Constants.Fields.TYPE, Constants.TransactionTypes.EXPENSE),
            category = o.optString(Constants.Fields.CATEGORY, Constants.Categories.MISC),
            amount = o.optDouble(Constants.Fields.AMOUNT, 0.0),
            description = o.optString(Constants.Fields.DESCRIPTION, ""),
            loggedBy = o.optString(Constants.Fields.LOGGED_BY, ""),
            timestamp = o.optLong(Constants.Fields.TIMESTAMP, 0L),
            editedAt = o.optNullableLong(Constants.Fields.EDITED_AT),
            chatMessageId = o.optNullableLong(Constants.Fields.CHAT_MESSAGE_ID),
            cloudId = o.optNullableString(Constants.Fields.CLOUD_ID),
            sourceMessageCloudId = o.optNullableString(Constants.Fields.SOURCE_MESSAGE_CLOUD_ID),
            serverUpdatedAt = o.optNullableLong(Constants.Fields.SERVER_UPDATED_AT)
        )

    /** Parse pesan dari JSON (lampiran lokal yang filenya sudah hilang dibuang). */
    internal fun messageFromJson(o: JSONObject): ChatMessage {
        val imagePath = o.optNullableString("imagePath")
        val filePath = o.optNullableString("filePath")
        return ChatMessage(
            sender = o.optString(Constants.Fields.SENDER, ""),
            messageText = o.optString(Constants.Fields.MESSAGE_TEXT, ""),
            timestamp = o.optLong(Constants.Fields.TIMESTAMP, 0L),
            isFinancial = o.optBoolean(Constants.Fields.IS_FINANCIAL, false),
            detectedAmount = o.optNullableDouble(Constants.Fields.DETECTED_AMOUNT),
            detectedCategory = o.optNullableString(Constants.Fields.DETECTED_CATEGORY),
            detectedType = o.optNullableString(Constants.Fields.DETECTED_TYPE),
            detectedCount = o.optNullableInt(Constants.Fields.DETECTED_COUNT),
            hasMixedTypes = o.optNullableBoolean(Constants.Fields.HAS_MIXED_TYPES),
            // Lampiran lokal tidak ikut di-backup; referensi yang file-nya
            // sudah tidak ada dibuang biar tidak muncul bubble rusak.
            imagePath = imagePath?.takeIf { File(it).exists() },
            filePath = filePath?.takeIf { File(it).exists() },
            fileName = o.optNullableString("fileName"),
            replyToSender = o.optNullableString(Constants.Fields.REPLY_TO_SENDER),
            replyToText = o.optNullableString(Constants.Fields.REPLY_TO_TEXT),
            editedAt = o.optNullableLong(Constants.Fields.EDITED_AT),
            cloudId = o.optNullableString(Constants.Fields.CLOUD_ID),
            sourceMessageCloudId = o.optNullableString(Constants.Fields.SOURCE_MESSAGE_CLOUD_ID),
            detectedBy = o.optNullableString(Constants.Fields.DETECTED_BY),
            serverUpdatedAt = o.optNullableLong(Constants.Fields.SERVER_UPDATED_AT)
        )
    }

    // JSONObject.opt* di Android bisa melempar IllegalArgumentException kalau
    // tipe field tidak cocok — helper aman untuk field nullable.
    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null
}
