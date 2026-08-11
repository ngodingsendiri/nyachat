package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.analytics.MonthlyAnalytics
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.SyncStatus
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.util.TransactionRow
import com.startupmini.nyachat.ui.util.buildTransactionRows
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Layar Rekap — ORKESTRASI setelah dekomposisi TASK-1.2 (awalnya 1442 baris):
 * - state (filter tab, bulan, kategori, hapus) → [RekapScreenState]
 * - kartu & grafik analitik → [RekapCharts] ([BalanceBannerCard], [DonutChart],
 *   [CategoryProgressRow], [RekapCategoryBreakdown])
 * - daftar & filter riwayat → [RekapList] ([TransactionItemCard], [RekapMonthNav],
 *   [RekapFilterHeader], [RekapEmptyState])
 * - kartu insight AI + aksi laporan → [AiReportCard] ([InsightsAiCard])
 *
 * File ini hanya menurunkan state dari ViewModel, menghitung agregat in-memory
 * (filter bulan/kategori/tab), dan menyusun LazyColumn — tanpa mengubah behavior.
 */
@OptIn(ExperimentalFoundationApi::class) // stickyHeader riwayat (audit P1.3)
@Composable
fun RekapScreen(
    transactions: List<FinancialTransaction>,
    totalIncome: Double,
    totalExpense: Double,
    isAuditLoading: Boolean,
    onGenerateAudit: () -> Unit,
    isMonthlyLoading: Boolean,
    onGenerateMonthly: () -> Unit,
    onAddTransactionClicked: () -> Unit,
    onDeleteTransaction: (FinancialTransaction) -> Unit,
    onEditTransaction: (FinancialTransaction) -> Unit,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
    // 3.8: waktu terakhir sinkron berhasil — label "Tersinkron · HH:mm" di banner.
    lastSyncedAtMillis: Long? = null,
    insights: List<String> = emptyList()
) {
    val state = rememberRekapScreenState()
    val semantic = LocalSemanticColors.current
    val categoryColors = semantic.categoryPalette

    // Navigasi bulan (UI-level): null = semua bulan; Pair(tahun, bulan 1..12).
    // Filter in-memory — banner/donut/riwayat ikut ter-scope tanpa mengubah ViewModel.
    val currentYearMonth = remember {
        val cal = Calendar.getInstance()
        cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
    }
    val monthLabelFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.forLanguageTag("id-ID")) }
    val monthLabel = remember(state.selectedMonth, monthLabelFormat) {
        state.selectedMonth?.let { (year, month) ->
            val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
            monthLabelFormat.format(cal.time)
        }
    }

    val monthTransactions = remember(transactions, state.selectedMonth) {
        state.selectedMonth?.let { (year, month) ->
            transactions.filter { MonthlyAnalytics.isSameMonth(it.timestamp, year, month) }
        } ?: transactions
    }
    val scopedIncome = remember(monthTransactions) {
        monthTransactions.filter { it.type == Constants.TransactionTypes.INCOME }.sumOf { it.amount }
    }
    val scopedExpense = remember(monthTransactions) {
        monthTransactions.filter { it.type == Constants.TransactionTypes.EXPENSE }.sumOf { it.amount }
    }
    // Bulan terpilih memakai angka sebulan; mode "Semua" memakai agregat ViewModel.
    val displayIncome = if (state.selectedMonth != null) scopedIncome else totalIncome
    val displayExpense = if (state.selectedMonth != null) scopedExpense else totalExpense
    val balance = displayIncome - displayExpense
    val balanceColor = when {
        balance > 0 -> semantic.income
        balance < 0 -> semantic.expense
        else -> MaterialTheme.colorScheme.onSurface
    }
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
            maximumFractionDigits = 0
        }
    }

    val filteredTransactions = remember(monthTransactions, state.selectedFilterTab, state.selectedCategory) {
        val byCategory = state.selectedCategory?.let { cat -> monthTransactions.filter { it.category == cat } } ?: monthTransactions
        when (state.selectedFilterTab) {
            1 -> byCategory.filter { it.type == Constants.TransactionTypes.EXPENSE }
            2 -> byCategory.filter { it.type == Constants.TransactionTypes.INCOME }
            else -> byCategory
        }
    }

    // Grouping riwayat per hari (audit P1.3) — header tanggal disisipkan tiap
    // pergantian hari, mengikuti urutan list (terbaru dulu).
    val todayLabel = stringResource(R.string.today_label)
    val yesterdayLabel = stringResource(R.string.yesterday_label)
    val filteredRows = remember(filteredTransactions, todayLabel, yesterdayLabel) {
        buildTransactionRows(filteredTransactions, todayLabel, yesterdayLabel)
    }

    val categoryTotals = remember(monthTransactions) {
        monthTransactions.filter { it.type == Constants.TransactionTypes.EXPENSE }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    // Tren MoM per kategori (item 4): selisih pengeluaran bulan terpilih vs bulan
    // sebelumnya. null di mode "Semua" — tidak ada bulan pembanding tunggal.
    val trendByCategory = remember(transactions, state.selectedMonth) {
        state.selectedMonth?.let { (year, month) ->
            val prev = if (month == 1) (year - 1) to 12 else year to (month - 1)
            transactions
                .filter {
                    it.type == Constants.TransactionTypes.EXPENSE &&
                        MonthlyAnalytics.isSameMonth(it.timestamp, prev.first, prev.second)
                }
                .groupBy { it.category }
                .mapValues { entry -> entry.value.sumOf { it.amount } }
        }
    }

    // Chip saldo di stickyHeader muncul saat banner ter-scroll keluar viewport (item 2).
    val listState = rememberLazyListState()
    val bannerVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "balance_banner" } }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 90.dp, top = 16.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Navigasi bulan + Balance Summary Banner Card
            item(key = "balance_banner") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RekapMonthNav(
                        selectedMonth = state.selectedMonth,
                        currentYearMonth = currentYearMonth,
                        monthLabel = monthLabel,
                        onStep = { delta ->
                            state.selectedMonth = stepMonth(state.selectedMonth, currentYearMonth, delta)
                        },
                        onClear = { state.selectedMonth = null }
                    )
                    BalanceBannerCard(
                        totalIncome = displayIncome,
                        totalExpense = displayExpense,
                        balance = balance,
                        syncStatus = syncStatus,
                        lastSyncedAtMillis = lastSyncedAtMillis
                    )
                }
            }

            // 2. Category Breakdown Section (Visual Analytics)
            if (categoryTotals.isNotEmpty()) {
                item {
                    RekapCategoryBreakdown(
                        categoryTotals = categoryTotals,
                        totalExpense = displayExpense,
                        colors = categoryColors,
                        trendByCategory = trendByCategory,
                        selectedCategory = state.selectedCategory,
                        onCategoryClick = { category ->
                            // Tap baris = filter riwayat per kategori;
                            // tap lagi = matikan filter (item 3).
                            state.selectedCategory =
                                if (state.selectedCategory == category) null else category
                        }
                    )
                }
            }

            // 3. Header Riwayat + filter — sticky agar filter selalu terjangkau
            // saat men-scroll daftar transaksi yang panjang (audit P1.3).
            stickyHeader(key = "rekap_history_header") {
                RekapFilterHeader(
                    bannerVisible = bannerVisible,
                    balance = balance,
                    balanceColor = balanceColor,
                    currencyFormat = currencyFormat,
                    selectedFilterTab = state.selectedFilterTab,
                    onTabSelected = { state.selectedFilterTab = it },
                    selectedCategory = state.selectedCategory,
                    onClearCategory = { state.selectedCategory = null },
                    onAddTransactionClicked = onAddTransactionClicked
                )
            }

            // 4. Transactions List or Empty State
            if (filteredTransactions.isEmpty()) {
                item { RekapEmptyState() }
            } else {
                // Riwayat dikelompokkan per hari — header tanggal disisipkan tiap
                // pergantian hari (audit P1.3, helper di ui/util/DateLabels.kt).
                items(
                    filteredRows,
                    key = { row ->
                        when (row) {
                            is TransactionRow.DayHeader -> row.key
                            is TransactionRow.Item -> "tx_${row.transaction.id}"
                        }
                    }
                ) { row ->
                    when (row) {
                        is TransactionRow.DayHeader -> TransactionDayHeader(label = row.label)
                        is TransactionRow.Item -> TransactionItemCard(
                            transaction = row.transaction,
                            onDelete = { state.pendingDelete = row.transaction },
                            onEdit = { onEditTransaction(row.transaction) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // 5. Kartu gabungan Insight lokal + aksi AI (audit P1.3): dipindah ke
            // bawah riwayat — hierarki utama (saldo → analitik → riwayat) didahulukan.
            item {
                InsightsAiCard(
                    insights = insights,
                    isAuditLoading = isAuditLoading,
                    onGenerateAudit = onGenerateAudit,
                    isMonthlyLoading = isMonthlyLoading,
                    onGenerateMonthly = onGenerateMonthly
                )
            }
        }

        // Konfirmasi hapus transaksi
        state.pendingDelete?.let { tx ->
            AlertDialog(
                onDismissRequest = { state.pendingDelete = null },
                title = { Text(stringResource(R.string.rekap_delete_title)) },
                text = { Text(stringResource(R.string.rekap_delete_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteTransaction(tx)
                            state.pendingDelete = null
                        }
                    ) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state.pendingDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        // Floating Action Button for Fast Entry
        FloatingActionButton(
            onClick = onAddTransactionClicked,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("fab_add_transaction")
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = stringResource(R.string.rekap_fab_desc))
        }
    }
}

/**
 * Langkah navigasi bulan: [delta] -1/+1 dari [current]; null = mode "Semua".
 * Dari "Semua" langkah mundur masuk ke bulan berjalan; tidak pernah melewati
 * bulan berjalan ke depan.
 */
private fun stepMonth(current: Pair<Int, Int>?, currentMonth: Pair<Int, Int>, delta: Int): Pair<Int, Int>? {
    val base = current ?: return if (delta < 0) currentMonth else null
    val idx = base.first * 12 + (base.second - 1) + delta
    val next = (idx / 12) to (idx % 12 + 1)
    // Tidak boleh melewati bulan berjalan (Pair tidak punya compareTo).
    val beyondCurrent = next.first > currentMonth.first ||
        (next.first == currentMonth.first && next.second > currentMonth.second)
    return if (beyondCurrent) currentMonth else next
}
