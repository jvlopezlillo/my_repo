package com.castbrowser

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CastDevice(
    val key: String,
    val name: String,
    val ip: String,
    val port: Int = 8009,
    val model: String? = null,
    val lastSeen: Long = 0,
    val via: String = "",
    /** cast | dlna | tv (nuestra app receptora) */
    val type: String = TYPE_CAST,
    val dlna: DlnaInfo? = null,
) {
    val available: Boolean get() = System.currentTimeMillis() - lastSeen < 3 * 60_000

    val typeLabel: String
        get() = when (type) {
            TYPE_DLNA -> "DLNA"
            TYPE_TV -> "App CastBrowser TV"
            else -> "Chromecast"
        }

    val icon: String
        get() = when (type) {
            TYPE_DLNA -> "📡"
            TYPE_TV -> "🟦"
            else -> "📺"
        }

    companion object {
        const val TYPE_CAST = "cast"
        const val TYPE_DLNA = "dlna"
        const val TYPE_TV = "tv"
    }
}

/**
 * Busca Chromecast de varias formas a la vez, para que funcione también con VPN:
 *  - mDNS por multicast, enviado explícitamente por la interfaz Wi-Fi.
 *  - NsdManager del sistema.
 *  - Comprobación directa de los Chromecast recordados (IP guardada).
 *  - Escaneo de la subred Wi-Fi (mDNS unicast + puerto 8009) si no aparece nada.
 */
class DeviceDiscovery(private val context: Context, private val net: NetworkHelper) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val devices = ConcurrentHashMap<String, CastDevice>()
    private val prefs = context.getSharedPreferences("cast_devices", Context.MODE_PRIVATE)
    private val running = AtomicBoolean(false)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var nsdTvListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = java.util.concurrent.LinkedBlockingQueue<NsdServiceInfo>()

    @Volatile var lastScan = 0L; private set
    @Volatile var scanning = false; private set

    /** Se llama en el hilo principal cuando cambia la lista. */
    var onDevicesChanged: (() -> Unit)? = null

    init {
        loadRemembered()
    }

    fun availableDevices(): List<CastDevice> =
        devices.values.filter { it.available }.sortedBy { it.name.lowercase() }

    fun allDevices(): List<CastDevice> = devices.values.sortedBy { it.name.lowercase() }

    fun start() {
        startNsd()
        Thread({
            while (true) {
                val r = resolveQueue.take()
                resolveBlocking(r)
            }
        }, "nsd-resolve").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { nsdListener?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { nsdTvListener?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        nsdListener = null
        nsdTvListener = null
    }

    /** Lanza una búsqueda completa (si no hay otra en curso). */
    fun discoverNow(forceSubnetScan: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        scanning = true
        notifyChanged()
        io.execute {
            val lock = try {
                wifi?.createMulticastLock("castbrowser")?.apply { setReferenceCounted(false); acquire() }
            } catch (_: Exception) { null }
            try {
                val tasks = listOf(
                    io.submit { mdnsMulticast() },
                    io.submit { ssdpSearch() },
                    io.submit { checkRemembered() },
                )
                tasks.forEach { try { it.get(8, TimeUnit.SECONDS) } catch (_: Exception) {} }
                // Con punto de acceso suele haber pocos aparatos y el multicast falla más: escaneamos.
                val hotspots = net.localLinks().filter { it.hotspot }
                val hotspotEmpty = hotspots.isNotEmpty() &&
                    availableDevices().none { d -> hotspots.any { it.contains(d.ip) } }
                if (forceSubnetScan || availableDevices().isEmpty() || hotspotEmpty) subnetScan()
                if (nsdListener == null) main.post { startNsd() }
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                lastScan = System.currentTimeMillis()
                scanning = false
                running.set(false)
                notifyChanged()
            }
        }
    }

    /** Añade un Chromecast a mano por su IP (útil si la VPN bloquea la búsqueda). */
    fun addManual(ip: String, done: (CastDevice?) -> Unit) {
        io.execute {
            val tv = probeTvReceiver(ip, "manual")
            val dev = when {
                net.isReachable(ip, 8009, 3000) -> {
                    val name = probeName(ip) ?: "Chromecast $ip"
                    found(CastDevice(key = "ip:$ip", name = name, ip = ip, via = "manual"))
                }
                else -> null
            } ?: tv
            main.post { done(dev) }
        }
    }

    fun forget(device: CastDevice) {
        devices.remove(device.key)
        saveRemembered()
        notifyChanged()
    }

    /** Guarda el dispositivo para encontrarlo luego por IP aunque falle mDNS. */
    fun remember(device: CastDevice) {
        devices[device.key] = device.copy(lastSeen = System.currentTimeMillis())
        saveRemembered()
    }

    // ---------------------------------------------------------------- mDNS

    /**
     * Sockets para enviar búsquedas por multicast:
     *  - atado a la Wi-Fi (salta la VPN si se permite),
     *  - uno por cada punto de acceso del móvil (la TV conectada a nuestros datos),
     *  - uno normal, por si la VPN deja pasar la red local.
     */
    private fun multicastSockets(): MutableList<DatagramSocket> {
        val sockets = mutableListOf<DatagramSocket>()
        try {
            val s = MulticastSocket(0)
            net.localInterfaceName()?.let { name ->
                NetworkInterface.getByName(name)?.let { s.networkInterface = it }
            }
            net.bindToLocal(s)
            s.timeToLive = 255
            sockets.add(s)
        } catch (_: Exception) {}
        for (link in net.localLinks().filter { it.hotspot }) {
            try {
                val s = MulticastSocket(java.net.InetSocketAddress(link.address, 0))
                NetworkInterface.getByName(link.iface)?.let { s.networkInterface = it }
                s.timeToLive = 255
                sockets.add(s)
            } catch (_: Exception) {}
        }
        try { sockets.add(MulticastSocket(0)) } catch (_: Exception) {}
        return sockets
    }

    private fun mdnsMulticast() {
        val queries = listOf(Mdns.buildQuery(Mdns.SERVICE), Mdns.buildQuery(Mdns.TV_SERVICE))
        val group = InetAddress.getByName(Mdns.GROUP)
        val sockets = multicastSockets()
        try {

            repeat(2) {
                for (s in sockets) for (query in queries) {
                    try { s.send(DatagramPacket(query, query.size, group, Mdns.PORT)) } catch (_: Exception) {}
                }
                Thread.sleep(150)
            }
            val readers = sockets.map { s -> io.submit { receiveLoop(s, 3000) } }
            readers.forEach { try { it.get(5, TimeUnit.SECONDS) } catch (_: Exception) {} }
        } catch (_: Exception) {
        } finally {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    private fun receiveLoop(s: DatagramSocket, durationMs: Long) {
        val buf = ByteArray(9000)
        val end = System.currentTimeMillis() + durationMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            try {
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val data = p.data.copyOf(p.length)
                for (c in Mdns.parseResponse(data, p.address.hostAddress)) {
                    val ip = c.ip ?: continue
                    found(CastDevice(
                        key = c.id?.let { "id:$it" } ?: "ip:$ip",
                        name = c.name, ip = ip, port = c.port, model = c.model, via = "wifi",
                    ))
                }
                for (c in Mdns.parseResponse(data, p.address.hostAddress, Mdns.TV_SERVICE)) {
                    val ip = c.ip ?: continue
                    found(CastDevice(
                        key = "tv:" + (c.id ?: ip), name = c.name, ip = ip, port = c.port,
                        model = c.model, via = "wifi", type = CastDevice.TYPE_TV,
                    ))
                }
            } catch (_: SocketTimeoutException) {
                break
            } catch (_: Exception) {
                break
            }
        }
    }

    // ---------------------------------------------------------------- DLNA

    /** Busca aparatos DLNA (UPnP MediaRenderer) con SSDP por la Wi-Fi. */
    private fun ssdpSearch() {
        val locations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val group = InetAddress.getByName("239.255.255.250")
        val sockets = multicastSockets()
        try {
            val msgs = listOf(Dlna.searchMessage(Dlna.MEDIA_RENDERER), Dlna.searchMessage(Dlna.AV_TRANSPORT))
            repeat(2) {
                for (s in sockets) for (m in msgs) {
                    try { s.send(DatagramPacket(m, m.size, group, 1900)) } catch (_: Exception) {}
                }
                Thread.sleep(200)
            }
            val readers = sockets.map { s ->
                io.submit {
                    val buf = ByteArray(4096)
                    val end = System.currentTimeMillis() + 3000
                    while (true) {
                        val left = end - System.currentTimeMillis()
                        if (left <= 0) break
                        try {
                            s.soTimeout = left.toInt().coerceAtLeast(1)
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            Dlna.location(String(p.data, 0, p.length, Charsets.ISO_8859_1))?.let { locations.add(it) }
                        } catch (_: Exception) { break }
                    }
                }
            }
            readers.forEach { try { it.get(5, TimeUnit.SECONDS) } catch (_: Exception) {} }
        } catch (_: Exception) {
        } finally {
            sockets.forEach { try { it.close() } catch (_: Exception) {} }
        }
        locations.forEach { loc -> io.execute { describeDlna(loc, "wifi") } }
    }

    private fun describeDlna(location: String, via: String): CastDevice? = try {
        val c = net.openLocal(location) as java.net.HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val xml = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        Dlna.parseDescription(xml, location)?.let { info ->
            val u = java.net.URI(location)
            found(CastDevice(
                key = "dlna:" + info.udn, name = info.name, ip = u.host,
                port = if (u.port > 0) u.port else 80, model = info.model, via = via,
                type = CastDevice.TYPE_DLNA, dlna = info,
            ))
        }
    } catch (_: Exception) { null }

    /** Comprueba si en esa IP está nuestra app receptora para Android TV. */
    private fun probeTvReceiver(ip: String, via: String, port: Int = TvReceiverRenderer.DEFAULT_PORT): CastDevice? = try {
        val c = net.openLocal("http://$ip:$port/info") as java.net.HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 1500
        val o = JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        if (o.optString("app") != "castbrowser-tv") null
        else found(CastDevice(
            key = "tv:" + o.optString("id", ip), name = o.optString("name", "Android TV"),
            ip = ip, port = port, model = o.optString("model").ifEmpty { null }, via = via,
            type = CastDevice.TYPE_TV,
        ))
    } catch (_: Exception) { null }

    // ------------------------------------------------------- Recordados

    private fun checkRemembered() {
        val pool = Executors.newFixedThreadPool(8)
        devices.values.toList().forEach { d ->
            pool.execute {
                if (net.isReachable(d.ip, d.port, 1500)) found(d.copy(via = "recordado"))
                else if (d.type == CastDevice.TYPE_TV) probeTvReceiver(d.ip, "recordado")
            }
        }
        pool.shutdown()
        pool.awaitTermination(6, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------ Escaneo subred

    private fun subnetScan() {
        // La Wi-Fi y, si lo hay, el punto de acceso del móvil (como mucho una /24 de cada).
        for (link in net.localLinks()) scanLink(link)
    }

    private fun scanLink(link: LocalLink) {
        val hosts = link.neighbours()

        // 1) mDNS unicast a cada IP: los Chromecast contestan con su nombre.
        try {
            (if (link.hotspot) DatagramSocket(java.net.InetSocketAddress(link.address, 0)) else DatagramSocket()).use { s ->
                if (!link.hotspot) net.bindToLocal(s)
                val q = Mdns.buildQuery()
                for (h in hosts) {
                    try { s.send(DatagramPacket(q, q.size, InetAddress.getByName(h), Mdns.PORT)) } catch (_: Exception) {}
                }
                receiveLoop(s, 2500)
            }
        } catch (_: Exception) {}

        // 2) Puerto 8009 (Chromecast) y 8765 (nuestra app de TV) abiertos.
        val known = devices.values.filter { it.available }.map { it.type + it.ip }.toSet()
        val pool = Executors.newFixedThreadPool(48)
        for (h in hosts) {
            pool.execute {
                if (CastDevice.TYPE_CAST + h !in known && net.isReachable(h, 8009, 500)) {
                    val name = probeName(h) ?: "Chromecast $h"
                    found(CastDevice(key = "ip:$h", name = name, ip = h, via = "escaneo"))
                }
                if (CastDevice.TYPE_TV + h !in known && net.isReachable(h, TvReceiverRenderer.DEFAULT_PORT, 500)) {
                    probeTvReceiver(h, "escaneo")
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
    }

    /** Intenta leer el nombre del dispositivo (mDNS unicast o API local 8008). */
    private fun probeName(ip: String): String? {
        try {
            DatagramSocket().use { s ->
                net.bindToLocal(s)
                s.soTimeout = 800
                val q = Mdns.buildQuery()
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), Mdns.PORT))
                val buf = ByteArray(9000)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                Mdns.parseResponse(buf.copyOf(p.length), ip).firstOrNull()?.let { return it.name }
            }
        } catch (_: Exception) {}
        try {
            val c = net.openLocal("http://$ip:8008/setup/eureka_info?params=name") as java.net.HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 1500
            val txt = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return JSONObject(txt).optString("name").ifEmpty { null }
        } catch (_: Exception) {}
        return null
    }

    // --------------------------------------------------------------- NSD

    private fun startNsd() {
        if (nsdListener != null) return
        val mgr = context.getSystemService(NsdManager::class.java) ?: return
        nsd = mgr
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { nsdListener = null }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.offer(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        nsdListener = l
        try {
            mgr.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            nsdListener = null
        }
        if (nsdTvListener == null) {
            val t = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { nsdTvListener = null }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.offer(info) }
                override fun onServiceLost(info: NsdServiceInfo) {}
            }
            nsdTvListener = t
            try {
                mgr.discoverServices("_castbrowser._tcp", NsdManager.PROTOCOL_DNS_SD, t)
            } catch (_: Exception) {
                nsdTvListener = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveBlocking(info: NsdServiceInfo) {
        val mgr = nsd ?: return
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            mgr.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) { latch.countDown() }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    try {
                        val ip = si.host?.hostAddress
                        if (ip != null && !ip.contains(':')) {
                            val attrs = si.attributes
                            val fn = attrs["fn"]?.toString(Charsets.UTF_8)
                            val id = attrs["id"]?.toString(Charsets.UTF_8)
                            val md = attrs["md"]?.toString(Charsets.UTF_8)
                            if (si.serviceType?.contains("castbrowser") == true) {
                                found(CastDevice(
                                    key = "tv:" + (id ?: ip), name = fn ?: si.serviceName, ip = ip, port = si.port,
                                    model = md, via = "wifi", type = CastDevice.TYPE_TV,
                                ))
                                return
                            }
                            found(CastDevice(
                                key = id?.let { "id:$it" } ?: "ip:$ip",
                                name = fn ?: si.serviceName, ip = ip, port = si.port, model = md, via = "wifi",
                            ))
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            })
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------- Estado

    private fun found(d: CastDevice): CastDevice {
        val now = System.currentTimeMillis()
        // Si ya teníamos el mismo dispositivo con otra clave (p. ej. por IP), los unimos.
        val dup = devices.values.firstOrNull { it.key != d.key && it.ip == d.ip && it.type == d.type }
        val key = when {
            dup == null -> d.key
            d.key.startsWith("id:") || d.key.startsWith("dlna:") -> { devices.remove(dup.key); d.key }
            else -> dup.key
        }
        val prev = devices[key] ?: dup
        val better = d.copy(
            key = key,
            name = if (d.name.startsWith("Chromecast ") && prev != null) prev.name else d.name,
            model = d.model ?: prev?.model,
            dlna = d.dlna ?: prev?.dlna,
            lastSeen = now,
        )
        devices[key] = better
        saveRemembered()
        notifyChanged()
        return better
    }

    private fun notifyChanged() {
        main.post { onDevicesChanged?.invoke() }
    }

    private fun loadRemembered() {
        try {
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = CastDevice(
                    key = o.getString("key"), name = o.getString("name"), ip = o.getString("ip"),
                    port = o.optInt("port", 8009), model = o.optString("model").ifEmpty { null },
                    lastSeen = 0, via = "recordado",
                    type = o.optString("type", CastDevice.TYPE_CAST),
                    dlna = o.optJSONObject("dlna")?.let { j ->
                        DlnaInfo(
                            name = j.getString("name"), udn = j.getString("udn"),
                            model = j.optString("model").ifEmpty { null },
                            avControlUrl = j.getString("av"), avServiceType = j.getString("avType"),
                            rcControlUrl = j.optString("rc").ifEmpty { null },
                            rcServiceType = j.optString("rcType").ifEmpty { null },
                            cmControlUrl = j.optString("cm").ifEmpty { null },
                            cmServiceType = j.optString("cmType").ifEmpty { null },
                        )
                    },
                )
                devices[d.key] = d
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun saveRemembered() {
        val arr = JSONArray()
        devices.values.forEach { d ->
            val o = JSONObject().put("key", d.key).put("name", d.name).put("ip", d.ip)
                .put("port", d.port).put("model", d.model ?: "").put("type", d.type)
            d.dlna?.let { i ->
                o.put("dlna", JSONObject().put("name", i.name).put("udn", i.udn).put("model", i.model ?: "")
                    .put("av", i.avControlUrl).put("avType", i.avServiceType)
                    .put("rc", i.rcControlUrl ?: "").put("rcType", i.rcServiceType ?: "")
                    .put("cm", i.cmControlUrl ?: "").put("cmType", i.cmServiceType ?: ""))
            }
            arr.put(o)
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
