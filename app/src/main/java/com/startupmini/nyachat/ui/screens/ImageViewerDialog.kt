package com.startupmini.nyachat.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.BitmapCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Skala zoom maksimum viewer foto (5×) — cukup untuk membaca detail nota. */
internal const val MAX_ZOOM = 5f

/** Skala saat double-tap (toggle 1× ↔ 2.5×) — gaya galeri standar. */
internal const val DOUBLE_TAP_ZOOM = 2.5f

/** Hasil transform zoom/pan — dipakai gestur & diuji langsung (unit test). */
internal data class ZoomPan(val scale: Float, val offset: Offset)

/**
 * Terapkan pinch/pan ke state zoom saat ini (murni, tanpa side-effect —
 * diuji deterministik di ImageViewerDialogTest).
 *
 * [containerSize] ukuran area viewer (px); [centroid] & [pan] dari gestur;
 * [zoom] faktor zoom gestur (1f = tidak zoom). Titik di bawah jari dijaga
 * stabil (offset_baru = centroid + pan − (centroid − offset_lama) × rasio
 * skala), dan pan dibatasi agar tepi konten tidak masuk ke dalam layar —
 * saat skala 1× offset otomatis kembali ke pusat (0).
 */
internal fun applyZoomPan(
    scale: Float,
    offset: Offset,
    containerSize: IntSize,
    centroid: Offset,
    pan: Offset,
    zoom: Float
): ZoomPan {
    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val c = centroid - center
    val raw = c + pan - (c - offset) * (newScale / scale)
    val maxX = ((containerSize.width * newScale - containerSize.width) / 2f).coerceAtLeast(0f)
    val maxY = ((containerSize.height * newScale - containerSize.height) / 2f).coerceAtLeast(0f)
    return ZoomPan(
        scale = newScale,
        offset = Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    )
}

/**
 * Viewer foto full-screen (audit gestur 2026-08-13, permintaan user): sentuh
 * SEKALI pada bubble gambar membuka foto diperbesar — bukan menu (menu = tahan
 * lama). Latar hitam pekat (gaya galeri), gambar di-fit ke layar tanpa crop.
 *
 * GESTUR (2026-08-13, permintaan user):
 *  - PINCH (dua jari) → zoom 1×..5×, titik di bawah jari tetap stabil.
 *  - GESER (satu jari, saat sudah zoom) → pan; dibatasi agar tepi foto tidak
 *    masuk ke dalam layar dan otomatis kembali ke pusat saat zoom 1×.
 *  - DOUBLE-TAP → toggle zoom 2.5× / kembali 1×.
 *  - TAP / tombol ✕ → tutup (tap memakai deteksi double-tap, jadi ada jeda
 *    ±300ms sebelum menutup — pola galeri standar).
 *
 * Dekode memakai resolusi lebih tinggi (2200px) daripada bubble (1100px)
 * supaya detail nota belanja terbaca jelas saat diperbesar. Cache BitmapCache
 * menangani key per maxDim, jadi tidak menimpa thumbnail bubble.
 */
@Composable
fun ImageViewerDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ImageViewerContent(imagePath = imagePath, onDismiss = onDismiss)
    }
}

/**
 * Isi viewer (tanpa window Dialog) — dipisahkan supaya gestur zoom/pan bisa
 * diuji deterministik via Compose UI test (ImageViewerDialogTest) tanpa
 * window Dialog terpisah yang tidak terlihat oleh test framework.
 */
@Composable
internal fun ImageViewerContent(
    imagePath: String,
    onDismiss: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = imagePath
    ) {
        value = withContext(Dispatchers.IO) {
            BitmapCache.decodeMedia(imagePath, 2200)
        }
    }

    // State zoom/pan. Pan dibatasi (clamp) terhadap ukuran kontainer agar foto
    // tidak "terlempar" keluar layar; saat skala kembali 1× offset otomatis 0.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Lapisan gestur: pinch-zoom + pan, tap untuk tutup, double-tap
        // toggle zoom. clipToBounds supaya foto yang membesar tidak menimpa
        // tombol ✕ (yang berada di luar lapisan ini, tetap di atas).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val size = containerSize
                        if (size == IntSize.Zero) return@detectTransformGestures
                        val result = applyZoomPan(scale, offset, size, centroid, pan, zoom)
                        scale = result.scale
                        offset = result.offset
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                            }
                        }
                    )
                }
        ) {
            val bmp = bitmap
            if (bmp != null) {
                // stateDescription = persentase zoom — dibaca TalkBack (aksesibilitas)
                // DAN di-assert uji otomatis (zoom naik/turun) tanpa analisis piksel.
                val zoomLabel = stringResource(
                    R.string.image_viewer_zoom_desc,
                    (scale * 100).toInt()
                )
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.chat_image_desc),
                    modifier = Modifier
                        .fillMaxSize()
                        // testTag dipakai uji otomatis (ImageViewerDialogTest)
                        // untuk menunggu bitmap selesai di-decode.
                        .testTag("image_viewer_image")
                        .semantics { stateDescription = zoomLabel }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                // Sedang decode (atau file tidak ditemukan) — spinner agar layar
                // tidak kosong hitam tanpa umpan balik.
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Tombol tutup — scrim gelap tipis supaya tetap terlihat di atas
        // gambar terang. Berada di luar lapisan gestur (tetap di atas saat zoom).
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(10.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.image_viewer_close_desc),
                tint = Color.White
            )
        }
    }
}
