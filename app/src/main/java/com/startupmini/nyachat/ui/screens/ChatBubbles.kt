package com.startupmini.nyachat.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Receipt
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.content.FileProvider
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.BitmapCache
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.theme.Motion
import com.startupmini.nyachat.ui.util.AvatarImage
import com.startupmini.nyachat.ui.util.avatarColorFor
import com.startupmini.nyachat.ui.util.avatarNameColor
import com.startupmini.nyachat.ui.util.idrCurrencyFormat
import java.io.File
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
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    // Audit gestur (2026-08-13, permintaan user): sentuh SEKALI pada bubble
    // GAMBAR membuka viewer foto full-screen (bukan menu). null → bubble teks
    // tanpa aksi saat tap (menu hanya via tahan lama).
    onOpenImage: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onOpenTransaction: (() -> Unit)? = null,
    // r1.2.3 (P1): path foto avatar pengirim (dari map nama→foto di ChatScreen).
    // null → fallback lingkaran inisial berwarna unik.
    senderAvatarPath: String? = null,
    // r1.4.0 (indikator AI memproses): true untuk bubble pesan milik user yang
    // sedang diproses AI — titik kecil muncul di samping waktu (menyatu dengan
    // bubble, bukan elemen terpisah di sisi chat).
    isProcessing: Boolean = false
) {
    val isAi = message.sender == Constants.Sender.AI
    val isMe = message.sender == currentActiveSender
    // isDark dari token semantik (single source of truth) — sebelumnya
    // luminance() < 0.5f (pola rapuh yang malah bertentangan dengan
    // semantic.isDark di baris-baris lain file ini).
    val semantic = LocalSemanticColors.current
    val isDark = semantic.isDark

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

    // r1.2.3 (P0): warna sender UNIK per orang (dari hash nama) — sama dengan
    // topbar & fallback avatar, supaya konsisten identifikasi siapa bicara.
    // Teks memakai varian kontras-aman (avatarNameColor); lingkaran avatar
    // memakai warna murni dengan alpha tipis.
    val senderColor = when {
        isMe -> MaterialTheme.colorScheme.primary
        isAi -> semantic.ai
        else -> avatarNameColor(senderLabel, isDark)
    }
    val senderAvatarBase = when {
        isAi -> semantic.ai
        else -> avatarColorFor(senderLabel)
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID")) }
    val formattedTime = timeFormat.format(Date(message.timestamp))
    // Penanda pesan pernah diedit (mis. "14:05 • diedit")
    val timeDisplay = if (message.editedAt != null) {
        "$formattedTime • ${stringResource(R.string.chat_edited)}"
    } else formattedTime
    // Label custom accessibility action "Buka menu" — TalkBack tetap bisa membuka
    // menu pesan walau onClick bubble tidak lagi memicunya (audit gestur 2026-08-13).
    val openMenuActionLabel = stringResource(R.string.chat_open_menu_desc)

    // Dekode foto lampiran untuk ditampilkan di bubble (disampling, aman memori).
    // P1 (audit performa 2026-08-12): pakai cache thumbnail media per sesi —
    // scroll bolak-balik TIDAK men-decode ulang file 1100px dari disk (penyebab
    // skipped frames). Key cache = maxDim|path, jadi preview 640px & bubble
    // 1100px dari file sama di-cache terpisah.
    val imagePath = message.imagePath
    val imageBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = imagePath
    ) {
        value = withContext(Dispatchers.IO) {
            BitmapCache.decodeMedia(imagePath, 1100)
        }
    }
    // Snapshot lokal supaya smart-cast berfungsi (imageBitmap = delegated property).
    val mediaBitmap = imageBitmap

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isMe && showHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                // Avatar pengirim — FOTO bila tersedia (P1), else lingkaran inisial
                // berwarna unik (P0). Dekoratif (nama sudah di sampingnya);
                // disembunyikan dari pembaca layar (P3-2).
                // Audit avatar (2026-08-12): latar 0.16 alpha nyaris tak terlihat
                // di atas background chat (piksel = background murni) — dijadikan
                // SOLID supaya lingkaran warna identitas per orang tampak jelas,
                // konsisten dengan topbar & kartu identitas.
                // Teks inisial adaptif (audit WCAG, pola MainTopBar): bg avatar
                // terang (orange/sky/hijau) → inisial gelap; bg gelap (indigo/
                // ungu/crimson) → putih. Dipakai KEDUA jalur (fallback foto &
                // inisial) supaya kontras konsisten walau foto gagal di-decode.
                val avatarFg = if (senderAvatarBase.luminance() > 0.22f) {
                    Color(0xFF202124)
                } else {
                    Color.White
                }
                if (senderAvatarPath != null) {
                    AvatarImage(
                        name = senderLabel,
                        size = 24,
                        photoPath = senderAvatarPath,
                        backgroundColor = senderAvatarBase,
                        textColor = avatarFg,
                        textStyle = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(senderAvatarBase)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = senderLabel.take(1).uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = avatarFg
                        )
                    }
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
        val density = androidx.compose.ui.platform.LocalDensity.current
        val swipeThresholdPx = with(density) { 60.dp.toPx() }

        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isMe) 20.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 20.dp
            ),
            // Tanpa shadow untuk SEMUA bubble (2026-08-12): sisa shadow 1.dp
            // khusus bubble incoming membuat "bayangan" di mode terang — tidak
            // konsisten dengan prinsip tanpa-bayangan di seluruh app (composer
            // pill, chip, FAB, Rekap). Hierarki tetap jelas lewat warna bubble.
            color = bubbleColor,
            shadowElevation = 0.dp,
            modifier = Modifier
                // Media (foto) lebih lebar dari teks — screenshot/nota perlu ruang
            // baca; teks tetap 300dp agar nyaman dibaca.
            .widthIn(min = 60.dp, max = if (mediaBitmap != null) 340.dp else 300.dp)
                // r1.4.0 (lint): lambda overload supaya offset mengikuti state
                // swipeOffsetX saat berubah (non-lambda hanya dibaca sekali).
                .offset {
                    IntOffset(with(density) { swipeOffsetX.value.toDp().roundToPx() }, 0)
                }
                // GESTUR (audit 2026-08-13, permintaan user): sentuh SEKALI TIDAK
                // lagi membuka menu — menu hanya muncul lewat TAHAN LAMA (long-press).
                // Pada bubble GAMBAR, sentuh sekali membuka viewer foto full-screen
                // (onOpenImage). TalkBack/keyboard tetap punya jalur ke menu lewat
                // custom accessibility action "Buka menu" (semantics di bawah).
                .combinedClickable(
                    onClick = { onOpenImage?.invoke() },
                    onLongClick = { onLongPress?.invoke() }
                )
                // Aksesibilitas (lanjutan P1-2 audit keyboard): setelah onClick
                // dipakai viewer gambar, menu pesan di-expose sebagai custom
                // accessibility action supaya TalkBack tidak kehilangan akses.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(openMenuActionLabel) {
                            onLongPress?.invoke()
                            true
                        }
                    )
                }
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
                                    // Snap kembali ke posisi awal dengan spring LEMBUT
                                    // (audit motion 2026-08-12): MediumBouncy/StiffnessMedium
                                    // lama terasa "terlontar"; audit elastisitas 2026-08-12
                                    // menaikkan damping ke 0.88 — snap-back terasa "memberi"
                                    // tapi tanpa overshoot yang mengganggu, konsisten dengan
                                    // FAB jump-to-bottom & geser chips (satu motion
                                    // language: Motion.elastic).
                                    swipeScope.launch {
                                        swipeOffsetX.animateTo(
                                            0f,
                                            // Reduced-motion: settle instan (tanpa elastis)
                                            // saat sistem "Hapus animasi" aktif.
                                            animationSpec = Motion.springOrSnap(
                                                Motion.elastic(Spring.StiffnessMediumLow)
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
            if (mediaBitmap != null) {
                // ===== MEDIA MESSAGE (WhatsApp/Telegram): gambar = bubble =====
                // Layout terpisah dari pesan teks: TANPA padding generik bubble
                // (14/10dp), gambar mengisi hampir seluruh container, sudut
                // di-clip oleh shape Surface (radius tunggal, bukan ganda).
                ChatMediaBubbleContent(
                    message = message,
                    imageBitmap = mediaBitmap,
                    isMe = isMe,
                    textColor = textColor,
                    timeColor = timeColor,
                    senderColor = senderColor,
                    timeDisplay = timeDisplay,
                    isProcessing = isProcessing,
                    onOpenFile = onOpenFile,
                    onOpenTransaction = onOpenTransaction
                )
            } else {
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
                    AttachedFileCard(
                        message = message,
                        isMe = isMe,
                        textColor = textColor,
                        timeColor = timeColor,
                        onOpenFile = onOpenFile
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Catatan: pesan ber-foto SELALU lewat jalur media di atas
                // (mediaBitmap != null) — blok gambar lama di jalur teks tidak
                // lagi diperlukan (tidak akan pernah ter-render).
                if (message.messageText.isNotBlank()) {
                    Text(
                        text = message.messageText,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = textColor
                    )
                }

                if (isMe) {
                    MessageTimeRow(
                        timeDisplay = timeDisplay,
                        timeColor = timeColor,
                        isProcessing = isProcessing
                    )
                }

                // Financial Tag Badge inside message — warna pastel lebih lembut
                if (message.isFinancial && message.detectedAmount != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FinancialBadge(
                        message = message,
                        onOpenTransaction = onOpenTransaction
                    )
                }
            }
            }
        }
    }
}

/**
 * Kartu lampiran dokumen (PDF/invoice/nota) di dalam bubble — dipakai jalur
 * teks DAN media. Ketuk membuka file lewat aplikasi eksternal.
 */
@Composable
private fun AttachedFileCard(
    message: ChatMessage,
    isMe: Boolean,
    textColor: Color,
    timeColor: Color,
    onOpenFile: (() -> Unit)?
) {
    val semantic = LocalSemanticColors.current
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
}

/**
 * Badge finansial (pengeluaran/pemasukan) di dalam bubble — dipakai pesan teks
 * DAN pesan media. Warna pastel mode-aware; tap membuka transaksi di Rekap.
 */
@Composable
private fun FinancialBadge(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onOpenTransaction: (() -> Unit)? = null
) {
    val semantic = LocalSemanticColors.current
    val isIncome = message.detectedType == Constants.TransactionTypes.INCOME
    // r1.4.0 (badge campuran): pesan berisi pemasukan DAN pengeluaran sekaligus
    // → latar gradien PELANGI (bukan hijau/merah) sebagai penanda campuran.
    val isMixed = message.hasMixedTypes == true
    // Token semantik sudah mode-aware: di dark mode teks memakai varian terang
    // (audit P0: sebelumnya teks hijau gelap di atas latar hijau gelap ≈1.5:1).
    val tagBg = when {
        isMixed -> Color.Transparent // diganti brush gradien di bawah
        isIncome -> semantic.moneyTagIncomeBg
        else -> semantic.moneyTagExpenseBg
    }
    val tagColor = when {
        isMixed -> semantic.moneyTagMixedText
        isIncome -> semantic.income
        else -> semantic.expense
    }
    val tagBrush = if (isMixed) {
        Brush.horizontalGradient(semantic.moneyTagMixedBg)
    } else null

    // Formatter dibuat SEKALI per nominal (bukan tiap komposisi) — murah &
    // deterministik; satu sumber kebenaran idrCurrencyFormat (audit screens/ 2026-08-14).
    val formatRp = remember(message.detectedAmount) {
        idrCurrencyFormat().format(message.detectedAmount)
    }

    // Badge bisa di-tap untuk membuka transaksi di Rekap — inner clickable
    // menang atas combinedClickable bubble. Label aksesibilitas di-hoist.
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
        // Badge ringkas (2026-08-10): padding ramping + indikator sumber jadi
        // ikon kecil (AI teks 2 huruf / ⚡ heuristik) — bukan teks "heuristik".
        // Badge campuran: warna Surface dibiarkan transparan dan gradien
        // pelangi digambar lewat Modifier.background(brush) — Surface M3 tidak
        // punya param brush, jadi background modifier dipasang paling luar.
        shape = RoundedCornerShape(8.dp),
        color = tagBg,
        modifier = badgeClickModifier
            .then(if (tagBrush != null) Modifier.background(tagBrush, RoundedCornerShape(8.dp)) else Modifier)
            .then(modifier)
            .testTag("financial_badge_${message.id}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                // Badge campuran: ikon pelangi (AutoAwesome) menandakan lebih dari
                // satu jenis transaksi; single-type tetap CheckCircle/Receipt.
                imageVector = when {
                    isMixed -> Icons.Rounded.AutoAwesome
                    isIncome -> Icons.Rounded.CheckCircle
                    else -> Icons.Rounded.Receipt
                },
                contentDescription = null,
                tint = tagColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            // r1.4.0 (audit Finance AI): pesan multi-transaksi menampilkan jumlah
            // transaksi + TOTAL semua nominal — tanpa tanda +/-, tanpa kategori
            // transaksi pertama (tidak men-netting pemasukan vs pengeluaran).
            // Transaksi tunggal tetap "+/- RpX · kategori" seperti sebelumnya.
            val multiCount = message.detectedCount ?: 0
            if (multiCount > 1) {
                Text(
                    text = "$multiCount transaksi · $formatRp",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = tagColor
                )
            } else {
                Text(
                    text = "${if (isIncome) "+" else "-"} $formatRp · ${message.detectedCategory}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = tagColor
                )
            }
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

/**
 * Konten MEDIA message (gaya WhatsApp/Telegram) — gambar MENJADI bubble-nya
 * sendiri: tanpa padding generik bubble, gambar mengisi hampir seluruh
 * container, sudut di-clip oleh shape Surface (radius tunggal, bukan "frame di
 * dalam frame"). Caption/waktu/badge di bawah memakai padding kecil sendiri.
 */
@Composable
private fun ChatMediaBubbleContent(
    message: ChatMessage,
    imageBitmap: Bitmap,
    isMe: Boolean,
    textColor: Color,
    timeColor: Color,
    senderColor: Color,
    timeDisplay: String,
    isProcessing: Boolean,
    onOpenFile: (() -> Unit)?,
    onOpenTransaction: (() -> Unit)?
) {
    Column {
        // Kutipan balasan (swipe kanan / menu Balas) — TANPA panel bersarang:
        // garis aksen kiri (gaya Telegram) supaya pola "panel di dalam panel"
        // tidak hidup lagi di bubble media.
        message.replyToText?.takeIf { it.isNotBlank() }?.let { quoted ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(senderColor.copy(alpha = 0.6f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
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
            Spacer(modifier = Modifier.height(4.dp))
        }

        // File dokumen (PDF/nota) — jarang bersamaan dengan foto
        if (message.filePath != null) {
            AttachedFileCard(
                message = message,
                isMe = isMe,
                textColor = textColor,
                timeColor = timeColor,
                onOpenFile = onOpenFile
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Gambar — EDGE-TO-EDGE: lebar mengikuti bubble (max 340dp via widthIn
        // di Surface), tinggi mengikuti aspect ratio, TANPA clip sendiri (sudut
        // di-clip shape Surface). Tidak ada frame/padding di sekeliling gambar.
        //
        // CATATAN (2026-08-11): fillMaxWidth SAJA tidak cukup — Composer Image
        // menerapkan sizeToIntrinsics (ukuran intrinsic bitmap dalam px) yang
        // bisa mengalahkan width luar, sehingga gambar tampil menyusut (≈px
        // asli) dan menyisakan frame bubble hijau di sisi kiri/kanan. Solusi:
        // aspectRatio eksplisit → ukuran ditentukan penuh oleh fillMaxWidth +
        // rasio asli gambar (proporsi terjaga, tanpa crop/stretch).
        // Guard rasio ekstrem (bitmap 0/1px) — aspectRatio wajib finite & > 0.
        val mediaAspect = imageBitmap.width.toFloat() /
            imageBitmap.height.coerceAtLeast(1).toFloat()
        Image(
            bitmap = imageBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_image_desc),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(mediaAspect),
            contentScale = ContentScale.Fit
        )

        // Bagian bawah: caption + waktu + badge finansial — padding kecil.
        // r1.4.0: baris waktu juga dirender saat bubble sedang diproses AI
        // (foto nota tanpa caption tetap memperlihatkan indikator).
        val hasCaption = message.messageText.isNotBlank()
        val hasBadge = message.isFinancial && message.detectedAmount != null
        if (hasCaption || hasBadge || isProcessing) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (hasCaption) {
                    Text(
                        text = message.messageText,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = textColor
                    )
                }
                if (isMe) {
                    MessageTimeRow(
                        timeDisplay = timeDisplay,
                        timeColor = timeColor,
                        isProcessing = isProcessing
                    )
                }
                if (hasBadge) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FinancialBadge(
                        message = message,
                        onOpenTransaction = onOpenTransaction
                    )
                }
            }
        } else if (isMe) {
            // Media murni milik sendiri — waktu tetap tampil (padding ramping)
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(
                    text = timeDisplay,
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

/**
 * Baris waktu pesan milik user — menyatu dengan indikator "AI memproses"
 * (r1.4.0, permintaan user): saat [isProcessing], tiga titik kecil tampil di
 * kiri waktu. Dipakai footer bubble teks & media. Harus dipanggil dalam
 * ColumnScope (memakai [ColumnScope.align]).
 */
@Composable
private fun ColumnScope.MessageTimeRow(
    timeDisplay: String,
    timeColor: Color,
    isProcessing: Boolean
) {
    Row(
        modifier = Modifier
            .align(Alignment.End)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isProcessing) {
            AiProcessingSpark(
                tint = timeColor,
                modifier = Modifier.padding(end = 5.dp)
            )
        }
        Text(
            text = timeDisplay,
            style = MaterialTheme.typography.labelSmall,
            color = timeColor
        )
    }
}

/**
 * Indikator "AI memproses" minimalis (r1.4.0 — permintaan user): ikon spark
 * kecil (✨ AutoAwesome) di pojok bubble pesan yang sedang diproses AI —
 * menggantikan bubble "AI sedang memproses..." yang terpisah di sisi chat.
 * Pulse alpha halus menandakan proses berjalan; reduced-motion → statis.
 * Label aksesibilitas [R.string.chat_ai_thinking] dibawa Icon (contentDescription)
 * dan ter-merge ke node bubble (bubble bersifat clickable).
 */
@Composable
fun AiProcessingSpark(
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 13.dp
) {
    val alpha = if (Motion.reducedMotion) {
        // Reduced-motion (ANIMATOR_DURATION_SCALE=0): ikon statis, tanpa pulse.
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "aiSpark")
        val a by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "aiSparkAlpha"
        )
        a
    }
    Icon(
        imageVector = Icons.Rounded.AutoAwesome,
        contentDescription = stringResource(R.string.chat_ai_thinking),
        tint = tint,
        modifier = modifier
            .size(iconSize)
            .graphicsLayer { this.alpha = alpha }
    )
}
