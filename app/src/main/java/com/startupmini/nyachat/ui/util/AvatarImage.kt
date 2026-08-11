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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.data.remote.ImageFileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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