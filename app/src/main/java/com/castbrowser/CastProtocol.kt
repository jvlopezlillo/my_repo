package com.castbrowser

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream

/**
 * Mensaje del protocolo Cast v2 (protobuf "CastMessage"), codificado a mano
 * para no depender de librerías externas.
 *
 *   1 protocol_version (enum, 0 = CASTV2_1_0)
 *   2 source_id        (string)
 *   3 destination_id   (string)
 *   4 namespace        (string)
 *   5 payload_type     (enum, 0 = STRING, 1 = BINARY)
 *   6 payload_utf8     (string)
 *   7 payload_binary   (bytes)
 */
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, 0)
        writeStringField(out, 2, sourceId)
        writeStringField(out, 3, destinationId)
        writeStringField(out, 4, namespace)
        writeVarintField(out, 5, 0)
        writeStringField(out, 6, payloadUtf8)
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): CastMessage {
            var pos = 0
            var source = ""
            var dest = ""
            var ns = ""
            var payload = ""

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw EOFException("varint truncado")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalStateException("varint demasiado largo")
                }
            }

            while (pos < data.size) {
                val key = readVarint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> readVarint()
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw EOFException("campo truncado")
                        val s = String(data, pos, len, Charsets.UTF_8)
                        when (field) {
                            2 -> source = s
                            3 -> dest = s
                            4 -> ns = s
                            6 -> payload = s
                        }
                        pos += len
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> throw IllegalStateException("wire type no soportado")
                }
            }
            return CastMessage(source, dest, ns, payload)
        }

        /** Escribe un mensaje con el prefijo de 4 bytes big-endian de longitud. */
        fun writeFramed(out: OutputStream, msg: CastMessage) {
            val body = msg.encode()
            val n = body.size
            val frame = ByteArray(4 + n)
            frame[0] = (n ushr 24).toByte()
            frame[1] = (n ushr 16).toByte()
            frame[2] = (n ushr 8).toByte()
            frame[3] = n.toByte()
            System.arraycopy(body, 0, frame, 4, n)
            out.write(frame)
            out.flush()
        }

        fun readFramed(input: DataInputStream): CastMessage {
            val n = input.readInt()
            if (n < 0 || n > 4 * 1024 * 1024) throw IllegalStateException("tamaño de mensaje inválido: $n")
            val body = ByteArray(n)
            input.readFully(body)
            return decode(body)
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }

        private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
            writeVarint(out, ((field shl 3) or 0).toLong())
            writeVarint(out, value)
        }

        private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeVarint(out, ((field shl 3) or 2).toLong())
            writeVarint(out, bytes.size.toLong())
            out.write(bytes)
        }
    }
}

object CastNs {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
}
