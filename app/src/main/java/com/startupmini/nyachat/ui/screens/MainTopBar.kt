package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.ChatMessage

/**
 * TopAppBar utama: avatar bertumpuk anggota + judul + aksi (kelola anggota &
 * pengaturan). Ekstraksi dari MainActivity (TASK-1.3) — tanpa perubahan behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    messages: List<ChatMessage>,
    userName: String?,
    onManageMembers: () -> Unit,
    onSettings: () -> Unit
) {
    // M8: indeks pengirim unik untuk avatar — dibangun ulang hanya saat daftar
    // pesan berubah (dulu di MainActivity, dipindah bersama TopAppBar).
    val uniqueSenders by remember(messages) {
        derivedStateOf {
            messages
                .map { it.sender }
                .filter { it != Constants.Sender.AI }
                .distinct()
                .take(4)
        }
    }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 0.dp)
            ) {
                StackedAvatars(
                    senders = uniqueSenders.ifEmpty {
                        userName?.let { listOf(it) } ?: emptyList()
                    },
                    modifier = Modifier.padding(end = 10.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.topbar_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (uniqueSenders.size > 1) {
                        Text(
                            text = stringResource(R.string.topbar_member_count, uniqueSenders.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (uniqueSenders.size == 1) {
                        Text(
                            text = uniqueSenders.first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onManageMembers) {
                Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = stringResource(R.string.manage_members_title),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.action_settings),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Avatar bertumpuk (stacked) bergaya Twitter/X group chat.
 * Setiap avatar adalah lingkaran berwarna unik dari hash nama pengirim,
 * dengan inisial huruf di tengah. Avatar berikutnya sedikit overlap ke kiri.
 */
@Composable
private fun StackedAvatars(
    senders: List<String>,
    modifier: Modifier = Modifier,
    avatarSize: Int = 32,
    overlapDp: Int = 10
) {
    if (senders.isEmpty()) return
    val show = senders.take(3)
    val totalWidth = (avatarSize + (show.size - 1) * (avatarSize - overlapDp)).dp

    Box(
        modifier = modifier
            .width(totalWidth)
            .height(avatarSize.dp)
    ) {
        show.forEachIndexed { index, name ->
            val avatarColor = avatarColorFor(name)
            val initials = name.take(2).uppercase()
            // Teks adaptif (audit WCAG): inisial gelap saat bg avatar terang (orange/sky/
            // hijau — putih hanya ~2.3-3:1), putih saat bg gelap (indigo/ungu/crimson).
            val fgColor = if (avatarColor.luminance() > 0.22f) Color(0xFF202124) else Color.White
            Box(
                modifier = Modifier
                    .size(avatarSize.dp)
                    .offset(x = (index * (avatarSize - overlapDp)).dp)
                    .zIndex((show.size - index).toFloat())
                    .clip(CircleShape)
                    .drawBehind { drawCircle(color = avatarColor) },
                contentAlignment = Alignment.Center
            ) {
                // White border ring
                Box(
                    modifier = Modifier
                        .size(avatarSize.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                )
                Text(
                    text = initials,
                    color = fgColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = (avatarSize / 2.8).sp,
                    maxLines = 1
                )
            }
        }
    }
}

/** Warna avatar deterministik berdasarkan hash nama — konsisten antar sesi.
 *  Gunakan & 0x7FFFFFFF, bukan Math.abs: abs(Int.MIN_VALUE) tetap negatif dan
 *  bisa menghasilkan indeks negatif → ArrayIndexOutOfBoundsException. */
private fun avatarColorFor(name: String): Color {
    val palette = listOf(
        Color(0xFF6C3DE8), // indigo
        Color(0xFF00A878), // teal
        Color(0xFFE84393), // pink
        Color(0xFFFF8C42), // orange
        Color(0xFF3D9BE9), // sky blue
        Color(0xFFB23A48), // crimson
        Color(0xFF4CAF50), // green
        Color(0xFF9C27B0), // purple
    )
    return palette[(name.hashCode() and 0x7FFFFFFF) % palette.size]
}
