package com.example.iptv.cast

import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico en memoria (últimas líneas) para el envío a Chromecast.
 * No guarda credenciales: las direcciones se muestran sin rutas ni valores de parámetros (ver [redact]).
 */
object DebugLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX = 800

    /** Cambia con cada línea nueva: la interfaz lo lee para refrescarse. */
    var version by mutableStateOf(0)
        private set

    @Synchronized
    fun log(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
        version++
        Log.d("IptvCast", msg)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        version++
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("IPTV TV · registro de diagnóstico de Chromecast")
        appendLine("Móvil: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Las direcciones van sin rutas ni credenciales; sí aparece el dominio del servidor y, al elegir un Chromecast, el nombre de su Wi-Fi.")
        appendLine("-----")
        lines.forEach { appendLine(it) }
    }

    /** Oculta rutas (pueden llevar usuario/contraseña) y valores de parámetros; deja host, nº de segmentos y extensión. */
    fun redact(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return "(dirección no válida)"
        val segs = u.pathSegments.filter { it.isNotEmpty() }
        val ext = segs.lastOrNull()?.substringAfterLast('.', "")?.take(6)?.takeIf { it.isNotEmpty() }
        val q = u.queryParameterNames.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " ?[", postfix = "]") ?: ""
        return "${u.scheme}://${u.host}:${u.port}/(${segs.size} tramos${if (ext != null) ", .$ext" else ""})$q"
    }
}
