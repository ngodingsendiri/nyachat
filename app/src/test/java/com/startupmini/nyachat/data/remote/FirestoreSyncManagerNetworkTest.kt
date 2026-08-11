package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BUG-06 lanjutan (P0) — resolusi status indikator sync berbasis jaringan.
 *
 * Sebelumnya app tidak punya deteksi jaringan: snapshot Firestore dari offline
 * cache selalu sukses → markSynced() → indikator "Tersinkron" walau offline
 * murni. Dengan NetworkMonitor, status jaringan diteruskan ke sini (fungsi
 * murni, tanpa dependency Android/Firebase) — mudah diuji di JVM.
 */
class FirestoreSyncManagerNetworkTest {

    // ---- resolveStatusOnNetworkChange ----

    @Test
    fun `jaringan hilang selalu jadi OFFLINE apapun status sebelumnya`() {
        assertEquals(SyncStatus.OFFLINE, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.SYNCED, false))
        assertEquals(SyncStatus.OFFLINE, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.SYNCING, false))
        assertEquals(SyncStatus.OFFLINE, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.OFFLINE, false))
        assertEquals(SyncStatus.OFFLINE, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.ERROR, false))
    }

    @Test
    fun `jaringan pulih dari OFFLINE jadi SYNCING sampai snapshot konfirmasi`() {
        assertEquals(SyncStatus.SYNCING, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.OFFLINE, true))
    }

    @Test
    fun `jaringan aktif tidak menimpa status non-OFFLINE`() {
        assertEquals(SyncStatus.SYNCED, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.SYNCED, true))
        assertEquals(SyncStatus.SYNCING, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.SYNCING, true))
        // ERROR nyata tidak boleh dikira pulih karena jaringan aktif.
        assertEquals(SyncStatus.ERROR, FirestoreSyncManager.resolveStatusOnNetworkChange(SyncStatus.ERROR, true))
    }

    // ---- resolveStatusOnSyncSuccess (markSynced / drain) ----

    @Test
    fun `snapshot sukses dengan jaringan jelas mati tetap OFFLINE`() {
        assertEquals(SyncStatus.OFFLINE, FirestoreSyncManager.resolveStatusOnSyncSuccess(false))
    }

    @Test
    fun `snapshot sukses dengan jaringan aktif atau belum diketahui jadi SYNCED`() {
        assertEquals(SyncStatus.SYNCED, FirestoreSyncManager.resolveStatusOnSyncSuccess(true))
        // null = monitor belum menembak → perilaku lama (SYNCED).
        assertEquals(SyncStatus.SYNCED, FirestoreSyncManager.resolveStatusOnSyncSuccess(null))
    }

    // ---- resolveStatusOnDraining (drain antrian pending) ----

    @Test
    fun `drain aktif dengan jaringan mati jadi OFFLINE bukan SYNCING`() {
        // Offline + pending ops: retry tidak mungkin sukses — indikator harus
        // "Mode offline", bukan "Menyinkronkan…" (temuan reviewer).
        assertEquals(SyncStatus.OFFLINE, FirestoreSyncManager.resolveStatusOnDraining(false))
    }

    @Test
    fun `drain aktif dengan jaringan aktif atau belum diketahui jadi SYNCING`() {
        assertEquals(SyncStatus.SYNCING, FirestoreSyncManager.resolveStatusOnDraining(true))
        assertEquals(SyncStatus.SYNCING, FirestoreSyncManager.resolveStatusOnDraining(null))
    }
}
