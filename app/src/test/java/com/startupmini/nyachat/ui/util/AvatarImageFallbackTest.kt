package com.startupmini.nyachat.ui.util

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Avatar fallback (permintaan user 2026-08-14): FOTO profil asli bila tersedia
 * (avatarBytes/photoUrl → path valid), dan HANYA bila pengguna belum punya foto
 * barulah tampil lingkaran inisial. Inisial = huruf pertama nama, warna
 * deterministik per orang (konsisten di chat/topbar/kartu identitas).
 *
 * - photoPath null (tidak punya foto)   → inisial tampil.
 * - photoPath tidak bisa di-decode      → fallback inisial (foto gagal tidak
 *   boleh meninggalkan avatar kosong).
 * - foto valid                           → tidak ada teks inisial.
 *
 * Berjalan di JVM via Robolectric — siap CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AvatarImageFallbackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showAvatar(name: String, photoPath: String?) {
        composeRule.setContent {
            CoupleFinanceTheme {
                AvatarImage(name = name, photoPath = photoPath)
            }
        }
    }

    @Test
    fun `tanpa foto profil tampil inisial huruf pertama`() {
        showAvatar(name = "Budi", photoPath = null)
        composeRule.onNodeWithText("B").assertIsDisplayed()
    }

    @Test
    fun `path foto rusak - fallback ke inisial`() {
        // File tidak ada → decode gagal → jangan tampilkan avatar kosong.
        showAvatar(name = "Ari", photoPath = "/tidak/ada/foto.jpg")
        composeRule.onNodeWithText("A").assertIsDisplayed()
    }

    @Test
    fun `inisial memakai huruf pertama nama dan huruf besar`() {
        showAvatar(name = "siti aminah", photoPath = null)
        composeRule.onNodeWithText("S").assertIsDisplayed()
    }

    @Test
    fun `warna avatar deterministik per nama`() {
        // Nama sama → warna selalu sama; nama beda umumnya beda (indeks palet).
        assertEquals(avatarColorFor("Budi"), avatarColorFor("Budi"))
        assertNotEquals(avatarColorFor("Budi"), avatarColorFor("Sari"))
    }
}
