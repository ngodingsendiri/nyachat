package com.startupmini.nyachat.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r1.7.0 — Unit test mesin E2EE workspace (WorkspaceCrypto). Murni JVM
 * (tidak butuh Firebase/Android) — memakai BackupCrypto.encodeBase64 yang
 * juga murni JVM, jadi seluruh alur kripto bisa diverifikasi di unit test.
 */
class WorkspaceCryptoTest {

    // ======================= Keypair & grup key =======================

    @Test
    fun keypairMengirimkanPublicKeyYangBerbedaPerBentuk() {
        val a = WorkspaceCrypto.generateKeyPair()
        val b = WorkspaceCrypto.generateKeyPair()
        // Public key berbeda antar perangkat → wrap bukan untuk semua orang.
        assertFalse(WorkspaceCrypto.publicKeyBase64(a.public) ==
            WorkspaceCrypto.publicKeyBase64(b.public))
        // Base64 public key bisa di-parse balik → public key yang sama.
        assertEquals(
            WorkspaceCrypto.publicKeyBase64(a.public),
            WorkspaceCrypto.publicKeyBase64(WorkspaceCrypto.parsePublicKey(
                WorkspaceCrypto.publicKeyBase64(a.public)
            ))
        )
    }

    @Test
    fun grupKeyTigaPuluhDuaByteDanEnkripsiDuaKaliTidakIdentik() {
        val gk1 = WorkspaceCrypto.generateGroupKey()
        val gk2 = WorkspaceCrypto.generateGroupKey()
        assertEquals(32, gk1.size)
        // IV acak per enkripsi → dua hasil enkripsi plaintext sama TIDAK boleh identik.
        val plain = "pesan yang sama".toByteArray()
        assertFalse(WorkspaceCrypto.encryptContent(gk1, plain) ==
            WorkspaceCrypto.encryptContent(gk1, plain))
    }

    // ======================= EciesWrap grup key =======================

    @Test
    fun wrapDanUnwrapGroupKeyMengembalikanKunciAsli() {
        val owner = WorkspaceCrypto.generateKeyPair()
        val member = WorkspaceCrypto.generateKeyPair()
        val groupKey = WorkspaceCrypto.generateGroupKey()

        val wrapped = WorkspaceCrypto.wrapGroupKey(groupKey, WorkspaceCrypto.publicKeyBase64(member.public))
        val unwrapped = WorkspaceCrypto.unwrapGroupKey(wrapped, member.private)

        assertArrayEquals(groupKey, unwrapped)
    }

    @Test
    fun unwrapDenganPrivateKeyOrangLainGagal() {
        val member = WorkspaceCrypto.generateKeyPair()
        val attacker = WorkspaceCrypto.generateKeyPair()
        val groupKey = WorkspaceCrypto.generateGroupKey()

        val wrapped = WorkspaceCrypto.wrapGroupKey(groupKey, WorkspaceCrypto.publicKeyBase64(member.public))
        // GCM auth gagal karena kunci turunan berbeda → exception, bukan data bocor.
        assertThrows(Exception::class.java) {
            WorkspaceCrypto.unwrapGroupKey(wrapped, attacker.private)
        }
    }

    @Test
    fun wrapDenganPublicKeyRusakDitolak() {
        assertThrows(Exception::class.java) {
            WorkspaceCrypto.wrapGroupKey(WorkspaceCrypto.generateGroupKey(), "bukan-base64-pub")
        }
    }

    // ======================= Enkripsi konten =======================

    @Test
    fun enkripsiDekripsiKontenMengembalikanIsiAsli() {
        val groupKey = WorkspaceCrypto.generateGroupKey()
        val plain = """{"type":"PENGELUARAN","amount":25000.0,"category":"Makanan & Minuman"}""".toByteArray()

        val enc = WorkspaceCrypto.encryptContent(groupKey, plain)
        assertArrayEquals(plain, WorkspaceCrypto.decryptContent(groupKey, enc)!!)
    }

    @Test
    fun kontenKunciSalahAtauFormatRusakDitolak() {
        val groupKey = WorkspaceCrypto.generateGroupKey()
        val enc = WorkspaceCrypto.encryptContent(groupKey, "rahasia".toByteArray())

        // Kunci salah → null (GCM auth gagal), bukan exception.
        assertNull(WorkspaceCrypto.decryptContent(WorkspaceCrypto.generateGroupKey(), enc))
        // Format tidak valid → null.
        assertNull(WorkspaceCrypto.decryptContent(groupKey, "cuma-satu-bagian"))
        assertNull(WorkspaceCrypto.decryptContent(groupKey, "a.b.c"))
        assertNull(WorkspaceCrypto.decryptContent(groupKey, ""))
    }

    // ======================= Enkripsi bytes (foto) =======================

    @Test
    fun enkripsiBytesMendahuluiIvDanDekripsiMengembalikanAsli() {
        val groupKey = WorkspaceCrypto.generateGroupKey()
        val photo = ByteArray(1_024) { it.toByte() }

        val blob = WorkspaceCrypto.encryptBytes(groupKey, photo)
        // Format blob: iv(12) || ciphertext → minimal 12 byte lebih besar.
        assertEquals(photo.size + 12 + 16, blob.size) // 16 = tag GCM 128-bit
        assertArrayEquals(photo, WorkspaceCrypto.decryptBytes(groupKey, blob)!!)
    }

    @Test
    fun bytesKunciSalahAtauTerpotongDitolak() {
        val groupKey = WorkspaceCrypto.generateGroupKey()
        val blob = WorkspaceCrypto.encryptBytes(groupKey, "foto".toByteArray())

        assertNull(WorkspaceCrypto.decryptBytes(WorkspaceCrypto.generateGroupKey(), blob))
        assertNull(WorkspaceCrypto.decryptBytes(groupKey, blob.copyOf(blob.size - 1)))
        assertNull(WorkspaceCrypto.decryptBytes(groupKey, ByteArray(12)))
    }

    // ======================= Sha-256 =======================

    @Test
    fun sha256DeterministikDanKonsisten() {
        val data = "kunci-bersama".toByteArray()
        assertEquals(WorkspaceCrypto.sha256(data), WorkspaceCrypto.sha256(data))
        assertEquals(64, WorkspaceCrypto.sha256(data).length)
        assertTrue(WorkspaceCrypto.sha256(data) != WorkspaceCrypto.sha256("beda".toByteArray()))
    }
}