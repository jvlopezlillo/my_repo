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
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
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

        // Si quedó un grupo anterior, se elimina.
        if (groupInfo(mgr, ch) != null) {
            suspendCancellableCoroutine<Unit> { c -> mgr.removeGroup(ch, listener(c, "eliminar el grupo anterior")) }
            delay(500)
        }

        suspendCancellableCoroutine<Unit> { c -> mgr.createGroup(ch, listener(c, "crear la red Wi-Fi")) }

        // Esperar a que el grupo esté formado y tenga credenciales.
        repeat(40) {
            val g = groupInfo(mgr, ch)
            val ssid = g?.networkName
            val pass = g?.passphrase
            if (g != null && g.isGroupOwner && !ssid.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                return LinkInfo(ssid, pass, Protocol.DEFAULT_GROUP_OWNER_IP, Protocol.TCP_PORT, token)
            }
            delay(250)
        }
        throw IOException("La red Wi-Fi Direct no llegó a formarse")
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

    private fun listener(c: CancellableContinuation<Unit>, what: String) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (c.isActive) c.resume(Unit)
            }

            override fun onFailure(reason: Int) {
                if (c.isActive) c.resumeWithException(IOException("No se pudo $what (código $reason)"))
            }
        }
}

/**
 * Lado EMISOR: se une a la red del receptor como cliente Wi-Fi.
 * Android 10+: WifiNetworkSpecifier. Android 5-9: WifiConfiguration clásico.
 */
@Suppress("DEPRECATION")
class WifiClientLink(ctx: Context) {
    private val cm = ContextCompat.getSystemService(ctx, ConnectivityManager::class.java)!!
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetId = -1
    private var previousNetId = -1

    suspend fun connect(ssid: String, passphrase: String): Network =
        if (Build.VERSION.SDK_INT >= 29) connectModern(ssid, passphrase) else connectLegacy(ssid, passphrase)

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
