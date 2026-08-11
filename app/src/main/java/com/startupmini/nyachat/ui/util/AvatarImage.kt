package com.startupmini.nyachat.ui.util

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.data.remote.ImageFileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Warna avatar deterministik berdasarkan hash nama (r1.2.3 — P0). Dipakai
 * BERSAMA oleh header bubble chat, topbar (StackedAvatars), dan fallback
 * avatar anggota — supaya warna satu orang konsisten di seluruh aplikasi.
 * Gunakan & 0x7FFFFFFF, bukan Math.abs: abs(Int.MIN_VALUE) tetap negatif dan
 * bisa menghasilkan indeks negatif → ArrayIndexOutOfBoundsException.
 */
fun avatarColorFor(name: String): Color {
    val palette = listOf(
        Color(0xFF6C3DE8), // indigo
        Color(0xFF00A878), // teal
        Color(0xFFE84393), // pink
        Color(0xFFFF8C42), // orange
        Color(0xFF3D9BE9), // sky blue
        Color(0xFFB23A48), // crimson
        Color(0xFF4CAF50), // green
        Color(0xFF9C27B0), // purple
    )
    return palette[(name.hashCode() and 0x7FFFFFFF) % palette.size]
}

/**
 * Warna TEKS pengirim yang kontras aman (WCAG AA untuk teks kecil) di kedua
 * mode, tapi tetap ber-nuansa warna avatar orangnya — dipakai label nama di
 * header bubble chat & kutipan balasan. Warna avatar murni (mis. orange) terlalu
 * terang untuk teks di background terang — padatkan/cerahkan secukupnya.
 */
fun avatarNameColor(name: String, isDark: Boolean): Color {
    val base = avatarColorFor(name)
    return if (isDark) {
        // Background gelap → cerahkan warna avatar (campur putih 55%).
        lerp(base, Color.White, 0.55f)
    } else {
        // Background terang → gelapkan warna avatar (campur hitam 55%).
        lerp(base, Color.Black, 0.55f)
    }
}

/**
 * Avatar umum (audit #2): menampilkan FOTO profil bila [photoPath] tersedia &
 * bisa dibaca, jika tidak fallback ke lingkaran inisial dengan warna latar
 * [backgroundColor]. Memberi pengenal visual konsisten di chat, topbar, dan
 * kartu identitas pengaturan.
 *
 * @param photoPath path file foto (null → inisial). Harus path AKTIF (ikut
 *   lifecycle + context). Loading bitmap disampling via ImageFileUtil supaya
 *   tidak memuat gambar asli beresolusi besar ke memori.
 */
@Composable
fun AvatarImage(
    name: String,
    size: Int = 40,
    modifier: Modifier = Modifier,
    photoPath: String? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    // Dekode foto (sampling 128px) — aman memori untuk avatar kecil.
    val photo: Bitmap? by produceState<Bitmap?>(null, photoPath) {
        value = withContext(Dispatchers.IO) {
            photoPath?.let { ImageFileUtil.decodeImage(it, 128) }
        }
    }

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val bmp = photo
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = textStyle,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1
                )
            }
        }
    }
}