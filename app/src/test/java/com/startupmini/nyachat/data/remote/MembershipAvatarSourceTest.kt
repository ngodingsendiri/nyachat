package com.startupmini.nyachat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keputusan sumber foto avatar member (r1.4.0 — permintaan user): avatarBytes
 * (foto ter-upload, versi bernomor) lebih diutamakan; fallback ke URL foto
 * Google (photoUrl di member doc) untuk anggota yang belum pernah sync — supaya
 * device lain tetap menampilkan foto profil asli, bukan inisial. Logika murni
 * [decideAvatarSource].
 */
class MembershipAvatarSourceTest {

    @Test
    fun `avatarBytes versi baru menang atas photoUrl`() {
        // Bytes baru + photoUrl ada → bytes tetap diutamakan.
        assertEquals(
            AvatarSourceDecision.USE_BYTES,
            decideAvatarSource(
                avatarVersion = 5L, publishedVersion = 3L,
                photoUrl = "https://a.google.com/photo", publishedPhotoUrl = "https://a.google.com/photo"
            )
        )
    }

    @Test
    fun `versi bytes baru berarti gunakan bytes`() {
        assertEquals(
            AvatarSourceDecision.USE_BYTES,
            decideAvatarSource(avatarVersion = 5L, publishedVersion = 3L, photoUrl = null, publishedPhotoUrl = null)
        )
    }

    @Test
    fun `bytes sama tapi photoUrl baru berarti unduh photoUrl`() {
        assertEquals(
            AvatarSourceDecision.DOWNLOAD_PHOTO_URL,
            decideAvatarSource(
                avatarVersion = 3L, publishedVersion = 3L,
                photoUrl = "https://a.google.com/new", publishedPhotoUrl = "https://a.google.com/old"
            )
        )
    }

    @Test
    fun `belum pernah sync bytes - photoUrl baru berarti unduh photoUrl`() {
        // Anggota lama: avatarVersion 0 (belum pernah upload) + photoUrl dari join.
        assertEquals(
            AvatarSourceDecision.DOWNLOAD_PHOTO_URL,
            decideAvatarSource(
                avatarVersion = 0L, publishedVersion = 0L,
                photoUrl = "https://a.google.com/photo", publishedPhotoUrl = null
            )
        )
    }

    @Test
    fun `photoUrl sama dengan yang sudah dipublish berarti skip`() {
        assertEquals(
            AvatarSourceDecision.SKIP,
            decideAvatarSource(
                avatarVersion = 0L, publishedVersion = 0L,
                photoUrl = "https://a.google.com/photo", publishedPhotoUrl = "https://a.google.com/photo"
            )
        )
    }

    @Test
    fun `tanpa photoUrl dan tanpa bytes baru berarti skip`() {
        assertEquals(
            AvatarSourceDecision.SKIP,
            decideAvatarSource(avatarVersion = 0L, publishedVersion = 0L, photoUrl = null, publishedPhotoUrl = null)
        )
    }

    @Test
    fun `photoUrl kosong berarti skip`() {
        assertEquals(
            AvatarSourceDecision.SKIP,
            decideAvatarSource(avatarVersion = 0L, publishedVersion = 0L, photoUrl = "  ", publishedPhotoUrl = null)
        )
    }

    // ===== Refresh photoUrl di member doc lama saat connect (r1.4.0) =====
    @Test
    fun `photoUrl baru berbeda dari tersimpan berarti refresh`() {
        assertTrue(
            shouldRefreshPhotoUrl(
                currentPhoto = "https://a.google.com/old",
                newPhoto = "https://a.google.com/new"
            )
        )
    }

    @Test
    fun `member lama tanpa photoUrl tersimpan berarti refresh`() {
        // Doc dibuat sebelum fitur avatar foto — currentPhoto null, foto Google ada.
        assertTrue(
            shouldRefreshPhotoUrl(currentPhoto = null, newPhoto = "https://a.google.com/photo")
        )
    }

    @Test
    fun `photoUrl sama berarti tidak perlu refresh`() {
        assertFalse(
            shouldRefreshPhotoUrl(
                currentPhoto = "https://a.google.com/photo",
                newPhoto = "https://a.google.com/photo"
            )
        )
    }

    @Test
    fun `tanpa photoUrl Google berarti tidak refresh`() {
        assertFalse(shouldRefreshPhotoUrl(currentPhoto = null, newPhoto = null))
        assertFalse(shouldRefreshPhotoUrl(currentPhoto = "https://a.google.com/photo", newPhoto = "  "))
    }
}
