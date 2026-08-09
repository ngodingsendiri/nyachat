package com.startupmini.nyachat.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.ImageFileUtil
import com.startupmini.nyachat.ui.util.dayLabel
import com.startupmini.nyachat.ui.util.isSameDay
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Baris chat: pemisah tanggal atau pesan (dengan flag header grup pengirim). */
private sealed interface ChatRow {
    data class Header(val label: String, val key: String) : ChatRow
    data class MessageRow(val message: ChatMessage, val showSenderHeader: Boolean) : ChatRow
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    activeSender: String,
    isAiThinking: Boolean,
    quickSuggestions: List<String>,
    onSendMessage: (String, String?, String?, String?, String?, String?) -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onAskAiClicked: (String) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onOpenTransaction: (ChatMessage) -> Unit = {},
    // M9: PIN workspace aktif — lampiran (foto nota/dokumen) disimpan di folder
    // khusus per-workspace supaya ganti workspace tidak merusak foto workspace lama.
    workspacePin: String? = null,
    // BUG-2: draf chat DI-HOIST ke MainActivity (`rememberSaveable`) — AnimatedContent
    // menghancurkan state ChatScreen saat pindah tab, sehingga rememberSaveable lokal
    // di sini tidak cukup. Nilai + callback datang dari pemilik state.
    draftText: String = "",
    onDraftChange: (String) -> Unit = {},
    // BUG-08: reset draf saat dialog transaksi manual (dari tab Chat) ditutup —
    // diproses MainActivity (pemilik state), tidak lagi di sini.
    resetChatInputTrigger: Int = 0
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    var pendingFilePath by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var lastKnownCount by remember { mutableIntStateOf(-1) }

    val inputFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    // BUG-08: reset draf tetap diproses di sini (nulis ke state hoisted) —
    // karakter sisa (mis. titik ribuan dari kolom nominal) tidak menempel.
    LaunchedEffect(resetChatInputTrigger) {
        if (resetChatInputTrigger > 0) onDraftChange("")
    }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraTempUri
        cameraTempUri = null
        if (success && uri != null) {
            coroutineScope.launch {
                pendingImagePath = ImageFileUtil.saveImageFromUri(context, uri, workspacePin)
            }
        }
    }
    val pickGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                pendingImagePath = ImageFileUtil.saveImageFromUri(context, uri, workspacePin)
            }
        }
    }
    val pickPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val saved = ImageFileUtil.saveFileFromUri(context, uri, workspacePin)
                if (saved != null) {
                    pendingFilePath = saved.path
                    pendingFileName = saved.name
                }
            }
        }
    }

    val todayLabel = stringResource(R.string.today_label)
    val yesterdayLabel = stringResource(R.string.yesterday_label)
    val rows = remember(messages, todayLabel, yesterdayLabel) {
        buildChatRows(messages, todayLabel, yesterdayLabel)
    }

    // Tombol "lompat ke pesan terbaru" muncul saat user tidak di dasar obrolan.
    val shouldShowJumpButton by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible < info.totalItemsCount - 4
        }
    }

    // Auto-scroll: halus kalau sudah di bawah & ada konten baru; instan saat pertama
    // dibuka; TIDAK menarik user yang sedang membaca riwayat di atas.
    LaunchedEffect(rows.size, isAiThinking) {
        if (rows.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 4
            if (lastKnownCount >= 0) {
                if (nearBottom) listState.animateScrollToItem(rows.size - 1)
            } else {
                listState.scrollToItem(rows.size - 1)
            }
            lastKnownCount = rows.size
        }
    }

    // Re-anchor saat keyboard (IME) muncul: viewport menyusut sehingga pesan yang
    // tadinya menempel di dasar ikut "naik". Kalau user memang sedang di dekat dasar,
    // kembalikan posisi ke pesan terbaru supaya tetap terlihat tepat di atas input.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && rows.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 4
            if (nearBottom) listState.scrollToItem(rows.size - 1)
        }
    }

    val sendMessage = {
        val text = draftText.trim()
        val image = pendingImagePath
        val file = pendingFilePath
        val fileName = pendingFileName
        if (text.isNotBlank() || image != null || file != null) {
            onSendMessage(
                text, image, file, fileName,
                replyTarget?.sender, replyTarget?.messageText
            )
            onDraftChange("")
            pendingImagePath = null
            pendingFilePath = null
            pendingFileName = null
            replyTarget = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Chat Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)
            ) {
                if (messages.isEmpty() && !isAiThinking) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.chat_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.chat_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                items(rows, key = { row ->
                    when (row) {
                        is ChatRow.Header -> row.key
                        is ChatRow.MessageRow -> "msg_${row.message.id}"
                    }
                }) { row ->
                    when (row) {
                        is ChatRow.Header -> DateSeparator(label = row.label)

                        is ChatRow.MessageRow -> {
                            var menuOpen by remember { mutableStateOf(false) }
                            val clipboard = LocalClipboardManager.current
                            val msg = row.message
                            // Grouping pengirim sama (item 6): jarak rapat antar
                            // pesan berurutan dari pengirim yang sama; jarak penuh
                            // hanya di awal grup (header pengirim baru).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (row.showSenderHeader) 10.dp else 2.dp)
                            ) {
                                ChatMessageBubble(
                                    message = msg,
                                    currentActiveSender = activeSender,
                                    showHeader = row.showSenderHeader,
                                    onLongPress = { menuOpen = true },
                                    onReply = { replyTarget = msg },
                                    onOpenFile = { openAttachedFile(context, msg) },
                                    onOpenTransaction = { onOpenTransaction(msg) },
                                    modifier = Modifier.animateItem()
                                )
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_reply)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null) },
                                        onClick = {
                                            replyTarget = msg
                                            menuOpen = false
                                        }
                                    )
                                    if (msg.sender == activeSender && msg.sender != Constants.Sender.AI) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_edit)) },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                            onClick = {
                                                editingMessage = msg
                                                menuOpen = false
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_copy)) },
                                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            clipboard.setText(AnnotatedString(msg.messageText))
                                            menuOpen = false
                                        }
                                    )
                                    if (msg.sender == activeSender || msg.sender == Constants.Sender.AI) {
                                        // Konsisten dengan izin edit: hanya pesan milik sendiri
                                        // (dan bubble AI bersama) yang boleh dihapus — pesan
                                        // anggota lain tidak bisa dihapus dari perangkat ini.
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_delete)) },
                                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                            onClick = {
                                                pendingDelete = msg
                                                menuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAiThinking) {
                    item {
                        AiThinkingBubble(
                            modifier = Modifier
                                .animateItem()
                                .padding(top = 10.dp)
                        )
                    }
                }
            }

            // Quick Suggestion Chips (placed above input field)
            // BUG-05 (r1.2.0): AnimatedVisibility dari compose-bom 2026.06 meng-komposisi
            // content tapi TIDAK me-layout-nya (chips tak pernah terlihat di runtime walau
            // kondisi visible terpenuhi — terverifikasi live). Dipakai if biasa; LazyRow di
            // QuickSuggestionRow diganti Row + horizontalScroll karena juga tak me-layout item.
            if (draftText.isBlank() && quickSuggestions.isNotEmpty()) {
                QuickSuggestionRow(
                    suggestions = quickSuggestions,
                    onSuggestionClicked = { onDraftChange(it) }
                )
            }

            // Bar balasan (reply) — muncul saat user membalas pesan via swipe/menu
            ChatReplyBar(
                replyTarget = replyTarget,
                onDismiss = { replyTarget = null }
            )

            // Pratinjau dokumen (PDF) sebelum dikirim
            ChatFilePreviewBar(
                fileName = pendingFileName,
                onRemove = {
                    pendingFilePath = null
                    pendingFileName = null
                }
            )

            // Pratinjau foto lampiran (nota belanja) sebelum dikirim
            ChatImagePreviewBar(
                imagePath = pendingImagePath,
                onRemove = { pendingImagePath = null }
            )

            // Info transparan: lampiran TIDAK ikut sinkron antar perangkat
            AnimatedVisibility(
                visible = pendingImagePath != null || pendingFilePath != null,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(150))
            ) {
                Text(
                    text = stringResource(R.string.chat_attach_no_sync),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            // Chat Input Box — Telegram-style: Plus | TextField (auto-expand) | Send
            ChatInputBar(
                value = draftText,
                onValueChange = { onDraftChange(it) },
                isDark = isDark,
                canSend = draftText.isNotBlank() || pendingImagePath != null || pendingFilePath != null,
                onAttachClick = { showAttachmentSheet = true },
                onSend = { sendMessage() },
                onAskAi = {
                    if (draftText.isNotBlank()) {
                        onAskAiClicked(draftText)
                        onDraftChange("")
                    }
                },
                inputFocusRequester = inputFocusRequester
            )

            // ModalBottomSheet untuk pilihan lampiran (Telegram-style)
            if (showAttachmentSheet) {
                ChatAttachmentSheet(
                    onDismiss = { showAttachmentSheet = false },
                    onCamera = {
                        showAttachmentSheet = false
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraTempUri = uri
                        runCatching { takePictureLauncher.launch(uri) }
                    },
                    onGallery = {
                        showAttachmentSheet = false
                        pickGalleryLauncher.launch("image/*")
                    },
                    onPdf = {
                        showAttachmentSheet = false
                        pickPdfLauncher.launch(arrayOf("application/pdf"))
                    }
                )
            } // end ModalBottomSheet if-block
        } // end Column

        // Tombol lompat ke pesan terbaru (muncul saat scroll ke atas)
        AnimatedVisibility(
            visible = shouldShowJumpButton,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 12.dp),
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(150))
        ) {
            FloatingActionButton(
                onClick = { coroutineScope.launch { listState.animateScrollToItem(rows.size - 1) } },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("jump_to_bottom")
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_jump_bottom_desc)
                )
            }
        }

        // Konfirmasi hapus pesan
        pendingDelete?.let { msg ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.confirm_delete_message_title)) },
                text = { Text(stringResource(R.string.confirm_delete_message_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteMessage(msg.id)
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

        // Dialog edit pesan
        editingMessage?.let { msg ->
            var editText by remember(msg.id) { mutableStateOf(msg.messageText) }

            // F3 (audit focus order): fokus langsung ke kolom teks saat dialog edit dibuka.
            val editFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(120)
                editFocusRequester.requestFocus()
            }

            AlertDialog(
                onDismissRequest = { editingMessage = null },
                title = { Text(stringResource(R.string.chat_edit_title)) },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.chat_edit_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { if (it.length <= MAX_MESSAGE_LENGTH) editText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(editFocusRequester),
                            maxLines = 5
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = editText.isNotBlank(),
                        onClick = {
                            onEditMessage(msg.id, editText)
                            editingMessage = null
                        }
                    ) {
                        Text(stringResource(R.string.chat_save_edit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingMessage = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

// ---- Helpers pembangun baris chat ----

private fun buildChatRows(
    messages: List<ChatMessage>,
    todayLabel: String,
    yesterdayLabel: String
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    messages.forEachIndexed { index, msg ->
        val prev = messages.getOrNull(index - 1)
        if (prev == null || !isSameDay(prev.timestamp, msg.timestamp)) {
            rows.add(
                ChatRow.Header(
                    label = dayLabel(msg.timestamp, todayLabel, yesterdayLabel),
                    key = "day_${msg.timestamp}"
                )
            )
        }
        rows.add(ChatRow.MessageRow(msg, prev?.sender != msg.sender))
    }
    return rows
}
