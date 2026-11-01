package com.castbrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sigue la red Wi-Fi (o Ethernet) local por separado de la VPN.
 *
 * Con una VPN activa, Android manda todo el tráfico por el túnel. Para llegar
 * al Chromecast de casa intentamos, en este orden:
 *  1. Atar el socket a la red Wi-Fi (funciona si la VPN permite "bypass").
 *  2. Socket normal (funciona si la VPN deja fuera la red local / LAN).
 */
class NetworkHelper(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var localNetwork: Network? = null
        private set

    var onChanged: (() -> Unit)? = null

    /**
     * Si la VPN no permite atar sockets a la Wi-Fi (EPERM), lo recordamos y usamos
     * conexiones normales (funcionan si la VPN deja pasar la red local).
     */
    @Volatile private var bindingAllowed: Boolean? = null

    private fun canBind(net: Network): Boolean {
        bindingAllowed?.let { return it }
        val ok = try {
            DatagramSocket().use { net.bindSocket(it) }
            true
        } catch (_: Exception) { false }
        bindingAllowed = ok
        return ok
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            localNetwork = network
            bindingAllowed = null
            onChanged?.invoke()
        }

        override fun onLost(network: Network) {
            if (localNetwork == network) localNetwork = null
            bindingAllowed = null
            onChanged?.invoke()
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (localNetwork == network) onChanged?.invoke()
        }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm.registerNetworkCallback(req, callback) } catch (_: Exception) {}
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    fun isVpnActive(): Boolean = try {
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (_: Exception) {
        false
    }

    /** IPv4 local y longitud de prefijo de la Wi-Fi, p. ej. 192.168.1.34/24. */
    fun localIpv4(): Pair<Inet4Address, Int>? {
        val net = localNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return (la.address as Inet4Address) to la.prefixLength
    }

    fun localInterfaceName(): String? {
        val net = localNetwork ?: return null
        return cm.getLinkProperties(net)?.interfaceName
    }

    /** Ata un socket UDP a la Wi-Fi si es posible. */
    fun bindToLocal(socket: DatagramSocket): Boolean {
        val net = localNetwork ?: return false
        if (!canBind(net)) return false
        return try { net.bindSocket(socket); true } catch (_: Exception) { false }
    }

    /** Abre una conexión TCP al dispositivo, probando primero por la Wi-Fi. */
    fun connect(host: String, port: Int, timeoutMs: Int = 4000): Socket {
        var last: Exception? = null
        localNetwork?.takeIf { canBind(it) }?.let { net ->
            try {
                val s = net.socketFactory.createSocket()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                return s
            } catch (e: Exception) { last = e }
        }
        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return s
        } catch (e: Exception) { last = e }
        throw last ?: java.io.IOException("Sin red local")
    }

    fun isReachable(host: String, port: Int, timeoutMs: Int): Boolean = try {
        connect(host, port, timeoutMs).close(); true
    } catch (_: Exception) {
        false
    }

    /** Abre una URL HTTP en la red local (para leer el nombre del Chromecast). */
    fun openLocal(url: String): java.net.URLConnection {
        val u = java.net.URL(url)
        return localNetwork?.takeIf { canBind(it) }?.let { net ->
            try { net.openConnection(u) } catch (_: Exception) { null }
        } ?: u.openConnection()
    }
}
