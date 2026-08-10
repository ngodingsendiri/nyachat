package com.startupmini.nyachat.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.ImageFileUtil
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Buka file dokumen terkirim (PDF/invoice) lewat aplikasi pembaca eksternal. */
internal fun openAttachedFile(context: Context, message: ChatMessage) {
    val path = message.filePath ?: return
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // MIME ditebak dari ekstensi (bukan hardcode PDF) — lampiran non-PDF
        // (doc, xls, gambar) tetap bisa dibuka aplikasi yang sesuai.
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.chat_file_open_failed), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DateSeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    currentActiveSender: String,
    showHeader: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onOpenTransaction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isAi = message.sender == Constants.Sender.AI
    val isMe = message.sender == currentActiveSender
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = LocalSemanticColors.current

    val alignment = when {
        isAi -> Alignment.Start
        isMe -> Alignment.End
        else -> Alignment.Start
    }

    // Warna bubble lebih lembut & konsisten dengan tema (container tones)
    val bubbleColor = when {
        isAi -> if (semantic.isDark) MaterialTheme.colorScheme.surfaceVariant else semantic.aiBg
        isMe -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isAi -> if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        isMe -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val timeColor = when {
        isMe -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }

    val senderLabel = when (message.sender) {
        Constants.Sender.BENDARAHA -> stringResource(R.string.sender_bendahara)
        Constants.Sender.ANGGOTA -> stringResource(R.string.sender_anggota)
        Constants.Sender.KETUA -> stringResource(R.string.sender_ketua)
        Constants.Sender.AI -> stringResource(R.string.sender_ai)
        else -> message.sender
    }

    val senderColor = when {
        isMe -> MaterialTheme.colorScheme.primary
        isAi -> semantic.ai
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID")) }
    val formattedTime = timeFormat.format(Date(message.timestamp))
    // Penanda pesan pernah diedit (mis. "14:05 • diedit")
    val timeDisplay = if (message.editedAt != null) {
        "$formattedTime • ${stringResource(R.string.chat_edited)}"
    } else formattedTime

    // Dekode foto lampiran untuk ditampilkan di bubble (disampling, aman memori)
    val imagePath = message.imagePath
    val imageBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = imagePath
    ) {
        value = withContext(Dispatchers.IO) {
            imagePath?.let { ImageFileUtil.decodeImage(it, 1100) }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isMe && showHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                // Avatar inisial pengirim — dekoratif (nama pengirim sudah ada di
                // sampingnya); disembunyikan dari pembaca layar supaya TalkBack tidak
                // membacakan huruf tunggal yang membingungkan (P3-2).
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(senderColor.copy(alpha = 0.16f))
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = senderLabel.take(1).uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = senderColor
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = senderLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = senderColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeDisplay,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        // State animasi swipe — bubble bergerak mengikuti jari lalu snap balik (spring)
        val swipeOffsetX = remember { Animatable(0f) }
        val haptic = LocalHapticFeedback.current
        var hapticFired = remember { false }
        val swipeScope = rememberCoroutineScope()
        val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }

        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isMe) 20.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 20.dp
            ),
            color = bubbleColor,
            shadowElevation = if (isMe) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(min = 60.dp, max = 300.dp)
                .offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { swipeOffsetX.value.toDp() })
                // P1-2 (audit keyboard): onClick = menu aksi (bukan kosong) supaya
                // keyboard (Enter) & TalkBack bisa membuka menu balas/edit/salin/hapus —
                // sebelumnya hanya long-press/swipe (tak terjangkau keyboard).
                .combinedClickable(
                    onClick = { onLongPress?.invoke() },
                    onLongClick = { onLongPress?.invoke() }
                )
                .then(
                    if (onReply != null) {
                        Modifier.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { hapticFired = false },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    // Hanya izinkan geser ke kanan (untuk balas)
                                    val newOffset = (swipeOffsetX.value + dragAmount).coerceIn(0f, swipeThresholdPx * 1.2f)
                                    swipeScope.launch { swipeOffsetX.snapTo(newOffset) }
                                    // Haptic saat pertama kali melampaui threshold
                                    if (swipeOffsetX.value >= swipeThresholdPx && !hapticFired) {
                                        hapticFired = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDragEnd = {
                                    if (swipeOffsetX.value >= swipeThresholdPx) {
                                        onReply()
                                    }
                                    // Snap kembali ke posisi awal dengan spring
                                    swipeScope.launch {
                                        swipeOffsetX.animateTo(
                                            0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                    hapticFired = false
                                }
                            )
                        }
                    } else Modifier
                )
                .testTag("chat_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Kutipan pesan yang dibalas (swipe kanan / menu Balas)
                message.replyToText?.takeIf { it.isNotBlank() }?.let { quoted ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bubbleColor.copy(alpha = 0.8f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                text = message.replyToSender ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = senderColor
                            )
                            Text(
                                text = quoted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = textColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // File dokumen (PDF/invoice/nota) — ketuk untuk membuka
                if (message.filePath != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isMe) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier
                            .widthIn(max = 230.dp)
                            .combinedClickable(onClick = { onOpenFile?.invoke() })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PictureAsPdf,
                                contentDescription = null,
                                tint = if (isMe) Color.White else semantic.expense,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.fileName ?: stringResource(R.string.chat_pdf_attached),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.chat_pdf_attached),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = timeColor
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Foto lampiran (nota belanja) — proporsional, tidak memenuhi lebar chat
                imageBitmap?.let { b ->
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_image_desc),
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                    if (message.messageText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (message.messageText.isNotBlank()) {
                    Text(
                        text = message.messageText,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = textColor
                    )
                }

                if (isMe) {
                    Text(
                        text = timeDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp)
                    )
                }

                // Financial Tag Badge inside message — warna pastel lebih lembut
                if (message.isFinancial && message.detectedAmount != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val isIncome = message.detectedType == Constants.TransactionTypes.INCOME
                    // Token semantik sudah mode-aware: di dark mode teks memakai
                    // varian terang (audit P0: sebelumnya teks hijau gelap di atas
                    // latar hijau gelap ≈1.5:1 — gagal WCAG berat).
                    val tagBg = if (isIncome) semantic.moneyTagIncomeBg else semantic.moneyTagExpenseBg
                    val tagColor = if (isIncome) semantic.income else semantic.expense

                    val formatRp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
                        maximumFractionDigits = 0
                    }.format(message.detectedAmount)

                    // Badge bisa di-tap untuk membuka transaksi di Rekap (item 5)
                    // — inner clickable menang atas combinedClickable bubble.
                    // Label aksesibilitas di-hoist: semantics {} bukan context composable.
                    val badgeDesc = stringResource(R.string.chat_open_transaction_desc)
                    val badgeClickModifier = if (onOpenTransaction != null) {
                        Modifier
                            .clickable(onClick = onOpenTransaction)
                            .semantics {
                                contentDescription = badgeDesc
                                role = Role.Button
                            }
                    } else Modifier

                    Surface(
                        // Badge ringkas (2026-08-10): padding ramping + indikator sumber
                        // jadi ikon kecil (AI teks 2 huruf / ⚡ heuristik) — sebelumnya
                        // teks "heuristik" (9 huruf) bikin badge memanjang & memakan tempat.
                        shape = RoundedCornerShape(8.dp),
                        color = tagBg,
                        modifier = badgeClickModifier.testTag("financial_badge_${message.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isIncome) Icons.Rounded.CheckCircle else Icons.Rounded.Receipt,
                                contentDescription = null,
                                tint = tagColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${if (isIncome) "+" else "-"} $formatRp · ${message.detectedCategory}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = tagColor
                            )
                            // M7: indikator sumber deteksi (AI vs heuristik offline).
                            // Ringkas: "AI" = teks pendek; heuristik = ikon ⚡ (OfflineBolt).
                            message.detectedBy?.let { source ->
                                if (source.equals("HEURISTIK", ignoreCase = true) ||
                                    source.equals("AI", ignoreCase = true)
                                ) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    val isAi = source.equals("AI", ignoreCase = true)
                                    if (isAi) {
                                        val indicatorDesc = stringResource(R.string.badge_detected_ai_desc)
                                        Text(
                                            text = stringResource(R.string.badge_detected_ai),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = tagColor.copy(alpha = 0.7f),
                                            modifier = Modifier.semantics { contentDescription = indicatorDesc }
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.OfflineBolt,
                                            contentDescription = stringResource(R.string.badge_detected_heuristic_desc),
                                            tint = tagColor.copy(alpha = 0.7f),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiThinkingBubble(modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    // Teks/spinner AI di atas tint AiBlueLight pakai AiBlueText (lebih gelap) —
    // #0066FF di atas #E3ECFF hanya ~3.4:1, di bawah AA untuk teks kecil.
    val aiColor = semantic.aiText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (semantic.isDark) MaterialTheme.colorScheme.surfaceVariant else semantic.aiBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, aiColor.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = aiColor,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_ai_thinking),
                    style = MaterialTheme.typography.labelMedium,
                    color = aiColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
