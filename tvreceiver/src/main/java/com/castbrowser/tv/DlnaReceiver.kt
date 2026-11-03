package com.castbrowser.tv

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.UUID

/** Conecta las órdenes DLNA con el reproductor de la tele (PlayerHub). */
class HubDlnaPlayer(private val ctx: Context) : DlnaPlayer {
    @Volatile private var uri = ""
    @Volatile private var meta = ""
    @Volatile private var playingUri = ""

    override fun setUri(uri: String, metadata: String) {
        this.uri = uri
        this.meta = metadata
    }

    override fun play() {
        if (uri.isEmpty()) return
        val st = PlayerHub.state
        val open = PlayerHub.player != null
        if (open && playingUri == uri) {
            // Ya es este vídeo: sólo reanudar si estaba en pausa.
            if (st == "paused") { PlayerHub.control(ctx, "play", null); return }
            if (st == "playing" || st == "buffering" || st == "loading") return
        }
        playingUri = uri
        PlayerHub.play(ctx, MediaRequest(
            url = uri,
            contentType = Didl.mime(meta),
            title = Didl.title(meta) ?: uri.substringBefore('?').substringAfterLast('/').ifEmpty { "Vídeo" },
            position = 0.0,
            live = false,
            headers = emptyMap(),
        ))
    }

    override fun pause() = PlayerHub.control(ctx, "pause", null)

    override fun stop() {
        playingUri = ""
        PlayerHub.control(ctx, "stop", null)
    }

    override fun seek(seconds: Double) = PlayerHub.control(ctx, "seek", seconds)
    override fun volume(): Int = PlayerHub.volumePercent(ctx)
    override fun setVolume(percent: Int) = PlayerHub.setVolumePercent(ctx, percent)
    override fun muted(): Boolean = PlayerHub.isMuted(ctx)
    override fun setMuted(mute: Boolean) = PlayerHub.setMuted(ctx, mute)

    override fun transportState(): String = when (PlayerHub.state) {
        "playing" -> "PLAYING"
        "paused" -> "PAUSED_PLAYBACK"
        "loading", "buffering" -> "TRANSITIONING"
        else -> if (uri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
    }

    override fun position(): Double = PlayerHub.position
    override fun duration(): Double = PlayerHub.duration
    override fun currentUri(): String = uri
    override fun currentMetadata(): String = meta
}

/**
 * Receptor DLNA (UPnP MediaRenderer): se anuncia por SSDP, atiende las órdenes
 * SOAP por HTTP y avisa de los cambios de estado. Así cualquier app DLNA
 * (BubbleUPnP, Web Video Caster, VLC, Windows…) puede enviar vídeos a la tele.
 */
class DlnaReceiver(context: Context, val name: String, deviceId: String) {
    private val ctx = context.applicationContext
    val uuid: String = UUID.nameUUIDFromBytes("castbrowser-tv-dlna-$deviceId".toByteArray()).toString()

    private val core = DlnaCore(
        name, uuid, HubDlnaPlayer(ctx),
        notify = { url, headers, body -> try { RawHttp.send("NOTIFY", url, headers, body) in 200..299 } catch (_: Exception) { false } },
    )
    private val http = SimpleHttpServer(49152) { m, p, h, b -> core.handle(m, p, h, b) }

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    val port: Int get() = http.port

    fun start() {
        if (running) return
        running = true
        lock = try {
            ctx.getSystemService(WifiManager::class.java)?.createMulticastLock("castbrowser-dlna")
                ?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { null }

        http.start()
        DlnaStatus.httpPort = http.port

        var ssdpError: String? = null
        val s = try {
            MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORT))
                timeToLive = 4
            }
        } catch (e: Exception) {
            // Otro servicio de la tele ocupa el puerto 1900: seguimos anunciándonos, aunque no oiremos las búsquedas.
            ssdpError = "no se pudo abrir el puerto 1900 (${e.message})"
            try { MulticastSocket().apply { timeToLive = 4 } } catch (_: Exception) { null }
        }
        DlnaStatus.ssdp = ssdpError ?: if (s != null) "ok" else "sin socket"
        socket = s
        if (s != null) {
            joinAll(s)
            daemon("dlna-ssdp") { searchLoop(s) }
            daemon("dlna-announce") { announceLoop(s) }
        }
        daemon("dlna-events") {
            while (running) {
                try { core.pollEvents() } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        if (!running) return
        try { socket?.let { announce(it, "ssdp:byebye") } } catch (_: Exception) {}
        running = false
        try { socket?.close() } catch (_: Exception) {}
        http.close()
        try { lock?.release() } catch (_: Exception) {}
    }

    private fun daemon(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    private fun interfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { ni ->
            ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                ni.interfaceAddresses.any { it.address is Inet4Address }
        }
    } catch (_: Exception) { emptyList() }

    private fun joinAll(s: MulticastSocket) {
        val group = InetSocketAddress(InetAddress.getByName(Ssdp.GROUP), Ssdp.PORT)
        val names = ArrayList<String>()
        for (ni in interfaces()) {
            try { s.joinGroup(group, ni) } catch (_: Exception) { /* ya unido */ }
            val ip = ni.interfaceAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
            names.add("${ni.name} $ip")
        }
        DlnaStatus.interfaces = names.joinToString(", ")
    }

    /** IP de la tele que ve ese cliente (la de su misma red). */
    private fun localAddressFor(remote: InetAddress): String? {
        val r = (remote as? Inet4Address)?.address ?: return null
        var fallback: String? = null
        for (ni in interfaces()) for (ia in ni.interfaceAddresses) {
            val a = ia.address as? Inet4Address ?: continue
            if (fallback == null) fallback = a.hostAddress
            if (sameSubnet(a.address, r, ia.networkPrefixLength.toInt())) return a.hostAddress
        }
        return fallback
    }

    private fun searchLoop(s: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running && !s.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val st = Ssdp.searchTarget(String(p.data, 0, p.length, Charsets.ISO_8859_1)) ?: continue
                DlnaStatus.searches.incrementAndGet()
                val local = localAddressFor(p.address) ?: continue
                val location = "http://$local:${http.port}/dlna/desc.xml"
                for (t in Ssdp.matching(st, uuid)) {
                    val data = Ssdp.response(t, location, uuid)
                    synchronized(s) { s.send(DatagramPacket(data, data.size, p.address, p.port)) }
                }
            } catch (_: Exception) {
                if (!running || s.isClosed) break
            }
        }
    }

    private fun announceLoop(s: MulticastSocket) {
        var first = true
        while (running && !s.isClosed) {
            try {
                joinAll(s)
                announce(s, "ssdp:alive")
                if (first) { Thread.sleep(400); announce(s, "ssdp:alive") }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
            first = false
            try { Thread.sleep(5 * 60_000L) } catch (_: InterruptedException) { break }
        }
    }

    private fun announce(s: MulticastSocket, nts: String) {
        val group = InetAddress.getByName(Ssdp.GROUP)
        for (ni in interfaces()) {
            val addr = ni.interfaceAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull() ?: continue
            val location = "http://${addr.hostAddress}:${http.port}/dlna/desc.xml"
            sendAnnounce(s, ni, group, location, nts)
        }
    }

    private fun sendAnnounce(s: MulticastSocket, ni: NetworkInterface, group: InetAddress, location: String, nts: String) {
        synchronized(s) {
            try {
                s.networkInterface = ni
                for (t in Ssdp.targets(uuid)) {
                    val data = Ssdp.notify(t, location, uuid, nts)
                    s.send(DatagramPacket(data, data.size, group, Ssdp.PORT))
                }
            } catch (_: Exception) {
            }
        }
    }
}
