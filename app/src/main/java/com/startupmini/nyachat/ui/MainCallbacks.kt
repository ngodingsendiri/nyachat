package com.startupmini.nyachat.ui

import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction

/**
 * TASK-1.3.2 — ekstraksi callback kelompok layar dari MainActivity (no behavior change).
 *
 * [ChatCallbacks] = semua aksi yang dipicu dari ChatScreen;
 * [RekapCallbacks] = semua aksi yang dipicu dari RekapScreen.
 *
 * Factory `build*Callbacks(...)` merangkum wiring lambdas yang sebelumnya inline
 * di MainActivity. State UI (editTarget, dialog, reset chat) tetap dipegang
 * MainActivity — factory hanya menerima setter-nya supaya layar tidak bocor ke
 * detail ViewModel/state.
 */
interface ChatCallbacks {
    fun onSendMessage(
        text: String,
        imagePath: String?,
        filePath: String?,
        fileName: String?,
        replyToSender: String?,
        replyToText: String?
    )
    fun onEditMessage(id: Long, newText: String)
    fun onAskAiClicked(prompt: String)
    fun onDeleteMessage(id: Long)
    fun onOpenTransaction(message: ChatMessage)
}

interface RekapCallbacks {
    fun onGenerateAudit()
    fun onGenerateMonthly()
    fun onAddTransactionClicked()
    fun onDeleteTransaction(transaction: FinancialTransaction)
    fun onEditTransaction(transaction: FinancialTransaction)
}

/** Wiring aksi ChatScreen — lihat MainActivity untuk pemakaian state UI. */
fun buildChatCallbacks(
    viewModel: MainViewModel,
    txBySourceCloudId: Map<String, FinancialTransaction>,
    txByChatMessageId: Map<Long, FinancialTransaction>,
    onEditTarget: (FinancialTransaction?) -> Unit,
    onSetResetChatOnDialogClose: (Boolean) -> Unit,
    onSetShowAddDialog: (Boolean) -> Unit,
    showSnack: (String, String?, (() -> Unit)?) -> Unit,
    chatTransactionNotFoundMessage: String
): ChatCallbacks = object : ChatCallbacks {
    override fun onSendMessage(
        text: String,
        imagePath: String?,
        filePath: String?,
        fileName: String?,
        replyToSender: String?,
        replyToText: String?
    ) {
        viewModel.sendMessage(text, imagePath, filePath, fileName, replyToSender, replyToText)
    }

    override fun onEditMessage(id: Long, newText: String) {
        viewModel.editMessage(id, newText)
    }

    override fun onAskAiClicked(prompt: String) {
        viewModel.askAiInChat(prompt)
    }

    override fun onDeleteMessage(id: Long) {
        viewModel.deleteChatMessage(id)
    }

    override fun onOpenTransaction(message: ChatMessage) {
        // tap badge finansial (item 5): cari transaksi terkait lalu buka dialog
        // edit. Cross-device: di perangkat lain, id lokal Room berbeda sehingga
        // fallback chatMessageId gagal. Transaksi menyimpan sourceMessageCloudId
        // = cloudId pesan asal → cari transaksi dengan sourceMessageCloudId ==
        // cloudId pesan kamu. M8: lookup via Map indeks O(1), bukan scan linear.
        val tx = message.cloudId?.let { msgCloudId ->
            txBySourceCloudId[msgCloudId]
        } ?: txByChatMessageId[message.id]
        if (tx != null) {
            onEditTarget(tx)
            // BUG-08: dialog dibuka dari tab Chat → reset input saat ditutup.
            onSetResetChatOnDialogClose(true)
            onSetShowAddDialog(true)
        } else {
            showSnack(chatTransactionNotFoundMessage, null, null)
        }
    }
}

/** Wiring aksi RekapScreen — lihat MainActivity untuk pemakaian state UI. */
fun buildRekapCallbacks(
    viewModel: MainViewModel,
    onEditTarget: (FinancialTransaction?) -> Unit,
    onSetResetChatOnDialogClose: (Boolean) -> Unit,
    onSetShowAddDialog: (Boolean) -> Unit
): RekapCallbacks = object : RekapCallbacks {
    override fun onGenerateAudit() {
        viewModel.generateAiAuditReport()
    }

    override fun onGenerateMonthly() {
        viewModel.generateMonthlyAnalysis()
    }

    override fun onAddTransactionClicked() {
        onEditTarget(null)
        // BUG-08: dialog dari tab Rekap — jangan reset draf chat.
        onSetResetChatOnDialogClose(false)
        onSetShowAddDialog(true)
    }

    override fun onDeleteTransaction(transaction: FinancialTransaction) {
        viewModel.deleteTransaction(transaction)
    }

    override fun onEditTransaction(transaction: FinancialTransaction) {
        onEditTarget(transaction)
        // BUG-08: dialog dari tab Rekap — jangan reset draf chat.
        onSetResetChatOnDialogClose(false)
        onSetShowAddDialog(true)
    }
}
