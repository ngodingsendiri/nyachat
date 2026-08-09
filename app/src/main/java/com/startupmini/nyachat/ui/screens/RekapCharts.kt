package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.SyncStatus
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.util.formatClockTime
import java.text.NumberFormat
import java.util.Locale

// Kartu & grafik analitik layar Rekap — diekstrak dari RekapScreen.kt (TASK-1.2.1)
// tanpa perubahan behavior. Warna kategori dipilih sesuai tema.

@Composable
fun BalanceBannerCard(
    totalIncome: Double,
    totalExpense: Double,
    balance: Double,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
    // 3.8: waktu terakhir sinkron — ditampilkan "Tersinkron · HH:mm" saat SYNCED.
    lastSyncedAtMillis: Long? = null
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
                SyncIndicator(syncStatus = syncStatus, lastSyncedAtMillis = lastSyncedAtMillis)
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
private fun SyncIndicator(syncStatus: SyncStatus, lastSyncedAtMillis: Long? = null) {
    // 3.8: saat tersinkron & waktu terakhir diketahui, tampilkan detail jam
    // ("Tersinkron · 14:32") — informatif tanpa menambah baris.
    val label = if (syncStatus == SyncStatus.SYNCED && lastSyncedAtMillis != null) {
        stringResource(R.string.sync_status_synced_at, formatClockTime(lastSyncedAtMillis))
    } else {
        stringResource(
            when (syncStatus) {
                SyncStatus.SYNCED -> R.string.sync_status_synced
                SyncStatus.SYNCING -> R.string.sync_status_syncing
                SyncStatus.OFFLINE -> R.string.sync_status_offline
                SyncStatus.ERROR -> R.string.sync_status_error
            }
        )
    }
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

/** Bagian breakdown kategori (analitik visual Rekap): donut chart + baris progres
 *  per kategori dengan tren MoM. Diekstrak dari RekapScreen (TASK-1.2.1) — aksi
 *  filter kategori di-hoist ke layar via [onCategoryClick]. */
@Composable
internal fun RekapCategoryBreakdown(
    categoryTotals: List<Pair<String, Double>>,
    totalExpense: Double,
    colors: List<Color>,
    trendByCategory: Map<String, Double>?,
    selectedCategory: String?,
    onCategoryClick: (String) -> Unit
) {
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
                DonutChart(
                    categoryTotals = categoryTotals,
                    totalExpense = totalExpense,
                    colors = colors
                )

                Spacer(modifier = Modifier.height(16.dp))

                categoryTotals.forEachIndexed { index, (category, amount) ->
                    val percentage = if (totalExpense > 0) (amount / totalExpense).toFloat() else 0f
                    val accentColor = colors[index % colors.size]

                    CategoryProgressRow(
                        category = category,
                        amount = amount,
                        percentage = percentage,
                        accentColor = accentColor,
                        trendDelta = trendByCategory?.let { amount - (it[category] ?: 0.0) },
                        selected = selectedCategory == category,
                        onClick = { onCategoryClick(category) }
                    )
                    if (index < categoryTotals.size - 1) {
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
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
