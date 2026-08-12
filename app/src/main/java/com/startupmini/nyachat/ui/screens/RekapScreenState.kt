package com.startupmini.nyachat.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.startupmini.nyachat.data.local.FinancialTransaction

/**
 * TASK-1.2.4 — state holder layar Rekap (mengikuti pola [MainDialogController]):
 * filter tab, transaksi yang menunggu konfirmasi hapus, bulan terpilih, dan
 * filter kategori dipindah dari beberapa `remember` terpisah di dalam
 * komposisi RekapScreen ke satu class — mempermudah baca & uji.
 *
 * Audit UI/UX Rekap: state DI-HOIST ke MainActivity via [Saver] (pola
 * chatDraft) supaya filter bulan/kategori/tab tidak hilang saat pindah tab
 * Chat ⇄ Rekap atau rotasi. [pendingDelete] sengaja TIDAK disimpan (dialog
 * transien — harus bersih saat layar dibuka kembali).
 */
class RekapScreenState {
    /** 0: Semua, 1: Pengeluaran, 2: Pemasukan. */
    var selectedFilterTab by mutableStateOf(0)

    /** Transaksi yang menunggu konfirmasi dialog hapus (null = tidak ada). */
    var pendingDelete by mutableStateOf<FinancialTransaction?>(null)

    /** Navigasi bulan (UI-level): null = semua bulan; Pair(tahun, bulan 1..12).
     *  Filter in-memory — banner/donut/riwayat ikut ter-scope. */
    var selectedMonth by mutableStateOf<Pair<Int, Int>?>(null)

    /** Filter kategori dari tap baris breakdown (null = semua kategori). */
    var selectedCategory by mutableStateOf<String?>(null)

    companion object {
        /** Saver untuk rememberSaveable — tab/kategori/bulan bertahan lintas tab. */
        val Saver = listSaver<RekapScreenState, Any>(
            save = { state ->
                listOf(
                    state.selectedFilterTab,
                    state.selectedMonth?.first ?: -1,
                    state.selectedMonth?.second ?: -1,
                    state.selectedCategory ?: ""
                )
            },
            restore = { saved ->
                RekapScreenState().apply {
                    val tab = saved.getOrNull(0) as? Int
                    if (tab != null && tab in 0..2) selectedFilterTab = tab
                    val year = saved.getOrNull(1) as? Int ?: -1
                    val month = saved.getOrNull(2) as? Int ?: -1
                    if (year > 0 && month in 1..12) selectedMonth = year to month
                    val category = saved.getOrNull(3) as? String
                    if (!category.isNullOrEmpty()) selectedCategory = category
                }
            }
        )
    }
}
