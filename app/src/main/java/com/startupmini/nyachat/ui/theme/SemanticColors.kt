package com.startupmini.nyachat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Token warna semantik (audit UI/UX P2.5): semua warna bermakna (pemasukan,
 * pengeluaran, identitas AI, tag finansial, identitas anggota, palet kategori)
 * dikumpulkan di sini dalam varian light + dark, lalu disediakan lewat
 * [LocalSemanticColors] oleh [CoupleFinanceTheme].
 *
 * Sebelumnya tiap layar mendeteksi mode gelap sendiri lewat
 * `colorScheme.background.luminance() < 0.5f` (rapuh & berulang) dan beberapa
 * komponen lupa memakai varian dark (kontras badge finansial jatuh ke ~1.5:1).
 * Nilai warna identik dengan palet lama di [Color.kt] — hanya aksesnya yang
 * terpusat, sehingga tidak ada perubahan visual.
 */
@Immutable
data class SemanticColors(
    /** true saat tema gelap — pengganti pemeriksaan luminance manual. */
    val isDark: Boolean,
    /** Teks/ikon pemasukan (kontras AA di atas latar light & dark). */
    val income: Color,
    /** Latar lembut bernuansa pemasukan (ikon/badge). */
    val incomeBg: Color,
    /** Teks/ikon pengeluaran. */
    val expense: Color,
    /** Latar lembut bernuansa pengeluaran. */
    val expenseBg: Color,
    /** Identitas AI (ikon, border, spinner). */
    val ai: Color,
    /** Latar bubble/kartu AI. */
    val aiBg: Color,
    /** Teks AI di atas [aiBg] (lebih gelap di light mode supaya AA). */
    val aiText: Color,
    /** Latar badge tag finansial pemasukan di bubble chat. */
    val moneyTagIncomeBg: Color,
    /** Latar badge tag finansial pengeluaran di bubble chat. */
    val moneyTagExpenseBg: Color,
    /** Latar badge CAMPURAN (pemasukan+pengeluaran) — gradien pelangi pastel. */
    val moneyTagMixedBg: List<Color>,
    /** Teks/ikon di atas gradien pelangi badge campuran (mode-aware kontras). */
    val moneyTagMixedText: Color,
    /** Identitas anggota "istri"/perempuan. */
    val wife: Color,
    /** Identitas anggota "suami"/laki-laki (= biru brand). */
    val husband: Color,
    /** Palet 7 warna kategori chart, sudah mode-aware. */
    val categoryPalette: List<Color>
)

val LightSemanticColors = SemanticColors(
    isDark = false,
    income = IncomeGreen,
    incomeBg = IncomeGreenLight,
    expense = ExpenseRed,
    expenseBg = ExpenseRedLight,
    ai = AiBlue,
    aiBg = AiBlueLight,
    aiText = AiBlueText,
    moneyTagIncomeBg = MoneyTagIncomeBg,
    moneyTagExpenseBg = MoneyTagExpenseBg,
    moneyTagMixedBg = MoneyTagMixedBgLight,
    moneyTagMixedText = MoneyTagMixedTextLight,
    wife = WifePink,
    husband = HusbandBlue,
    categoryPalette = CategoryColorsLight
)

val DarkSemanticColors = SemanticColors(
    isDark = true,
    income = IncomeGreenDark,
    incomeBg = MoneyTagIncomeDark,
    expense = ExpenseRedDark,
    expenseBg = MoneyTagExpenseDark,
    ai = AiBlueDark,
    aiBg = Color.Transparent, // dark: bubble/kartu AI memakai surfaceVariant tema
    aiText = AiBlueDark,
    moneyTagIncomeBg = MoneyTagIncomeDark,
    moneyTagExpenseBg = MoneyTagExpenseDark,
    moneyTagMixedBg = MoneyTagMixedBgDark,
    moneyTagMixedText = MoneyTagMixedTextDark,
    wife = WifePinkDark,
    husband = HusbandBlueDark,
    categoryPalette = CategoryColorsDark
)

internal val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }
