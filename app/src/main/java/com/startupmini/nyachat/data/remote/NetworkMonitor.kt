package com.startupmini.nyachat.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * BUG-06 lanjutan (P0) — pemantau jaringan berbasis ConnectivityManager.
 *
 * Sebelumnya app TIDAK punya deteksi jaringan sama sekali: snapshot listener
 * Firestore yang berhasil dipenuhi dari offline cache selalu memanggil
 * `markSynced()` → indikator tetap "Tersinkron" walau jaringan benar-benar mati
 * (airplane mode, network unreachable). Ini menyesatkan user & menyembunyikan
 * kenyataan bahwa data tidak tersinkron.
 *
 * [NetworkMonitor] hanyalah PEMBAWA SINYAL: mendaftarkan default network
 * callback dan meneruskan status online/offline ke [FirestoreSyncManager]
 * (via `setNetworkAvailable`). Keputusan status indikator ada di
 * FirestoreSyncManager (fungsi murni `resolveStatusOnNetworkChange` /
 * `resolveStatusOnSyncSuccess` — mudah di-unit-test). Masa pakai diatur pemanggil
 * (start/stop mengikuti lifecycle activity di SyncLifecycleGlue).
 *
 * Catatan: `registerDefaultNetworkCallback` butuh API 24+ (minSdk 24 ✓) dan
 * izin ACCESS_NETWORK_STATE (ditambahkan di AndroidManifest).
 */
class NetworkMonitor(
    private val context: Context,
    private val onStatusChange: (Boolean) -> Unit
) {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onStatusChange(true)
        }

        override fun onLost(network: Network) {
            onStatusChange(false)
        }

        override fun onUnavailable() {
            onStatusChange(false)
        }
    }

    fun start() {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Daftar NetworkCallback gagal: ${it.message}") }
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
            .onFailure { /* sudah dilepas — abaikan */ }
    }

    /** Cek satu kali status jaringan aktif — fallback bila callback belum menembak. */
    val isOnlineNow: Boolean
        get() = runCatching {
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
