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
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lado RECEPTOR: crea una red Wi-Fi Direct propia (el dispositivo actúa como punto de acceso).
 * El sistema genera SSID y contraseña; se leen del grupo una vez formado (funciona desde Android 5).
 */
@SuppressLint("MissingPermission")
class WifiDirectHost(private val ctx: Context) {
    private val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    suspend fun start(token: String): LinkInfo {
        val mgr = manager ?: throw IOException("Wi-Fi Direct no disponible en este dispositivo")
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) throw IOException("Activa el Wi-Fi (no hace falta estar conectado a ninguna red)")

        val ch = mgr.initialize(ctx, Looper.getMainLooper(), null)
        channel = ch

        // ¿Ya hay un grupo nuestro utilizable (de un intento anterior)? Se reutiliza sin tocarlo.
        readyGroup(mgr, ch, token)?.let { return it }

        // Limpieza de estado previo. Los fallos aquí NO son fatales (el sistema suele responder BUSY).
        runCatching { mgr.stopPeerDiscovery(ch, null) }
        runCatching { mgr.cancelConnect(ch, null) }
        if (groupInfo(mgr, ch) != null) {
            suspendCancellableCoroutine<Unit> { c -> mgr.removeGroup(ch, ignoringListener(c)) }
            delay(1_000)
        }

        // Android 10+: grupo forzado a 2,4 GHz (compatible con cualquier móvil; en 5 GHz algunos no se unen).
        val cfg: WifiP2pConfig? = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                WifiP2pConfig.Builder()
                    .setNetworkName("DIRECT-dt-" + Protocol.randomString(4))
                    .setPassphrase(Protocol.randomString(12))
                    .enablePersistentMode(false)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
            }.getOrNull()
        } else null

        // Crear el grupo; si el sistema está ocupado se reintenta. Los últimos intentos van sin configuración.
        var lastCode = -1
        var created = false
        for (attempt in 1..6) {
            val useCfg = cfg != null && attempt <= 3
            val err: Int? = suspendCancellableCoroutine { c ->
                if (useCfg) mgr.createGroup(ch, cfg, codeListener(c)) else mgr.createGroup(ch, codeListener(c))
            }
            if (err == null) {
                created = true
                break
            }
            lastCode = err
            if (err == WifiP2pManager.P2P_UNSUPPORTED) break
            readyGroup(mgr, ch, token)?.let { return it } // puede haberse formado a pesar del error
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
            readyGroup(mgr, ch, token)?.let { return it }
            delay(250)
        }
        throw IOException("La red Wi-Fi Direct no llegó a formarse")
    }

    private suspend fun readyGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, token: String): LinkInfo? {
        val g = groupInfo(mgr, ch) ?: return null
        val ssid = g.networkName
        val pass = g.passphrase
        if (!g.isGroupOwner || ssid.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        return LinkInfo(ssid, pass, Protocol.DEFAULT_GROUP_OWNER_IP, Protocol.TCP_PORT, token)
    }

    private fun describe(code: Int) = when (code) {
        WifiP2pManager.ERROR -> "error interno"
        WifiP2pManager.P2P_UNSUPPORTED -> "este dispositivo no soporta Wi-Fi Direct"
        WifiP2pManager.BUSY -> "el sistema está ocupado"
        else -> "código $code"
    }

    fun stop() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.removeGroup(ch, null) }
        if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        channel = null
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

    suspend fun connect(ssid: String, passphrase: String, fallbackHost: String, onStatus: (String) -> Unit): Link {
        if (Build.VERSION.SDK_INT >= 29) {
            onStatus("Uniéndose al grupo Wi-Fi Direct del receptor…")
            var p2pError: String? = null
            val first = try {
                connectP2p(ssid, passphrase, fallbackHost)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                p2pError = e.message ?: e.javaClass.simpleName
                releaseP2p()
                null
            }
            if (first != null) return first
            onStatus("Probando conexión Wi-Fi clásica… (Android puede pedir confirmación)")
            try {
                val network = connectModern(ssid, passphrase)
                return Link(network.socketFactory, listOfNotNull(gatewayOf(network), fallbackHost).distinct())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IOException(
                    "No se pudo unir a la red «$ssid».\n• Wi-Fi Direct: $p2pError\n• Wi-Fi clásica: ${e.message}",
                )
            }
        }
        val network = connectLegacy(ssid, passphrase)
        return Link(network.socketFactory, listOfNotNull(gatewayOf(network), fallbackHost).distinct())
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
