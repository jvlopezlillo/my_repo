package com.example.directtransfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Hay un grupo Wi-Fi Direct antiguo que el sistema no deja eliminar (hace falta reiniciar el Wi-Fi). */
class StaleGroupException(message: String) : IOException(message)

/** Resuelve con null si tuvo éxito, o con el código de error. */
private fun p2pCodeListener(c: CancellableContinuation<Int?>) =
    object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (c.isActive) c.resume(null)
        }

        override fun onFailure(reason: Int) {
            if (c.isActive) c.resume(reason)
        }
    }

/** Estado del Wi-Fi normal (diagnóstico): una conexión a un router en otro canal puede impedir Wi-Fi Direct. */
@Suppress("DEPRECATION")
fun wifiStateDescription(ctx: Context): String {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    if (!wm.isWifiEnabled) return "apagado"
    val i = wm.connectionInfo
    return if (i != null && i.networkId != -1 && i.supplicantState == SupplicantState.COMPLETED)
        "conectado a una red (${i.frequency} MHz)" else "encendido, sin red"
}

/**
 * Lado RECEPTOR: crea una red Wi-Fi Direct propia (el dispositivo actúa como punto de acceso).
 * El sistema genera SSID y contraseña; se leen del grupo una vez formado (funciona desde Android 5).
 */
@SuppressLint("MissingPermission")
class WifiDirectHost(private val ctx: Context) {
    private companion object {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** Limpieza del grupo de la sesión anterior; la siguiente sesión debe esperarla. */
        @Volatile var lastCleanup: Job? = null
    }

    private val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    suspend fun start(token: String, compat24: Boolean = false, log: (String) -> Unit = {}): LinkInfo {
        val mgr = manager ?: throw IOException("Wi-Fi Direct no disponible en este dispositivo")
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) throw IOException("Activa el Wi-Fi (no hace falta estar conectado a ninguna red)")

        lastCleanup?.let {
            if (it.isActive) {
                log("Esperando a que termine la limpieza de la sesión anterior…")
                it.join()
            }
        }

        var ch = mgr.initialize(ctx, Looper.getMainLooper(), null)
        channel = ch
        log("Wi-Fi: ${wifiStateDescription(ctx)} · Wi-Fi Direct: ${p2pState(mgr, ch)}")

        // Un grupo anterior solo se reutiliza si lo creamos nosotros (2,4 GHz). Los grupos del sistema
        // (p. ej. «DIRECT-Ms-…», normalmente en 5 GHz) se eliminan para crear uno propio.
        val existing = groupInfo(mgr, ch)
        if (existing != null) {
            log(
                "Había un grupo anterior: ${existing.networkName} (propietario=${existing.isGroupOwner}, " +
                    "clientes=${existing.clientList.size}, interfaz=${existing.`interface`})",
            )
            readyGroup(mgr, ch, token, onlyOurs = true)?.let {
                log("Se reutiliza (es nuestro, 2,4 GHz)")
                return it
            }
            runCatching { mgr.stopPeerDiscovery(ch, null) }
            runCatching { mgr.cancelConnect(ch, null) }
            delay(500)
            var gone = removeGroupWithRetries(mgr, ch, 6, log)
            if (!gone) {
                // Segundo intento con un canal nuevo (el grupo puede estar ligado a un canal anterior).
                log("Reiniciando el canal Wi-Fi Direct de la app…")
                if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
                ch = mgr.initialize(ctx, Looper.getMainLooper(), null)
                channel = ch
                gone = removeGroupWithRetries(mgr, ch, 4, log)
            }
            if (!gone) {
                throw StaleGroupException(
                    "Hay un grupo Wi-Fi Direct anterior («${existing.networkName}») que Android no deja eliminar. " +
                        "Apaga y vuelve a encender el Wi-Fi de este móvil y pulsa Recibir otra vez. Si sigue igual, reinicia el móvil.",
                )
            }
        }

        // Planes de creación: primero 2,4 GHz forzado (compatible con cualquier móvil), luego banda
        // automática y, por último, sin configuración (el sistema elige nombre y banda).
        fun cfgFor(band: Int): WifiP2pConfig? =
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching {
                    WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-dt-" + Protocol.randomString(4))
                        .setPassphrase(Protocol.randomString(12))
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(band)
                        .build()
                }.getOrNull()
            } else null

        val plans = buildList<Pair<String, WifiP2pConfig?>> {
            if (compat24) {
                // Modo compatible: 2,4 GHz (más lento, pero lo soporta cualquier móvil).
                cfgFor(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)?.let { add("2,4 GHz" to it); add("2,4 GHz" to it) }
                cfgFor(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)?.let { add("banda automática" to it) }
            } else {
                // Modo rápido: el sistema elige la mejor banda (5 GHz si es posible).
                cfgFor(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)?.let { add("banda automática" to it); add("banda automática" to it) }
                cfgFor(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)?.let { add("2,4 GHz" to it) }
            }
            add("sin configuración" to null)
            add("sin configuración" to null)
        }

        var lastCode = -1
        var created = false
        for ((name, cfg) in plans) {
            val err: Int? = suspendCancellableCoroutine { c ->
                if (cfg != null) mgr.createGroup(ch, cfg, p2pCodeListener(c)) else mgr.createGroup(ch, p2pCodeListener(c))
            }
            log("Crear grupo ($name): ${err?.let { describe(it) } ?: "OK"}")
            if (err == null) {
                created = true
                break
            }
            lastCode = err
            if (err == WifiP2pManager.P2P_UNSUPPORTED) break
            delay(1_500)
        }
        if (!created) {
            throw IOException(
                "No se pudo crear la red Wi-Fi Direct (${describe(lastCode)}). " +
                    "Prueba a desactivar y volver a activar el Wi-Fi y a cerrar otras apps que usen Wi-Fi Direct.",
            )
        }

        // Esperar a que el grupo esté formado y tenga credenciales.
        repeat(40) {
            readyGroup(mgr, ch, token)?.let {
                log("Grupo listo: ${it.ssid}" + if (it.ssid.startsWith("DIRECT-dt-")) " (configuración propia)" else " (nombre del sistema)")
                return it
            }
            delay(250)
        }
        throw IOException("La red Wi-Fi Direct no llegó a formarse")
    }

    private suspend fun readyGroup(
        mgr: WifiP2pManager, ch: WifiP2pManager.Channel, token: String, onlyOurs: Boolean = false,
    ): LinkInfo? {
        val g = groupInfo(mgr, ch) ?: return null
        val ssid = g.networkName
        val pass = g.passphrase
        if (!g.isGroupOwner || ssid.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        if (onlyOurs && !ssid.startsWith("DIRECT-dt-")) return null
        return LinkInfo(
            ssid, pass, Protocol.DEFAULT_GROUP_OWNER_IP, Protocol.TCP_PORT, token,
            g.owner?.deviceAddress, deviceDescription(), p2pDeviceName(g),
        )
    }

    private fun p2pDeviceName(g: WifiP2pGroup): String =
        g.owner?.deviceName?.takeIf { it.isNotEmpty() }
            ?: android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
            ?: Build.MODEL

    private fun describe(code: Int) = when (code) {
        WifiP2pManager.ERROR -> "error interno"
        WifiP2pManager.P2P_UNSUPPORTED -> "este dispositivo no soporta Wi-Fi Direct"
        WifiP2pManager.BUSY -> "el sistema está ocupado"
        else -> "código $code"
    }

    /** Elimina el grupo y ESPERA a que desaparezca antes de cerrar el canal (si no, queda un grupo huérfano). */
    fun stop() {
        val mgr = manager ?: return
        val ch = channel ?: return
        channel = null
        lastCleanup = cleanupScope.launch {
            repeat(5) {
                if (groupInfo(mgr, ch) == null) return@repeat
                withTimeoutOrNull(3_000) {
                    suspendCancellableCoroutine<Int?> { c -> mgr.removeGroup(ch, p2pCodeListener(c)) }
                }
                delay(1_000)
            }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
    }

    private suspend fun p2pState(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): String =
        if (Build.VERSION.SDK_INT >= 29) {
            withTimeoutOrNull(2_000) {
                suspendCancellableCoroutine<String> { c ->
                    mgr.requestP2pState(ch) { st ->
                        if (c.isActive) c.resume(
                            when (st) {
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "activado"
                                WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "desactivado"
                                else -> "estado $st"
                            },
                        )
                    }
                }
            } ?: "sin respuesta"
        } else "?"

    private suspend fun removeGroupWithRetries(
        mgr: WifiP2pManager, ch: WifiP2pManager.Channel, tries: Int, log: (String) -> Unit,
    ): Boolean {
        repeat(tries) {
            val err: Int? = suspendCancellableCoroutine { c -> mgr.removeGroup(ch, p2pCodeListener(c)) }
            log("Eliminar grupo anterior: ${err?.let { describe(it) } ?: "OK"}")
            delay(1_000)
            if (groupInfo(mgr, ch) == null) return true
        }
        return false
    }

    private suspend fun groupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { c -> mgr.requestGroupInfo(ch) { g -> if (c.isActive) c.resume(g) } }

    /** Resuelve con null si tuvo éxito, o con el código de error. */
    private fun codeListener(c: CancellableContinuation<Int?>) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (c.isActive) c.resume(null)
            }

            override fun onFailure(reason: Int) {
                if (c.isActive) c.resume(reason)
            }
        }

    /** Resuelve siempre (éxito o fallo): se usa para limpiezas no críticas. */
    private fun ignoringListener(c: CancellableContinuation<Unit>) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (c.isActive) c.resume(Unit)
            }

            override fun onFailure(reason: Int) {
                if (c.isActive) c.resume(Unit)
            }
        }
}

/**
 * Lado EMISOR: se une a la red del receptor.
 *  - Android 10+: primero por Wi-Fi Direct con nombre+clave (sin diálogos); si falla, WifiNetworkSpecifier.
 *  - Android 5-9: WifiConfiguration clásico.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class WifiClientLink(private val ctx: Context) {
    /** Cómo abrir sockets hacia el receptor y qué IPs probar. */
    class Link(val socketFactory: SocketFactory, val hosts: List<String>)

    private val cm = ContextCompat.getSystemService(ctx, ConnectivityManager::class.java)!!
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val p2p = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetId = -1
    private var previousNetId = -1

    suspend fun connect(
        ssid: String,
        passphrase: String,
        fallbackHost: String,
        mac: String?,
        pname: String,
        onStatus: (String) -> Unit,
        log: (String) -> Unit,
    ): Link {
        if (Build.VERSION.SDK_INT >= 29) {
            onStatus("1/3 Uniéndose por Wi-Fi Direct (credenciales)…")
            step("Wi-Fi Direct con credenciales", log) { connectP2p(ssid, passphrase, fallbackHost) }?.let { return it }

            if (mac != null || pname.isNotEmpty()) {
                onStatus("2/3 Wi-Fi Direct clásico… (el receptor puede pedir aceptar la invitación)")
                step("Wi-Fi Direct clásico", log) { connectP2pClassic(mac, pname, fallbackHost) }?.let { return it }
            } else {
                log("Wi-Fi Direct clásico: omitido (el receptor no envió su identificación)")
            }

            onStatus("3/3 Probando conexión Wi-Fi clásica… (Android puede pedir confirmación)")
            step("Wi-Fi clásica (WifiNetworkSpecifier)", log) {
                val network = connectModern(ssid, passphrase)
                Link(network.socketFactory, listOfNotNull(gatewayOf(network), fallbackHost).distinct())
            }?.let { return it }
            throw IOException("No se pudo unir a la red «$ssid» con ninguno de los 3 métodos.")
        }
        log("Android < 10: conexión Wi-Fi clásica")
        val network = connectLegacy(ssid, passphrase)
        return Link(network.socketFactory, listOfNotNull(gatewayOf(network), fallbackHost).distinct())
    }

    private suspend fun step(name: String, log: (String) -> Unit, block: suspend () -> Link): Link? =
        try {
            block().also { log("$name: OK") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$name: ${e.message ?: e.javaClass.simpleName}")
            releaseP2p()
            runCatching { close0() }
            null
        }

    // ------------------------------------------------ Wi-Fi Direct (credenciales)

    @RequiresApi(29)
    private suspend fun connectP2p(ssid: String, pass: String, fallbackHost: String): Link {
        val mgr = p2p ?: throw IOException("Wi-Fi Direct no disponible")
        val ch = mgr.initialize(ctx, Looper.getMainLooper(), null)
        p2pChannel = ch

        // Si este móvil tenía un grupo propio o una conexión previa, se limpia (los fallos no importan).
        runCatching { mgr.stopPeerDiscovery(ch, null) }
        runCatching { mgr.cancelConnect(ch, null) }
        val existing = suspendCancellableCoroutine<WifiP2pGroup?> { c ->
            mgr.requestGroupInfo(ch) { g -> if (c.isActive) c.resume(g) }
        }
        if (existing != null) {
            suspendCancellableCoroutine<Unit> { c ->
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (c.isActive) c.resume(Unit) }
                    override fun onFailure(reason: Int) { if (c.isActive) c.resume(Unit) }
                })
            }
            delay(1_000)
        }

        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
        var lastCode = -1
        var ok = false
        for (attempt in 1..4) {
            val err: Int? = suspendCancellableCoroutine { c ->
                mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (c.isActive) c.resume(null) }
                    override fun onFailure(reason: Int) { if (c.isActive) c.resume(reason) }
                })
            }
            if (err == null) { ok = true; break }
            lastCode = err
            delay(1_500)
        }
        if (!ok) throw IOException("Wi-Fi Direct rechazó la conexión (código $lastCode)")

        val owner = try {
            withTimeout(35_000) { awaitGroupOwner(mgr, ch) }
        } catch (e: TimeoutCancellationException) {
            throw IOException("No se pudo unir al grupo Wi-Fi Direct")
        }
        // En una conexión Wi-Fi Direct el sistema añade la ruta del grupo: sockets normales, sin enlazar a una Network.
        return Link(SocketFactory.getDefault(), listOf(owner, fallbackHost).distinct())
    }

    // ------------------------------------------------ Wi-Fi Direct clásico (descubrir + conectar con invitación)

    @RequiresApi(29)
    private suspend fun connectP2pClassic(mac: String?, pname: String, fallbackHost: String): Link {
        val mgr = p2p ?: throw IOException("Wi-Fi Direct no disponible")
        val ch = p2pChannel ?: mgr.initialize(ctx, Looper.getMainLooper(), null).also { p2pChannel = it }
        runCatching { mgr.cancelConnect(ch, null) }

        val dErr: Int? = suspendCancellableCoroutine { c -> mgr.discoverPeers(ch, p2pCodeListener(c)) }
        if (dErr != null) throw IOException("No se pudo buscar dispositivos Wi-Fi Direct (código $dErr)")

        // Desde Android 10 la dirección propia llega oculta (02:00:00:00:00:00): se empareja por nombre.
        val realMac = mac?.takeIf { it != "02:00:00:00:00:00" }
        var found: WifiP2pDevice? = null
        var seen = ""
        try {
            withTimeout(20_000) {
                while (true) {
                    val peers = suspendCancellableCoroutine<WifiP2pDeviceList?> { c ->
                        mgr.requestPeers(ch) { l -> if (c.isActive) c.resume(l) }
                    }
                    val list = peers?.deviceList.orEmpty()
                    seen = list.joinToString { it.deviceName }
                    found = list.firstOrNull {
                        (realMac != null && it.deviceAddress.equals(realMac, ignoreCase = true)) ||
                            (pname.isNotEmpty() && it.deviceName == pname)
                    }
                    if (found != null) break
                    delay(700)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("el receptor «$pname» no aparece entre los dispositivos Wi-Fi Direct cercanos (vistos: ${seen.ifEmpty { "ninguno" }})")
        }
        val target = found ?: throw IOException("receptor no encontrado")
        runCatching { mgr.stopPeerDiscovery(ch, null) }

        val cfg = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        var lastCode = -1
        var ok = false
        for (attempt in 1..3) {
            val err: Int? = suspendCancellableCoroutine { c -> mgr.connect(ch, cfg, p2pCodeListener(c)) }
            if (err == null) { ok = true; break }
            lastCode = err
            delay(1_500)
        }
        if (!ok) throw IOException("la conexión fue rechazada (código $lastCode)")

        val owner = try {
            withTimeout(60_000) { awaitGroupOwner(mgr, ch) } // incluye el tiempo para aceptar en el receptor
        } catch (e: TimeoutCancellationException) {
            throw IOException("el receptor no aceptó la invitación a tiempo")
        }
        return Link(SocketFactory.getDefault(), listOf(owner, fallbackHost).distinct())
    }

    private suspend fun awaitGroupOwner(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): String {
        while (true) {
            val info = suspendCancellableCoroutine<WifiP2pInfo?> { c ->
                mgr.requestConnectionInfo(ch) { i -> if (c.isActive) c.resume(i) }
            }
            val addr = info?.groupOwnerAddress?.hostAddress
            if (info != null && info.groupFormed && !info.isGroupOwner && addr != null) return addr
            delay(500)
        }
    }

    private fun releaseP2p() {
        val ch = p2pChannel ?: return
        runCatching { p2p?.cancelConnect(ch, null) }
        runCatching { p2p?.removeGroup(ch, null) }
        if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        p2pChannel = null
    }

    // ------------------------------------------------ WifiNetworkSpecifier (Android 10+)

    @RequiresApi(29)
    private suspend fun connectModern(ssid: String, passphrase: String): Network =
        suspendCancellableCoroutine { c ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (c.isActive) c.resume(network)
                }

                override fun onUnavailable() {
                    if (c.isActive) c.resumeWithException(IOException("No se pudo unir a la red Wi-Fi del receptor"))
                }
            }
            callback = cb
            cm.requestNetwork(request, cb, 45_000)
            c.invokeOnCancellation { close() }
        }

    // ------------------------------------------------ Android 5-9

    private suspend fun connectLegacy(ssid: String, passphrase: String): Network {
        if (!wm.isWifiEnabled) throw IOException("Activa el Wi-Fi")
        previousNetId = wm.connectionInfo?.networkId ?: -1
        val quoted = "\"$ssid\""
        val cfg = WifiConfiguration().apply {
            SSID = quoted
            preSharedKey = "\"$passphrase\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        var id = wm.addNetwork(cfg)
        if (id == -1) id = wm.configuredNetworks?.firstOrNull { it.SSID == quoted }?.networkId ?: -1
        if (id == -1) throw IOException("No se pudo configurar la red Wi-Fi del receptor")
        legacyNetId = id
        wm.disconnect()
        wm.enableNetwork(id, true)
        wm.reconnect()

        try {
            withTimeout(40_000) {
                while (true) {
                    val i = wm.connectionInfo
                    if (i != null && i.networkId == id && i.supplicantState == SupplicantState.COMPLETED && i.ipAddress != 0) break
                    delay(500)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("No se pudo unir a la red Wi-Fi del receptor")
        }

        repeat(20) {
            val n = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            if (n != null) return n
            delay(500)
        }
        throw IOException("No se encontró la red Wi-Fi del receptor")
    }

    fun gatewayOf(network: Network): String? =
        cm.getLinkProperties(network)?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress

    private fun close0() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    fun close() {
        releaseP2p()
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        if (legacyNetId != -1) {
            runCatching {
                wm.removeNetwork(legacyNetId)
                if (previousNetId != -1) wm.enableNetwork(previousNetId, true)
                wm.reconnect()
            }
            legacyNetId = -1
        }
    }
}
