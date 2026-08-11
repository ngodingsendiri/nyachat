package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Simpan hanya digit mentah di state ("Rp 50.000" → "50000"). */
internal fun amountDigitsOnly(input: String): String = input.filter { it.isDigit() }

/** Tampilan grouping ribuan id-ID: "50000" → "50.000" (tanpa NumberFormat supaya
 *  stabil & murni — mudah di-unit-test). */
internal fun formatAmountDisplay(digits: String): String {
    if (digits.isEmpty()) return ""
    val sb = StringBuilder()
    val reversed = digits.reversed()
    reversed.forEachIndexed { index, c ->
        sb.append(c)
        if ((index + 1) % 3 == 0 && index + 1 < reversed.length) sb.append('.')
    }
    return sb.reverse().toString()
}

/** Parse teks nominal (mentah maupun berformat) menjadi Double; null jika tidak valid. */
internal fun parseAmount(text: String): Double? {
    val digits = text.replace(".", "")
    return if (digits.isEmpty()) null else digits.toDoubleOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    transaction: FinancialTransaction? = null,
    initialLoggedBy: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (FinancialTransaction) -> Unit
) {
    val isEdit = transaction != null
    var type by remember { mutableStateOf(transaction?.type ?: Constants.TransactionTypes.EXPENSE) }
    // State nominal = digit mentah; grouping ribuan dihitung hanya untuk tampilan
    // (audit P1.4). Prefill edit dibulatkan ke rupiah penuh.
    var amountText by remember {
        mutableStateOf(transaction?.amount?.roundToLong()?.toString() ?: "")
    }
    var description by remember { mutableStateOf(transaction?.description ?: "") }
    val loggedBy = remember {
        // Default netral (L4): jangan asumsi peran "Bendahara" — pakai identitas
        // pengguna aktif (initialLoggedBy) atau label netral bila tidak diketahui.
        transaction?.loggedBy ?: initialLoggedBy ?: Constants.Defaults.LABEL
    }

    val categories = Constants.Categories.ALL

    var selectedCategory by remember(transaction?.id) {
        mutableStateOf(
            transaction?.category ?: if (type == Constants.TransactionTypes.INCOME) Constants.Categories.SALARY else Constants.Categories.GROCERIES
        )
    }
    var expandedCategoryMenu by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current

    // F3 (audit focus order): fokus langsung ke kolom Jumlah saat sheet terbuka —
    // sebelumnya Tab pertama mendarat di chip tipe (perlu 2× Tab untuk ke field).
    // delay kecil supaya field sudah ter-attach & window sheet mendapat fokus.
    val amountFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        amountFocusRequester.requestFocus()
    }

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Constants.Ui.CORNER_XL.dp, topEnd = Constants.Ui.CORNER_XL.dp),
        dragHandle = null,
        // Sheet hidup di area konten (berhenti di atas NavigationBar) — padding
        // navbar bawaan sheet malah membuat celah, jadi dinolkan. Insets IME
        // ditangani windowInsetsPadding di konten bawah.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }

            Text(
                text = stringResource(if (isEdit) R.string.add_dialog_edit_title else R.string.add_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Type Toggle (Pengeluaran vs Pemasukan) — chip berwarna
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = type == Constants.TransactionTypes.EXPENSE,
                    onClick = {
                        type = Constants.TransactionTypes.EXPENSE
                        if (selectedCategory == Constants.Categories.SALARY) selectedCategory = Constants.Categories.GROCERIES
                    },
                    label = { Text(stringResource(R.string.add_type_expense), fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = semantic.expenseBg,
                        selectedLabelColor = semantic.expense,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_dialog_expense_chip")
                )

                FilterChip(
                    selected = type == Constants.TransactionTypes.INCOME,
                    onClick = {
                        type = Constants.TransactionTypes.INCOME
                        if (selectedCategory != Constants.Categories.SALARY) selectedCategory = Constants.Categories.SALARY
                    },
                    label = { Text(stringResource(R.string.add_type_income), fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = semantic.incomeBg,
                        selectedLabelColor = semantic.income,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_dialog_income_chip")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Amount Input — grouping ribuan live + prefix "Rp" (audit P1.4).
            // State menyimpan digit mentah; validasi & simpan men-strip separator.
            val amountVal = parseAmount(amountText) ?: 0.0
            val isAmountInvalid = amountText.isNotBlank() && amountVal <= 0
            OutlinedTextField(
                value = formatAmountDisplay(amountText),
                onValueChange = { amountText = amountDigitsOnly(it) },
                label = { Text(stringResource(R.string.add_amount_label_plain)) },
                leadingIcon = { Text("Rp", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) },
                placeholder = { Text(stringResource(R.string.add_amount_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = isAmountInvalid,
                supportingText = if (isAmountInvalid) {
                    { Text(stringResource(R.string.add_amount_error)) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocusRequester)
                    .testTag("add_dialog_amount_field"),
                singleLine = true,
                shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Description Input
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.add_desc_label)) },
                placeholder = { Text(stringResource(R.string.add_desc_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_dialog_desc_field"),
                singleLine = true,
                shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Dropdown
            ExposedDropdownMenuBox(
                expanded = expandedCategoryMenu,
                onExpandedChange = { expandedCategoryMenu = !expandedCategoryMenu },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedCategory,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.add_category_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryMenu) },
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expandedCategoryMenu,
                    onDismissRequest = { expandedCategoryMenu = false }
                ) {
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat) },
                            // Ikon kategori di dropdown (bonus audit P1.4) — sama
                            // dengan ikon di riwayat rekap.
                            leadingIcon = {
                                Icon(
                                    imageVector = getCategoryIcon(cat),
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                selectedCategory = cat
                                expandedCategoryMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = ::dismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
                ) {
                    Text(stringResource(R.string.add_cancel))
                }

                Button(
                    onClick = {
                        val finalAmount = parseAmount(amountText) ?: 0.0
                        onConfirm(
                            FinancialTransaction(
                                id = transaction?.id ?: 0,
                                type = type,
                                category = selectedCategory,
                                amount = finalAmount,
                                description = description,
                                loggedBy = loggedBy,
                                timestamp = transaction?.timestamp ?: System.currentTimeMillis(),
                                chatMessageId = transaction?.chatMessageId,
                                cloudId = transaction?.cloudId
                            )
                        )
                        onDismiss()
                    },
                    enabled = description.isNotBlank() && amountText.isNotBlank() && (parseAmount(amountText) ?: 0.0) > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_dialog_save_button"),
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
                ) {
                    Text(stringResource(R.string.add_save), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
