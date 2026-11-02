package com.castbrowser.tv

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Receptor DLNA / UPnP "MediaRenderer": sin código de Android para poder probarlo en el PC.

/** Lo que el receptor DLNA necesita del reproductor de la tele. */
interface DlnaPlayer {
    fun setUri(uri: String, metadata: String)
    fun play()
    fun pause()
    fun stop()
    fun seek(seconds: Double)
    fun volume(): Int
    fun setVolume(percent: Int)
    fun muted(): Boolean
    fun setMuted(mute: Boolean)

    /** STOPPED, PLAYING, PAUSED_PLAYBACK, TRANSITIONING o NO_MEDIA_PRESENT. */
    fun transportState(): String
    fun position(): Double
    fun duration(): Double
    fun currentUri(): String
    fun currentMetadata(): String
}

data class HttpReply(
    val code: Int,
    val contentType: String = "text/xml; charset=\"utf-8\"",
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

class UpnpError(val code: Int, message: String) : Exception(message)

object Xml {
    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(s: String): String {
        val v = s.trim()
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) return v.substring(9, v.length - 3)
        return v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}

fun fmtTime(sec: Double): String {
    val t = sec.toLong().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
}

fun parseTime(s: String?): Double {
    if (s.isNullOrBlank() || !s[0].isDigit()) return 0.0
    return try {
        s.split(':').fold(0.0) { acc, p -> acc * 60 + p.toDouble() }
    } catch (_: Exception) {
        0.0
    }
}

fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
    if (a.size != b.size) return false
    var bits = prefix
    for (i in a.indices) {
        if (bits <= 0) return true
        val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
        if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
        bits -= 8
    }
    return true
}

/** Datos que vienen en el DIDL-Lite que manda el controlador. */
object Didl {
    fun title(meta: String): String? =
        Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(meta)
            ?.groupValues?.get(1)?.let { Xml.unescape(it) }?.takeIf { it.isNotBlank() }

    /** Tipo MIME del primer protocolInfo ("http-get:*:video/mp4:*"). */
    fun mime(meta: String): String =
        Regex("protocolInfo=\"[^:\"]*:[^:\"]*:([^:\"]*):").find(meta)?.groupValues?.get(1)
            ?.takeIf { it != "*" } ?: ""
}

// ------------------------------------------------------------------ definición de servicios

private class Arg(val name: String, val out: Boolean, val rel: String)
private class Act(val name: String, val args: List<Arg>)
private class Var(
    val name: String,
    val type: String,
    val events: Boolean = false,
    val allowed: List<String> = emptyList(),
    val range: List<Int>? = null,
)

private class ServiceDef(val key: String, val type: String, val id: String, val actions: List<Act>, val vars: List<Var>)

private fun act(name: String, vararg a: Arg) = Act(name, a.toList())
private fun inArg(name: String, rel: String) = Arg(name, false, rel)
private fun outArg(name: String, rel: String) = Arg(name, true, rel)

private val INSTANCE = inArg("InstanceID", "A_ARG_TYPE_InstanceID")

private val AVT_DEF = ServiceDef(
    "AVTransport", "urn:schemas-upnp-org:service:AVTransport:1", "urn:upnp-org:serviceId:AVTransport",
    listOf(
        act("SetAVTransportURI", INSTANCE, inArg("CurrentURI", "AVTransportURI"),
            inArg("CurrentURIMetaData", "AVTransportURIMetaData")),
        act("SetNextAVTransportURI", INSTANCE, inArg("NextURI", "NextAVTransportURI"),
            inArg("NextURIMetaData", "NextAVTransportURIMetaData")),
        act("GetMediaInfo", INSTANCE, outArg("NrTracks", "NumberOfTracks"),
            outArg("MediaDuration", "CurrentMediaDuration"), outArg("CurrentURI", "AVTransportURI"),
            outArg("CurrentURIMetaData", "AVTransportURIMetaData"), outArg("NextURI", "NextAVTransportURI"),
            outArg("NextURIMetaData", "NextAVTransportURIMetaData"), outArg("PlayMedium", "PlaybackStorageMedium"),
            outArg("RecordMedium", "RecordStorageMedium"), outArg("WriteStatus", "RecordMediumWriteStatus")),
        act("GetTransportInfo", INSTANCE, outArg("CurrentTransportState", "TransportState"),
            outArg("CurrentTransportStatus", "TransportStatus"), outArg("CurrentSpeed", "TransportPlaySpeed")),
        act("GetPositionInfo", INSTANCE, outArg("Track", "CurrentTrack"),
            outArg("TrackDuration", "CurrentTrackDuration"), outArg("TrackMetaData", "CurrentTrackMetaData"),
            outArg("TrackURI", "CurrentTrackURI"), outArg("RelTime", "RelativeTimePosition"),
            outArg("AbsTime", "AbsoluteTimePosition"), outArg("RelCount", "RelativeCounterPosition"),
            outArg("AbsCount", "AbsoluteCounterPosition")),
        act("GetDeviceCapabilities", INSTANCE, outArg("PlayMedia", "PossiblePlaybackStorageMedia"),
            outArg("RecMedia", "PossibleRecordStorageMedia"), outArg("RecQualityModes", "PossibleRecordQualityModes")),
        act("GetTransportSettings", INSTANCE, outArg("PlayMode", "CurrentPlayMode"),
            outArg("RecQualityMode", "CurrentRecordQualityMode")),
        act("Stop", INSTANCE),
        act("Play", INSTANCE, inArg("Speed", "TransportPlaySpeed")),
        act("Pause", INSTANCE),
        act("Seek", INSTANCE, inArg("Unit", "A_ARG_TYPE_SeekMode"), inArg("Target", "A_ARG_TYPE_SeekTarget")),
        act("Next", INSTANCE),
        act("Previous", INSTANCE),
        act("SetPlayMode", INSTANCE, inArg("NewPlayMode", "CurrentPlayMode")),
        act("GetCurrentTransportActions", INSTANCE, outArg("Actions", "CurrentTransportActions")),
    ),
    listOf(
        Var("TransportState", "string", allowed = listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")),
        Var("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
        Var("PlaybackStorageMedium", "string"),
        Var("RecordStorageMedium", "string"),
        Var("PossiblePlaybackStorageMedia", "string"),
        Var("PossibleRecordStorageMedia", "string"),
        Var("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
        Var("TransportPlaySpeed", "string", allowed = listOf("1")),
        Var("RecordMediumWriteStatus", "string"),
        Var("CurrentRecordQualityMode", "string"),
        Var("PossibleRecordQualityModes", "string"),
        Var("NumberOfTracks", "ui4", range = listOf(0, 1, 1)),
        Var("CurrentTrack", "ui4", range = listOf(0, 1, 1)),
        Var("CurrentTrackDuration", "string"),
        Var("CurrentMediaDuration", "string"),
        Var("CurrentTrackMetaData", "string"),
        Var("CurrentTrackURI", "string"),
        Var("AVTransportURI", "string"),
        Var("AVTransportURIMetaData", "string"),
        Var("NextAVTransportURI", "string"),
        Var("NextAVTransportURIMetaData", "string"),
        Var("RelativeTimePosition", "string"),
        Var("AbsoluteTimePosition", "string"),
        Var("RelativeCounterPosition", "i4"),
        Var("AbsoluteCounterPosition", "ui4"),
        Var("CurrentTransportActions", "string"),
        Var("LastChange", "string", events = true),
        Var("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
        Var("A_ARG_TYPE_SeekTarget", "string"),
        Var("A_ARG_TYPE_InstanceID", "ui4"),
    ),
)

private val RCS_DEF = ServiceDef(
    "RenderingControl", "urn:schemas-upnp-org:service:RenderingControl:1", "urn:upnp-org:serviceId:RenderingControl",
    listOf(
        act("ListPresets", INSTANCE, outArg("CurrentPresetNameList", "PresetNameList")),
        act("SelectPreset", INSTANCE, inArg("PresetName", "A_ARG_TYPE_PresetName")),
        act("GetMute", INSTANCE, inArg("Channel", "A_ARG_TYPE_Channel"), outArg("CurrentMute", "Mute")),
        act("SetMute", INSTANCE, inArg("Channel", "A_ARG_TYPE_Channel"), inArg("DesiredMute", "Mute")),
        act("GetVolume", INSTANCE, inArg("Channel", "A_ARG_TYPE_Channel"), outArg("CurrentVolume", "Volume")),
        act("SetVolume", INSTANCE, inArg("Channel", "A_ARG_TYPE_Channel"), inArg("DesiredVolume", "Volume")),
    ),
    listOf(
        Var("LastChange", "string", events = true),
        Var("PresetNameList", "string"),
        Var("Mute", "boolean"),
        Var("Volume", "ui2", range = listOf(0, 100, 1)),
        Var("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
        Var("A_ARG_TYPE_InstanceID", "ui4"),
        Var("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")),
    ),
)

private val CM_DEF = ServiceDef(
    "ConnectionManager", "urn:schemas-upnp-org:service:ConnectionManager:1", "urn:upnp-org:serviceId:ConnectionManager",
    listOf(
        act("GetProtocolInfo", outArg("Source", "SourceProtocolInfo"), outArg("Sink", "SinkProtocolInfo")),
        act("GetCurrentConnectionIDs", outArg("ConnectionIDs", "CurrentConnectionIDs")),
        act("GetCurrentConnectionInfo", inArg("ConnectionID", "A_ARG_TYPE_ConnectionID"),
            outArg("RcsID", "A_ARG_TYPE_RcsID"), outArg("AVTransportID", "A_ARG_TYPE_AVTransportID"),
            outArg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), outArg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
            outArg("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), outArg("Direction", "A_ARG_TYPE_Direction"),
            outArg("Status", "A_ARG_TYPE_ConnectionStatus")),
    ),
    listOf(
        Var("SourceProtocolInfo", "string", events = true),
        Var("SinkProtocolInfo", "string", events = true),
        Var("CurrentConnectionIDs", "string", events = true),
        Var("A_ARG_TYPE_ConnectionStatus", "string",
            allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
        Var("A_ARG_TYPE_ConnectionManager", "string"),
        Var("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
        Var("A_ARG_TYPE_ProtocolInfo", "string"),
        Var("A_ARG_TYPE_ConnectionID", "i4"),
        Var("A_ARG_TYPE_AVTransportID", "i4"),
        Var("A_ARG_TYPE_RcsID", "i4"),
    ),
)

private val SERVICES = listOf(AVT_DEF, RCS_DEF, CM_DEF)

// ------------------------------------------------------------------ núcleo

private class Subscription(
    val sid: String,
    val service: String,
    val callbacks: List<String>,
    @Volatile var expires: Long,
) {
    @Volatile var initial = true
    @Volatile var seq = 0L
    @Volatile var last: String? = null
    @Volatile var failures = 0
}

/**
 * Lógica del receptor: descripción del aparato, servicios SOAP (AVTransport,
 * RenderingControl, ConnectionManager) y avisos de eventos a los controladores.
 */
class DlnaCore(
    val name: String,
    val uuid: String,
    private val player: DlnaPlayer,
    private val notify: (url: String, headers: Map<String, String>, body: String) -> Boolean = { _, _, _ -> true },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
        const val SERVER = "Linux/4.9 UPnP/1.0 CastBrowserTV/1.0"
        const val SUBSCRIPTION_SECONDS = 1800

        val SINK_TYPES = listOf(
            "video/mp4", "video/x-matroska", "video/webm", "video/mpeg", "video/x-msvideo", "video/quicktime",
            "video/3gpp", "video/mp2t", "video/x-m4v", "application/vnd.apple.mpegurl", "application/x-mpegURL",
            "application/dash+xml", "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-flac", "audio/ogg",
            "audio/wav", "audio/x-wav", "audio/webm",
        )
    }

    private val subs = ConcurrentHashMap<String, Subscription>()

    fun sinkProtocolInfo(): String = SINK_TYPES.joinToString(",") { "http-get:*:$it:*" }

    // ---------------------------------------------------------- HTTP

    fun handle(method: String, path: String, headers: Map<String, String>, body: String): HttpReply {
        val m = method.uppercase()
        val p = path.substringBefore('?')
        if (p == "/dlna/desc.xml") return if (m == "GET" || m == "HEAD") HttpReply(200, body = description()) else HttpReply(405, "text/plain")
        for (svc in SERVICES) {
            when (p) {
                "/dlna/${svc.key}.xml" -> return if (m == "GET" || m == "HEAD") HttpReply(200, body = scpd(svc)) else HttpReply(405, "text/plain")
                "/dlna/${svc.key}/control" -> return if (m == "POST") control(svc, headers, body) else HttpReply(405, "text/plain")
                "/dlna/${svc.key}/event" -> return event(svc, m, headers)
            }
        }
        return HttpReply(404, "text/plain", "not found")
    }

    fun description(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>")
        sb.append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
        sb.append("<specVersion><major>1</major><minor>0</minor></specVersion><device>")
        sb.append("<deviceType>$DEVICE</deviceType>")
        sb.append("<friendlyName>${Xml.escape(name)}</friendlyName>")
        sb.append("<manufacturer>CastBrowser</manufacturer>")
        sb.append("<modelDescription>Receptor DLNA de CastBrowser TV</modelDescription>")
        sb.append("<modelName>CastBrowser TV</modelName><modelNumber>1.0</modelNumber>")
        sb.append("<UDN>uuid:$uuid</UDN>")
        sb.append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>")
        sb.append("<serviceList>")
        for (s in SERVICES) {
            sb.append("<service><serviceType>${s.type}</serviceType><serviceId>${s.id}</serviceId>")
            sb.append("<SCPDURL>/dlna/${s.key}.xml</SCPDURL>")
            sb.append("<controlURL>/dlna/${s.key}/control</controlURL>")
            sb.append("<eventSubURL>/dlna/${s.key}/event</eventSubURL></service>")
        }
        sb.append("</serviceList></device></root>")
        return sb.toString()
    }

    private fun scpd(def: ServiceDef): String {
        val sb = StringBuilder("<?xml version=\"1.0\"?>")
        sb.append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
        sb.append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for (a in def.actions) {
            sb.append("<action><name>${a.name}</name>")
            if (a.args.isNotEmpty()) {
                sb.append("<argumentList>")
                for (g in a.args) {
                    sb.append("<argument><name>${g.name}</name><direction>${if (g.out) "out" else "in"}</direction>")
                    sb.append("<relatedStateVariable>${g.rel}</relatedStateVariable></argument>")
                }
                sb.append("</argumentList>")
            }
            sb.append("</action>")
        }
        sb.append("</actionList><serviceStateTable>")
        for (v in def.vars) {
            sb.append("<stateVariable sendEvents=\"${if (v.events) "yes" else "no"}\">")
            sb.append("<name>${v.name}</name><dataType>${v.type}</dataType>")
            if (v.allowed.isNotEmpty()) {
                sb.append("<allowedValueList>")
                v.allowed.forEach { sb.append("<allowedValue>$it</allowedValue>") }
                sb.append("</allowedValueList>")
            }
            v.range?.let { r ->
                sb.append("<allowedValueRange><minimum>${r[0]}</minimum><maximum>${r[1]}</maximum><step>${r[2]}</step></allowedValueRange>")
            }
            sb.append("</stateVariable>")
        }
        sb.append("</serviceStateTable></scpd>")
        return sb.toString()
    }

    // ---------------------------------------------------------- SOAP

    private fun envelope(inner: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$inner</s:Body></s:Envelope>"

    private fun fault(code: Int, msg: String) = HttpReply(500, body = envelope(
        "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode>" +
            "<errorDescription>${Xml.escape(msg)}</errorDescription></UPnPError></detail></s:Fault>"
    ))

    private fun ok(type: String, action: String, out: List<Pair<String, String>>): HttpReply {
        val sb = StringBuilder("<u:${action}Response xmlns:u=\"$type\">")
        out.forEach { (k, v) -> sb.append("<$k>${Xml.escape(v)}</$k>") }
        sb.append("</u:${action}Response>")
        return HttpReply(200, body = envelope(sb.toString()))
    }

    private fun control(svc: ServiceDef, headers: Map<String, String>, body: String): HttpReply {
        val fromHeader = headers["soapaction"]?.trim()?.trim('"')?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }
        val action = fromHeader
            ?: Regex("<(?:[\\w-]+:)?Body[^>]*>\\s*<(?:[\\w-]+:)?(\\w+)").find(body)?.groupValues?.get(1)
            ?: return fault(401, "Invalid Action")
        val args = parseArgs(body, action)
        return try {
            val out = when (svc.key) {
                "AVTransport" -> avTransport(action, args)
                "RenderingControl" -> renderingControl(action, args)
                else -> connectionManager(action, args)
            }
            ok(svc.type, action, out)
        } catch (e: UpnpError) {
            fault(e.code, e.message ?: "Error")
        } catch (e: Exception) {
            fault(501, "Action Failed: ${e.message}")
        }
    }

    private fun parseArgs(body: String, action: String): Map<String, String> {
        val inner = Regex("<(?:[\\w-]+:)?$action(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$action>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return emptyMap()
        val out = HashMap<String, String>()
        Regex("<(\\w+)(?:\\s[^>]*)?>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(inner)
            .forEach { out[it.groupValues[1]] = Xml.unescape(it.groupValues[2]) }
        Regex("<(\\w+)\\s*/>").findAll(inner).forEach { out.putIfAbsent(it.groupValues[1], "") }
        return out
    }

    private fun transportActions(state: String) = when (state) {
        "PLAYING" -> "Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "STOPPED" -> "Play"
        "TRANSITIONING" -> "Stop"
        else -> ""
    }

    private fun avTransport(action: String, a: Map<String, String>): List<Pair<String, String>> {
        val none = emptyList<Pair<String, String>>()
        when (action) {
            "SetAVTransportURI" -> {
                player.setUri(a["CurrentURI"].orEmpty(), a["CurrentURIMetaData"].orEmpty()); return none
            }
            "SetNextAVTransportURI", "SetPlayMode" -> return none
            "Play" -> { player.play(); return none }
            "Pause" -> { player.pause(); return none }
            "Stop" -> { player.stop(); return none }
            "Seek" -> {
                when (a["Unit"]) {
                    "REL_TIME", "ABS_TIME" -> player.seek(parseTime(a["Target"]))
                    "TRACK_NR" -> {}
                    else -> throw UpnpError(710, "Seek mode not supported")
                }
                return none
            }
            "Next", "Previous" -> throw UpnpError(701, "Transition not available")
        }
        val state = player.transportState()
        val uri = player.currentUri()
        val meta = player.currentMetadata()
        val dur = fmtTime(player.duration())
        val pos = fmtTime(player.position())
        return when (action) {
            "GetTransportInfo" -> listOf("CurrentTransportState" to state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> listOf(
                "Track" to if (uri.isEmpty()) "0" else "1", "TrackDuration" to dur, "TrackMetaData" to meta,
                "TrackURI" to uri, "RelTime" to pos, "AbsTime" to pos,
                "RelCount" to "2147483647", "AbsCount" to "2147483647",
            )
            "GetMediaInfo" -> listOf(
                "NrTracks" to if (uri.isEmpty()) "0" else "1", "MediaDuration" to dur,
                "CurrentURI" to uri, "CurrentURIMetaData" to meta,
                "NextURI" to "", "NextURIMetaData" to "", "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetDeviceCapabilities" -> listOf(
                "PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to transportActions(state))
            else -> throw UpnpError(401, "Invalid Action")
        }
    }

    private fun renderingControl(action: String, a: Map<String, String>): List<Pair<String, String>> = when (action) {
        "GetVolume" -> listOf("CurrentVolume" to player.volume().toString())
        "SetVolume" -> {
            val v = a["DesiredVolume"]?.toIntOrNull() ?: throw UpnpError(402, "Invalid Args")
            player.setVolume(v.coerceIn(0, 100)); emptyList()
        }
        "GetMute" -> listOf("CurrentMute" to if (player.muted()) "1" else "0")
        "SetMute" -> {
            val v = a["DesiredMute"]?.lowercase() ?: throw UpnpError(402, "Invalid Args")
            player.setMuted(v == "1" || v == "true" || v == "yes"); emptyList()
        }
        "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
        "SelectPreset" -> emptyList()
        else -> throw UpnpError(401, "Invalid Action")
    }

    private fun connectionManager(action: String, a: Map<String, String>): List<Pair<String, String>> = when (action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to sinkProtocolInfo())
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
        )
        else -> throw UpnpError(401, "Invalid Action")
    }

    // ---------------------------------------------------------- eventos

    private fun event(svc: ServiceDef, method: String, headers: Map<String, String>): HttpReply {
        when (method) {
            "SUBSCRIBE" -> {
                val renew = headers["sid"]
                if (renew != null) {
                    val sub = subs[renew] ?: return HttpReply(412, "text/plain")
                    sub.expires = clock() + SUBSCRIPTION_SECONDS * 1000L
                    return HttpReply(200, "text/plain", headers = subHeaders(renew))
                }
                val urls = Regex("<([^>]+)>").findAll(headers["callback"].orEmpty())
                    .map { it.groupValues[1] }.filter { it.startsWith("http") }.toList()
                if (urls.isEmpty()) return HttpReply(412, "text/plain")
                val sid = "uuid:" + UUID.randomUUID()
                subs[sid] = Subscription(sid, svc.key, urls, clock() + SUBSCRIPTION_SECONDS * 1000L)
                return HttpReply(200, "text/plain", headers = subHeaders(sid))
            }
            "UNSUBSCRIBE" -> {
                headers["sid"]?.let { subs.remove(it) }
                return HttpReply(200, "text/plain")
            }
        }
        return HttpReply(405, "text/plain")
    }

    private fun subHeaders(sid: String) = mapOf("SID" to sid, "TIMEOUT" to "Second-$SUBSCRIPTION_SECONDS")

    private fun attr(n: String, v: String) = "<$n val=\"${Xml.escape(v)}\"/>"

    fun avtLastChange(): String {
        val st = player.transportState()
        val uri = player.currentUri()
        val meta = player.currentMetadata()
        val dur = fmtTime(player.duration())
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
            attr("TransportState", st) + attr("TransportStatus", "OK") +
            attr("NumberOfTracks", if (uri.isEmpty()) "0" else "1") + attr("CurrentTrack", "1") +
            attr("CurrentTrackURI", uri) + attr("AVTransportURI", uri) +
            attr("CurrentTrackMetaData", meta) + attr("AVTransportURIMetaData", meta) +
            attr("CurrentTrackDuration", dur) + attr("CurrentMediaDuration", dur) +
            attr("CurrentTransportActions", transportActions(st)) +
            "</InstanceID></Event>"
    }

    fun rcsLastChange(): String =
        "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"${player.volume()}\"/>" +
            "<Mute channel=\"Master\" val=\"${if (player.muted()) 1 else 0}\"/>" +
            "</InstanceID></Event>"

    private fun propertySet(vararg props: Pair<String, String>): String {
        val sb = StringBuilder("<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
        props.forEach { (k, v) -> sb.append("<e:property><$k>${Xml.escape(v)}</$k></e:property>") }
        sb.append("</e:propertyset>")
        return sb.toString()
    }

    /** Avisa a los controladores suscritos cuando cambia el estado (se llama cada ~1 s). */
    fun pollEvents() {
        val t = clock()
        subs.values.removeIf { it.expires < t || it.failures >= 3 }
        if (subs.isEmpty()) return
        val avt = avtLastChange()
        val rcs = rcsLastChange()
        for (sub in subs.values.toList()) {
            val change = when (sub.service) {
                "AVTransport" -> avt
                "RenderingControl" -> rcs
                else -> "cm"
            }
            if (!sub.initial && sub.last == change) continue
            val body = when (sub.service) {
                "AVTransport", "RenderingControl" -> propertySet("LastChange" to change)
                else -> propertySet(
                    "SourceProtocolInfo" to "", "SinkProtocolInfo" to sinkProtocolInfo(), "CurrentConnectionIDs" to "0",
                )
            }
            var delivered = false
            for (url in sub.callbacks) {
                val ok = try {
                    notify(url, mapOf(
                        "NT" to "upnp:event", "NTS" to "upnp:propchange", "SID" to sub.sid,
                        "SEQ" to sub.seq.toString(), "CONTENT-TYPE" to "text/xml; charset=\"utf-8\"",
                    ), body)
                } catch (_: Exception) { false }
                if (ok) { delivered = true; break }
            }
            if (delivered) {
                sub.last = change
                sub.initial = false
                sub.seq++
                sub.failures = 0
            } else {
                sub.failures++
            }
        }
    }

    fun subscriberCount(): Int = subs.size
}

// ------------------------------------------------------------------ SSDP

object Ssdp {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900

    fun targets(uuid: String): List<String> =
        listOf("upnp:rootdevice", "uuid:$uuid", DlnaCore.DEVICE, DlnaCore.AVT, DlnaCore.RCS, DlnaCore.CM)

    fun usn(uuid: String, target: String) = if (target == "uuid:$uuid") target else "uuid:$uuid::$target"

    private fun header(lines: List<String>, name: String): String? =
        lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.trim('"')

    /** El ST de un M-SEARCH, o null si el paquete no es una búsqueda. */
    fun searchTarget(packet: String): String? {
        val lines = packet.lines()
        if (!lines.first().startsWith("M-SEARCH", ignoreCase = true)) return null
        if (header(lines, "MAN")?.contains("ssdp:discover", ignoreCase = true) != true) return null
        return header(lines, "ST")
    }

    fun matching(st: String, uuid: String): List<String> =
        if (st.equals("ssdp:all", ignoreCase = true)) targets(uuid)
        else targets(uuid).filter { it.equals(st, ignoreCase = true) }

    fun response(target: String, location: String, uuid: String): ByteArray = (
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $location\r\n" +
            "SERVER: ${DlnaCore.SERVER}\r\nST: $target\r\nUSN: ${usn(uuid, target)}\r\n\r\n"
        ).toByteArray(Charsets.ISO_8859_1)

    fun notify(target: String, location: String, uuid: String, nts: String): ByteArray {
        val sb = StringBuilder("NOTIFY * HTTP/1.1\r\nHOST: $GROUP:$PORT\r\n")
        if (nts == "ssdp:alive") sb.append("CACHE-CONTROL: max-age=1800\r\nLOCATION: $location\r\n")
        sb.append("NT: $target\r\nNTS: $nts\r\nSERVER: ${DlnaCore.SERVER}\r\nUSN: ${usn(uuid, target)}\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

// ------------------------------------------------------------------ HTTP

/** Cliente HTTP mínimo para métodos que HttpURLConnection no permite (NOTIFY). */
object RawHttp {
    fun send(method: String, url: String, headers: Map<String, String>, body: String, timeoutMs: Int = 2500): Int {
        val u = URL(url)
        val port = if (u.port > 0) u.port else 80
        Socket().use { s ->
            s.connect(InetSocketAddress(u.host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val bytes = body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder("$method ${u.file.ifEmpty { "/" }} HTTP/1.1\r\nHOST: ${u.host}:$port\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("CONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
            val status = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return -1
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }
}

/** Servidor HTTP mínimo (GET, POST, SUBSCRIBE, NOTIFY…) que sólo atiende a la red local. */
class SimpleHttpServer(
    preferredPort: Int,
    private val handler: (method: String, path: String, headers: Map<String, String>, body: String) -> HttpReply,
) {
    private val server: ServerSocket = try { ServerSocket(preferredPort) } catch (_: Exception) { ServerSocket(0) }
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    val port: Int get() = server.localPort

    fun start() {
        if (running) return
        running = true
        Thread({
            while (running && !server.isClosed) {
                try {
                    val s = server.accept()
                    pool.execute { serve(s) }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "simple-http").apply { isDaemon = true }.start()
    }

    fun close() {
        running = false
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val remote = s.inetAddress
            if (!(remote.isSiteLocalAddress || remote.isLoopbackAddress || remote.isLinkLocalAddress)) return
            try {
                s.soTimeout = 10_000
                val input = java.io.BufferedInputStream(s.getInputStream())
                val parts = (readLine(input) ?: return).split(' ')
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 1_000_000)
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val reply = try {
                    handler(parts[0].uppercase(), parts[1], headers, String(body, 0, read, Charsets.UTF_8))
                } catch (e: Exception) {
                    HttpReply(500, "text/plain", e.message ?: "error")
                }
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder("HTTP/1.1 ${reply.code} ${reason(reply.code)}\r\n")
                sb.append("Content-Type: ${reply.contentType}\r\nContent-Length: ${bytes.size}\r\n")
                sb.append("SERVER: ${DlnaCore.SERVER}\r\nEXT:\r\n")
                reply.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                sb.append("Connection: close\r\n\r\n")
                val out = s.getOutputStream()
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (parts[0].uppercase() != "HEAD") out.write(bytes)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 412 -> "Precondition Failed"
        500 -> "Internal Server Error"; else -> "OK"
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }
}
