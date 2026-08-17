package com.startupmini.nyachat.data.crypto

import com.startupmini.nyachat.data.backup.BackupCrypto
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * r1.7.0 — End-to-End Encryption untuk workspace keluarga.
 *
 * Semua kripto berjalan DI PERANGKAT; server (Firestore/Storage) hanya
 * menyimpan ciphertext yang tidak bisa dibaca tanpa kunci klien.
 *
 * Model kunci (terinspirasi Signal, disederhanakan untuk keluarga):
 *  - Setiap perangkat punya keypair EC P-256 (private key dienkripsi oleh
 *    [com.startupmini.nyachat.data.local.SecureStorage] / Android Keystore;
 *    public key ditulis ke member doc Firestore).
 *  - Workspace punya SATU grup key AES-256. Di-wrap (EciesWrap) ke tiap
 *    perangkat dan disimpan di `families/{PIN}/e2eeKeys/{uid}`. Member baru
 *    mendapat wrap-annya saat disetujui owner (self-heal oleh siapa pun yang
 *    memegang grup key).
 *  - Pesan/transaksi/foto dienkripsi AES-GCM dengan grup key, IV acak per item.
 *
 * Format data (semua Base64 RFC 4648, dipisah titik):
 *  - wrap  : `ephemeralPubB64.ivB64.ctB64` (EciesWrap grup key ke publik recipient)
 *  - enc   : `ivB64.ctB64` (konten pesan / hasil AES-GCM grup key)
 * Base64 memakai [BackupCrypto.encodeBase64]/[decodeBase64] (satu sumber,
 * murni JVM → unit-testable).
 */
object WorkspaceCrypto {

    private const val EC_CURVE = "secp256r1"
    private const val KEY_AGREEMENT = "ECDH"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GROUP_KEY_BYTES = 32
    private const val HKDF_HMAC = "HmacSHA256"
    private const val HKDF_INFO_WRAP = "nyachat:e2ee:wrap:v1"
    private val RANDOM = SecureRandom()

    // ======================= Keypair perangkat =======================

    /** Generate keypair EC P-256 (software — private key tak pernah disimpan
     *  sebagai plaintext; pemanggil menyimpannya via SecureStorage). */
    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(EC_CURVE))
        return gen.generateKeyPair()
    }

    /** Encode public key (X.509/SPKI) → Base64 utk member doc. */
    fun publicKeyBase64(pub: PublicKey): String = BackupCrypto.encodeBase64(pub.encoded)

    /** Parse Base64 public key kembali (dari member doc). */
    fun parsePublicKey(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(BackupCrypto.decodeBase64(b64)))

    // ======================= Grup key workspace =======================

    /** Generate grup key AES-256 (32 byte acak) — dibuat device owner. */
    fun generateGroupKey(): ByteArray = ByteArray(GROUP_KEY_BYTES).also { RANDOM.nextBytes(it) }

    /**
     * EciesWrap: bungkus grup key agar hanya bisa dibuka pemegang private key
     * [recipientPubB64]. Skema: ephemeral EC → ECDH → HKDF-SHA256 → AES-GCM.
     * Output `ephemeralPubB64.ivB64.ctB64` (stateless, tanpa perlu simpan kunci).
     */
    fun wrapGroupKey(groupKey: ByteArray, recipientPubB64: String): String {
        val recipientPub = parsePublicKey(recipientPubB64)
        val ephemeral = generateKeyPair()
        val shared = ecdhSharedSecret(ephemeral.private, recipientPub)
        val wrapKey = hkdf(shared, HKDF_INFO_WRAP)
        val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
        val ct = aesGcmEncrypt(wrapKey, iv, groupKey)
        return listOf(publicKeyBase64(ephemeral.public), BackupCrypto.encodeBase64(iv), BackupCrypto.encodeBase64(ct))
            .joinToString(".")
    }

    /** Buka EciesWrap dengan private key sendiri → kembalikan grup key. */
    fun unwrapGroupKey(wrapped: String, myPrivateKey: PrivateKey): ByteArray {
        val parts = wrapped.split(".")
        require(parts.size == 3) { "Format wrap tidak valid" }
        val ephemeralPub = parsePublicKey(parts[0])
        val iv = BackupCrypto.decodeBase64(parts[1])
        val ct = BackupCrypto.decodeBase64(parts[2])
        val shared = ecdhSharedSecret(myPrivateKey, ephemeralPub)
        return aesGcmDecrypt(hkdf(shared, HKDF_INFO_WRAP), iv, ct)
    }

    // ======================= Enkripsi konten =======================

    /** Enkripsi konten (JSON pesan / transaksi) → `ivB64.ctB64`. */
    fun encryptContent(groupKey: ByteArray, plaintext: ByteArray): String {
        val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
        val ct = aesGcmEncrypt(groupKey, iv, plaintext)
        return listOf(BackupCrypto.encodeBase64(iv), BackupCrypto.encodeBase64(ct)).joinToString(".")
    }

    /** Dekripsi `ivB64.ctB64` → plaintext (null bila kunci salah/format rusak). */
    fun decryptContent(groupKey: ByteArray, enc: String): ByteArray? = runCatching {
        val parts = enc.split(".")
        require(parts.size == 2) { "Format enc tidak valid" }
        aesGcmDecrypt(groupKey, BackupCrypto.decodeBase64(parts[0]), BackupCrypto.decodeBase64(parts[1]))
    }.getOrNull()

    /** Enkripsi bytes (foto) → `iv(12) || ciphertext` — dipakai blob Storage. */
    fun encryptBytes(groupKey: ByteArray, data: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
        val ct = aesGcmEncrypt(groupKey, iv, data)
        return iv + ct
    }

    /** Dekripsi `iv(12) || ciphertext` — null bila kunci salah. */
    fun decryptBytes(groupKey: ByteArray, data: ByteArray): ByteArray? = runCatching {
        require(data.size > GCM_IV_BYTES) { "Ciphertext terlalu pendek" }
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val ct = data.copyOfRange(GCM_IV_BYTES, data.size)
        aesGcmDecrypt(groupKey, iv, ct)
    }.getOrNull()

    // ======================= Primitif =======================

    private fun ecdhSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(KEY_AGREEMENT)
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    /** HKDF-SHA256 (RFC 5869) — info membedakan turunan kunci. */
    private fun hkdf(ikm: ByteArray, info: String): ByteArray {
        // Extract: PRK = HMAC(salt=zeros, ikm)
        val extract = Mac.getInstance(HKDF_HMAC)
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        // Expand: T(1) = HMAC(PRK, info || 0x01) — cukup 1 blok (32 byte)
        val input = info.toByteArray() + byteArrayOf(1)
        val expand = Mac.getInstance(HKDF_HMAC)
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        return expand.doFinal(input).copyOf(GROUP_KEY_BYTES)
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** SHA-256 (dipakai deterministik untuk verifikasi/menandai kunci). */
    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
