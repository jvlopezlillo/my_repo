package com.example.directtransfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lado RECEPTOR: crea una red Wi-Fi Direct propia (el dispositivo actúa como punto de acceso)
 * con SSID y contraseña aleatorios.
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

        // Si quedó un grupo anterior, se elimina.
        if (groupInfo(mgr, ch) != null) {
            suspendCancellableCoroutine<Unit> { c -> mgr.removeGroup(ch, listener(c, "eliminar grupo anterior")) }
            delay(500)
        }

        val ssid = "DIRECT-dt-" + Protocol.randomString(4)
        val pass = Protocol.randomString(12)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(ssid)
            .setPassphrase(pass)
            .enablePersistentMode(false)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()
        suspendCancellableCoroutine<Unit> { c -> mgr.createGroup(ch, config, listener(c, "crear la red Wi-Fi")) }

        // Esperar a que el grupo esté realmente formado.
        repeat(24) {
            val g = groupInfo(mgr, ch)
            if (g != null && g.isGroupOwner) {
                return LinkInfo(
                    g.networkName ?: ssid, g.passphrase ?: pass,
                    Protocol.DEFAULT_GROUP_OWNER_IP, Protocol.TCP_PORT, token,
                )
            }
            delay(250)
        }
        throw IOException("La red Wi-Fi Direct no llegó a formarse")
    }

    fun stop() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.removeGroup(ch, null) }
        runCatching { ch.close() }
        channel = null
    }

    private suspend fun groupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { c -> mgr.requestGroupInfo(ch) { g -> if (c.isActive) c.resume(g) } }

    private fun listener(c: kotlinx.coroutines.CancellableContinuation<Unit>, what: String) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (c.isActive) c.resume(Unit)
            }

            override fun onFailure(reason: Int) {
                if (c.isActive) c.resumeWithException(IOException("No se pudo $what (código $reason)"))
            }
        }
}

/** Lado EMISOR: se une a la red creada por el receptor como un cliente Wi-Fi normal. */
class WifiClientLink(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun connect(ssid: String, passphrase: String): Network = suspendCancellableCoroutine { c ->
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

    fun gatewayOf(network: Network): String? =
        cm.getLinkProperties(network)?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress

    fun close() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
