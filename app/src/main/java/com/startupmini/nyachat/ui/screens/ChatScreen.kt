package com.startupmini.nyachat.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.firebase.auth.FirebaseAuth
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.remote.E2eeSyncManager
import com.startupmini.nyachat.data.remote.FirestoreSyncManager
import com.startupmini.nyachat.data.remote.MembershipManager
import com.startupmini.nyachat.data.remote.ReceiptInfo
import com.startupmini.nyachat.data.remote.receiptStats
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.ImageFileUtil
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.theme.Motion
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

/**
 * Kekakuan spring FAB jump-to-bottom & geser chips (r1.2.0) — dipakai bersama
 * supaya kemunculan FAB dan pergeseran chips selalu sinkron. Awalnya 600f
 * (≈ settle ±1 detik), diturunkan ke 1600f (≈ ±600ms) oleh audit motion
 * 2026-08-12: tetap soft (Motion.elastic — damping 0.88, tanpa overshoot
 * berlebihan) tapi lebih responsif — fade-in 300ms kini berakhir hampir
 * bersamaan dengan slide.
 */
private const val FAB_SPRING_STIFFNESS = 1600f

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
    resetChatInputTrigger: Int = 0,
    // r1.2.3 (P1): map nama-tampilan → path foto avatar (untuk header bubble
    // pesan masuk). Dibangun MainActivity dari daftar member + foto diri sendiri.
    senderAvatarPaths: Map<String, String> = emptyMap()
) {
    // isDark dari token semantik (single source of truth) — pola luminance()
    // yang rapuh sudah ditinggalkan (audit konsistensi 2026-08-11).
    val isDark = LocalSemanticColors.current.isDark
    var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    var pendingFilePath by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    // Audit response (2026-08-12): umpan balik saat menyimpan lampiran (foto/PDF) —
    // sebelumnya proses berjalan senyap; sekarang baris info menampilkan
    // "Menyimpan lampiran…" sampai file selesai disalin.
    var isSavingAttachment by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    // Viewer foto full-screen (audit gestur 2026-08-13): sentuh sekali pada
    // bubble GAMBAR membuka foto diperbesar — bukan menu (menu = tahan lama).
    var viewerMessage by remember { mutableStateOf<ChatMessage?>(null) }
    // r1.7.1: dialog "Info Pesan" — daftar anggota yang menerima/membaca.
    var infoMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var lastKnownCount by remember { mutableIntStateOf(-1) }
    // Indeks tempat auto-scroll terakhir berhenti. Dipakai untuk tahu apakah user
    // masih "menempel" di dasar (lastVisible == lastAnchorIndex) — kalau iya,
    // konten baru tetap diikuti ke bawah, meski posisi sudah tidak "dekat dasar"
    // karena sync menyisipkan pesan bertahap (lihat LaunchedEffect rows.size).
    var lastAnchorIndex by remember { mutableIntStateOf(-1) }

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
                // try/finally: kalau simpan melempar (IO/disk penuh), indikator
                // "Menyimpan…" tidak boleh macet selamanya (reviewer response audit).
                try {
                    isSavingAttachment = true
                    pendingImagePath = ImageFileUtil.saveImageFromUri(context, uri, workspacePin)
                } finally {
                    isSavingAttachment = false
                }
            }
        }
    }
    val pickGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    isSavingAttachment = true
                    pendingImagePath = ImageFileUtil.saveImageFromUri(context, uri, workspacePin)
                } finally {
                    isSavingAttachment = false
                }
            }
        }
    }
    val pickPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    isSavingAttachment = true
                    val saved = ImageFileUtil.saveFileFromUri(context, uri, workspacePin)
                    if (saved != null) {
                        pendingFilePath = saved.path
                        pendingFileName = saved.name
                    }
                } finally {
                    isSavingAttachment = false
                }
            }
        }
    }

    val todayLabel = stringResource(R.string.today_label)
    val yesterdayLabel = stringResource(R.string.yesterday_label)
    val rows = remember(messages, todayLabel, yesterdayLabel) {
        buildChatRows(messages, todayLabel, yesterdayLabel)
    }

    // r1.4.0 (indikator AI memproses): bubble pesan TERAKHIR milik user yang
    // sedang diproses AI. Pesan anggota lain yang tiba belakangan TIDAK
    // diindikasi (aman lintas perangkat). null saat tidak ada proses berjalan
    // → tidak ada titik di bubble mana pun.
    val processingMessageId = if (isAiThinking) {
        messages.lastOrNull { it.sender == activeSender }?.id
    } else null

    // r1.7.1 (tanda terima/baca): marker `receipts/{cloudId}` per pesan
    // terenkripsi → titik abu (diterima) / hijau (dibaca) / pelangi (semua
    // baca). Pengganti centang ✓/✓✓ (r1.7.0) yang hanya tahu "semua menerima".
    val receipts by FirestoreSyncManager.receipts.collectAsState()
    // UID akun sendiri + himpunan anggota lain → titik menghitung anggota lain
    // (device pengirim di-exclude), konsisten dengan model delivery/read.
    val myUid = remember { runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() }
    val members by MembershipManager.members.collectAsState()
    val otherUids = remember(members, myUid) {
        members.filter { it.uid.isNotBlank() && it.uid != myUid }.map { it.uid }.toSet()
    }
    // Label tampilan (nama/alias) per UID → dialog "Info Pesan".
    val memberLabels = remember(members) {
        members.associate { it.uid to (it.label.ifBlank { it.name }.ifBlank { it.uid }) }
    }

    // r1.7.1 (anti-degradasi #2): workspace terenkripsi tapi kunci belum siap →
    // banner kecil di atas input menjelaskan mengapa pesan baru tertunda.
    val e2eeStatus by E2eeSyncManager.status.collectAsState()

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
    //
    // Race sync bertahap (r1.7.1): saat app dibuka, Room memancarkan pesan
    // bertahap dari sync Firestore (0 → N1 → N2 → ...). Batch pertama di-anchor
    // ke dasar (index N1-1), tapi begitu batch N2>N1 tiba posisi itu "jauh dari
    // dasar" → gate nearBottom lama menolak follow → chat berhenti di area atas.
    // Solusi: selama user belum sengaja scroll menjauh dari posisi yang kita
    // tinggalkan (lastVisible == lastAnchorIndex), terus ikuti ke pesan terbaru.
    LaunchedEffect(rows.size, isAiThinking) {
        if (rows.isNotEmpty()) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val atBottom = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 4
            val firstOpen = lastKnownCount == -1
            val untouched = lastVisible == lastAnchorIndex
            if (firstOpen || atBottom || untouched) {
                if (firstOpen) {
                    listState.scrollToItem(rows.size - 1)
                } else {
                    listState.animateScrollToItem(rows.size - 1)
                }
                lastAnchorIndex = rows.size - 1
            }
            lastKnownCount = rows.size
        }
    }

    // r1.7.1 (umpan balik tester): buka app → chat harus mendarat di pesan
    // TERBARU, bukan di posisi terakhir. Komposisi ChatScreen TIDAK dibongkar
    // saat app ke background (lastKnownCount & scroll state diingat), jadi tanpa
    // ini posisi menempel di tempat lama. Tidak menarik user yang sedang membaca
    // riwayat: re-anchor hanya bila masih "menempel" di dasar / di posisi yang
    // kita tinggalkan — kalau user sengaja scroll ke atas, posisi dihormati
    // (juga membuat kembali dari picker foto/PDF tidak melompat).
    // r1.7.1 (mark-as-read): hanya tandai pesan DIBACA saat layar chat benar
    // aktif (resumed) — app di background / tab lain tidak boleh menandai baca.
    var isChatResumed by remember { mutableStateOf(false) }
    // r1.7.1 (tanda terima/baca): cloudId yang SUDAH mencapai titik pelangi
    // (semua anggota lain baca) — diingat sesi ini karena server menghapus
    // marker begitu semua baca; tanpa ini titik pelangi "turun" jadi abu lagi.
    var rainbowIdsState by remember { mutableStateOf<Set<String>>(emptySet()) }
    LifecycleResumeEffect(Unit) {
        isChatResumed = true
        coroutineScope.launch {
            delay(120)
            if (rows.isNotEmpty()) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                val atBottom = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 4
                if (atBottom || lastVisible == lastAnchorIndex) {
                    listState.scrollToItem(rows.size - 1)
                    lastAnchorIndex = rows.size - 1
                }
            }
        }
        onPauseOrDispose { isChatResumed = false }
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
            // Tekstur grid halus di area chat (2026-08-11) — pola kotak-kotak
            // sangat tipis & alpha rendah supaya background hampir terlihat
            // polos tapi ada depth; tetap di belakang semua elemen chat.
            // Audit warna (2026-08-11): dark memakai surfaceVariant (hue
            // menyatu dengan palet gelap kehijauan, bukan putih murni yang
            // tadinya nyaris seterang surface saat di-blend).
            .chatGridBackground(
                if (isDark) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.06f)
                } else {
                    Color.Black.copy(alpha = 0.04f)
                }
            )
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // TELEGRAM-STYLE (2026-08-12): daftar pesan mengisi SELURUH Box
            // (fillMaxSize) — batas scroll TURUN ke kolom input, bukan lagi di atas
            // chips. Chips saran & FAB jump-to-bottom melayang sebagai OVERLAY di
            // dasar Box, DI ATAS pesan yang lewat di belakangnya (chips & FAB dibuat
            // memudar — CHIP_FILL_ALPHA — supaya pesan tetap samar terbaca).
            //
            // contentPadding bottom DINAMIS: CHIP_ROW_HEIGHT + 8dp saat draf kosong
            // (pesan terakhir berhenti tepat di atas zona chips/FAB → selalu terbaca
            // penuh, dan FAB tak pernah menutupi bubble terakhir), 16dp saat mengetik
            // (chips tersembunyi → pesan terakhir rapat ke input, tanpa gap kosong).
            // ANIMASI (reviewer 2026-08-12): padding di-animasi supaya peralihan
            // 64↔16dp saat mulai/berhenti mengetik tidak "lompat" — sinkron dengan
            // naik/turunnya chips & keyboard (motion language: soft, tidak snap).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val listBottomPadding by animateDpAsState(
                    targetValue = if (draftText.isBlank()) CHIP_ROW_HEIGHT + 8.dp else 16.dp,
                    animationSpec = Motion.base(),
                    label = "listBottomPadding"
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 12.dp, bottom = listBottomPadding
                    )
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
                            // r1.7.1: status titik baca/diterima per bubble milik user
                            // (pengganti centang ✓/✓✓ r1.7.0). PENDING saat belum punya
                            // cloudId; SYNCED + stats → deretan titik; allRead → pelangi.
                            val delivery = when {
                                msg.sender != activeSender -> null
                                msg.cloudId.isNullOrBlank() -> DeliveryStatus.PENDING
                                else -> DeliveryStatus.SYNCED
                            }
                            // Semua anggota lain sudah baca → titik pelangi. Server
                            // menghapus marker begitu itu terjadi (cleanupReceipt),
                            // jadi status pelangi diINGAT sesi ini supaya tidak
                            // "turun" lagi jadi titik abu setelah marker hilang.
                            val rainbowIds = rainbowIdsState
                            val dotsStats = if (delivery != null && msg.cloudId != null) {
                                val stats = receiptStats(receipts[msg.cloudId], otherUids)
                                if (stats.allRead || rainbowIds.contains(msg.cloudId)) {
                                    stats.copy(allRead = true)
                                } else stats
                            } else null
                            // r1.7.1: tandai pesan anggota lain DIBACA begitu bubble
                            // tampil di layar (chat resumed). Sekali per komposisi;
                            // arrayUnion idempoten → aman meski di-recompose.
                            LaunchedEffect(msg.cloudId, isChatResumed) {
                                val cid = msg.cloudId
                                if (isChatResumed && !cid.isNullOrBlank() &&
                                    msg.sender != Constants.Sender.AI &&
                                    msg.senderUid != null && msg.senderUid != myUid
                                ) {
                                    FirestoreSyncManager.markRead(cid)
                                }
                            }
                            // r1.7.1: ingat status pelangi sebelum marker dihapus
                            // server (cleanupReceipt) — sekali pelangi, tetap pelangi.
                            LaunchedEffect(msg.cloudId, dotsStats?.allRead) {
                                val cid = msg.cloudId
                                if (dotsStats?.allRead == true && cid != null) {
                                    if (cid !in rainbowIdsState) {
                                        rainbowIdsState = rainbowIdsState + cid
                                    }
                                }
                            }
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
                                    // Sentuh sekali pada bubble GAMBAR → buka viewer
                                    // full-screen; bubble teks tidak punya aksi tap
                                    // (menu hanya via tahan lama).
                                    onOpenImage = if (msg.imagePath != null) {
                                        { viewerMessage = msg }
                                    } else null,
                                    onReply = { replyTarget = msg },
                                    onOpenFile = { openAttachedFile(context, msg) },
                                    onOpenTransaction = { onOpenTransaction(msg) },
                                    senderAvatarPath = senderAvatarPaths[msg.sender],
                                    isProcessing = processingMessageId == msg.id,
                                    delivery = delivery,
                                    receiptStats = dotsStats,
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
                                    val receipt = receipts[msg.cloudId]
                                    if (receipt != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_message_info)) },
                                            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                            onClick = {
                                                infoMessage = msg
                                                menuOpen = false
                                            }
                                        )
                                    }
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

                }

                // Chips saran cepat — kini OVERLAY di dasar Box (align
                // BottomCenter), MELAYANG DI ATAS daftar pesan (Telegram-style):
                // pesan scroll penuh di belakangnya dan tetap samar terbaca karena
                // chips memudar (CHIP_FILL_ALPHA). BUG-05 (r1.2.0): AnimatedVisibility
                // dari compose-bom 2026.06 meng-komposisi content tapi TIDAK
                // me-layout-nya — dipakai if biasa.
                //
                // ANIMASI GESER (r1.2.0): FAB jump-to-bottom di ujung KIRI baris
                // chips. Saat FAB muncul, chips bergeser ELASTIS ke kanan
                // (startPadding 0 → 64dp) dengan spring — bukan lompat instan —
                // sehingga FAB dan chips saling memberi ruang tanpa menimpa.
                val chipShift by animateDpAsState(
                    targetValue = if (shouldShowJumpButton) 64.dp else 0.dp,
                                    // r1.2.0 (masukan user): geser chips LEBIH LEMBUT —
                    // elastic stiffness FAB_SPRING_STIFFNESS (±600ms sejak
                    // audit motion 2026-08-12; damping dinaikkan 0.88 oleh audit
                    // elastisitas 2026-08-12 → overshoot nyaris tak terlihat)
                    // sinkron dengan slide-in FAB.
                    // Reduced-motion: springOrSnap → settle instan (sistem
                    // "Hapus animasi" aktif).
                    animationSpec = Motion.springOrSnap(
                        Motion.elastic(FAB_SPRING_STIFFNESS)
                    ),
                    label = "chipShiftForFab"
                )
                if (draftText.isBlank() && quickSuggestions.isNotEmpty()) {
                    QuickSuggestionRow(
                        suggestions = quickSuggestions,
                        onSuggestionClicked = { onDraftChange(it) },
                        startPadding = chipShift,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

            // Tombol lompat ke pesan terbaru (muncul saat scroll ke atas) —
            // OVERLAY di pojok KIRI-bawah Box, sejajar baris chips (keduanya
            // melayang DI ATAS daftar pesan, Telegram-style). Saat FAB muncul,
            // chips bergeser elastis ke kanan (startPadding animasi) memberi
            // ruang — jadi tidak saling menimpa. Pesan yang scroll di belakangnya
            // tetap samar terbaca karena fill-nya memudar (CHIP_FILL_ALPHA).
            //
            // Desain (2026-08-11, masukan user): FAB di ujung KIRI baris chips,
            // ukuran 40dp (= tinggi chip), pusat SEJAJAR baris chips (bukan lebih
            // rendah). Posisi kiri karena chips mengalir dari kiri ke kanan — FAB
            // tidak "memaksa" di ujung kanan.
            // Catatan: pakai nama lengkap (bukan import) untuk memaksa overload
            // generik tanpa receiver — di dalam Box, resolver Kotlin justru memilih
            // ColumnScope.AnimatedVisibility dari receiver Column di luar dan gagal
            // ("cannot be called in this context with an implicit receiver").
            androidx.compose.animation.AnimatedVisibility(
                // Sembunyikan juga saat mengetik: baris chips tersembunyi saat draf
                // terisi, sehingga jika FAB tetap tampil ia akan melayang DI ATAS
                // daftar pesan lagi (menutupi bubble). Saat mengetik FAB tak perlu
                // tampil — user sedang menulis, bukan menavigasi riwayat.
                visible = shouldShowJumpButton && draftText.isBlank(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // bottom 8dp: baris chips total 56dp (pad 4 + row 48 + pad 4),
                    // FAB 40dp bottom-aligned → pusat FAB 8dp lebih rendah dari
                    // pusat chips; padding bottom menyamakan pusat keduanya.
                    .padding(start = 12.dp, bottom = 8.dp),
                // MASUK DARI KIRI (2026-08-11, masukan user): FAB slide dari luar
                // tepi kiri layar (initialOffsetX = -width) + fade — bukan muncul
                // dari bawah. Keluar juga ke kiri.
                // r1.2.0 (masukan user #3): kemunculan LEBIH SOFT — elastic
                // stiffness FAB_SPRING_STIFFNESS (±600ms sejak audit motion
                // 2026-08-12; damping 0.88 → tanpa overshoot liar).
                enter = fadeIn(animationSpec = Motion.nav()) +
                    slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = Motion.springOrSnap(
                            Motion.elastic(FAB_SPRING_STIFFNESS)
                        )
                    ),
                exit = fadeOut(animationSpec = Motion.base()) +
                    slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = Motion.base()
                    )
            ) {
                // FAB BERLATAR & MEMUDAR (2026-08-12, permintaan user): gaya
                // DISERAGAMKAN dengan chip rekomendasi — fill surfaceVariant
                // dengan alpha SAMA (CHIP_FILL_ALPHA) karena keduanya kini
                // melayang DI ATAS pesan yang scroll di belakangnya (bukan frame
                // transparan, BUKAN shadow). Ikon panah primary tetap penanda
                // aksi. Ripple ter-clip lingkaran (clip sebelum clickable).
                // Penyempurnaan (2026-08-12): alpha dinaikkan ke 0.92/0.90
                // (transparansi ±8-10%) supaya teks chat di belakang tidak lagi
                // tembus mengganggu; border tipis glass-edge menyamakan karakter
                // dengan chip (fill tinggi + border outline, tanpa shadow).
                val fabFill = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (isDark) CHIP_FILL_ALPHA_DARK else CHIP_FILL_ALPHA_LIGHT
                )
                val fabBorder = MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = CHIP_GLASS_BORDER_ALPHA
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(fabFill, CircleShape)
                        // clickable SEBELUM border (reviewer 2026-08-12): ripple
                        // digambar di atas modifier sebelumnya, jadi border harus
                        // datang SETELAH clickable agar glass-edge tetap terlihat
                        // saat tombol ditekan (tidak tertutup ripple).
                        .clickable { coroutineScope.launch { listState.animateScrollToItem(rows.size - 1) } }
                        .border(1.dp, fabBorder, CircleShape)
                        .testTag("jump_to_bottom"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_jump_bottom_desc),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            } // end Box (daftar chat + FAB overlay)

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

            // Info lampiran: saat menyimpan tampil "Menyimpan lampiran…", setelah
            // selesai berganti info bahwa lampiran tidak ikut sinkron antar perangkat.
            AnimatedVisibility(
                visible = pendingImagePath != null || pendingFilePath != null || isSavingAttachment,
                enter = fadeIn(animationSpec = Motion.fast()),
                exit = fadeOut(animationSpec = Motion.quick())
            ) {
                Text(
                    text = stringResource(
                        if (isSavingAttachment) R.string.chat_attach_saving else R.string.chat_attach_no_sync
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            // r1.7.1 (#2): E2EE aktif tapi kunci lokal belum siap → kirim pesan
            // BARU ditunda (tidak diam-diam turun ke plaintext). Banner kecil
            // menjelaskan; auto-pulih via self-heal (wrap re-install device baru).
            if (e2eeStatus.active && !e2eeStatus.ready) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.chat_e2ee_preparing),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
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
                inputFocusRequester = inputFocusRequester,
                // Quote balasan menempel DI DALAM pill composer (gaya Telegram)
                replyTarget = replyTarget,
                onReplyDismiss = { replyTarget = null }
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

        // Viewer foto full-screen — sentuh sekali pada bubble gambar (bukan menu).
        viewerMessage?.let { msg ->
            msg.imagePath?.let { path ->
                ImageViewerDialog(
                    imagePath = path,
                    onDismiss = { viewerMessage = null }
                )
            }
        }

        // r1.7.1: "Info Pesan" — siapa yang sudah menerima/membaca (ala WhatsApp).
        infoMessage?.let { msg ->
            val r = receipts[msg.cloudId]
            if (r != null) {
                MessageInfoDialog(
                    receipt = r,
                    memberLabels = memberLabels,
                    onDismiss = { infoMessage = null }
                )
            }
        }
    }
}

// ---- Tekstur background chat ----

/**
 * Grid kotak-kotak halus untuk area chat (2026-08-11) — tekstur subtle,
 * bukan wallpaper. Garis 0.5dp dengan alpha sangat rendah sehingga background
 * hampir terlihat polos; grid digambar lewat drawBehind (sekali per ukuran
 * layar, efisien — tidak ada bitmap pattern).
 *
 * [gridColor] diteruskan dari caller (yang punya akses MaterialTheme):
 *  - light: Black α0.04 — garis gelap tipis di atas latar terang.
 *  - dark : surfaceVariant α0.06 — hue MENYATU dengan palet gelap kehijauan
 *    (audit 2026-08-11). Sebelumnya White murni α0.03 yang saat di-blend ke
 *    background #101414 menghasilkan #171B1B — nyaris seterang surface
 *    (selisih 5-6 level) sehingga grid terasa "berbeda dari warna gelap lain".
 */
private fun Modifier.chatGridBackground(gridColor: Color): Modifier = this.drawBehind {
    val gridLine = gridColor
    val cellSize = 32.dp.toPx()
    val stroke = 0.5.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        x += cellSize
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        y += cellSize
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

// ---- Dialog "Info Pesan" (r1.7.1) ----

/**
 * Dialog ala WhatsApp: daftar anggota yang sudah menerima / membaca pesan
 * terenkripsi (marker `receipts/{cloudId}`). Nama diambil dari label/alias
 * member; anggota yang sudah meninggalkan keluarga di-lewati (mapNotNull).
 */
@Composable
private fun MessageInfoDialog(
    receipt: ReceiptInfo,
    memberLabels: Map<String, String>,
    onDismiss: () -> Unit
) {
    val delivered = receipt.deliveredBy.mapNotNull { memberLabels[it] }
    val read = receipt.readBy.mapNotNull { memberLabels[it] }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_message_info)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.chat_info_delivered),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (delivered.isEmpty()) {
                        stringResource(R.string.chat_info_nobody)
                    } else {
                        delivered.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.chat_info_read),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (read.isEmpty()) {
                        stringResource(R.string.chat_info_nobody)
                    } else {
                        read.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}
