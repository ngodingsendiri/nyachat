package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keputusan auto-connect (r1.4.0): hasil `discoverMyWorkspaces` → aksi yang
 * harus dijalankan MainActivity setelah Google login. Logika murni
 * [resolveAutoConnect] — anti-regresi untuk alur "login akun sama → langsung
 * masuk workspace tanpa PIN" (bug 2026-08-14: setelah logout biasa, PIN pulih
 * dari Keystore tapi userName null → guard lama melewatkan connect dan user
 * nyangkut di layar PIN).
 */
class MembershipAutoConnectTest {

    private val ws = MyWorkspace(pin = "28429426", role = "owner", name = "Ari")

    // ── 0 workspace ────────────────────────────────────────────────────────────

    @Test
    fun `nol workspace dengan PIN lokal basi berarti bersihkan`() {
        val d = resolveAutoConnect(discovered = emptyList(), workspacePin = "28429426", userName = "Ari")
        assertEquals(AutoConnectDecision.ClearStalePin, d)
    }

    @Test
    fun `nol workspace tanpa PIN lokal berarti tidak ada aksi`() {
        val d = resolveAutoConnect(discovered = emptyList(), workspacePin = null, userName = null)
        assertEquals(AutoConnectDecision.Noop, d)
    }

    // ── 1 workspace ────────────────────────────────────────────────────────────

    @Test
    fun `satu workspace dengan PIN aktif berbeda berarti connect`() {
        val d = resolveAutoConnect(listOf(ws), workspacePin = "111111", userName = "Ari")
        assertEquals(AutoConnectDecision.Connect(ws), d)
    }

    @Test
    fun `satu workspace PIN sama tapi identitas kosong berarti connect`() {
        // BUG 2026-08-14: setelah logout biasa PIN pulih dari Keystore (sama
        // dengan ws.pin) tapi userName null — guard lama skip connect → nyangkut.
        val d = resolveAutoConnect(listOf(ws), workspacePin = "28429426", userName = null)
        assertEquals(AutoConnectDecision.Connect(ws), d)
    }

    @Test
    fun `satu workspace PIN sama dan identitas terisi berarti tidak ada aksi`() {
        val d = resolveAutoConnect(listOf(ws), workspacePin = "28429426", userName = "Ari")
        assertEquals(AutoConnectDecision.Noop, d)
    }

    @Test
    fun `satu workspace tanpa PIN lokal berarti connect`() {
        // Fresh install / pm clear: PIN hilang dari Keystore → harus connect.
        val d = resolveAutoConnect(listOf(ws), workspacePin = null, userName = null)
        assertEquals(AutoConnectDecision.Connect(ws), d)
    }

    // ── >1 workspace (user lama, defensif) ─────────────────────────────────────

    private val ws2 = MyWorkspace(pin = "999999", role = "member", name = "Ari")

    @Test
    fun `banyak workspace PIN aktif ada dan identitas terisi berarti tidak ada aksi`() {
        val d = resolveAutoConnect(listOf(ws, ws2), workspacePin = "28429426", userName = "Ari")
        assertEquals(AutoConnectDecision.Noop, d)
    }

    @Test
    fun `banyak workspace PIN aktif ada tapi identitas kosong berarti resume aktif`() {
        // Setelah logout: resume workspace aktif dari Keystore, bukan pilih ulang.
        val d = resolveAutoConnect(listOf(ws, ws2), workspacePin = "28429426", userName = null)
        assertEquals(AutoConnectDecision.Connect(ws), d)
    }

    @Test
    fun `banyak workspace tanpa PIN aktif berarti tampilkan pemilih`() {
        val d = resolveAutoConnect(listOf(ws, ws2), workspacePin = null, userName = null)
        assertEquals(AutoConnectDecision.ShowPicker, d)
    }

    @Test
    fun `banyak workspace PIN aktif tidak ada di daftar berarti tampilkan pemilih`() {
        val d = resolveAutoConnect(listOf(ws, ws2), workspacePin = "777777", userName = "Ari")
        assertEquals(AutoConnectDecision.ShowPicker, d)
    }

    // ── Sanity: Connect membawa nama member doc ────────────────────────────────

    @Test
    fun `connect membawa nama dari member doc`() {
        val d = resolveAutoConnect(listOf(ws), workspacePin = null, userName = null)
        assertTrue(d is AutoConnectDecision.Connect)
        assertEquals("Ari", (d as AutoConnectDecision.Connect).ws.name)
    }
}
