package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.FamilyMember
import com.startupmini.nyachat.data.remote.MembershipManager
import com.startupmini.nyachat.data.remote.JoinRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layar "Kelola Anggota" — full-screen dialog.
 * - Pemilik: menyetujui/menolak permintaan bergabung, mengubah label & peran
 *   (pemilik/anggota), menghapus anggota.
 * - Anggota: hanya melihat daftar anggota (baca-saja).
 */
@Composable
fun ManageMembersScreen(
    pin: String,
    isOwner: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val members by MembershipManager.members.collectAsState()
    val joinRequests by MembershipManager.joinRequests.collectAsState()
    // r1.2.3 (P1): foto avatar anggota (cache disk, map uid → path) —
    // ditampilkan di kartu anggota bila tersedia.
    val memberAvatars by MembershipManager.memberAvatarPaths.collectAsState()
    val myUid = remember { MembershipManager.currentUid() }

    var labelTarget by remember { mutableStateOf<FamilyMember?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.manage_members_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isOwner) {
                        item { SectionTitle(stringResource(R.string.manage_members_join_requests)) }
                        if (joinRequests.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.manage_members_no_requests),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(joinRequests, key = { it.uid }) { request ->
                                JoinRequestCard(
                                    request = request,
                                    onApprove = {
                                        scope.launch {
                                            MembershipManager.approveJoin(pin, request)
                                        }
                                    },
                                    onReject = {
                                        scope.launch { MembershipManager.rejectJoin(pin, request.uid) }
                                    }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(4.dp)) }
                        item { SectionTitle(stringResource(R.string.manage_members_list)) }
                    }

                    if (members.isEmpty() && !isOwner) {
                        item {
                            Text(
                                text = stringResource(R.string.manage_members_empty),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(members, key = { it.uid }) { member ->
                            MemberCard(
                                member = member,
                                isSelf = member.uid == myUid,
                                isOwner = isOwner,
                                avatarPath = memberAvatars[member.uid],
                                onEditLabel = { labelTarget = member },
                                onToggleRole = {
                                    scope.launch {
                                        MembershipManager.setMemberRole(
                                            pin, member.uid,
                                            if (member.isOwner) MembershipManager.ROLE_MEMBER
                                            else MembershipManager.ROLE_OWNER
                                        )
                                    }
                                },
                                onRemove = {
                                    scope.launch { MembershipManager.removeMember(pin, member.uid) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    labelTarget?.let { target ->
        LabelEditDialog(
            member = target,
            onDismiss = { labelTarget = null },
            onSave = { newLabel ->
                scope.launch {
                    MembershipManager.setMemberRole(pin, target.uid, target.role, newLabel)
                }
                labelTarget = null
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun JoinRequestCard(
    request: JoinRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(name = request.name.ifBlank { "?" }, request.uid)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.name.ifBlank { request.email },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (request.name.isNotBlank() && request.email.isNotBlank()) {
                    Text(
                        text = request.email,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onApprove) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.manage_members_approve), fontSize = 12.sp)
            }
            TextButton(onClick = onReject) {
                Text(
                    stringResource(R.string.manage_members_reject),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: FamilyMember,
    isSelf: Boolean,
    isOwner: Boolean,
    onEditLabel: () -> Unit,
    onToggleRole: () -> Unit,
    onRemove: () -> Unit,
    // r1.2.3 (P1): path foto avatar anggota (null → inisial berwarna).
    avatarPath: String? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (avatarPath != null) {
                com.startupmini.nyachat.ui.util.AvatarImage(
                    name = member.label.ifBlank { member.name.ifBlank { "?" } },
                    size = 40,
                    photoPath = avatarPath,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else {
                AvatarCircle(name = member.label.ifBlank { member.name.ifBlank { "?" } }, member.uid)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.label.ifBlank { member.name.ifBlank { stringResource(R.string.sender_anggota) } },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isSelf) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.manage_members_you),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (member.isOwner) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.manage_members_role_owner),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.manage_members_role_member),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (member.name.isNotBlank()) {
                        Text(
                            text = " • ${member.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isOwner && !isSelf) {
                IconButton(onClick = onEditLabel) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.manage_members_edit_label),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box(modifier = Modifier) {
                    IconButton(onClick = { menuOpen = true }) {
                        Text(
                            text = if (member.isOwner) {
                                stringResource(R.string.manage_members_make_member)
                            } else {
                                stringResource(R.string.manage_members_make_owner)
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.isOwner) {
                                        stringResource(R.string.manage_members_make_member)
                                    } else {
                                        stringResource(R.string.manage_members_make_owner)
                                    },
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleRole()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.manage_members_remove),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarCircle(name: String, seed: String) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val color = androidx.compose.ui.graphics.Color.hsl(
        hue = (seed.hashCode() % 360).toFloat().let { if (it < 0) it + 360 else it },
        saturation = 0.5f,
        lightness = 0.5f
    )
    // Teks adaptif (audit WCAG): gelap saat bg terang, putih saat bg gelap —
    // inisial tetap terbaca di avatar hue kuning/hijau/cyan (putih hanya 1.96:1).
    // Dekoratif: nama anggota sudah tercetak di sebelah avatar, jadi inisial
    // tidak perlu dibacakan TalkBack (P3-2).
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = if (color.luminance() > 0.22f) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun LabelEditDialog(
    member: FamilyMember,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(member.label) }

    // F3 (audit focus order): fokus langsung ke kolom label saat dialog dibuka.
    val labelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        labelFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_members_label_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.manage_members_label_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(labelFocusRequester)
            )
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.manage_members_label_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
