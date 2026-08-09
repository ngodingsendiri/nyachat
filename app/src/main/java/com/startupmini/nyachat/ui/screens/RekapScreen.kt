package com.startupmini.nyachat.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.analytics.MonthlyAnalytics
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.SyncStatus
import com.startupmini.nyachat.ui.theme.AiBlue
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.util.TransactionRow
import com.startupmini.nyachat.ui.util.buildTransactionRows
import com.startupmini.nyachat.ui.theme.ExpenseRed
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Warna kategori dipilih sesuai tema — dipanggil di sisi Composable

/**
 * Kartu gabungan (audit UI/UX P1.3): insight lokal mingguan + dua aksi AI
 * (laporan bulanan & audit finansial) dalam satu kartu di bawah riwayat.
 * Tombol full-width disusun vertikal — aman di layar 360dp & font scale besar.
 * Saat loading label teks TETAP tampil + spinner kecil di depan (tidak diganti).
 */
@Composable
fun InsightsAiCard(
    insights: List<String>,
    isAuditLoading: Boolean,
    onGenerateAudit: () -> Unit,
    isMonthlyLoading: Boolean,
    onGenerateMonthly: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AiBlue.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = AiBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.rekap_insights_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.rekap_insights_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (insights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                insights.forEach { insight ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = AiBlue,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            text = insight,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGenerateMonthly,
                    enabled = !isMonthlyLoading,
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_monthly_button")
                ) {
                    if (isMonthlyLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(R.string.rekap_monthly_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onGenerateAudit,
                    colors = ButtonDefaults.buttonColors(containerColor = AiBlue),
                    enabled = !isAuditLoading,
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_audit_button")
                ) {
                    if (isAuditLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(R.string.rekap_ai_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

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
    insights: List<String> = emptyList()
) {
    val semantic = LocalSemanticColors.current
    val categoryColors = semantic.categoryPalette

    var selectedFilterTab by remember { mutableStateOf(0) } // 0: Semua, 1: Pengeluaran, 2: Pemasukan
    var pendingDelete by remember { mutableStateOf<FinancialTransaction?>(null) }

    // Navigasi bulan (UI-level): null = semua bulan; Pair(tahun, bulan 1..12).
    // Filter in-memory — banner/donut/riwayat ikut ter-scope tanpa mengubah ViewModel.
    var selectedMonth by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val currentYearMonth = remember {
        val cal = Calendar.getInstance()
        cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
    }
    val monthLabelFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.forLanguageTag("id-ID")) }
    val monthLabel = remember(selectedMonth, monthLabelFormat) {
        selectedMonth?.let { (year, month) ->
            val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
            monthLabelFormat.format(cal.time)
        }
    }

    val monthTransactions = remember(transactions, selectedMonth) {
        selectedMonth?.let { (year, month) ->
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
    val displayIncome = if (selectedMonth != null) scopedIncome else totalIncome
    val displayExpense = if (selectedMonth != null) scopedExpense else totalExpense
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

    val filteredTransactions = remember(monthTransactions, selectedFilterTab, selectedCategory) {
        val byCategory = selectedCategory?.let { cat -> monthTransactions.filter { it.category == cat } } ?: monthTransactions
        when (selectedFilterTab) {
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
    val trendByCategory = remember(transactions, selectedMonth) {
        selectedMonth?.let { (year, month) ->
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
                    // ‹ bulan › — dari "Semua", ‹ masuk ke bulan berjalan; ›
                    // dinonaktifkan di bulan berjalan supaya tidak melihat masa depan.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { selectedMonth = stepMonth(selectedMonth, currentYearMonth, -1) },
                            modifier = Modifier.testTag("rekap_prev_month")
                        ) {
                            Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.rekap_prev_month_desc))
                        }
                        Text(
                            text = monthLabel ?: stringResource(R.string.rekap_month_all),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("rekap_month_label")
                        )
                        IconButton(
                            onClick = { selectedMonth = stepMonth(selectedMonth, currentYearMonth, 1) },
                            enabled = selectedMonth != null && selectedMonth != currentYearMonth,
                            modifier = Modifier.testTag("rekap_next_month")
                        ) {
                            Icon(Icons.Rounded.ChevronRight, stringResource(R.string.rekap_next_month_desc))
                        }
                        if (selectedMonth != null) {
                            TextButton(
                                onClick = { selectedMonth = null },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.testTag("rekap_month_all_button")
                            ) {
                                Text(stringResource(R.string.rekap_month_all))
                            }
                        }
                    }
                    BalanceBannerCard(
                        totalIncome = displayIncome,
                        totalExpense = displayExpense,
                        balance = balance,
                        syncStatus = syncStatus
                    )
                }
            }

            // 2. Category Breakdown Section (Visual Analytics)
            if (categoryTotals.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.rekap_category_header),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Donut Chart here
                                DonutChart(
                                    categoryTotals = categoryTotals,
                                    totalExpense = displayExpense,
                                    colors = categoryColors
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))

                                categoryTotals.forEachIndexed { index, (category, amount) ->
                                    val percentage = if (displayExpense > 0) (amount / displayExpense).toFloat() else 0f
                                    val accentColor = categoryColors[index % categoryColors.size]
                                    
                                    CategoryProgressRow(
                                        category = category,
                                        amount = amount,
                                        percentage = percentage,
                                        accentColor = accentColor,
                                        trendDelta = trendByCategory?.let { amount - (it[category] ?: 0.0) },
                                        selected = selectedCategory == category,
                                        onClick = {
                                            // Tap baris = filter riwayat per kategori;
                                            // tap lagi = matikan filter (item 3).
                                            selectedCategory = if (selectedCategory == category) null else category
                                        }
                                    )
                                    if (index < categoryTotals.size - 1) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Header Riwayat + filter — sticky agar filter selalu terjangkau
            // saat men-scroll daftar transaksi yang panjang (audit P1.3).
            stickyHeader(key = "rekap_history_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = 4.dp)
                ) {
                    // Chip saldo ringkas — muncul saat banner ter-scroll keluar
                    // supaya saldo tetap terpantau di riwayat panjang (item 2).
                    AnimatedVisibility(visible = !bannerVisible) {
                        Surface(
                            shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .testTag("rekap_balance_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.balance_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = currencyFormat.format(balance),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = balanceColor
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.rekap_history_header),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedButton(
                            onClick = onAddTransactionClicked,
                            shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("manual_add_button")
                        ) {
                            Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.rekap_add), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Custom Segmented Control
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val filterOptions = listOf(
                                0 to stringResource(R.string.filter_all),
                                1 to stringResource(R.string.filter_expense),
                                2 to stringResource(R.string.filter_income)
                            )

                            filterOptions.forEach { (index, label) ->
                                val isSelected = selectedFilterTab == index
                                val segBg by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                    animationSpec = tween(220),
                                    label = "segBg"
                                )
                                val segText by animateColorAsState(
                                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    animationSpec = tween(220),
                                    label = "segText"
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(Constants.Ui.CORNER_S.dp))
                                        .background(segBg)
                                        // P3-2: selectable + Role.Tab — TalkBack mengumumkan
                                        // "Terpilih"/"Tidak terpilih" (sebelumnya hanya clickable
                                        // tanpa status pilihan).
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.Tab,
                                            onClick = { selectedFilterTab = index }
                                        )
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = segText
                                    )
                                }
                            }
                        }
                    }

                    // Chip filter kategori aktif (dari tap baris breakdown — item 3).
                    // Tap chip untuk menghapus filter dan kembali ke semua kategori.
                    selectedCategory?.let { category ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            onClick = { selectedCategory = null },
                            shape = RoundedCornerShape(Constants.Ui.CORNER_S.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.testTag("rekap_category_filter_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.rekap_category_filter_active, category),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.rekap_category_filter_clear),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 5. Transactions List or Empty State
            if (filteredTransactions.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.rekap_empty_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.rekap_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                            onDelete = { pendingDelete = row.transaction },
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
        pendingDelete?.let { tx ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.rekap_delete_title)) },
                text = { Text(stringResource(R.string.rekap_delete_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteTransaction(tx)
                            pendingDelete = null
                        }
                    ) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
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

@Composable
fun BalanceBannerCard(
    totalIncome: Double,
    totalExpense: Double,
    balance: Double,
    syncStatus: SyncStatus = SyncStatus.SYNCED
) {
    val semantic = LocalSemanticColors.current
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
            maximumFractionDigits = 0
        }
    }
    // Warna balance: hijau jika surplus, merah jika defisit, default jika nol
    val balanceColor = when {
        balance > 0 -> semantic.income
        balance < 0 -> semantic.expense
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        shape = RoundedCornerShape(Constants.Ui.CORNER_XL.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Wallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.balance_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                }

                // Indikator sinkronisasi JUJUR (P2-16): hijau = tersinkron, abu = offline,
                // kuning = sinkronisasi sedang berjalan, merah = error. Menunjukkan status
                // sebenarnya, bukan asumsi "selalu tersinkron".
                SyncIndicator(syncStatus = syncStatus)
            }

            // Main Balance Amount
            Text(
                text = currencyFormat.format(balance),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = balanceColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            // Income and Expense Breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Income Summary
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            tint = semantic.income,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.income_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currencyFormat.format(totalIncome),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Expense Summary
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            tint = semantic.expense,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.expense_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currencyFormat.format(totalExpense),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncIndicator(syncStatus: SyncStatus) {
    val label = stringResource(
        when (syncStatus) {
            SyncStatus.SYNCED -> R.string.sync_status_synced
            SyncStatus.SYNCING -> R.string.sync_status_syncing
            SyncStatus.OFFLINE -> R.string.sync_status_offline
            SyncStatus.ERROR -> R.string.sync_status_error
        }
    )
    // Dot mode-aware (audit WCAG): light pakai warna gelap (>=4.7:1 di atas putih),
    // dark pakai warna cerah (>=3:1 di atas surface gelap).
    val semantic = LocalSemanticColors.current
    val dotColor = when (syncStatus) {
        SyncStatus.SYNCED -> if (semantic.isDark) Color(0xFF34A853) else Color(0xFF188038)
        SyncStatus.SYNCING -> if (semantic.isDark) Color(0xFFFBBC04) else Color(0xFF8D6E00)
        // OFFLINE & ERROR keduanya netral (BUG-06): "Mode offline"/"Belum sinkron"
        // adalah status informatif — merah hanya menimbulkan alarm palsu padahal
        // data lokal tetap aman dan akan tersinkron saat koneksi pulih.
        SyncStatus.OFFLINE, SyncStatus.ERROR -> if (semantic.isDark) Color(0xFF9AA0A6) else Color(0xFF5F6368)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun CategoryProgressRow(
    category: String,
    amount: Double,
    percentage: Float,
    accentColor: Color,
    trendDelta: Double? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val semantic = LocalSemanticColors.current
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
            maximumFractionDigits = 0
        }
    }

    val categoryIcon = getCategoryIcon(category)
    val percentText = (percentage * 100).toInt()
    val newCategoryLabel = stringResource(R.string.rekap_trend_new)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Constants.Ui.CORNER_M.dp))
            // Tap baris mem-filter riwayat per kategori (item 3); highlight saat aktif.
            // selectable + Role.Button supaya TalkBack mengumumkan "Terpilih/Tidak
            // terpilih" dan tahu barisnya bisa di-tap.
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent
            )
            .then(
                if (onClick != null) Modifier.selectable(selected = selected, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(vertical = 4.dp, horizontal = if (onClick != null) 6.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = category,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = currencyFormat.format(amount),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Tren MoM (item 4): hanya muncul saat bulan terpilih.
                    // Pengeluaran naik = merah (buruk), turun = hijau (baik).
                    trendDelta?.takeIf { it != 0.0 }?.let { delta ->
                        val rising = delta > 0
                        val trendTint = if (rising) semantic.expense else semantic.income
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (rising) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = trendTint,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = trendPercentLabel(delta, amount, newCategoryLabel),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = trendTint
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "$percentText%",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { percentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = accentColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

/** Persentase perubahan vs bulan sebelumnya; [newLabel] bila bulan lalu tidak ada pengeluaran kategori ini. */
private fun trendPercentLabel(delta: Double, amount: Double, newLabel: String): String {
    val prev = amount - delta
    if (prev <= 0) return newLabel
    val pct = (delta / prev * 100).toInt()
    return if (delta > 0) "+$pct%" else "$pct%"
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

/** Header grup tanggal pada riwayat transaksi (audit P1.3). */
@Composable
private fun TransactionDayHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionItemCard(
    transaction: FinancialTransaction,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isIncome = transaction.type == Constants.TransactionTypes.INCOME
    val semantic = LocalSemanticColors.current
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
            maximumFractionDigits = 0
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("id-ID")) }

    val amountColor = if (isIncome) semantic.income else semantic.expense
    val amountPrefix = if (isIncome) "+ " else "- "

    val loggedByTag = when (transaction.loggedBy) {
        "Bendahara" -> stringResource(R.string.tag_bendahara)
        "Anggota" -> stringResource(R.string.tag_anggota)
        "Ketua" -> stringResource(R.string.tag_ketua)
        "ISTRI" -> stringResource(R.string.tag_istri)
        "SUAMI" -> stringResource(R.string.tag_suami)
        else -> stringResource(R.string.tag_other, transaction.loggedBy)
    }

    // SwipeToDismissBox: swipe kiri → Delete, swipe kanan → Edit
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false // false = jangan auto-dismiss, konfirmasi via dialog
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onEdit()
                    false
                }
                else -> false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f }
    )

    // Reset state setelah aksi dipicu (biar item tidak menghilang)
    val scope = rememberCoroutineScope()
    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            scope.launch { swipeState.reset() }
        }
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier,
        enableDismissFromStartToEnd = true,  // kanan → Edit
        enableDismissFromEndToStart = true,  // kiri → Delete
        backgroundContent = {
            // Latar belakang yang terungkap saat swipe
            val direction = swipeState.dismissDirection
            val isToDelete = direction == SwipeToDismissBoxValue.EndToStart
            val isToEdit = direction == SwipeToDismissBoxValue.StartToEnd
            val bgColor = when {
                isToDelete -> ExpenseRed.copy(alpha = 0.12f)
                isToEdit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else -> Color.Transparent
            }
            val icon = if (isToDelete) Icons.Rounded.Delete else Icons.Rounded.Edit
            val iconTint = if (isToDelete) ExpenseRed else MaterialTheme.colorScheme.primary
            val align = if (isToDelete) Alignment.CenterEnd else Alignment.CenterStart

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Constants.Ui.CORNER_L.dp))
                    .background(bgColor),
                contentAlignment = align
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .size(24.dp)
                )
            }
        }
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("transaction_item_${transaction.id}")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isIncome) semantic.incomeBg else semantic.expenseBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getCategoryIcon(transaction.category),
                            contentDescription = null,
                            tint = if (isIncome) semantic.income else semantic.expense,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transaction.description,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = transaction.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                text = loggedByTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (transaction.loggedBy == "ISTRI" || transaction.loggedBy == "Anggota") {
                                    semantic.wife
                                } else {
                                    semantic.husband
                                },
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$amountPrefix${currencyFormat.format(transaction.amount)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = amountColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dateFormat.format(Date(transaction.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    // ⋮ Menu aksi (audit touch/keyboard): swipe tetap ada untuk power
                    // user, tapi Edit/Hapus juga punya tombol — keyboard (Enter) &
                    // TalkBack bisa menjangkau tanpa gestur swipe.
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = stringResource(R.string.rekap_more_actions),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun getCategoryIcon(category: String): ImageVector {
    return when (category) {
        Constants.Categories.GROCERIES -> Icons.Rounded.ShoppingCart
        Constants.Categories.FOOD -> Icons.Rounded.Fastfood
        Constants.Categories.UTILITIES -> Icons.Rounded.HomeWork
        Constants.Categories.KIDS -> Icons.Rounded.ShoppingBag
        Constants.Categories.TRANSPORT -> Icons.Rounded.DirectionsCar
        Constants.Categories.HEALTH -> Icons.Rounded.MedicalServices
        Constants.Categories.ENTERTAINMENT -> Icons.Rounded.SportsEsports
        Constants.Categories.SALARY -> Icons.Rounded.Payments
        else -> Icons.Rounded.MoreHoriz
    }
}

@Composable
fun DonutChart(
    categoryTotals: List<Pair<String, Double>>,
    totalExpense: Double,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    if (totalExpense <= 0 || categoryTotals.isEmpty()) return

    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
            maximumFractionDigits = 0
        }
    }

    // P2-18: ringkasan aksesibel — pembaca layar membacakan proporsi kategori
    // daripada "grafik tanpa deskripsi".
    val chartSummary = categoryTotals
        .joinToString(", ") { (category, amount) ->
            val pct = (amount / totalExpense * 100).toInt()
            "$category $pct persen"
        }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics {
                contentDescription = "Ringkasan pengeluaran per kategori: $chartSummary"
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(160.dp)) {
            var startAngle = -90f
            val strokeWidth = 24.dp.toPx()
            // Gap tetap 2° tapi DIPOTONG di slice kecil: `sweepAngle - gap` tidak
            // boleh negatif (drawArc dengan sweep negatif melukis ke arah salah).
            val gap = 2f

            categoryTotals.forEachIndexed { index, (_, amount) ->
                val sweepAngle = ((amount / totalExpense) * 360).toFloat()
                // Skip slice yang hampir tak terlihat (<0.5°) — tapi tetap majukan
                // startAngle sebesar angle aslinya supaya posisi slice berikutnya
                // tetap akurat (bug sebelumnya: startAngle tidak maju saat skip).
                if (sweepAngle >= 0.5f) {
                    val drawnSweep = (sweepAngle - gap).coerceAtLeast(0.5f)
                    drawArc(
                        color = colors[index % colors.size],
                        startAngle = startAngle,
                        sweepAngle = drawnSweep,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        ),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 0f)
                    )
                }
                startAngle += sweepAngle
            }
        }
        
        // Inner text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.donut_total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.donut_expense),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            // P2.6: nominal total di tengah donut (pola Mint) — sebelumnya hanya
            // label tanpa angka, pengguna harus membaca legend untuk tahu total.
            Text(
                text = currencyFormat.format(totalExpense),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 118.dp)
            )
        }
    }
}