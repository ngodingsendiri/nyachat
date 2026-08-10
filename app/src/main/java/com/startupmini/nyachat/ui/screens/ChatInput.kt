package com.startupmini.nyachat.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.ImageFileUtil
import com.startupmini.nyachat.ui.theme.ExpenseRed
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_MESSAGE_LENGTH = 2000

/**
 * Lebar maksimum kartu floating (reply/preview) — disamakan dengan lebar
 * composer pill (~327dp) supaya ketiga elemen terasa satu keluarga.
 */
private val CHAT_CARD_MAX_WIDTH = 320.dp

@Composable
fun QuickSuggestionRow(
    suggestions: List<String>,
    onSuggestionClicked: (String) -> Unit
) {
    val semantic = LocalSemanticColors.current
    Column(
        modifier = Modifier
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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Reviewer (BUG-05): chip heightIn(min=40dp) di row 48dp default top-align
            // → ada slack ~8dp di bawah. CenterVertically agar seimbang.
            verticalAlignment = Alignment.CenterVertically
        ) {
            suggestions.forEach { text ->
                // Chip FLOATING (bukan bar/panel): fill surfaceVariant TIPIS + border
                // outline — sebelumnya surfaceVariant SOLID membuat 3-4 chip lebar
                // nyaris memenuhi layar dan tampak sebagai container/bar kontinu
                // dengan garis tegas di atas-bawahnya. Lebar dibatasi (180dp) agar
                // chip tampil sebagai pill kompak yang jelas mengambang, bukan slab
                // lebar. Alpha mode-aware (dark lebih pekat, pola sama dgn pill).
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (semantic.isDark) 0.5f else 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

/** Bar balasan (reply) — muncul saat user membalas pesan via swipe/menu. */
@Composable
fun ChatReplyBar(
    replyTarget: ChatMessage?,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = replyTarget != null,
        enter = slideInVertically(initialOffsetY = { it / 3 }, animationSpec = tween(240)) + fadeIn(animationSpec = tween(240)),
        exit = slideOutVertically(targetOffsetY = { it / 3 }, animationSpec = tween(180)) + fadeOut(animationSpec = tween(180))
    ) {
        val target = replyTarget
        if (target != null) {
            val snippet = target.messageText.ifBlank {
                target.fileName ?: target.imagePath?.let { "📷" } ?: ""
            }
            // Floating card (bukan surface full-width) — konsisten dengan composer
            // pill: rounded, shadow halus, lebar terbatas supaya terasa mengambang.
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shadowElevation = 2.dp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = CHAT_CARD_MAX_WIDTH)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
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
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.chat_reply_cancel),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
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
        enter = fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(150))
    ) {
        // Floating card (bukan surface full-width) — konsisten dengan composer pill.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shadowElevation = 2.dp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = CHAT_CARD_MAX_WIDTH)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
                IconButton(onClick = onRemove) {
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
        enter = fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(150))
    ) {
        // Floating card (bukan surface full-width) — konsisten dengan composer pill.
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shadowElevation = 2.dp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = CHAT_CARD_MAX_WIDTH)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                previewBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_image_desc),
                        modifier = Modifier
                            .size(56.dp)
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
                IconButton(onClick = onRemove) {
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
 * Chat Input Box — WhatsApp-style: floating rounded pill `[ +  pesan  ✨ ]`
 * dengan tombol Send circular terpisah di kanan. TIDAK ada panel/container besar
 * yang membungkus area input — pill & Send berdiri sendiri di atas background halaman.
 * State di-hoist: nilai, focus, dan callback datang dari pemilik (ChatScreen).
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
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current

    // Smooth color transitions instead of instant snapping
    val sendBgColor by animateColorAsState(
        targetValue = if (value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(200),
        label = "sendBg"
    )
    val sendTintColor by animateColorAsState(
        targetValue = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "sendTint"
    )
    val askAiTint by animateColorAsState(
        targetValue = when {
            value.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else -> semantic.ai
        },
        animationSpec = tween(200),
        label = "askAiTint"
    )

    // Dua elemen floating yang berdiri sendiri: pill input + tombol Send circular.
    // Tanpa Surface pembungkus → background halaman tetap terlihat di antara/sekitar.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Floating pill input: [ +  Ketik pesan...  ✨ ] ──
        // TANPA shadow/tonalElevation: bayangan pill sebelumnya menghasilkan garis
        // lunak di atas navbar sehingga composer terlihat "ditempel di panel lain".
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.9f else 0.55f),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
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

                // Kolom input teks — transparan (warna datang dari pill),
                // auto-grow hingga 6 baris, lalu scrollable
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= MAX_MESSAGE_LENGTH) onValueChange(it) },
                    placeholder = {
                        Text(stringResource(R.string.chat_input_placeholder))
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field")
                        .focusRequester(inputFocusRequester),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    maxLines = 6,
                    minLines = 1,
                    trailingIcon = {
                        IconButton(
                            enabled = value.isNotBlank(),
                            onClick = { onAskAi() },
                            modifier = Modifier.testTag("ask_ai_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = stringResource(R.string.chat_ask_ai_desc),
                                tint = askAiTint
                            )
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // ── Tombol Kirim — circular floating, vertikal tengah dengan pill ──
        IconButton(
            enabled = canSend,
            onClick = onSend,
            modifier = Modifier
                .size(52.dp)
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
