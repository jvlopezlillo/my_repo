package com.castbrowser

import java.net.URI

/** Servicios UPnP de un aparato DLNA (Smart TV, Kodi, VLC, BubbleUPnP…). */
data class DlnaInfo(
    val name: String,
    val udn: String,
    val model: String?,
    val avControlUrl: String,
    val avServiceType: String,
    val rcControlUrl: String?,
    val rcServiceType: String?,
)

object Dlna {
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun searchMessage(st: String) = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n" +
            "USER-AGENT: Android UPnP/1.1 CastBrowser/1.0\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** Saca la cabecera LOCATION de una respuesta SSDP. */
    fun location(response: String): String? =
        response.lineSequence().firstOrNull { it.startsWith("location:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    fun tag(xml: String, name: String): String? =
        Regex("<(?:[\\w-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let { unescape(it.trim()) }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /** Lee la descripción XML de un aparato y busca sus servicios de reproducción. */
    fun parseDescription(xml: String, location: String): DlnaInfo? {
        val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        val services = Regex("<(?:[\\w-]+:)?service>(.*?)</(?:[\\w-]+:)?service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }.toList()
        fun find(kind: String): Pair<String, String>? {
            val svc = services.firstOrNull { tag(it, "serviceType")?.contains(":service:$kind:") == true } ?: return null
            val ctl = tag(svc, "controlURL") ?: return null
            return URI(base).resolve(ctl.replace(" ", "%20")).toString() to tag(svc, "serviceType")!!
        }
        val av = find("AVTransport") ?: return null
        val rc = find("RenderingControl")
        return DlnaInfo(
            name = tag(xml, "friendlyName") ?: "DLNA",
            udn = tag(xml, "UDN") ?: location,
            model = tag(xml, "modelName"),
            avControlUrl = av.first,
            avServiceType = av.second,
            rcControlUrl = rc?.first,
            rcServiceType = rc?.second,
        )
    }

    fun parseTime(s: String?): Double {
        if (s.isNullOrBlank() || s.contains("NOT_IMPLEMENTED")) return 0.0
        val parts = s.split(':')
        return try {
            parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() }
        } catch (_: Exception) { 0.0 }
    }

    fun formatTime(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    fun didl(url: String, title: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${escape(url)}</res>" +
            "</item></DIDL-Lite>"
}

/**
 * Envía vídeos a un aparato DLNA (UPnP MediaRenderer) mediante SOAP:
 * SetAVTransportURI + Play, y consulta el estado cada 2 s.
 */
class DlnaRenderer(
    private val info: DlnaInfo,
    private val open: HttpOpener,
    private val onStatus: (CastStatus) -> Unit,
    private val onClosed: (String?) -> Unit,
    private val log: (String) -> Unit = {},
    /** Tiempo máximo para SetAVTransportURI y Play: muchas teles (LG…) bajan el vídeo antes de contestar. */
    private val slowActionTimeoutMs: Int = 45_000,
) : Renderer {
    @Volatile override var lastStatus = CastStatus(); private set
    @Volatile private var closed = false
    @Volatile private var started = false

    override fun connect() {
        // DLNA no mantiene conexión: comprobamos que responde.
        soap(info.avControlUrl, info.avServiceType, "GetTransportInfo", listOf("InstanceID" to "0"))
        info.rcControlUrl?.let { url ->
            try {
                val xml = soap(url, info.rcServiceType!!, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))
                Dlna.tag(xml, "CurrentVolume")?.toDoubleOrNull()?.let { lastStatus = lastStatus.copy(volume = it / 100.0) }
            } catch (_: Exception) {}
        }
        log("DLNA: ${info.name} responde")
    }

    override fun load(
        url: String,
        contentType: String,
        title: String,
        startTime: Double,
        live: Boolean,
        hlsFmp4: Boolean,
        headers: Map<String, String>,
    ) {
        started = false
        val mime = when {
            contentType.contains("mpegurl", true) -> "application/vnd.apple.mpegurl"
            else -> contentType
        }
        try { soap(info.avControlUrl, info.avServiceType, "Stop", listOf("InstanceID" to "0")) } catch (_: Exception) {}
        log("DLNA: SetAVTransportURI $url")
        soap(info.avControlUrl, info.avServiceType, "SetAVTransportURI", listOf(
            "InstanceID" to "0",
            "CurrentURI" to url,
            "CurrentURIMetaData" to Dlna.didl(url, title, mime),
        ))
        soap(info.avControlUrl, info.avServiceType, "Play", listOf("InstanceID" to "0", "Speed" to "1"))

        // Esperamos a que pase a PLAYING (o a que se pare, que indica error).
        val begin = System.currentTimeMillis()
        var stoppedSince = 0L
        while (true) {
            val elapsed = System.currentTimeMillis() - begin
            val state = transportState()
            if (state != null) log("DLNA: estado $state")
            when (state) {
                "PLAYING" -> break
                "STOPPED", "NO_MEDIA_PRESENT" -> {
                    if (stoppedSince == 0L) stoppedSince = System.currentTimeMillis()
                    if (elapsed > 6000 && System.currentTimeMillis() - stoppedSince > 4000) {
                        throw CastException("El aparato DLNA no pudo reproducir el vídeo (formato no soportado o no llega a la dirección)")
                    }
                }
                else -> stoppedSince = 0L
            }
            if (elapsed > 45_000) {
                if (state == "TRANSITIONING") break
                throw CastException("El aparato DLNA no empezó a reproducir en 45 s")
            }
            Thread.sleep(1000)
        }
        started = true
        if (!live && startTime > 5) seek(startTime)
        updateStatus()
        Thread(::pollLoop, "dlna-poll").apply { isDaemon = true }.start()
    }

    private fun transportState(): String? = try {
        Dlna.tag(soap(info.avControlUrl, info.avServiceType, "GetTransportInfo", listOf("InstanceID" to "0")),
            "CurrentTransportState")
    } catch (_: Exception) { null }

    private fun updateStatus() {
        val state = transportState() ?: return
        val pos = try {
            soap(info.avControlUrl, info.avServiceType, "GetPositionInfo", listOf("InstanceID" to "0"))
        } catch (_: Exception) { "" }
        val player = when (state) {
            "PLAYING" -> "PLAYING"
            "PAUSED_PLAYBACK" -> "PAUSED"
            "TRANSITIONING" -> "BUFFERING"
            else -> "IDLE"
        }
        lastStatus = lastStatus.copy(
            playerState = player,
            currentTime = Dlna.parseTime(Dlna.tag(pos, "RelTime")),
            duration = Dlna.parseTime(Dlna.tag(pos, "TrackDuration")),
            idleReason = if (player == "IDLE") "FINISHED" else null,
        )
        onStatus(lastStatus)
    }

    private fun pollLoop() {
        var failures = 0
        while (!closed) {
            try {
                Thread.sleep(2000)
                if (closed) break
                updateStatus()
                failures = 0
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                if (++failures >= 5) { close("Se perdió la conexión con el aparato DLNA"); break }
            }
        }
    }

    private fun async(block: () -> Unit) = Thread {
        try { block(); updateStatus() } catch (e: Exception) { log("DLNA: ${e.message}") }
    }.start()

    override fun play() = async { soap(info.avControlUrl, info.avServiceType, "Play", listOf("InstanceID" to "0", "Speed" to "1")) }
    override fun pause() = async { soap(info.avControlUrl, info.avServiceType, "Pause", listOf("InstanceID" to "0")) }
    override fun seek(seconds: Double) = async {
        soap(info.avControlUrl, info.avServiceType, "Seek",
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to Dlna.formatTime(seconds)))
    }

    override fun setVolume(level: Double) {
        val url = info.rcControlUrl ?: return
        val v = (level.coerceIn(0.0, 1.0) * 100).toInt()
        lastStatus = lastStatus.copy(volume = v / 100.0)
        async {
            soap(url, info.rcServiceType!!, "SetVolume",
                listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to v.toString()))
        }
    }

    override fun stop() {
        try { soap(info.avControlUrl, info.avServiceType, "Stop", listOf("InstanceID" to "0")) } catch (_: Exception) {}
        close(null)
    }

    private fun close(reason: String?) {
        if (closed) return
        closed = true
        onClosed(reason)
    }

    private fun soap(url: String, service: String, action: String, args: List<Pair<String, String>>): String {
        val slow = action == "SetAVTransportURI" || action == "Play"
        val timeout = if (slow) slowActionTimeoutMs else 8000
        if (slow) log("DLNA: $action (la tele puede tardar hasta ${timeout / 1000} s en contestar)")
        val argXml = args.joinToString("") { (k, v) -> "<$k>${Dlna.escape(v)}</$k>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$service\">$argXml</u:$action></s:Body></s:Envelope>"
        val started = System.currentTimeMillis()
        val (code, text) = try {
            Http.request(open, url, "POST", body, mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPACTION" to "\"$service#$action\"",
            ), timeoutMs = timeout)
        } catch (e: java.net.SocketTimeoutException) {
            throw CastException(
                "La tele no contestó a $action en ${timeout / 1000} s. " +
                    if (slow) "Suele ser que no consigue descargar el vídeo (mira en el diagnóstico si pidió algo al móvil) o que no admite este formato por DLNA."
                    else "Comprueba que está encendida y en la misma red."
            )
        } catch (e: java.net.ConnectException) {
            throw CastException("No se puede conectar con la tele por DLNA (${e.message})")
        }
        if (slow) log("DLNA: $action contestado en ${System.currentTimeMillis() - started} ms")
        if (code >= 400) {
            val err = Dlna.tag(text, "errorDescription") ?: Dlna.tag(text, "faultstring") ?: "HTTP $code"
            throw CastException("El aparato DLNA respondió con error en $action: $err")
        }
        return text
    }
}
