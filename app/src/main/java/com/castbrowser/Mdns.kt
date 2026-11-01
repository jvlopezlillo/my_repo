package com.castbrowser

import java.io.ByteArrayOutputStream

/** Chromecast encontrado en una respuesta mDNS. */
data class MdnsCast(
    val instance: String,
    val name: String,
    val id: String?,
    val model: String?,
    val ip: String?,
    val port: Int,
)

/** Construcción y lectura mínimas de paquetes DNS / mDNS (RFC 1035 / 6762). */
object Mdns {
    const val SERVICE = "_googlecast._tcp.local"
    const val GROUP = "224.0.0.251"
    const val PORT = 5353

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33

    fun buildQuery(service: String = SERVICE, unicastResponse: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        // cabecera: id, flags, qd=1, an=0, ns=0, ar=0
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in service.split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        out.write(0); out.write(TYPE_PTR)
        // clase IN, con el bit QU si se pide respuesta unicast
        out.write(if (unicastResponse) 0x80 else 0x00); out.write(1)
        return out.toByteArray()
    }

    private class Reader(val d: ByteArray) {
        var pos = 0
        fun u8(): Int = d[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun name(): String {
            val labels = mutableListOf<String>()
            var p = pos
            var jumped = false
            var guard = 0
            while (true) {
                if (p >= d.size || guard++ > 128) throw IllegalStateException("nombre DNS inválido")
                val len = d[p].toInt() and 0xFF
                when {
                    len == 0 -> { p++; break }
                    len and 0xC0 == 0xC0 -> {
                        val off = ((len and 0x3F) shl 8) or (d[p + 1].toInt() and 0xFF)
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = off
                    }
                    else -> {
                        labels.add(String(d, p + 1, len, Charsets.UTF_8))
                        p += 1 + len
                    }
                }
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
    }

    /** Devuelve los Chromecast descritos en un paquete de respuesta mDNS. */
    fun parseResponse(packet: ByteArray, sourceIp: String? = null): List<MdnsCast> {
        val ptrs = mutableListOf<String>()
        val srv = HashMap<String, Pair<String, Int>>()
        val txt = HashMap<String, Map<String, String>>()
        val a = HashMap<String, String>()
        try {
            val r = Reader(packet)
            r.u16() // id
            val flags = r.u16()
            if (flags and 0x8000 == 0) return emptyList() // no es respuesta
            val qd = r.u16()
            val total = r.u16() + r.u16() + r.u16()
            repeat(qd) { r.name(); r.u16(); r.u16() }
            repeat(total) {
                val name = r.name()
                val type = r.u16()
                r.u16() // class
                r.u32() // ttl
                val rdLen = r.u16()
                val start = r.pos
                when (type) {
                    TYPE_PTR -> if (name.equals(SERVICE, true)) ptrs.add(r.name())
                    TYPE_SRV -> {
                        r.u16(); r.u16()
                        val port = r.u16()
                        srv[name.lowercase()] = r.name() to port
                    }
                    TYPE_TXT -> {
                        val map = HashMap<String, String>()
                        while (r.pos < start + rdLen) {
                            val len = r.u8()
                            if (len == 0) continue
                            val s = String(packet, r.pos, len, Charsets.UTF_8)
                            r.pos += len
                            val eq = s.indexOf('=')
                            if (eq > 0) map[s.substring(0, eq).lowercase()] = s.substring(eq + 1)
                        }
                        txt[name.lowercase()] = map
                    }
                    TYPE_A -> if (rdLen == 4) {
                        a[name.lowercase()] = "${r.u8()}.${r.u8()}.${r.u8()}.${r.u8()}"
                    }
                }
                r.pos = start + rdLen
            }
        } catch (_: Exception) {
            // paquete truncado: nos quedamos con lo que se haya podido leer
        }

        val instances = LinkedHashSet<String>()
        ptrs.forEach { instances.add(it) }
        srv.keys.filter { it.endsWith(SERVICE.lowercase()) }.forEach { k ->
            if (instances.none { it.lowercase() == k }) instances.add(k)
        }
        txt.keys.filter { it.endsWith(SERVICE.lowercase()) }.forEach { k ->
            if (instances.none { it.lowercase() == k }) instances.add(k)
        }

        return instances.map { inst ->
            val key = inst.lowercase()
            val t = txt[key].orEmpty()
            val s = srv[key]
            val ip = s?.let { a[it.first.lowercase()] } ?: a.values.firstOrNull() ?: sourceIp
            val friendly = t["fn"]?.takeIf { it.isNotBlank() }
                ?: inst.substringBefore("._googlecast").substringBeforeLast('-')
            MdnsCast(
                instance = inst,
                name = friendly,
                id = t["id"],
                model = t["md"],
                ip = ip,
                port = s?.second ?: 8009,
            )
        }
    }
}
