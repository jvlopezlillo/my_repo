package com.example.directtransfer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/** Envoltorio fino: la lógica vive en [TransferEngine] para sobrevivir a la Activity. */
class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = TransferEngine.get(app)
    val state: StateFlow<UiState> = engine.state

    fun openSend() = engine.openSend()
    fun setCompat24(on: Boolean) = engine.setCompat24(on)
    fun goHome() = engine.goHome()
    fun addFiles(uris: List<Uri>) = engine.addFiles(uris)
    fun addFolder(tree: Uri?) = engine.addFolder(tree)
    fun clearItems() = engine.clearItems()
    fun startScan() = engine.startScan()
    fun stopScan() = engine.stopScan()
    fun sendTo(device: DiscoveredDevice) = engine.sendTo(device)
    fun resumeSend() = engine.resumeSend()
    fun startReceiving() = engine.startReceiving()
    fun answerRequest(accept: Boolean) = engine.answerRequest(accept)
    fun cancelTransfer() = engine.cancelTransfer()
}
