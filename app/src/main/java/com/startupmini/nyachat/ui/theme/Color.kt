package com.startupmini.nyachat.ui.theme

import androidx.compose.ui.graphics.Color

// Palet aksen yang dipakai di seluruh UI (warna utama mengikuti tema Material 3)
val WifePink = Color(0xFFD81B60) // Clean Pink
val WifePinkLight = Color(0xFFFCE4EC)
val WifePinkDark = Color(0xFFFF80AB)  // Lebih terang untuk dark mode

val HusbandBlue = Color(0xFF0066FF) // Brand blue Nyachat (logo #0066FF)
val HusbandBlueLight = Color(0xFFE3ECFF) // Tint lembut brand blue
val HusbandBlueDark = Color(0xFFAAC7FF)  // Lebih terang untuk dark mode

// Identitas AI memakai keluarga brand blue — nilai sengaja MIRROR dari HusbandBlue
// (bedanya peran semantik: AI vs chip anggota). Jangan diubah salah satu saja.
val AiBlue = Color(0xFF0066FF) // Brand blue Nyachat — identitas AI (bubble biru logo)
val AiBlueLight = Color(0xFFE3ECFF) // Tint lembut brand blue — bg bubble AI
val AiBlueDark = Color(0xFFAAC7FF)  // Tone terang brand blue untuk dark mode
val AiBlueText = Color(0xFF0B57D0)  // Biru lebih gelap utk TEKS AI di atas tint (kontras AA ~4.9:1)

val IncomeGreen = Color(0xFF256B45) // Muted forest green (digenapkan utk kontras teks chip 5.42:1)
val IncomeGreenLight = Color(0xFFDEF0E6) // Soft mint
val IncomeGreenDark = Color(0xFF69EFC4)  // Primary dark - eye-friendly

val ExpenseRed = Color(0xFFC0392B) // Slightly desaturated red
val ExpenseRedLight = Color(0xFFFAE8E7) // Very soft blush
val ExpenseRedDark = Color(0xFFF2A096)   // Dark: merah pastel lembut (tidak "keras" seperti FF8A80)

// Financial tag badge - warna khusus yang tidak terlalu mencolok
val MoneyTagIncomeBg   = Color(0xFFF0FAF4) // Soft green tint (light) — lebih terang utk kontras badge income 4.72:1
val MoneyTagExpenseBg  = Color(0xFFFAECEA) // Soft red tint (light)
val MoneyTagIncomeDark  = Color(0xFF1A4D30) // Deep green for dark mode bg
val MoneyTagExpenseDark = Color(0xFF4D1A17) // Deep red for dark mode bg

// Kategori chart - light mode. Versi DIGELAPKAN dari Google palette supaya ikon &
// teks persentase kategori tetap AA (>=4.5:1) di atas latar putih — hasil audit
// WCAG: versi vivid sebelumnya (Yellow 1.67:1, Orange 2.59:1, Cyan 2.41:1) gagal.
// Donut chart sedikit lebih dalam warnanya, tapi tetap 7 warna berbeda.
val CategoryColorsLight = listOf(
    Color(0xFF1967D2), // Google Blue (darken, 5.25:1)
    Color(0xFFC5221F), // Google Red (darken, 5.66:1)
    Color(0xFF8D6E00), // Google Yellow (darken, 4.70:1)
    Color(0xFF188038), // Google Green (darken, 4.90:1)
    Color(0xFF9334E6), // Google Purple (5.30:1, aman)
    Color(0xFF00626E), // Google Cyan (darken, 6.90:1)
    Color(0xFFA83D00)  // Google Orange (darken, 6.16:1)
)

// Kategori chart - dark mode (muted, lebih nyaman di layar gelap)
val CategoryColorsDark = listOf(
    Color(0xFF82B1FF), // Muted Blue
    Color(0xFFFF8A80), // Muted Red
    Color(0xFFFFD740), // Amber
    Color(0xFF69F0AE), // Muted Green
    Color(0xFFEA80FC), // Muted Purple
    Color(0xFF80DEEA), // Muted Cyan
    Color(0xFFFFD180)  // Muted Orange
)

val CardBackground = Color(0xFFFFFFFF)
val TextDark = Color(0xFF202124)
val TextMuted = Color(0xFF5E6368)
