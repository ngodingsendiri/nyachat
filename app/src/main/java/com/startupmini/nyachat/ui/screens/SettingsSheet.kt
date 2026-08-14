package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet Pengaturan — di-ekstrak dari MainActivity (P2-13) supaya
 * MainActivity tidak terus membengkak dan tiap aksi bisa diuji berdiri sendiri.
 * Semua aksi (ubah tema, cek update, kelola API key, backup/restore, logout)
 * didelegasikan lewat callback; komponen ini murni tampilan + pemicu aksi.
 *
 * Tata letak (item 7-9): kartu identitas workspace di atas, lalu seksi
 * UMUM / AI & API / DATA & BACKUP / ZONA BERBAHAYA dengan baris aksi khusus
 * (bukan DropdownMenuItem) + status backup terakhir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    isDarkMode: Boolean,
    userName: String?,
    workspaceRole: String?,
    workspacePin: String?,
    backupBusy: Boolean,
    isBackupEncrypted: Boolean,
    lastBackupMillis: Long,
    // Status enkripsi FILE backup terakhir yang berhasil dibuat (bukan setting
    // toggle [isBackupEncrypted]) — label "Backup terakhir" harus mencerminkan
    // isi file di Drive.
    lastBackupEncrypted: Boolean,
    onDismiss: () -> Unit,
    onToggleDarkMode: () -> Unit,
    // 3.7: toggle notifikasi chat real-time.
    chatNotificationsEnabled: Boolean = true,
    onToggleChatNotifications: () -> Unit = {},
    onToggleBackupEncryption: () -> Unit,
    onCheckUpdate: () -> Unit,
    onGeminiKey: () -> Unit,
    onOpenRouterKey: () -> Unit,
    onPin: () -> Unit,
    onExportCsv: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onClearData: () -> Unit,
    onLogout: () -> Unit,
    // Audit menu Pengaturan (2026-08-12): kebijakan privasi dibuka dari seksi
    // Tentang (wajib untuk rilis Play Store).
    onPrivacyPolicy: () -> Unit = {},
    // Audit #2 + r1.2.1: foto profil (Google/custom/inisial). Tap kartu profil
    // membuka halaman Profil & Akun (null = kartu tidak bisa di-tap).
    avatarPath: String? = null,
    onOpenProfile: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Audit motion (2026-08-12): tutup sheet WAJIB lewat sheetState.hide()
    // dulu (jendela turun ke bawah), baru onDismiss dipanggil — sebelum ini
    // onDismissRequest langsung memanggil onDismiss sehingga sheet hilang
    // INSTAN tanpa animasi turun, tidak konsisten dengan sheet lain.
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Navigasi keluar (audit 2026-08-13, permintaan user): aksi yang menutup
    // sheet untuk membuka jendela lain (Profil, PIN, API key, backup, dll.)
    // TIDAK lagi mematikan sheet instan — sheet SETTINGS turun dengan animasi
    // dulu (sheetState.hide()), BARU aksi dijalankan. Sebelumnya pemanggil
    // (MainAppDialogs) langsung set showSettingsSheet=false sehingga sheet
    // hilang paksa tanpa motion, terasa "di-close paksa" (mis. klik kartu
    // profil → Profil & Akun naik dari bawah tapi Settings hilang instan).
    // Toggle (mode, notifikasi, enkripsi) TIDAK ikut dibungkus — sheet tetap
    // terbuka saat toggle ditekan.
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }

    val checkUpdateAction = { dismissThen { onCheckUpdate() } }
    val geminiKeyAction = { dismissThen { onGeminiKey() } }
    val openRouterKeyAction = { dismissThen { onOpenRouterKey() } }
    val pinAction = { dismissThen { onPin() } }
    val exportCsvAction = { dismissThen { onExportCsv() } }
    val backupAction = { dismissThen { onBackup() } }
    val restoreAction = { dismissThen { onRestore() } }
    val clearDataAction = { dismissThen { onClearData() } }
    val logoutAction = { dismissThen { onLogout() } }
    val privacyPolicyAction = { dismissThen { onPrivacyPolicy() } }
    // onOpenProfile nullable (kartu profil hanya bisa di-tap bila tidak null).
    val openProfileAction = onOpenProfile?.let { profile -> { dismissThen { profile() } } }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        // Sheet berada di area konten (di atas NavigationBar) — padding navbar
        // bawaan sheet dinolkan agar tidak muncul celah.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Audit menu Pengaturan (2026-08-12): tinggi dibatasi supaya sheet
                // tidak penuh layar (sebelumnya konten ~2310px di layar 2400px —
                // konsisten dengan ManageMembers yang heightIn max 560dp).
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.action_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Kartu identitas workspace (item 8): siapa yang login di workspace ini.
            IdentityCard(
                userName = userName,
                workspaceRole = workspaceRole,
                workspacePin = workspacePin,
                avatarPath = avatarPath,
                onOpenProfile = openProfileAction
            )

            // ── TAMPILAN ──
            SectionLabel(stringResource(R.string.settings_section_appearance))
            SettingRow(
                icon = if (isDarkMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                title = stringResource(if (isDarkMode) R.string.menu_mode_light else R.string.menu_mode_dark),
                onClick = onToggleDarkMode
            )
            // 3.7: notifikasi chat real-time (FCM) — off hanya menyembunyikan
            // tampilan di perangkat ini; cloud tetap mengirim (di-filter di app).
            SettingRow(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.menu_chat_notifications),
                subtitle = stringResource(R.string.menu_chat_notifications_desc),
                onClick = onToggleChatNotifications,
                trailing = {
                    Switch(checked = chatNotificationsEnabled, onCheckedChange = { onToggleChatNotifications() })
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // ── KEAMANAN ──
            SectionLabel(stringResource(R.string.settings_section_security))
            SettingRow(
                icon = Icons.Rounded.Pin,
                title = stringResource(R.string.menu_pin),
                subtitle = workspacePin?.let { maskPin(it) },
                onClick = pinAction,
                chevron = true
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // ── AI & API ──
            SectionLabel(stringResource(R.string.settings_section_ai))
            SettingRow(
                icon = Icons.Rounded.Key,
                title = stringResource(R.string.menu_gemini_key),
                onClick = geminiKeyAction,
                chevron = true
            )
            SettingRow(
                icon = Icons.Rounded.Route,
                title = stringResource(R.string.menu_openrouter_key),
                onClick = openRouterKeyAction,
                chevron = true
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // ── DATA & BACKUP ──
            SectionLabel(stringResource(R.string.settings_section_data))
            SettingRow(
                icon = Icons.Rounded.CloudUpload,
                title = stringResource(R.string.menu_backup_drive),
                // Status backup terakhir digabung jadi subtitle (item 9) —
                // pakai [lastBackupEncrypted] (status file AKTUAL), bukan toggle.
                subtitle = stringResource(
                    R.string.settings_backup_last_subtitle,
                    lastBackupSubtitle(lastBackupMillis, lastBackupEncrypted)
                ),
                enabled = !backupBusy,
                onClick = backupAction
            )
            SettingRow(
                icon = Icons.Rounded.CloudDownload,
                title = stringResource(R.string.menu_restore_drive),
                enabled = !backupBusy,
                onClick = restoreAction
            )
            // Enkripsi backup (Sprint-2): passphrase diminta saat backup/restore
            // MANUAL, tidak pernah disimpan. M5: auto-backup harian TETAP
            // berjalan saat enkripsi aktif — memakai passphrase otomatis Keystore
            // (BACKUP_AUTO_PASSPHRASE) jadi tidak ada prompt tengah malam.
            SettingRow(
                icon = Icons.Rounded.EnhancedEncryption,
                title = stringResource(R.string.settings_backup_encrypt),
                subtitle = stringResource(R.string.settings_backup_encrypt_desc),
                onClick = onToggleBackupEncryption,
                trailing = {
                    Switch(checked = isBackupEncrypted, onCheckedChange = { onToggleBackupEncryption() })
                }
            )
            SettingRow(
                icon = Icons.Rounded.TableChart,
                title = stringResource(R.string.menu_export_csv),
                onClick = exportCsvAction
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // ── TENTANG ──
            SectionLabel(stringResource(R.string.settings_section_about))
            SettingRow(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.menu_check_update),
                onClick = checkUpdateAction,
                chevron = true
            )
            SettingRow(
                icon = Icons.Rounded.PrivacyTip,
                title = stringResource(R.string.menu_privacy_policy),
                onClick = privacyPolicyAction,
                chevron = true
            )
            // Versi — informatif, bukan aksi (di sini, bukan header, supaya semua
            // info "tentang aplikasi" satu blok).
            SettingRow(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.menu_version, BuildConfig.VERSION_NAME),
                onClick = null
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // ── ZONA BERBAHAYA ──
            DangerSectionLabel()
            SettingRow(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.menu_clear_data),
                tint = MaterialTheme.colorScheme.error,
                onClick = clearDataAction
            )
            SettingRow(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                title = stringResource(R.string.menu_logout, userName ?: "User"),
                tint = MaterialTheme.colorScheme.error,
                onClick = logoutAction
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/** Label "Backup terakhir: …" + status enkripsi untuk baris status (item 9).
 *  [isLastBackupEncrypted] = status enkripsi FILE backup aktual (bukan setting
 *  toggle) — dipisah dari toggle supaya label tidak menyesatkan. */
@Composable
private fun lastBackupSubtitle(lastBackupMillis: Long, isLastBackupEncrypted: Boolean): String {
    val whenLabel = if (lastBackupMillis > 0) {
        val date = Date(lastBackupMillis)
        val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("id-ID"))
        val timeFmt = SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID"))
        stringResource(R.string.settings_last_backup_time, dateFmt.format(date), timeFmt.format(date))
    } else {
        stringResource(R.string.settings_last_backup_never)
    }
    val encLabel = stringResource(
        if (isLastBackupEncrypted) R.string.settings_backup_encrypted_yes
        else R.string.settings_backup_encrypted_no
    )
    return "$whenLabel · $encLabel"
}

/** PIN workspace disamarkan — hanya 4 digit terakhir yang terlihat. */
private fun maskPin(pin: String): String =
    "•".repeat((pin.length - 4).coerceAtLeast(0)) + pin.takeLast(4)

/**
 * Kartu identitas workspace (item 8): avatar (foto atau inisial) + nama + peran
 * + PIN tersamar. Memberi konteks "siapa & di workspace mana" sebelum daftar aksi.
 * r1.2.1: SELURUH kartu bisa di-tap membuka halaman Profil & Akun bila
 * [onOpenProfile] tidak null (chevron ditampilkan sebagai penanda navigasi).
 */
@Composable
private fun IdentityCard(
    userName: String?,
    workspaceRole: String?,
    workspacePin: String?,
    avatarPath: String? = null,
    onOpenProfile: (() -> Unit)? = null
) {
    val displayName = userName ?: stringResource(R.string.pin_default_name)
    val roleLabel = stringResource(
        if (workspaceRole == Constants.Roles.OWNER) R.string.pin_role_owner
        else R.string.pin_role_member
    )
    val profileDesc = stringResource(R.string.profile_title)
    Surface(
        shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(
                if (onOpenProfile != null) {
                    Modifier
                        .clip(RoundedCornerShape(Constants.Ui.CORNER_L.dp))
                        .clickable(onClick = onOpenProfile)
                        .semantics { contentDescription = profileDesc }
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.startupmini.nyachat.ui.util.AvatarImage(
                name = displayName,
                size = 44,
                photoPath = avatarPath,
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                textStyle = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            workspacePin?.let { pin ->
                Surface(
                    shape = RoundedCornerShape(Constants.Ui.CORNER_S.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = maskPin(pin),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            if (onOpenProfile != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Baris pengaturan khusus (item 7): ikon + judul (+ subjudul opsional) +
 * elemen trailing opsional (mis. Switch). Tinggi min 48dp (target sentuh).
 * [onClick] null → baris informatif (tidak bisa di-tap).
 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    // Audit menu Pengaturan (2026-08-12): affordance navigasi — baris yang
    // membuka sub-dialog/halaman menampilkan chevron (sebelumnya hanya kartu
    // profil yang punya, sehingga baris lain tidak terlihat bisa di-tap).
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        tint != MaterialTheme.colorScheme.onSurfaceVariant -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        } else if (chevron) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun DangerSectionLabel() {
    Text(
        text = stringResource(R.string.settings_section_danger),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
    )
}
