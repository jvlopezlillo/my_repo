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
import java.net.NetworkInterface
import java.net.Socket

/** Una red local donde puede haber teles: la Wi-Fi a la que estamos conectados o nuestro punto de acceso. */
data class LocalLink(
    val address: Inet4Address,
    val prefix: Int,
    val iface: String,
    val hotspot: Boolean,
) {
    fun contains(ip: String): Boolean = try {
        val target = java.net.InetAddress.getByName(ip) as? Inet4Address ?: return false
        val p = prefix.coerceIn(8, 32)
        val mask = if (p == 32) -1 else (-1 shl (32 - p))
        (toInt(address) and mask) == (toInt(target) and mask)
    } catch (_: Exception) { false }

    /** Las IP de la /24 alrededor de la nuestra (para escanear). */
    fun neighbours(): List<String> {
        val b = address.address
        val prefix3 = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}."
        return (1..254).map { prefix3 + it }.filter { it != address.hostAddress }
    }

    val label: String get() = "${address.hostAddress}/$prefix (${if (hotspot) "punto de acceso" else "Wi-Fi"} $iface)"

    companion object {
        private fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, x -> (acc shl 8) or (x.toInt() and 0xFF) }
    }
}

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

    companion object {
        private val NON_LOCAL_PREFIXES = listOf(
            "tun", "ppp", "rmnet", "ccmni", "dummy", "ipsec", "clat", "v4-", "wwan", "rev_rmnet",
            "seth", "pdp", "ifb", "ip6tnl", "sit", "gre", "erspan", "ip_vti", "lo",
        )
    }

    @Volatile var localNetwork: Network? = null
        private set

    var onChanged: (() -> Unit)? = null

    /**
     * Si la VPN no permite atar sockets a la Wi-Fi (EPERM), lo recordamos y usamos
     * conexiones normales (funcionan si la VPN deja pasar la red local).
     */
    @Volatile private var bindingAllowed = false
    @Volatile private var bindCheckedAt = 0L

    /**
     * ¿Nos deja Android atar sockets a la Wi-Fi? Cambia al activar o desactivar la VPN, así que
     * se vuelve a comprobar a menudo (la comprobación es instantánea) en vez de recordarla para siempre.
     */
    private fun canBind(net: Network): Boolean {
        val now = System.currentTimeMillis()
        if (now - bindCheckedAt < 1500) return bindingAllowed
        val ok = try {
            DatagramSocket().use { net.bindSocket(it) }
            true
        } catch (_: Exception) { false }
        bindingAllowed = ok
        bindCheckedAt = now
        return ok
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            localNetwork = network
            bindCheckedAt = 0L
            onChanged?.invoke()
        }

        override fun onLost(network: Network) {
            if (localNetwork == network) localNetwork = null
            bindCheckedAt = 0L
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

    /**
     * Todas las redes locales: la Wi-Fi a la que estamos conectados y, si el móvil
     * comparte su conexión (punto de acceso / USB), la red de ese punto de acceso.
     */
    fun localLinks(): List<LocalLink> {
        val out = mutableListOf<LocalLink>()
        val wifiIface = localInterfaceName()
        localIpv4()?.let { (a, p) -> out.add(LocalLink(a, p, wifiIface ?: "", hotspot = false)) }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback || ni.name == wifiIface) continue
                // Fuera: VPN, datos móviles y interfaces internas.
                if (NON_LOCAL_PREFIXES.any { ni.name.startsWith(it) }) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    if (!a.isSiteLocalAddress) continue
                    if (out.any { it.address == a }) continue
                    out.add(LocalLink(a, ia.networkPrefixLength.toInt(), ni.name, hotspot = true))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** La red local en la que está esa IP (o la primera que tengamos). */
    fun linkFor(ip: String): LocalLink? {
        val links = localLinks()
        return links.firstOrNull { it.contains(ip) } ?: links.firstOrNull()
    }

    private fun inWifi(host: String): Boolean {
        val wifi = localIpv4() ?: return true
        return LocalLink(wifi.first, wifi.second, "", false).contains(host)
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
        localNetwork?.takeIf { inWifi(host) && canBind(it) }?.let { net ->
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
        return localNetwork?.takeIf { inWifi(u.host) && canBind(it) }?.let { net ->
            try { net.openConnection(u) } catch (_: Exception) { null }
        } ?: u.openConnection()
    }
}
