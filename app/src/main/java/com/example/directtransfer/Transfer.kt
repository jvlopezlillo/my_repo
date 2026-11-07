package com.example.directtransfer

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext

/** Construye la lista de ficheros a enviar a partir de lo que elige el usuario. */
object ItemCollector {
    fun fromFiles(ctx: Context, uris: List<Uri>): List<SendItem> = uris.mapNotNull { uri ->
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0) ?: "archivo"
                var size = if (c.isNull(1)) -1L else c.getLong(1)
                if (size < 0) {
                    size = runCatching {
                        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    }.getOrDefault(-1L)
                }
                if (size < 0) return@use null // tamaño desconocido: no se puede enviar de forma fiable
                SendItem(uri, name, size)
            }
    }

    /** Recorre la carpeta recursivamente conservando la estructura (las carpetas vacías no se envían). */
    fun fromTree(ctx: Context, treeUri: Uri): List<SendItem> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val out = mutableListOf<SendItem>()
        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val name = f.name ?: continue
                if (f.isDirectory) walk(f, "$prefix$name/") else if (f.isFile) out.add(SendItem(f.uri, prefix + name, f.length()))
            }
        }
        walk(root, (root.name ?: "carpeta") + "/")
        return out
    }
}

/**
 * Protocolo TCP (todo big-endian, vía DataInput/OutputStream):
 *
 *  emisor -> receptor : int MAGIC, UTF token, UTF nombreEmisor, int len, byte[len] manifiesto JSON
 *  receptor -> emisor : byte 1 (aceptado) / 0 (rechazado)
 *  emisor -> receptor : bytes de cada fichero, uno tras otro, en el orden del manifiesto
 *  receptor -> emisor : byte 1 (todo recibido)
 *
 * Manifiesto: {"files":[{"path":"dir/a.txt","size":123}, ...]}
 */
class FileSender(private val ctx: Context) {
    @Volatile private var socket: Socket? = null

    fun abort() {
        runCatching { socket?.close() }
    }

    suspend fun send(
        items: List<SendItem>,
        info: LinkInfo,
        network: Network,
        gateway: String?,
        deviceName: String,
        onAccepted: () -> Unit,
        onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val sock = connectWithRetry(network, listOfNotNull(gateway, info.host).distinct(), info.port)
        socket = sock
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 0
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), Protocol.BUFFER_SIZE))
            val inp = DataInputStream(sock.getInputStream())

            val manifest = JSONObject().put(
                "files",
                JSONArray().also { arr ->
                    items.forEach { arr.put(JSONObject().put("path", it.relativePath).put("size", it.size)) }
                },
            ).toString().toByteArray(Charsets.UTF_8)

            out.writeInt(Protocol.MAGIC)
            out.writeUTF(info.token)
            out.writeUTF(deviceName)
            out.writeInt(manifest.size)
            out.write(manifest)
            out.flush()

            if (inp.readByte().toInt() != 1) throw RejectedException()
            onAccepted()

            val total = items.sumOf { it.size }
            val tracker = ProgressTracker(items.size, total, onProgress)
            val buf = ByteArray(Protocol.BUFFER_SIZE)
            items.forEachIndexed { index, item ->
                tracker.startFile(index + 1, item.relativePath)
                var remaining = item.size
                val stream = ctx.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("No se pudo abrir ${item.relativePath}")
                stream.use { s ->
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val n = s.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("${item.relativePath} cambió durante el envío")
                        out.write(buf, 0, n)
                        remaining -= n
                        tracker.add(n)
                    }
                }
            }
            out.flush()
            tracker.finish()
            if (inp.readByte().toInt() != 1) throw IOException("El receptor no confirmó la recepción")
        } finally {
            runCatching { sock.close() }
            socket = null
        }
    }

    private suspend fun connectWithRetry(network: Network, hosts: List<String>, port: Int): Socket {
        var last: Exception? = null
        repeat(8) {
            for (h in hosts) {
                coroutineContext.ensureActive()
                val s = network.socketFactory.createSocket()
                try {
                    s.connect(InetSocketAddress(h, port), 5_000)
                    return s
                } catch (e: IOException) {
                    last = e
                    runCatching { s.close() }
                }
            }
            delay(1_000) // el DHCP del grupo puede tardar un instante
        }
        throw IOException("No se pudo conectar con el receptor: ${last?.message}")
    }
}

class FileReceiver(private val ctx: Context, private val token: String, private val port: Int) {
    private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    data class Result(val files: Int, val bytes: Long)

    fun open() {
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    }

    fun abort() {
        runCatching { client?.close() }
        runCatching { server?.close() }
    }

    /**
     * Espera una conexión válida y la recibe. Si el usuario rechaza la petición se sigue esperando.
     */
    suspend fun receive(
        onRequest: suspend (IncomingRequest) -> Boolean,
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val srv = server ?: throw IOException("Servidor no iniciado")
        while (true) {
            val sock = srv.accept()
            client = sock
            var accepted = false
            try {
                sock.soTimeout = 10_000
                val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), Protocol.BUFFER_SIZE))
                val out = DataOutputStream(sock.getOutputStream())
                if (inp.readInt() != Protocol.MAGIC) continue
                if (inp.readUTF() != token) continue
                val senderName = inp.readUTF()
                val len = inp.readInt()
                if (len !in 1..(32 * 1024 * 1024)) continue
                val raw = ByteArray(len)
                inp.readFully(raw)
                val entries = parseManifest(String(raw, Charsets.UTF_8))
                val total = entries.sumOf { it.second }

                val ok = onRequest(IncomingRequest(senderName, entries.size, total))
                out.writeByte(if (ok) 1 else 0)
                out.flush()
                if (!ok) continue
                accepted = true

                sock.soTimeout = 60_000
                val tracker = ProgressTracker(entries.size, total, onProgress)
                val buf = ByteArray(Protocol.BUFFER_SIZE)
                entries.forEachIndexed { index, (path, size) ->
                    tracker.startFile(index + 1, path)
                    saveFile(path, size, inp, buf, tracker)
                }
                tracker.finish()
                out.writeByte(1)
                out.flush()
                return@withContext Result(entries.size, total)
            } catch (e: IOException) {
                if (accepted) throw e
            } finally {
                runCatching { sock.close() }
                client = null
            }
        }
        throw IllegalStateException()
    }

    private fun parseManifest(json: String): List<Pair<String, Long>> {
        val arr = JSONObject(json).getJSONArray("files")
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            o.getString("path") to o.getLong("size").also { if (it < 0) throw IOException("Tamaño inválido") }
        }
    }

    private suspend fun saveFile(
        path: String, size: Long, inp: DataInputStream, buf: ByteArray, tracker: ProgressTracker,
    ) {
        // Evitar rutas maliciosas: se descartan segmentos vacíos, "." y "..".
        val segments = path.split('/').map { it.replace(Regex("[\\\\:*?\"<>|]"), "_") }
            .filter { it.isNotBlank() && it != "." && it != ".." }
        if (segments.isEmpty()) throw IOException("Ruta inválida")
        val name = segments.last()
        val dir = segments.dropLast(1).joinToString("/")
        val relative = "Download/$RECEIVE_DIR" + if (dir.isEmpty()) "" else "/$dir"
        val ext = name.substringAfterLast('.', "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("No se pudo crear $name")
        try {
            resolver.openOutputStream(uri)?.use { os ->
                var remaining = size
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Conexión cerrada antes de terminar $name")
                    os.write(buf, 0, n)
                    remaining -= n
                    tracker.add(n)
                }
            } ?: throw IOException("No se pudo escribir $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    companion object {
        const val RECEIVE_DIR = "DirectTransfer"
    }
}
