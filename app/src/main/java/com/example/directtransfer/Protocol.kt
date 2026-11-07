package com.example.directtransfer

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

object Protocol {
    /** Servicio BLE que anuncia el receptor. */
    val SERVICE_UUID: UUID = UUID.fromString("7d1f0001-5b0e-4c1a-9c55-3f6a2d9e1b10")

    /** Característica legible con los datos de la red Wi-Fi Direct (JSON de [LinkInfo]). */
    val LINK_CHAR_UUID: UUID = UUID.fromString("7d1f0002-5b0e-4c1a-9c55-3f6a2d9e1b10")

    const val TCP_PORT = 8988
    const val MAGIC = 0x44545232 // "DTR2" (protocolo con reanudación)
    const val BUFFER_SIZE = 256 * 1024
    const val DEFAULT_GROUP_OWNER_IP = "192.168.49.1"

    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun randomString(length: Int): String =
        buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}

/** Datos para unirse a la red creada por el receptor. */
data class LinkInfo(
    val ssid: String,
    val passphrase: String,
    val host: String,
    val port: Int,
    val token: String,
    /** Dirección Wi-Fi Direct del receptor (para el método de conexión clásico). */
    val mac: String? = null,
    /** Descripción del móvil receptor (solo diagnóstico). */
    val dev: String = "",
    /** Nombre del dispositivo Wi-Fi Direct del receptor (la dirección viene oculta en Android 10+). */
    val pname: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("ssid", ssid).put("pass", passphrase).put("host", host)
        .put("port", port).put("token", token)
        .put("mac", mac?.takeIf { it != "02:00:00:00:00:00" } ?: "").put("dev", dev).put("pname", pname).toString()

    companion object {
        fun fromJson(json: String): LinkInfo {
            val o = JSONObject(json)
            return LinkInfo(
                o.getString("ssid"), o.getString("pass"), o.getString("host"),
                o.getInt("port"), o.getString("token"),
                o.optString("mac").ifEmpty { null }, o.optString("dev"), o.optString("pname"),
            )
        }
    }
}

/** Un fichero a enviar. [relativePath] conserva la estructura de carpetas ("dir/sub/a.txt"). */
data class SendItem(val uri: Uri, val relativePath: String, val size: Long)

data class IncomingRequest(
    val senderName: String,
    val fileCount: Int,
    val totalBytes: Long,
    /** Bytes que ya se tienen de un intento anterior (reanudación). */
    val resumeBytes: Long = 0,
)

data class Progress(
    val fileName: String = "",
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0,
) {
    val percent: Int
        get() = if (totalBytes <= 0) 100 else (bytesDone * 100 / totalBytes).toInt().coerceIn(0, 100)
}

class RejectedException : IOException("El otro dispositivo rechazó la transferencia")

/** Calcula el progreso global y limita la frecuencia de actualización de la UI. */
class ProgressTracker(
    private val fileCount: Int,
    private val totalBytes: Long,
    private val emit: (Progress) -> Unit,
    initialBytes: Long = 0,
) {
    private val start = SystemClock.elapsedRealtime()
    private val initial = initialBytes
    private var lastEmit = 0L
    private var done = initialBytes
    private var fileName = ""
    private var fileIndex = 0

    fun startFile(index: Int, name: String) {
        fileIndex = index
        fileName = name
        push(true)
    }

    fun add(bytes: Int) {
        done += bytes
        push(false)
    }

    fun finish() = push(true)

    private fun push(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEmit < 100) return
        lastEmit = now
        val elapsed = (now - start).coerceAtLeast(1)
        emit(Progress(fileName, fileIndex, fileCount, done, totalBytes, (done - initial) * 1000 / elapsed))
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return "%.1f %s".format(value, units[i])
}

fun deviceDescription(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
