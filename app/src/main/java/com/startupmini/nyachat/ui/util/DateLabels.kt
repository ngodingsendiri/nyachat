package com.startupmini.nyachat.ui.util

import com.startupmini.nyachat.data.local.FinancialTransaction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Helper label tanggal bersama (audit UI/UX P1.3): dipakai ChatScreen untuk
 * pemisah hari obrolan dan RekapScreen untuk grouping riwayat transaksi per
 * hari. Fungsi murni — mudah di-unit-test tanpa Robolectric.
 */

/** true kalau kedua timestamp jatuh pada hari kalender yang sama. */
internal fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Label hari: [todayLabel] / [yesterdayLabel] / "EEEE, dd MMM yyyy" (id-ID). */
internal fun dayLabel(timestamp: Long, todayLabel: String, yesterdayLabel: String): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val msgDay = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (msgDay.timeInMillis) {
        today.timeInMillis -> todayLabel
        today.timeInMillis - 86_400_000L -> yesterdayLabel
        else -> SimpleDateFormat("EEEE, dd MMM yyyy", Locale.forLanguageTag("id-ID")).format(Date(timestamp))
    }
}

/** Jam menit "HH:mm" — label detail sinkronisasi "Tersinkron · 14:32" (3.8). */
internal fun formatClockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** Baris riwayat rekap: header hari atau item transaksi. */
internal sealed interface TransactionRow {
    data class DayHeader(val label: String, val key: String) : TransactionRow
    data class Item(val transaction: FinancialTransaction) : TransactionRow
}

/**
 * Susun daftar transaksi menjadi baris dengan header hari — header disisipkan
 * setiap kali tanggal berganti (mengikuti urutan input, biasanya terbaru dulu).
 */
internal fun buildTransactionRows(
    transactions: List<FinancialTransaction>,
    todayLabel: String,
    yesterdayLabel: String
): List<TransactionRow> {
    val rows = mutableListOf<TransactionRow>()
    transactions.forEachIndexed { index, tx ->
        val prev = transactions.getOrNull(index - 1)
        if (prev == null || !isSameDay(prev.timestamp, tx.timestamp)) {
            rows.add(
                TransactionRow.DayHeader(
                    label = dayLabel(tx.timestamp, todayLabel, yesterdayLabel),
                    key = "txday_${tx.timestamp}"
                )
            )
        }
        rows.add(TransactionRow.Item(tx))
    }
    return rows
}
