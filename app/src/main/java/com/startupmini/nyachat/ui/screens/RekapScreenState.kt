package com.startupmini.nyachat.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.startupmini.nyachat.data.local.FinancialTransaction

/**
 * TASK-1.2.4 — state holder layar Rekap (mengikuti pola [MainDialogController]):
 * filter tab, transaksi yang menunggu konfirmasi hapus, bulan terpilih, dan
 * filter kategori dipindah dari beberapa `remember` terpisah di dalam
 * komposisi RekapScreen ke satu class — mempermudah baca & uji.
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
}

/** Buat & ingat satu [RekapScreenState] per komposisi layar. */
@Composable
internal fun rememberRekapScreenState(): RekapScreenState = remember { RekapScreenState() }
