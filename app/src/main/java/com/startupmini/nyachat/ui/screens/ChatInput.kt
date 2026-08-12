package com.startupmini.nyachat.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.ImageFileUtil
import com.startupmini.nyachat.ui.theme.ExpenseRed
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_MESSAGE_LENGTH = 2000

/**
 * Lebar maksimum kartu floating (reply/preview) — disamakan dengan lebar
 * composer pill (~327dp) supaya ketiga elemen terasa satu keluarga.
 */
private val CHAT_CARD_MAX_WIDTH = 320.dp

/**
 * Tinggi seragam baris composer (pill, tombol Send, bar balas, pratinjau) —
 * 52dp = ukuran tombol Send. Semua elemen di area input memakai nilai ini
 * supaya tidak ada yang lebih tinggi/rendah dari yang lain.
 */
private val CHAT_BAR_HEIGHT = 52.dp

/** Tinggi minimum kolom input (48dp) = pill 52dp dikurangi padding row 2dp×2. */
private val CHAT_FIELD_MIN_HEIGHT = CHAT_BAR_HEIGHT - 4.dp

/**
 * Fill chip rekomendasi & FAB jump-to-bottom (2026-08-12, penyempurnaan): alpha
 * dinaikkan dari 0.75/0.45 → 0.92/0.90 (transparansi ±8-10% dari skala 0=tidak
 * transparan, 100=full transparan — permintaan user 2026-08-12). Sebelumnya
 * terlalu tembus sehingga teks chat di belakangnya mengganggu keterbacaan.
 * Sekarang hampir solid (kesan kaca: fill tinggi + border tipis glass-edge),
 * tetapi masih sedikit memudar supaya overlay tetap terasa "di atas" pesan.
 */
internal const val CHIP_FILL_ALPHA_DARK = 0.92f
internal const val CHIP_FILL_ALPHA_LIGHT = 0.90f

/**
 * Warna border tipis "glass-edge" untuk chip rekomendasi & FAB jump-to-bottom
 * (2026-08-12): 1dp outlineVariant alpha sedang — memberi kesan tepi kaca yang
 * bersih tanpa shadow (konsisten dengan karakter composer pill: tanpa bayangan).
 */
internal val CHIP_GLASS_BORDER_ALPHA = 0.55f

/**
 * Tinggi total baris chips saran cepat (pad atas 4dp + row 48dp + pad bawah 4dp =
 * 56dp). Satu sumber kebenaran: dipakai ChatScreen untuk menghitung contentPadding
 * bottom LazyColumn (CHIP_ROW_HEIGHT + 8dp) supaya pesan terakhir berhenti tepat
 * di atas zona chips — jika tinggi chips diubah di sini, padding list ikut mengikuti.
 */
internal val CHIP_ROW_HEIGHT = 56.dp

@Composable
fun QuickSuggestionRow(
    suggestions: List<String>,
    onSuggestionClicked: (String) -> Unit,
    // Ruang cadangan di ujung KIRI baris chip saat FAB jump-to-bottom melayang
    // di ujung kiri baris saran (ChatScreen) — chip pertama tidak pernah
    // tersembunyi di balik FAB dan tetap bisa diketuk. 0.dp saat FAB tidak
    // tampil. Nilai berubah dengan ANIMASI (animateDpAsState di pemanggil)
    // sehingga chips bergeser halus ke kanan saat FAB muncul, bukan lompat.
    startPadding: Dp = 0.dp,
    // Overlay (2026-08-12): baris chips kini melayang DI ATAS daftar pesan (list
    // scroll sampai ke kolom input) — pemanggil mengatur posisi, mis.
    // Modifier.align(Alignment.BottomCenter) di dalam Box daftar.
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // BUG-05 (r1.2.0): LazyRow TIDAK men-layout item-nya di compose-bom
        // 2026.06 (chips mengambil tinggi 48dp tapi isi kosong — terverifikasi
        // live). Untuk 4-5 saran pendek, Row + horizontalScroll lebih sederhana
        // & deterministik; tetap scrollable jika saran memanjang.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                // startPadding DI LUAR area scroll (sebelum horizontalScroll):
                // mempersempit lebar yang bisa diisi chips, sehingga baris chips
                // berhenti sebelum area FAB (yang melayang di ujung kiri) —
                // bukan sekadar padding konten di ujung daftar.
                //
                // CLAMP (r1.2.0, crash 2026-08-11 17:24): pemanggil mengirim nilai
                // dari animateDpAsState dengan spring bouncy (DampingRatioMediumBouncy)
                // yang OVER-SHOOT di bawah 0 saat FAB menghilang (target 64dp→0dp).
                // Padding negatif = IllegalArgument crash. coerceAtLeast menjaga nilai
                // tetap >= 0 tanpa menghilangkan efek elastis.
                .padding(start = startPadding.coerceAtLeast(0.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Reviewer (BUG-05): chip heightIn(min=40dp) di row 48dp default top-align
            // → ada slack ~8dp di bawah. CenterVertically agar seimbang.
            verticalAlignment = Alignment.CenterVertically
        ) {
            suggestions.forEachIndexed { index, text ->
                // ANIMASI MASUK (2026-08-12, permintaan user): chip masuk satu per
                // satu dari arah KANAN seperti kereta — slide horizontal + fade,
                // stagger index*45ms — saat baris pertama muncul (bukan pop instan).
                // Berhenti saat user mengetik (baris dibuang), lalu muncul lagi dari
                // kanan saat draf dikosongkan.
                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(
                            durationMillis = Motion.BASE_MS,
                            delayMillis = index * 45,
                            easing = Motion.STANDARD
                        )
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = Motion.BASE_MS,
                            delayMillis = index * 45,
                            easing = Motion.STANDARD
                        )
                    )
                ) {
                    // Chip FLOATING BERLATAR (2026-08-12, permintaan user): tombol
                    // harus terbaca sebagai tombol → fill surfaceVariant hampir
                    // solid (CHIP_FILL_ALPHA) + border tipis glass-edge
                    // (CHIP_GLASS_BORDER_ALPHA) — satu keluarga dengan pill composer
                    // (tanpa shadow). Background BARIS tetap transparan — jangan
                    // pernah panel full-width (regresi lama yang menutupi chat di
                    // atas keyboard).
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = if (semantic.isDark) CHIP_FILL_ALPHA_DARK else CHIP_FILL_ALPHA_LIGHT
                        ),
                        // Kesan kaca (2026-08-12): tepi tipis outlineVariant —
                        // transparansi turun drastis (5-10%) jadi teks di belakang
                        // tidak lagi mengganggu, border memberi definisi bentuk.
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = CHIP_GLASS_BORDER_ALPHA)
                        ),
                        // P2-4 (audit touch target): tinggi chip minimal 40dp — sebelumnya
                        // hanya ~28dp, di bawah rekomendasi Android (48dp).
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .widthIn(max = 180.dp)
                            .clickable { onSuggestionClicked(text) }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quote pesan yang dibalas — GAYA TELEGRAM: menempel DI DALAM pill composer
 * (di atas baris input), bukan card terpisah di atas composer. Punya garis
 * aksen vertikal kiri, nama pengirim tebal berwarna, snippet 1 baris, dan
 * tombol ✕ untuk membatalkan. Tinggi kompak (2 baris teks kecil).
 */
@Composable
private fun ReplyQuoteRow(
    target: ChatMessage,
    onDismiss: () -> Unit
) {
    val snippet = target.messageText.ifBlank {
        target.fileName ?: target.imagePath?.let { "📷" } ?: ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Garis aksen vertikal (Telegram): 3dp, rounded, warna primary.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_reply_label, target.sender),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.chat_reply_cancel),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Pratinjau dokumen (PDF) sebelum dikirim. */
@Composable
fun ChatFilePreviewBar(
    fileName: String?,
    onRemove: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    AnimatedVisibility(
        visible = fileName != null,
        enter = fadeIn(animationSpec = Motion.fast()) + slideInVertically(initialOffsetY = { it }, animationSpec = Motion.fast()),
        exit = fadeOut(animationSpec = Motion.quick()) + slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.quick())
    ) {
        // Floating card (bukan surface full-width) — konsisten dengan composer
        // pill: TANPA shadow (audit konsistensi 2026-08-11 — shadow memberi
        // kesan "ditempel di panel lain", sama seperti masalah pill lama).
        // Tinggi seragam 52dp (isi min 44dp + margin 4dp × 2).
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = CHAT_CARD_MAX_WIDTH)
                    .heightIn(min = CHAT_BAR_HEIGHT - 8.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = semantic.expense,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = fileName ?: stringResource(R.string.chat_pdf_attached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.chat_image_remove)
                    )
                }
            }
        }
    }
}

/** Pratinjau foto lampiran (nota belanja) sebelum dikirim. */
@Composable
fun ChatImagePreviewBar(
    imagePath: String?,
    onRemove: () -> Unit
) {
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = imagePath
    ) {
        value = withContext(Dispatchers.IO) {
            imagePath?.let { ImageFileUtil.decodeImage(it, 640) }
        }
    }
    AnimatedVisibility(
        visible = imagePath != null,
        enter = fadeIn(animationSpec = Motion.fast()) + slideInVertically(initialOffsetY = { it }, animationSpec = Motion.fast()),
        exit = fadeOut(animationSpec = Motion.quick()) + slideOutVertically(targetOffsetY = { it }, animationSpec = Motion.quick())
    ) {
        // Floating card (bukan surface full-width) — konsisten dengan composer
        // pill: TANPA shadow (audit konsistensi 2026-08-11).
        // Tinggi seragam 52dp; thumbnail 44dp supaya tidak mendorong kartu lebih tinggi.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = CHAT_CARD_MAX_WIDTH)
                    .heightIn(min = CHAT_BAR_HEIGHT - 8.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                previewBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_image_desc),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.chat_image_attached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.chat_image_remove)
                    )
                }
            }
        }
    }
}

/**
 * Chat Input Box — Telegram-style: floating rounded pill `[ +  pesan  ✨ ]`
 * dengan tombol Send circular terpisah di kanan. TIDAK ada panel/container besar
 * yang membungkus area input — pill & Send berdiri sendiri di atas background halaman.
 * Quote balasan (reply) menempel DI DALAM pill di atas baris input (gaya Telegram),
 * bukan card terpisah. State di-hoist: nilai, focus, dan callback datang dari pemilik (ChatScreen).
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isDark: Boolean,
    canSend: Boolean,
    onAttachClick: () -> Unit,
    onSend: () -> Unit,
    onAskAi: () -> Unit,
    inputFocusRequester: FocusRequester,
    replyTarget: ChatMessage? = null,
    onReplyDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current

    // Smooth color transitions instead of instant snapping
    val sendBgColor by animateColorAsState(
        targetValue = if (value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = Motion.fast(),
        label = "sendBg"
    )
    val sendTintColor by animateColorAsState(
        targetValue = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.fast(),
        label = "sendTint"
    )
    val askAiTint by animateColorAsState(
        targetValue = when {
            value.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else -> semantic.ai
        },
        animationSpec = Motion.fast(),
        label = "askAiTint"
    )

    // Dua elemen floating yang berdiri sendiri: pill input + tombol Send circular.
    // Tanpa Surface pembungkus → background halaman tetap terlihat di antara/sekitar.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        // Bottom: saat reply aktif pill lebih tinggi (quote + input), tombol Send
        // tetap sejajar dengan baris input — gaya Telegram. Tanpa reply, pill 52dp
        // = Send 52dp sehingga keduanya sejajar sempurna.
        //
        // CATATAN ANIMASI: JANGAN pasang animateContentSize di Row ini — akan
        // dobel-animasi dengan expandVertically quote (rubber-band). Auto-grow
        // field (paragraf panjang) di-animasi oleh animateContentSize pada Box
        // field; quote di-animasi expandVertically-nya sendiri.
        verticalAlignment = Alignment.Bottom
    ) {
        // ── Floating pill input: [ +  Ketik pesan...  ✨ ] ──
        // TANPA shadow/tonalElevation: bayangan pill sebelumnya menghasilkan garis
        // lunak di atas navbar sehingga composer terlihat "ditempel di panel lain".
        // Tinggi default pill = 52dp, SEIMBANG dengan tombol Send (52dp):
        // isi row = tombol + 48dp, padding vertikal 2dp × 2 → 52dp. Field diberi
        // contentPadding ramping (single-line ≈44dp) agar tidak mendorong pill
        // lebih tinggi (sebelumnya field 56dp → pill 64dp, tidak seimbang).
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.9f else 0.55f),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = CHAT_BAR_HEIGHT)
        ) {
            // Telegram-style: quote pesan yang dibalas menempel DI DALAM pill
            // (di atas baris input), bukan card terpisah di atas composer.
            Column(modifier = Modifier.fillMaxWidth()) {
                // Quote balasan muncul LEMBUT (expand dari atas + fade) — bukan
                // pop instan. shrinkVertically saat dibatalkan. Ini juga membuat
                // pill tumbuh halus tanpa kesan gap/jumping.
                AnimatedVisibility(
                    visible = replyTarget != null,
                    enter = expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = Motion.fast()
                    ) + fadeIn(animationSpec = Motion.fast()),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = Motion.quick()
                    ) + fadeOut(animationSpec = Motion.quick())
                ) {
                    replyTarget?.let { target ->
                        ReplyQuoteRow(target = target, onDismiss = onReplyDismiss)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                // Tombol Plus (+) — BAGIAN DARI pill, di sisi kiri, pusat lampiran.
                // 48dp = touch target minimum (konsisten audit P2-4).
                IconButton(
                    onClick = onAttachClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.chat_attach_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Kolom input teks — BasicTextField polos (tanpa container sendiri;
                // warna datang dari pill). Catatan M3 1.4.0: param contentPadding
                // DIHAPUS dari OutlinedTextField (API TextField di-refactor), jadi
                // dipakai BasicTextField yang memberi kontrol tinggi penuh: single-line
                // 48dp → pill total 52dp, SEIMBANG dengan tombol Send. Auto-grow per
                // baris (maxLines 6) saat paragraf panjang, lalu scrollable.
                //
                // PENTING: JANGAN pakai fillMaxSize di sini — BasicTextField + weight(1f)
                // tanpa batas tinggi akan MELAR mencuri seluruh sisa tinggi Column
                // (terverifikasi live: pill membentang 1650px). fillMaxWidth + heightIn
                // di BasicTextField-nya langsung, sehingga tinggi mengikuti isi saja.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // Audit motion (2026-08-12): spec default animateContentSize
                        // memakai spring bawaan — diseragamkan ke Motion.fast() (200ms
                        // FastOutSlowIn) supaya auto-grow paragraf mengikuti motion
                        // language yang sama dengan elemen composer lain.
                        .animateContentSize(animationSpec = Motion.fast()),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_input_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.length <= MAX_MESSAGE_LENGTH) onValueChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = CHAT_FIELD_MIN_HEIGHT)
                            .padding(vertical = 12.dp)
                            .testTag("chat_input_field")
                            .focusRequester(inputFocusRequester),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        maxLines = 6,
                        minLines = 1
                    )
                }

                // Tombol ✨ (Tanya AI) — elemen Row langsung di dalam pill
                // (BasicTextField tidak punya trailingIcon bawaan).
                IconButton(
                    enabled = value.isNotBlank(),
                    onClick = { onAskAi() },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("ask_ai_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = stringResource(R.string.chat_ask_ai_desc),
                        tint = askAiTint
                    )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // ── Tombol Kirim — circular floating, vertikal tengah dengan pill ──
        IconButton(
            enabled = canSend,
            onClick = onSend,
            modifier = Modifier
                .size(CHAT_BAR_HEIGHT)
                .clip(CircleShape)
                .background(sendBgColor)
                .testTag("send_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = stringResource(R.string.chat_send_desc),
                tint = sendTintColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** ModalBottomSheet untuk pilihan lampiran (Telegram-style). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAttachmentSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onPdf: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        // Sheet berada di area konten (di atas NavigationBar) — padding
        // navbar bawaan sheet dinolkan agar tidak muncul celah.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_attach_desc),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            HorizontalDivider()

            // Opsi: Kamera
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_take_photo)) },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PhotoCamera,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onCamera)
            )

            // Opsi: Galeri
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_pick_gallery)) },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onGallery)
            )

            // Opsi: Dokumen PDF
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_send_pdf)) },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ExpenseRed.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PictureAsPdf,
                            contentDescription = null,
                            tint = semantic.expense,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onPdf)
            )
        }
    }
}
