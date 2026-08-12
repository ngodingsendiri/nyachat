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
 */
class ChatMessageFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val sender = data["sender"]?.takeIf { it.isNotBlank() } ?: return
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: getString(R.string.chat_notif_default_body)
        val cloudId = data["cloudId"] ?: ""

        // Toggle notifikasi (Settings) — default ON.
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.Prefs.CHAT_NOTIFICATIONS_ENABLED, true)) return

        // Pesan dari workspace perangkat ini (diri sendiri) → jangan notif.
        val localName = prefs.getString(Constants.Prefs.USER_NAME, null)
        if (!localName.isNullOrBlank() && sender == localName) return

        createChannel()

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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(sender)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this)
                .notify(cloudId.hashCode().takeIf { it != 0 } ?: body.hashCode(), notification)
        } catch (e: SecurityException) {
            // Izin POST_NOTIFICATIONS belum diberikan — abaikan diam-diam.
        }
    }

    override fun onNewToken(token: String) {
        syncFcmToken(applicationContext, token)
    }

    private fun createChannel() {
        // NotificationChannel baru ada sejak API 26 (minSdk 24 → guard).
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.chat_notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.chat_notif_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ChatNotifier"
        const val CHANNEL_ID = "chat_messages"
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
