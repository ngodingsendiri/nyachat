package com.startupmini.nyachat.ui.util

import java.text.NumberFormat
import java.util.Locale

/**
 * SATU sumber kebenaran formatter Rupiah UI (audit screens/ 2026-08-14).
 *
 * Sebelumnya pola identik ini diduplikasi di 7 tempat (RekapCharts ×3,
 * RekapList, RekapScreen, ChatBubbles, MainActivity) — mengubah format
 * (mis. 0 → 2 desimal) berarti mengedit 7 file dan mudah melenceng antar
 * layar. Sekarang cukup satu fungsi.
 *
 * Catatan: `NumberFormat` TIDAK thread-safe dan tidak di-cache antar
 * pemanggil — setiap pemanggil memegang instance sendiri (pola `remember {}`
 * di Composable tetap benar).
 */
fun idrCurrencyFormat(): NumberFormat =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
        maximumFractionDigits = 0
    }
