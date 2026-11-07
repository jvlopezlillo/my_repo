package com.example.directtransfer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

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
    /** El último envío se interrumpió y puede reanudarse con el mismo dispositivo. */
    val canResume: Boolean = false,
    /** Registro de pasos de la conexión (diagnóstico). */
    val log: List<String> = emptyList(),
    /** Hay un grupo Wi-Fi Direct atascado: el usuario debe apagar y encender el Wi-Fi. */
    val wifiReset: Boolean = false,
)

/**
 * Toda la lógica de la transferencia. Es un singleton con su propio scope (no depende de la Activity),
 * y [TransferService] lo mantiene vivo en segundo plano mientras haya una transferencia en curso.
 */
class TransferEngine private constructor(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    private var lastDevice: DiscoveredDevice? = null

    private val deviceName: String
        get() = Settings.Global.getString(app.contentResolver, "device_name") ?: Build.MODEL

    /** Android 6-12 exige que la ubicación del sistema esté activada para escanear BLE / usar Wi-Fi Direct. */
    private fun locationProblem(): String? {
        if (Build.VERSION.SDK_INT !in 23..32) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return if (LocationManagerCompat.isLocationEnabled(lm)) null
        else "Activa la ubicación del móvil (ajustes rápidos). Android la exige para encontrar dispositivos cercanos; la app no la usa."
    }

    private fun addLog(msg: String) = _state.update { it.copy(log = it.log + msg) }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java)) }
    }

    // ---------------------------------------------------------------- navegación

    fun openSend() = _state.update { UiState(screen = Screen.SEND, items = it.items) }

    fun goHome() {
        cancelAll()
        _state.value = UiState()
    }

    // ---------------------------------------------------------------- EMISOR

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val found = withContext(Dispatchers.IO) { ItemCollector.fromFiles(app, uris) }
            _state.update { it.copy(items = it.items + found, canResume = false) }
        }
    }

    fun addFolder(tree: Uri?) {
        if (tree == null) return
        scope.launch {
            _state.update { it.copy(status = "Leyendo carpeta…") }
            val found = withContext(Dispatchers.IO) { ItemCollector.fromTree(app, tree) }
            _state.update {
                it.copy(
                    items = it.items + found, status = "", canResume = false,
                    error = if (found.isEmpty()) "La carpeta no contiene archivos" else null,
                )
            }
        }
    }

    fun clearItems() = _state.update { it.copy(items = emptyList(), canResume = false) }

    fun startScan() {
        stopScan()
        _state.update { it.copy(devices = emptyList(), error = null) }
        locationProblem()?.let { msg ->
            _state.update { it.copy(error = msg) }
            return
        }
        try {
            scanner = BleScanner(
                app,
                onError = { msg -> _state.update { it.copy(scanning = false, error = msg) } },
            ) { dev ->
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

    fun resumeSend() {
        lastDevice?.let { sendTo(it) }
    }

    fun sendTo(device: DiscoveredDevice) {
        if (_state.value.items.isEmpty()) {
            _state.update { it.copy(error = "Elige primero archivos o una carpeta") }
            return
        }
        stopScan()
        lastDevice = device
        val items = _state.value.items
        _state.update {
            it.copy(busy = true, error = null, finished = false, progress = null, canResume = false,
                log = listOf("Emisor: ${deviceDescription()}", "Wi-Fi emisor: ${wifiStateDescription(app)}"),
                status = "Conectando por Bluetooth con ${device.name}…")
        }
        startService()
        job = scope.launch {
            try {
                val info = readLinkWithRetry(device)
                addLog("Receptor: ${info.dev.ifEmpty { "?" }} · red ${info.ssid} · dir ${info.mac ?: "?"}")
                val link = WifiClientLink(app).also { wifiLink = it }
                val conn = link.connect(
                    info.ssid, info.passphrase, info.host, info.mac, info.pname,
                    onStatus = { msg -> _state.update { it.copy(status = msg) } },
                    log = { msg -> addLog(msg) },
                )
                _state.update { it.copy(status = "Esperando que el otro dispositivo acepte…") }
                val s = FileSender(app).also { sender = it }
                s.send(
                    items, info, conn.socketFactory, conn.hosts, deviceName,
                    onAccepted = { resumed ->
                        _state.update { st ->
                            st.copy(status = if (resumed > 0) "Reanudando (${formatBytes(resumed)} ya enviados)…" else "Enviando…")
                        }
                    },
                    onProgress = { p -> _state.update { st -> st.copy(progress = p) } },
                )
                _state.update { it.copy(finished = true, busy = false, status = "Envío completado") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    fail(e, canResume = e !is RejectedException)
                }
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
                return LinkInfo.fromJson(withTimeout(15_000) { readLinkInfo(app, device.device) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(800) // el stack BLE de Android falla a veces con el estado 133
            }
        }
        throw last ?: IOException("No se pudo leer la información del receptor")
    }

    // ---------------------------------------------------------------- RECEPTOR

    fun startReceiving() {
        cancelAll()
        locationProblem()?.let { msg ->
            _state.value = UiState(screen = Screen.RECEIVE, error = msg)
            return
        }
        _state.value = UiState(
            screen = Screen.RECEIVE, busy = true, status = "Creando red Wi-Fi propia…",
            log = listOf("Receptor: ${deviceDescription()}"),
        )
        startService()
        job = scope.launch {
            try {
                val token = Protocol.randomString(16)
                val link = WifiDirectHost(app).also { wifiHost = it }.start(token) { msg -> addLog(msg) }
                val rx = FileReceiver(app, token, Protocol.TCP_PORT).also { receiver = it }
                rx.open()
                _state.update { it.copy(status = "Activando Bluetooth…") }
                BleHost(app).also { bleHost = it }.start(link.toJson())
                _state.update { it.copy(status = "Visible como «$deviceName» (red ${link.ssid}). Esperando al emisor…") }

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
                    onInterrupted = { msg ->
                        if (job?.isActive == true) {
                            _state.update {
                                it.copy(
                                    incoming = null,
                                    status = "Transferencia interrumpida ($msg). Los datos recibidos se conservan: " +
                                        "el emisor puede pulsar «Reanudar».",
                                )
                            }
                        }
                    },
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
                if (isActive) fail(e, canResume = false)
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
        val resumable = _state.value.progress != null && lastDevice != null && _state.value.screen == Screen.SEND
        cancelAll()
        _state.update {
            it.copy(busy = false, incoming = null, status = "Cancelado", canResume = resumable)
        }
    }

    private fun fail(e: Exception, canResume: Boolean) {
        _state.update {
            it.copy(
                busy = false, incoming = null, canResume = canResume, status = "",
                wifiReset = e is StaleGroupException,
                error = (e.message ?: e.javaClass.simpleName) +
                    if (canResume) "\nPuedes reanudar: lo ya enviado no se repite." else "",
            )
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

    companion object {
        @Volatile private var instance: TransferEngine? = null

        fun get(app: Application): TransferEngine =
            instance ?: synchronized(this) { instance ?: TransferEngine(app).also { instance = it } }
    }
}
