package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.R

/**
 * Fase alur pembukaan app (audit motion startup 2026-08-12) — target dari
 * [androidx.compose.animation.AnimatedContent] di MainActivity. Sebelumnya
 * if/else langsung menukar layar (hard cut → kesan "melompat-lompat").
 */
enum class StartupPhase {
    /** Secret (PIN/API key) sedang didekripsi dari Keystore — spinner + identitas app. */
    Loading,

    /** Belum masuk / belum punya workspace — layar PIN & login Google. */
    Pin,

    /** Sudah punya workspace — aplikasi utama (Chat/Rekap). */
    Main
}

/**
 * Layar pembukaan yang lebih hidup dari spinner polos: logo + nama app + spinner
 * halus. Fade-in lembut supaya tidak "pop". Background sudah diisi
 * [GlowingBackground] di MainActivity, jadi transisi keluar-masuk ke layar PIN
 * tetap mengalir (crossfade + zoom).
 */
@Composable
fun StartupLoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(28.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp
        )
    }
}
