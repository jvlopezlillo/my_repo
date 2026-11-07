package com.example.directtransfer

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class Screen { HOME, SEND, RECEIVE }

data class UiState(
    val screen: Screen = Screen.HOME,
    val items: List<SendItem> = emptyList(),
    val devices: List<DiscoveredDevice> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val progress: Progress? = null,
    val incoming: IncomingRequest? = null,
    val finished: Boolean = false,
    val error: String? = null,
)

class TransferViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var scanner: BleScanner? = null
    private var bleHost: BleHost? = null
    private var wifiHost: WifiDirectHost? = null
    private var wifiLink: WifiClientLink? = null
    private var receiver: FileReceiver? = null
    private var sender: FileSender? = null
    private var decision: CompletableDeferred<Boolean>? = null

    private val deviceName: String
        get() = Settings.Global.getString(getApplication<Application>().contentResolver, "device_name")
            ?: Build.MODEL

    // ---------------------------------------------------------------- navegación

    fun openSend() = _state.update { UiState(screen = Screen.SEND, items = it.items) }

    fun goHome() {
        cancelAll()
        _state.value = UiState()
    }

    // ---------------------------------------------------------------- EMISOR

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { ItemCollector.fromFiles(getApplication<Application>(), uris) }
            _state.update { it.copy(items = it.items + found) }
        }
    }

    fun addFolder(tree: Uri?) {
        if (tree == null) return
        viewModelScope.launch {
            _state.update { it.copy(status = "Leyendo carpeta…") }
            val found = withContext(Dispatchers.IO) { ItemCollector.fromTree(getApplication<Application>(), tree) }
            _state.update {
                it.copy(
                    items = it.items + found, status = "",
                    error = if (found.isEmpty()) "La carpeta no contiene archivos" else null,
                )
            }
        }
    }

    fun clearItems() = _state.update { it.copy(items = emptyList()) }

    fun startScan() {
        stopScan()
        _state.update { it.copy(devices = emptyList(), error = null) }
        try {
            scanner = BleScanner(getApplication<Application>()) { dev ->
                _state.update { s -> s.copy(devices = s.devices.filter { it.address != dev.address } + dev) }
            }.also { it.start() }
            _state.update { it.copy(scanning = true) }
        } catch (e: Exception) {
            _state.update { it.copy(scanning = false, error = e.message ?: "No se pudo buscar dispositivos") }
        }
    }

    fun stopScan() {
        scanner?.stop()
        scanner = null
        _state.update { it.copy(scanning = false) }
    }

    fun sendTo(device: DiscoveredDevice) {
        if (_state.value.items.isEmpty()) {
            _state.update { it.copy(error = "Elige primero archivos o una carpeta") }
            return
        }
        stopScan()
        val items = _state.value.items
        _state.update {
            it.copy(busy = true, error = null, finished = false, progress = null,
                status = "Conectando por Bluetooth con ${device.name}…")
        }
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val info = readLinkWithRetry(device)
                _state.update { it.copy(status = "Uniéndose a la red Wi-Fi del receptor… (Android puede pedir confirmación)") }
                val link = WifiClientLink(app).also { wifiLink = it }
                val network = link.connect(info.ssid, info.passphrase)
                _state.update { it.copy(status = "Esperando que el otro dispositivo acepte…") }
                val s = FileSender(app).also { sender = it }
                s.send(
                    items, info, network, link.gatewayOf(network), deviceName,
                    onAccepted = { _state.update { st -> st.copy(status = "Enviando…") } },
                    onProgress = { p -> _state.update { st -> st.copy(progress = p) } },
                )
                _state.update { it.copy(finished = true, busy = false, status = "Envío completado") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) fail(e) // si se canceló, los errores de socket son esperados
            } finally {
                sender = null
                wifiLink?.close()
                wifiLink = null
            }
        }
    }

    private suspend fun readLinkWithRetry(device: DiscoveredDevice): LinkInfo {
        var last: Exception? = null
        repeat(3) {
            try {
                return LinkInfo.fromJson(withTimeout(15_000) { readLinkInfo(getApplication<Application>(), device.device) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(800) // el stack BLE de Android falla a veces con el estado 133
            }
        }
        throw last ?: java.io.IOException("No se pudo leer la información del receptor")
    }

    // ---------------------------------------------------------------- RECEPTOR

    fun startReceiving() {
        cancelAll()
        _state.value = UiState(screen = Screen.RECEIVE, busy = true, status = "Creando red Wi-Fi propia…")
        job = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val token = Protocol.randomString(16)
                val link = WifiDirectHost(app).also { wifiHost = it }.start(token)
                val rx = FileReceiver(app, token, Protocol.TCP_PORT).also { receiver = it }
                rx.open()
                _state.update { it.copy(status = "Activando Bluetooth…") }
                BleHost(app).also { bleHost = it }.start(link.toJson())
                _state.update { it.copy(status = "Visible como «$deviceName». Esperando al emisor…") }

                val result = rx.receive(
                    onRequest = { req ->
                        val d = CompletableDeferred<Boolean>().also { decision = it }
                        _state.update { it.copy(incoming = req, status = "Petición de transferencia") }
                        val ok = d.await()
                        _state.update {
                            it.copy(
                                incoming = null,
                                status = if (ok) "Recibiendo…" else "Rechazada. Esperando al emisor…",
                            )
                        }
                        ok
                    },
                    onProgress = { p -> _state.update { st -> st.copy(progress = p) } },
                )
                _state.update {
                    it.copy(
                        finished = true, busy = false,
                        status = "Recibidos ${result.files} archivo(s) (${formatBytes(result.bytes)}) en " +
                            "Descargas/${FileReceiver.RECEIVE_DIR}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) fail(e) // si se canceló, los errores de socket son esperados
            } finally {
                stopReceiverSide()
            }
        }
    }

    fun answerRequest(accept: Boolean) {
        decision?.complete(accept)
    }

    // ---------------------------------------------------------------- común

    fun cancelTransfer() {
        cancelAll()
        _state.update { it.copy(busy = false, progress = null, incoming = null, status = "Cancelado") }
    }

    private fun fail(e: Exception) {
        _state.update {
            it.copy(busy = false, incoming = null, error = e.message ?: e.javaClass.simpleName, status = "")
        }
    }

    private fun stopReceiverSide() {
        bleHost?.stop(); bleHost = null
        wifiHost?.stop(); wifiHost = null
        receiver?.abort(); receiver = null
        decision?.complete(false); decision = null
    }

    private fun cancelAll() {
        stopScan()
        job?.cancel()
        job = null
        sender?.abort()
        receiver?.abort()
        stopReceiverSide()
        wifiLink?.close(); wifiLink = null
    }

    override fun onCleared() {
        cancelAll()
    }
}
