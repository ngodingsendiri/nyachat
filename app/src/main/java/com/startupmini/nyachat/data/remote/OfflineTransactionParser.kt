package com.startupmini.nyachat.data.remote

import com.startupmini.nyachat.Constants
import java.util.regex.Pattern

/**
 * MESIN HEURISTIK OFFLINE — dipecah dari GeminiService.kt (audit 2026-08-14):
 * GeminiService tadinya 1.635 baris monolit; ~545 baris logika parse transaksi
 * chat Indonesia TANPA AI dipindah ke sini supaya mudah dirawat & diuji.
 *
 * Semua fungsi MURNI & deterministik (tanpa I/O, tanpa state) — mudah diuji
 * unit. GeminiService tetap menyediakan delegasi (offlineHeuristicParse dkk)
 * agar referensi lama (test, FinanceAiService) tidak berubah.
 */
object OfflineTransactionParser {

    /** Pola angka + satuan opsional: "50rb", "50.000", "2,5jt", "5 juta", "10k".
     *  `(?![a-z])` mencegah huruf biasa terbaca sebagai satuan — mis. 'k' pada
     *  "2 kopi" bukan satuan ribu. `(?:[.,]\d+)*` menangkap SELURUH grup ribuan
     *  + desimal ("1.500.000", "2,5jt") — bukan cuma satu grup agar nominal
     *  ≥ 1 juta bertitik tidak terpotong (K1). */
    internal val NUMBER_UNIT_PATTERN =
        Pattern.compile("(\\d+(?:[.,]\\d+)*)\\s*(?:(rb|ribu|k|jt|juta)(?![a-z]))?")

    /**
     * Deteksi "jam" dari angka HH.MM ("07.30", "14.00", "19.45") — bukan
     * nominal. "07.30" sebelumnya terbaca Rp 730.000 (r1.4.0 — audit Finance AI):
     * tanpa unit, titik dihapus → 0730 → ×1000. Pola: 1-2 digit, titik, PERSIS 2
     * digit (menit 00-59) → jam. "1.500.000" (3 grup) & "15.000" (3 digit di
     * belakang) tidak kena.
     */
    internal fun isClockTime(numStr: String): Boolean {
        if (!numStr.contains('.')) return false
        val parts = numStr.split('.')
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return parts[0].length in 1..2 && parts[1].length == 2 &&
            h in 0..23 && m in 0..59
    }

    /**
     * r1.4.0 (audit Finance AI): angka polos TANPA satuan dengan >= 10 digit
     * = nomor rekening/telepon/ID, BUKAN nominal — "transfer ke rekening
     * 1234567890 sebesar 200rb" tidak boleh jadi transaksi Rp 1,23 miliar.
     * (Nominal Rupiah di chat praktis < 10 digit; nominal besar selalu ditulis
     * dengan satuan "jt"/"M" atau titik ribuan yang jumlah digitnya tetap < 10
     * untuk < 1 miliar.) Dipakai konsisten oleh ekstraksi, pemisahan batas
     * nominal, dan penghitungan jumlah nominal.
     */
    internal fun isImplausiblePlainNumber(numStr: String): Boolean =
        numStr.count { it.isDigit() } >= 10

    // ---- r1.4.0 (audit input 2026-08-14): angka polos NON-NOMINAL ----
    // Dua kelas angka polos (tanpa satuan) yang selama ini DIANGGAP nominal
    // sehingga transaksi asli hilang / tercatat salah:
    //  1) TAHUN 19xx/20xx — "bayar spp 2025 sebesar 2jt" → tadinya tercatat
    //     Rp 2.025 dan 2jt HILANG (split batas nominal memecah di "2025").
    //  2) KUANTITAS >= 2 digit diikuti satuan — "beli 12 buku seharga 50rb"
    //     → tadinya Rp 12.000 (12 buku) dan 50rb hilang.
    // Guard dipakai KONSISTEN oleh countAmounts, splitByAmountBoundaries, dan
    // extractAmountFromText (satu sumber kebenaran seperti isClockTime).

    internal fun isYearNumber(numStr: String): Boolean =
        numStr.length == 4 && numStr.matches(Regex("(?:19|20)\\d{2}"))

    private val QUANTITY_UNITS = listOf(
        "buku", "buah", "pcs", "kg", "kilo", "liter", "orang", "ekor",
        "lembar", "pasang", "unit", "potong", "gelas", "botol", "kantong",
        "butir", "batang", "helai", "kotak", "dus", "kardus", "rim", "lusin",
        "kali", "item", "produk", "bungkus", "kaleng", "tabung", "meter",
        "jam", "hari", "minggu", "bulan", "tahun", "porsi", "piring", "ekor"
    )

    /** Apakah ada NOMINAL BERSATUAN (rb/ribu/k/jt/juta) di luar posisi ini? */
    private fun hasUnitNumberElsewhere(textLower: String, numStart: Int, numEnd: Int): Boolean {
        val m = NUMBER_UNIT_PATTERN.matcher(textLower)
        while (m.find()) {
            if (m.start() == numStart && m.end() == numEnd) continue
            if (!m.group(2).isNullOrEmpty()) return true
        }
        return false
    }

    /**
     * true jika angka polos di posisi [numStart, numEnd) pada [textLower] BUKAN
     * nominal: jam (HH.MM), rekening/telepon (>=10 digit), tahun 19xx/20xx dalam
     * konteks (didahului spp/tahun/angkatan ATAU diikuti tahun/gelombang ATAU ada
     * nominal bersatuan lain di pesan), atau kuantitas >= 2 digit yang diikuti
     * satuan ("12 buku", "20 pcs", "25 tahun").
     */
    internal fun isNonMonetaryNumber(
        textLower: String,
        numStr: String,
        numStart: Int,
        numEnd: Int
    ): Boolean {
        if (isClockTime(numStr)) return true
        if (isImplausiblePlainNumber(numStr)) return true
        if (isYearNumber(numStr)) {
            val before = textLower.substring(0, numStart).trimEnd()
            val after = textLower.substring(numEnd).trimStart()
            val ctxBefore = listOf("spp", "tahun", "angkatan", "gelombang", "semester")
                .any { before.endsWith(it) }
            val ctxAfter = listOf("tahun", "gelombang", "angkatan", "semester")
                .any { after.startsWith(it) }
            if (ctxBefore || ctxAfter || hasUnitNumberElsewhere(textLower, numStart, numEnd)) {
                return true
            }
        }
        if (numStr.count { it.isDigit() } >= 2) {
            val after = textLower.substring(numEnd).trimStart()
            if (QUANTITY_UNITS.any { u ->
                    after.startsWith(u) &&
                        (after.length == u.length || !after[u.length].isLetter())
                }
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Ekstrak nominal Rupiah dari teks bebas. Angka BERSATUAN (rb/ribu/k/jt/juta)
     * diprioritaskan atas angka polos karena jauh lebih mungkin nominal transaksi:
     * "beli 2 kopi 20rb" mengambil 20rb (Rp 20.000), bukan 2 (Rp 2.000).
     * Tanpa angka bersatuan, fallback ke angka pertama.
     *
     * L2: angka polos dengan < 2 digit (1–9) TANPA satuan ditolak — mis. "makan
     * 2 kucing" / "beli 3 botol" sebenarnya jumlah item, bukan nominal. Konteks
     * ini mengurangi false-positive heuristik. Hanya nilai ≥ 10 (dianggap "ribuan"
     * lewat toRupiah) atau angka bersatuan yang diterima sebagai nominal.
     */
    internal fun extractAmountFromText(textLower: String): Double? {
        val matcher = NUMBER_UNIT_PATTERN.matcher(textLower)
        var fallbackNum: String? = null
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (!unit.isNullOrEmpty()) return toRupiah(numStr, unit)
            // L2: angka polos 1 digit (0–9) = kuantitas, bukan nominal.
            if (numStr.count { it.isDigit() } < 2) continue
            // r1.4.0 (audit input): jam, rekening, TAHUN dalam konteks, dan
            // kuantitas bersatuan ("12 buku") bukan nominal — lewati.
            if (isNonMonetaryNumber(textLower, numStr, matcher.start(), matcher.end())) continue
            if (fallbackNum == null) fallbackNum = numStr
        }
        val num = fallbackNum ?: return null
        return toRupiah(num, null)
    }

    private fun toRupiah(numStr: String, unit: String?): Double? {
        // Audit r1.2.4: "3.5jt" (TITIK desimal) sebelumnya jadi "35" → 35jt (salah
        // 10x). Bila ada unit & angka memakai TITIK TUNGGAL dengan ≤2 digit di
        // belakangnya, titik itu adalah DESIMAL ("3.5", "1.75"), bukan ribuan.
        // Koma selalu desimal. Titik 3+ digit / titik ganda = pemisah ribuan
        // ("1.500.000", "15.000") — dihapus seperti biasa.
        val isDecimalDot = unit != null &&
            numStr.count { it == '.' } == 1 &&
            numStr.substringAfter('.').length in 1..2 &&
            numStr.substringBefore('.').isNotBlank()
        val normalized = if (isDecimalDot) {
            numStr.replace(",", ".")
        } else {
            numStr.replace(".", "").replace(",", ".")
        }
        val rawNum = normalized.toDoubleOrNull() ?: return null
        return when (unit) {
            "rb", "ribu", "k" -> rawNum * 1000
            "jt", "juta" -> rawNum * 1000000
            else -> if (rawNum in 1.0..999.0) rawNum * 1000 else rawNum
        }
    }

    // ---- r1.2.4 (tuning AI): mesin heuristik offline multi-transaksi ----

    /**
     * Deteksi tanggal dari frasa waktu bahasa Indonesia (tuning AI): "kemarin",
     * "tadi", "minggu lalu", "tanggal N". Return OFFSET ms relatif sekarang
     * (negatif = masa lalu); null bila tidak ada indikasi waktu. Dipakai untuk
     * mengisi timestamp transaksi agar Rekap tidak selalu memakai waktu proses.
     */
    internal fun detectDateOffset(textLower: String): Long? {
        val dayMs = 86_400_000L
        return when {
            // "kemarin lusa" harus dicek DULU — "kemarin lusa" mengandung "kemarin"
            // sehingga cabang "kemarin" akan menang lebih dulu (review r1.2.4).
            textLower.contains("kemarin lusa") -> -2 * dayMs
            textLower.contains("kemarin") || textLower.contains("tadi malam") ||
                textLower.contains("tadi sore") -> -dayMs
            textLower.contains("tadi") -> -2 * 3_600_000L // "tadi pagi/siang" ≈ beberapa jam lalu
            textLower.contains("minggu lalu") || textLower.contains("pekan lalu") -> -7 * dayMs
            textLower.contains("bulan lalu") -> -30 * dayMs
            textLower.contains("tanggal") || textLower.contains("tgl") -> {
                // "tanggal 12" → tanggal 12 bulan ini; BILA sudah lewat (offset positif
                // = masa depan), geser ke bulan SEBELUMNYA supaya timestamp tidak
                // pernah di masa depan (review r1.2.4).
                val m = Regex("""(?:tanggal|tgl)\s*(\d{1,2})""").find(textLower)
                val day = m?.groupValues?.get(1)?.toIntOrNull() ?: return null
                val now = System.currentTimeMillis()
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
                if (cal.timeInMillis > now) {
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                cal.timeInMillis - now
            }
            else -> null
        }
    }

    /**
     * Pisahkan teks menjadi segmen kandidat multi-transaksi (r1.4.0 — audit
     * Finance AI: root cause "satu pesan berisi beberapa transaksi tidak
     * terdeteksi").
     *
     * Strategi 1 — separator eksplisit: koma, titik koma, " dan ", " sama ",
     * " atau " (kata utuh). Dipakai bila SETIAP segmen berangka ter-parse
     * ("beli bakso 15rb, bensin 30rb sama rokok 20rb").
     *
     * Strategi 2 — batas nominal: pesan multi-transaksi TANPA separator
     * ("Gaji lembur 200.000 Beli rokok 30.000 Makan Malam 45.000") — setiap
     * nominal menandai AKHIR satu transaksi; teks antar-nominal = deskripsinya.
     *
     * ATURAN AMAN (tuning AI): separator split dipakai HANYA jika tidak ada
     * segmen berangka yang gagal jadi transaksi — mis. "beli sayur dan buah
     * 20rb" maksudnya SATU transaksi; split " dan " memecahnya jadi "beli
     * sayur" (tanpa angka) + "buah 20rb" (angka tanpa trigger). Kasus ini
     * jatuh ke strategi 2 → 1 nominal → parse utuh → 1 transaksi benar.
     * Prinsip: jangan pernah salah catat.
     */
    internal fun splitTransactionSegments(text: String): List<String> {
        // Strategi 1: separator eksplisit.
        val sepSplit = text.trim()
            .split(Regex(""",\s*|;\s*|\s+dan\s+|\s+sama\s+|\s+atau\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sepSplit.size > 1) {
            val allValid = sepSplit.all { seg ->
                val segLower = seg.lowercase()
                val hasNumber = NUMBER_UNIT_PATTERN.matcher(segLower).find()
                !hasNumber || parseSegment(segLower, seg) != null
            }
            if (allValid) {
                // r1.4.0 (stress test): segmen hasil separator BISA masih memuat
                // ≥2 nominal ("bensin 30rb jajan 20rb") — pecah ulang per batas
                // nominal supaya transaksi kedua tidak hilang. Segmen 1 nominal
                // tetap utuh.
                return sepSplit.flatMap { seg ->
                    val sub = splitByAmountBoundaries(seg)
                    if (sub.size > 1) sub else listOf(seg)
                }
            }
        }

        // Strategi 2: batas nominal (multi-transaksi tanpa separator).
        val amountSegs = splitByAmountBoundaries(text)
        if (amountSegs.size > 1) return amountSegs
        return listOf(text.trim())
    }

    /**
     * Pecah teks per batas nominal (r1.4.0): setiap angka yang berpotensi
     * nominal menandai akhir satu segmen. Nominal jam (HH.MM), angka polos
     * 1 digit (kuantitas) dilewati. Kurang dari 2 nominal → utuh (1 segmen).
     */
    internal fun splitByAmountBoundaries(text: String): List<String> {
        val lower = text.lowercase()
        val matches = mutableListOf<Pair<Int, Int>>() // start..end tiap nominal
        val matcher = NUMBER_UNIT_PATTERN.matcher(lower)
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (unit.isNullOrEmpty()) {
                if (numStr.count { it.isDigit() } < 2) continue // kuantitas
                // r1.4.0 (audit input): jam/rekening/tahun/kuantitas bukan nominal.
                if (isNonMonetaryNumber(lower, numStr, matcher.start(), matcher.end())) continue
            }
            matches += matcher.start() to matcher.end()
        }
        if (matches.size < 2) return listOf(text.trim())

        val segs = mutableListOf<String>()
        var prevEnd = 0
        matches.forEach { (_, end) ->
            segs += text.substring(prevEnd, end).trim()
            prevEnd = end
        }
        // Teks sisa setelah nominal terakhir tetap milik segmen terakhir.
        if (prevEnd < text.length) {
            val last = segs.removeAt(segs.size - 1)
            segs += (last + " " + text.substring(prevEnd)).trim()
        }
        return segs.filter { it.isNotEmpty() }
    }

    /**
     * Hitung jumlah nominal dalam teks (r1.4.0) — dipakai [shouldHeuristicBackup]:
     * pesan dengan ≥2 nominal adalah kandidat multi-transaksi yang paling rawan
     * salah ditangani AI.
     */
    internal fun countAmounts(text: String): Int {
        val lower = text.lowercase()
        val matcher = NUMBER_UNIT_PATTERN.matcher(lower)
        var count = 0
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: continue
            val unit = matcher.group(2)
            if (unit.isNullOrEmpty()) {
                if (numStr.count { it.isDigit() } < 2) continue
                // r1.4.0 (audit input): jam/rekening/tahun/kuantitas bukan nominal.
                if (isNonMonetaryNumber(lower, numStr, matcher.start(), matcher.end())) continue
            }
            count++
        }
        return count
    }

    /**
     * Deteksi pesan KOREKSI/PEMBATALAN ("eh bukan 15rb, 25rb", "yang tadi
     * salah, hapus") — SATU sumber kebenaran (audit live 2026-08-14): dipakai
     * oleh [shouldHeuristicBackup] DAN [offlineHeuristicParse]. Sebelumnya
     * guard ini hanya ada di jalur AI online; saat AI offline pesan koreksi
     * lolos ke heuristik dan TERCATAT sebagai transaksi baru (bug ditemukan
     * live test: "eh bukan makan 45rb maksudnya 50rb" → PENGELUARAN 45rb).
     * Prinsip: koreksi = perbaikan pesan lama, BUKAN transaksi baru.
     */
    internal fun isCorrectionOrCancellation(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "batal", "bukan", "salah", "hapus", "yang tadi", "jangan",
            "revisi", "maksudnya", "eh ", "eh, ", "eh,", "bukan berarti"
        ).any { lower.contains(it) }
    }

    /**
     * Kapan hasil AI yang mengatakan "tidak ada transaksi" perlu diverifikasi
     * ulang heuristik (r1.4.0): hanya untuk pesan dengan ≥2 nominal (paling
     * rawan salah), dan TIDAK untuk pesan koreksi/pembatalan ("eh bukan 15rb,
     * 25rb" — AI sengaja tidak mencatat) atau pertanyaan keuangan.
     */
    internal fun shouldHeuristicBackup(text: String): Boolean {
        if (isCorrectionOrCancellation(text)) return false
        if (isFinancialQuestion(text)) return false
        val lower = text.lowercase()
        if (lower.contains("ingatkan") || lower.contains("reminder") || lower.contains("rencana")) return false
        return countAmounts(text) >= 2
    }

    /**
     * Parse SATU segmen menjadi transaksi (inti logika heuristik lama, di-refactor
     * agar bisa dipanggil per segmen untuk multi-transaksi). Return null bila
     * segmen tidak memuat transaksi yang jelas. [segText] asli dipakai untuk
     * deskripsi; [textLower] versi lowercase untuk pencocokan kata kunci.
     */
    private fun parseSegment(textLower: String, segText: String): AiTransaction? {
        val amount = extractAmountFromText(textLower)
        if (amount == null || amount <= 0) return null

        val isIncome = listOf(
            "terima gaji", "dapat gaji", "menerima gaji", "gaji masuk", "gaji cair", "cair gaji", "gaji",
            "terima bonus", "dapat bonus", "menerima bonus", "bonus masuk", "bonus cair", "cair bonus",
            "terima komisi", "dapat komisi", "menerima komisi", "komisi masuk", "komisi cair", "cair komisi",
            "bonus", // r1.2.4: mandiri (paritas dgn "gaji"/"dividen") — "gaji 5jt dan bonus 2jt"
            "terima dividen", "dapat dividen", "menerima dividen", "dividen masuk", "dividen cair", "cair dividen", "dividen",
            "terima arisan", "dapat arisan", "menerima arisan", "arisan masuk", "arisan cair", "cair arisan", "menang arisan",
            "terima rejeki", "dapat rejeki", "menerima rejeki", "rejeki nomplok", "rejeki",
            "terima uang", "dapat uang", "menerima uang", "uang masuk", "uang jajan masuk",
            "terima hadiah", "dapat hadiah", "menerima hadiah", "menang undian", "dapat undian", "undian",
            "hasil jualan", "hasil dagang", "hasil usaha", "omzet", "omset", "penjualan",
            "jualan", // r1.4.0 (stress test): "jualan online 300rb" = pemasukan usaha
            "laku", "terjual", "dapat hasil", "terima hasil",
            "cashback", "refund", "pengembalian dana", "uang kembali",
            "terima thr", "dapat thr", "thr masuk", "thr cair",
            "terima insentif", "dapat insentif", "insentif masuk",
            "terima tips", "dapat tips", "menerima tips",
            "bunga bank", "bunga deposito", "cair deposito", "kupon obligasi",
            "transfer masuk", "pemasukan", "pencairan", "bagi hasil", "warisan", "hibah"
        ).any { textLower.contains(it) }

        // r1.2.4 (review): "bonus/komisi/tips/insentif/thr/gaji" mandiri bisa
        // berarti MENGELUARKAN ("bagi bonus 500rb", "kasih tips 20rb", "bayar
        // gaji 5jt", "potong gaji") — blokir deteksi income bila ada verba
        // pengeluaran yang menempel. "bagi hasil" (investasi) TIDAK kena karena
        // "hasil" bukan kata yang diblokir. Audit r1.2.4: "bayar gaji" sebelumnya
        // salah tercatat PEMASUKAN karena "gaji" mandiri menang.
        val incomeBlocker = Regex(
            "(bagi|kasih|setor|kirim|beri|bayar|potong|kurang)\\s+(bonus|komisi|tips|insentif|thr|gaji)"
        ).containsMatchIn(textLower)

        val isExpenseTrigger = (
            textLower.contains("beli") || textLower.contains("bayar") ||
                textLower.contains("pengeluaran") || textLower.contains("habis") ||
                textLower.contains("belanja") || textLower.contains("ongkir") ||
                textLower.contains("sewa") || textLower.contains("pulsa") ||
                textLower.contains("listrik") || textLower.contains("air") ||
                textLower.contains("popok") || textLower.contains("susu") ||
                textLower.contains("makan") || textLower.contains("transaksi") ||
                // r1.4.0 (stress test): kata Indonesia umum yang selama ini lolos
                // dari heuristik ("kopi 15 ribu", "jajan 20rb", "renovasi 75jt",
                // "upgrade ram 0,5jt") — tanpa trigger, transaksi nyata hilang.
                // r1.4.0 (audit campuran income+expense): "uang keluar 3jt" /
                // "keluar 3jt" = frasa pengeluaran umum yang selama ini LOLOS
                // (hanya income "uang masuk" yang terekam → transaksi hilang).
                // "uang keluar" dicek dulu supaya tidak tertangkap "uang masuk".
                textLower.contains("uang keluar") || textLower.contains("keluar") ||
                textLower.contains("kopi") || textLower.contains("jajan") ||
                textLower.contains("renovasi") || textLower.contains("upgrade") ||
                textLower.contains("bensin") || textLower.contains("taxi") ||
                textLower.contains("ojek") || textLower.contains("grab") ||
                textLower.contains("gojek") || textLower.contains("tol") ||
                textLower.contains("parkir") || textLower.contains("isi") ||
                textLower.contains("cicilan") || textLower.contains("kredit") ||
                textLower.contains("angsuran") || textLower.contains("hutang") ||
                textLower.contains("utang") || textLower.contains("pinjaman") ||
                textLower.contains("spp") || textLower.contains("kuliah") ||
                textLower.contains("les") || textLower.contains("kursus") ||
                textLower.contains("sedekah") || textLower.contains("zakat") ||
                textLower.contains("infaq") || textLower.contains("infak") ||
                textLower.contains("donasi") || textLower.contains("sumbangan") ||
                textLower.contains("asuransi") || textLower.contains("premi") ||
                textLower.contains("pajak") || textLower.contains("stnk") ||
                textLower.contains("bpjs") || textLower.contains("topup") || textLower.contains("top up") ||
                textLower.contains("rokok") || textLower.contains("tembakau") // r1.2.4: barang konsumsi
            )

        if (isIncome && !incomeBlocker) {
            val category = when {
                textLower.contains("jual") || textLower.contains("dagang") || textLower.contains("omzet") ||
                    textLower.contains("omset") || textLower.contains("orderan") || textLower.contains("usaha") ||
                    textLower.contains("laku") || textLower.contains("terjual") || textLower.contains("penjualan") ->
                    Constants.Categories.BUSINESS
                textLower.contains("dividen") || textLower.contains("bunga") || textLower.contains("bagi hasil") ||
                    textLower.contains("saham") || textLower.contains("reksadana") || textLower.contains("investasi") ||
                    textLower.contains("deposito") || textLower.contains("capital gain") ->
                    Constants.Categories.INVESTMENT
                textLower.contains("arisan") || textLower.contains("hadiah") || textLower.contains("undian") ||
                    textLower.contains("rejeki") || textLower.contains("warisan") || textLower.contains("hibah") ->
                    Constants.Categories.GIFT
                textLower.contains("cashback") || textLower.contains("refund") ||
                    textLower.contains("pengembalian") || textLower.contains("uang kembali") ->
                    Constants.Categories.CASHBACK
                textLower.contains("bonus") || textLower.contains("komisi") ||
                    textLower.contains("thr") || textLower.contains("insentif") || textLower.contains("tips") ->
                    Constants.Categories.BONUS
                else -> Constants.Categories.SALARY
            }
            return AiTransaction(
                type = Constants.TransactionTypes.INCOME,
                category = category,
                amount = amount,
                // r1.4.0: deskripsi tanpa nominal ("Gaji lembur 200.000" →
                // "Gaji lembur") — konsisten dengan hasil AI & Rekap bersih.
                description = GeminiService.cleanSuggestionDescription(segText),
                timestamp = nowPlus(detectDateOffset(textLower))
            )
        }

        if (isExpenseTrigger) {
            val category = when {
                textLower.contains("beras") || textLower.contains("minyak") || textLower.contains("sayur") || textLower.contains("sembako") || textLower.contains("pasar") || textLower.contains("supermarket") || textLower.contains("market") -> "Groceries & Sembako"
                textLower.contains("makan") || textLower.contains("minum") || textLower.contains("kopi") || textLower.contains("jajan") || textLower.contains("bakso") || textLower.contains("snack") || textLower.contains("nasi") -> "Makanan & Minuman"
                textLower.contains("listrik") || textLower.contains("air") || textLower.contains("wifi") || textLower.contains("pulsa") || textLower.contains("kontrakan") || textLower.contains("token") -> "Tagihan & Utilitas"
                textLower.contains("spp") || textLower.contains("kuliah") ||
                    (textLower.contains("les") && !textLower.contains("lesehan")) ||
                    textLower.contains("kursus") || textLower.contains("bimbel") || textLower.contains("uang gedung") ||
                    textLower.contains("ujian") || textLower.contains("pendidikan") -> "Pendidikan"
                textLower.contains("popok") || textLower.contains("susu") || textLower.contains("sekolah") || textLower.contains("mainan") || textLower.contains("anak") -> "Kebutuhan Anak"
                textLower.contains("bensin") || textLower.contains("ojek") || textLower.contains("grab") || textLower.contains("gojek") || textLower.contains("tol") || textLower.contains("parkir") || textLower.contains("taxi") -> "Transportasi"
                textLower.contains("skincare") || textLower.contains("obat") || textLower.contains("dokter") || textLower.contains("sabun") || textLower.contains("shampoo") -> "Kesehatan & Skincare"
                textLower.contains("baju") || textLower.contains("sepatu") || textLower.contains("nonton") || textLower.contains("tas") || textLower.contains("shopee") || textLower.contains("tokped") || textLower.contains("belanja") ||
                    textLower.contains("rokok") || textLower.contains("tembakau") -> "Hiburan & Belanja"
                textLower.contains("cicilan") || textLower.contains("kredit") || textLower.contains("angsuran") ||
                    textLower.contains("kpr") || textLower.contains("kkb") || textLower.contains("hutang") ||
                    textLower.contains("utang") || textLower.contains("pinjaman") || textLower.contains("nyicil") -> "Cicilan & Pinjaman"
                textLower.contains("sedekah") || textLower.contains("zakat") || textLower.contains("infaq") ||
                    textLower.contains("infak") || textLower.contains("donasi") || textLower.contains("sumbangan") ||
                    textLower.contains("amal") || textLower.contains("kotak amal") -> "Sosial & Donasi"
                textLower.contains("asuransi") || (textLower.contains("premi") && !textLower.contains("premium")) ||
                    textLower.contains("pajak") ||
                    textLower.contains("stnk") || textLower.contains("pbb") || textLower.contains("bpjs") ||
                    textLower.contains("retribusi") -> "Asuransi & Pajak"
                textLower.contains("renovasi") || textLower.contains("upgrade") ||
                    textLower.contains("perbaikan") -> "Lain-lain"
                else -> "Lain-lain"
            }
            return AiTransaction(
                type = Constants.TransactionTypes.EXPENSE,
                category = category,
                amount = amount,
                // r1.4.0: deskripsi tanpa nominal (lihat cabang income).
                description = GeminiService.cleanSuggestionDescription(segText),
                timestamp = nowPlus(detectDateOffset(textLower))
            )
        }

        return null
    }

    /** now + offset (null → now). */
    private fun nowPlus(offset: Long?): Long =
        System.currentTimeMillis() + (offset ?: 0L)

    /**
     * Deteksi PERTANYAAN KEUANGAN di chat (tuning AI r1.2.4) — pesan yang
     * bukan transaksi tapi menanyakan kondisi keuangan ("hari ini sudah keluar
     * berapa?") dijawab berbasis data DB, bukan dibiarkan "tercatat saja".
     * Gate ketat: harus ada kata tanya nominal (berapa/total/saldo) ATAU tanda
     * tanya, DAN kata kunci finansial — supaya "besok makan dimana?" tidak
     * memicu jawaban data.
     */
    internal fun isFinancialQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        if (lower.length < 4 || lower.length > 150) return false
        val moneyQuestion = listOf(
            "berapa", "total", "saldo", "berapa banyak", "berapa sisa", "habis berapa", "sisa uang"
        ).any { lower.contains(it) }
        // "pengeluaran terbesar bulan ini apa" tanpa tanda tanya — kata tanya umum
        // juga dihitung ("apa", "mana", "kapan"). Tetap butuh kata finansial.
        val generalQuestion = listOf(" apa", " mana", " kapan", " berapa").any { lower.contains(it) }
        val hasQuestionMark = lower.contains("?")
        val financial = listOf(
            "uang", "keluar", "masuk", "pengeluaran", "pemasukan", "saldo", "transaksi",
            "rekap", "belanja", "beli", "bayar", "gaji", "arisan", "bensin", "listrik",
            "pulsa", "tabungan", "nabung", "cicilan", "utang", "hutang", "anggaran", "budget"
        ).any { lower.contains(it) }
        return (moneyQuestion || hasQuestionMark || generalQuestion) && financial
    }

    internal fun offlineHeuristicParse(messageText: String, sender: String): AiChatParseResult {
        // Guard false-positive (tuning AI): reminder/rencana BUKAN transaksi —
        // "ingatkan saya beli bakso 15rb" tidak boleh tercatat.
        val textLower = messageText.lowercase()
        // Guard koreksi/pembatalan (audit live 2026-08-14): pesan koreksi
        // ("eh bukan makan 45rb maksudnya 50rb") adalah perbaikan transaksi
        // LAMA, bukan transaksi baru — jangan dicatat. Sebelumnya guard ini
        // hanya di jalur AI online; kini konsisten di jalur offline.
        if (isCorrectionOrCancellation(textLower)) {
            return AiChatParseResult(
                containsTransaction = false,
                aiReply = "Pesan ini terlihat sebagai koreksi/pembatalan, jadi tidak dicatat sebagai transaksi baru. Edit pesan sebelumnya jika perlu."
            )
        }
        if (textLower.contains("ingatkan") || textLower.contains("reminder") ||
            textLower.contains("tolong catat nanti") || textLower.contains("rencana beli")
        ) {
            return AiChatParseResult(
                containsTransaction = false,
                aiReply = "Pesan ini terlihat sebagai pengingat/rencana, jadi tidak dicatat sebagai transaksi."
            )
        }

        val segments = splitTransactionSegments(messageText)
        val transactions = segments.mapNotNull { seg ->
            parseSegment(seg.lowercase(), seg)
        }
        if (transactions.isEmpty()) {
            return AiChatParseResult(
                containsTransaction = false,
                aiReply = "Tercatat dalam ruang obrolan Nyachat."
            )
        }

        // Field tunggal = ringkasan (pertama + total) untuk kompatibilitas UI.
        val first = transactions.first()
        val total = transactions.sumOf { it.amount }
        val reply = if (transactions.size == 1) {
            val label = if (first.type == Constants.TransactionTypes.INCOME) "PEMASUKAN" else "Pengeluaran"
            "$label Rp ${first.amount.toLong()} (${first.category}: ${first.description}) dicatat oleh $sender."
        } else {
            val ringkas = transactions.take(3).joinToString(" + ") {
                "${it.category} Rp ${it.amount.toLong()}"
            }
            "${transactions.size} transaksi dicatat oleh $sender: $ringkas."
        }
        return AiChatParseResult(
            containsTransaction = true,
            type = first.type,
            category = first.category,
            amount = total,
            description = first.description,
            aiReply = reply,
            detectedBy = "HEURISTIK",
            transactions = transactions
        )
    }
}
