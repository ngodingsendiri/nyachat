package com.startupmini.nyachat.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.MainActivity
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FASE 3 item 3.7 — notifikasi chat real-time (\"seperti WhatsApp\"):
 * pesan baru dari anggota workspace lain dikirim ke perangkat ini via FCM
 * (data message dari Cloud Function `onMessageWrite`), lalu ditampilkan di
 * sini sebagai notifikasi. Kebijakan tampil (toggle user + skip pesan sendiri)
 * dijalankan DI APP, bukan di cloud — cloud hanya menyalurkan payload.
 *
 * r1.6.0: selain chat, service ini menampilkan notifikasi KEANGGOTAAN dari
 * cloud function `handleJoinRequest`:
 *   - type=join_request → pemilik mendapat "X ingin bergabung".
 *   - type=join_decision → pemohon mendapat "disetujui/ditolak".
 * Keduanya di-gate toggle notifikasi chat yang sama (keputusan produk beta).
 */
class ChatMessageFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Toggle notifikasi (Settings) — default ON. Berlaku untuk chat &
        // keanggotaan (r1.6.0).
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.Prefs.CHAT_NOTIFICATIONS_ENABLED, true)) return

        createChannels()

        when (data[KEY_TYPE]) {
            KEY_TYPE_JOIN_REQUEST -> {
                val name = data[KEY_REQUESTER_NAME]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.sender_anggota)
                val familyName = data[KEY_FAMILY_NAME]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.topbar_title)
                postNotification(
                    channelId = CHANNEL_ID_MEMBERSHIP,
                    // ID per-pemohon: dua permintaan dari orang berbeda tidak
                    // saling menimpa (audit r1.6.0).
                    notificationId = membershipNotificationId(data[KEY_REQUESTER_UID]),
                    title = getString(R.string.notif_join_request_title),
                    text = getString(R.string.notif_join_request_body, name, familyName)
                )
            }
            KEY_TYPE_JOIN_DECISION -> {
                val approved = data[KEY_APPROVED] == "1"
                val familyName = data[KEY_FAMILY_NAME]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.topbar_title)
                val text = if (approved) {
                    getString(R.string.notif_join_approved_body, familyName)
                } else {
                    getString(R.string.notif_join_rejected_body, familyName)
                }
                postNotification(
                    channelId = CHANNEL_ID_MEMBERSHIP,
                    notificationId = membershipNotificationId(data[KEY_REQUESTER_UID]),
                    title = getString(R.string.notif_join_decision_title),
                    text = text
                )
            }
            else -> {
                val sender = data["sender"]?.takeIf { it.isNotBlank() } ?: return
                val body = data["body"]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.chat_notif_default_body)
                val cloudId = data["cloudId"] ?: ""

                // Pesan dari workspace perangkat ini (diri sendiri) → jangan notif.
                val localName = prefs.getString(Constants.Prefs.USER_NAME, null)
                if (!localName.isNullOrBlank() && sender == localName) return

                postNotification(
                    channelId = CHANNEL_ID,
                    notificationId = cloudId.hashCode().takeIf { it != 0 } ?: body.hashCode(),
                    title = sender,
                    text = body
                )
            }
        }
    }

    /** Tampilkan notifikasi (chat atau keanggotaan) dengan payload generik. */
    private fun postNotification(
        channelId: String,
        title: String,
        text: String,
        notificationId: Int = title.hashCode() xor text.hashCode()
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Audit ikon 2026-08-12: small icon harus MONOKROM (ic_stat_logo).
        // ic_logo (berwarna penuh) dirender sebagai kotak putih solid di
        // Android 5+ karena small icon dipakai sebagai alpha mask.
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Izin POST_NOTIFICATIONS belum diberikan — abaikan diam-diam.
        }
    }

    /**
     * ID notifikasi keanggotaan — per pemohon (requesterUid) supaya dua
     * permintaan/keputusan dari orang berbeda tidak saling menimpa (audit
     * r1.6.0). Fallback hash title jika uid tidak ada di payload.
     */
    private fun membershipNotificationId(requesterUid: String?): Int {
        val uid = requesterUid?.takeIf { it.isNotBlank() }
        return uid?.hashCode()?.takeIf { it != 0 }
            ?: (getString(R.string.notif_join_request_title).hashCode() and 0x7fffffff)
    }

    override fun onNewToken(token: String) {
        syncFcmToken(applicationContext, token)
    }

    private fun createChannels() {
        // NotificationChannel baru ada sejak API 26 (minSdk 24 → guard).
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        createChannel(
            manager,
            CHANNEL_ID,
            getString(R.string.chat_notif_channel_name),
            getString(R.string.chat_notif_channel_desc)
        )
        // r1.6.0: kanal keanggotaan — toggle-nya sama dengan chat (keputusan beta).
        createChannel(
            manager,
            CHANNEL_ID_MEMBERSHIP,
            getString(R.string.notif_membership_channel_name),
            getString(R.string.notif_membership_channel_desc)
        )
    }

    private fun createChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        description: String
    ) {
        // NotificationChannel baru ada sejak API 26 (minSdk 24 → guard).
        if (android.os.Build.VERSION.SDK_INT < 26) return
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            .apply { this.description = description }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ChatNotifier"
        const val CHANNEL_ID = "chat_messages"
        // r1.6.0: kanal notifikasi keanggotaan (permintaan bergabung & keputusan).
        const val CHANNEL_ID_MEMBERSHIP = "workspace_activity"

        // Payload keys cloud function handleJoinRequest (r1.6.0) — sinkron dengan
        // functions/index.js.
        private const val KEY_TYPE = "type"
        private const val KEY_TYPE_JOIN_REQUEST = "join_request"
        private const val KEY_TYPE_JOIN_DECISION = "join_decision"
        private const val KEY_REQUESTER_NAME = "requesterName"
        private const val KEY_REQUESTER_UID = "requesterUid"
        private const val KEY_FAMILY_NAME = "familyName"
        private const val KEY_APPROVED = "approved"

        /** Scope satu-shot sinkronisasi token (dibatalkan bersama proses app). */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Simpan token FCM perangkat ini ke dokumen member sendiri di Firestore
         * (families/{PIN}/members/{uid}/fcmToken) — dipakai Cloud Function untuk
         * mengirim notifikasi. Di-skip bila belum login / belum ada workspace.
         */
        fun syncFcmToken(context: Context, token: String) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
                Log.w(TAG, "syncFcmToken: tidak ada currentUser")
                return
            }
            // PIN (secret) dibaca async di IO — Keystore jangan diblokir di main.
            ioScope.launch {
                val pin = SecureStorage.getSecretAsync(context, Constants.Prefs.WORKSPACE_PIN)
                if (pin.isNullOrBlank()) {
                    Log.w(TAG, "syncFcmToken: PIN kosong, uid=$uid")
                    return@launch
                }
                FirebaseFirestore.getInstance()
                    .collection(Constants.Collections.FAMILIES).document(pin)
                    .collection(Constants.Collections.MEMBERS).document(uid)
                    .update(Constants.Fields.FCM_TOKEN, token)
                    .addOnSuccessListener {
                        Log.i(TAG, "Token FCM tersimpan: pin=${pin.take(4)}… uid=${uid.take(8)}…")
                    }
                    .addOnFailureListener { e ->
                        // Wajar bila rules baru belum di-deploy / belum member — token
                        // akan disinkronkan lagi saat onNewToken berikutnya.
                        Log.w(TAG, "Simpan token FCM gagal (uid=${uid.take(8)}…): ${e.message}")
                    }
            }
        }

        /** Pastikan token perangkat aktif tersinkron (dipanggil saat workspace aktif). */
        fun ensureTokenSynced(context: Context) {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        if (token.isNullOrBlank()) {
                            Log.w(TAG, "ensureTokenSynced: token kosong")
                            return@addOnCompleteListener
                        }
                        Log.i(TAG, "Token FCM didapat (${token.length} chars), sync ke Firestore...")
                        syncFcmToken(context, token)
                    } else {
                        Log.w(TAG, "Gagal dapat token FCM: ${task.exception?.message ?: "unknown"}")
                    }
                }
        }
    }
}
