package com.example.directtransfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.net.SocketFactory
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

/** Identificador estable de una transferencia: mismo conjunto de rutas+tamaños => mismo id (permite reanudar). */
fun transferId(entries: List<Pair<String, Long>>): String {
    val md = MessageDigest.getInstance("SHA-256")
    entries.forEach { md.update("${it.first}\u0000${it.second}\n".toByteArray(Charsets.UTF_8)) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun skipFully(s: InputStream, n: Long) {
    var left = n
    while (left > 0) {
        val k = s.skip(left)
        if (k > 0) left -= k
        else {
            if (s.read() < 0) throw IOException("Fin de archivo inesperado al reanudar")
            left--
        }
    }
}

/**
 * Protocolo TCP (big-endian, DataInput/OutputStream):
 *
 *  emisor -> receptor : int MAGIC, UTF token, UTF nombreEmisor, int len, byte[len] manifiesto JSON
 *  receptor -> emisor : byte 1 (aceptado) / 0 (rechazado);
 *                       si aceptado: int n, long offset[n]  (bytes que el receptor YA tiene de cada fichero)
 *  emisor -> receptor : para cada fichero, los bytes desde su offset hasta el final
 *  receptor -> emisor : byte 1 (todo recibido)
 *
 * Manifiesto: {"id":"<sha256>","files":[{"path":"dir/a.txt","size":123}, ...]}
 */
class FileSender(private val ctx: Context) {
    @Volatile private var socket: Socket? = null

    fun abort() {
        runCatching { socket?.close() }
    }

    suspend fun send(
        items: List<SendItem>,
        info: LinkInfo,
        socketFactory: SocketFactory,
        hosts: List<String>,
        deviceName: String,
        onAccepted: (resumedBytes: Long) -> Unit,
        onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val sock = connectWithRetry(socketFactory, hosts, info.port)
        socket = sock
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 0
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), Protocol.BUFFER_SIZE))
            val inp = DataInputStream(sock.getInputStream())

            val id = transferId(items.map { it.relativePath to it.size })
            val manifest = JSONObject()
                .put("id", id)
                .put(
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
            val n = inp.readInt()
            if (n != items.size) throw IOException("Respuesta de reanudación inválida")
            val offsets = LongArray(n) { inp.readLong() }
            offsets.forEachIndexed { i, o -> if (o < 0 || o > items[i].size) throw IOException("Offset inválido") }
            val already = offsets.sum()
            onAccepted(already)

            val total = items.sumOf { it.size }
            val tracker = ProgressTracker(items.size, total, onProgress, already)
            val buf = ByteArray(Protocol.BUFFER_SIZE)
            items.forEachIndexed { index, item ->
                tracker.startFile(index + 1, item.relativePath)
                var remaining = item.size - offsets[index]
                if (remaining <= 0 && item.size > 0) return@forEachIndexed
                val stream = ctx.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("No se pudo abrir ${item.relativePath}")
                stream.use { s ->
                    skipFully(s, offsets[index])
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val k = s.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (k < 0) throw IOException("${item.relativePath} cambió durante el envío")
                        out.write(buf, 0, k)
                        remaining -= k
                        tracker.add(k)
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

    private suspend fun connectWithRetry(socketFactory: SocketFactory, hosts: List<String>, port: Int): Socket {
        var last: Exception? = null
        repeat(8) {
            for (h in hosts) {
                coroutineContext.ensureActive()
                val s = socketFactory.createSocket()
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
    private val stagingRoot = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "incoming")

    data class Result(val files: Int, val bytes: Long)

    fun open() {
        // Limpia parciales abandonados hace más de 7 días.
        val limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        stagingRoot.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
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
     * Espera una conexión válida y la recibe. Si el usuario rechaza la petición, o la transferencia
     * se interrumpe, se sigue esperando (los datos parciales se conservan para reanudar).
     */
    suspend fun receive(
        onRequest: suspend (IncomingRequest) -> Boolean,
        onProgress: (Progress) -> Unit,
        onInterrupted: (String) -> Unit,
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
                val (id, entries) = parseManifest(String(raw, Charsets.UTF_8))
                if (id != transferId(entries)) continue
                val total = entries.sumOf { it.second }

                val stage = File(stagingRoot, id).apply { mkdirs() }
                stage.setLastModified(System.currentTimeMillis())
                val offsets = LongArray(entries.size) { i ->
                    val size = entries[i].second
                    val part = File(stage, "f_$i")
                    when {
                        File(stage, "done_$i").exists() -> size
                        part.exists() && part.length() <= size -> part.length()
                        else -> { part.delete(); 0L }
                    }
                }
                val already = offsets.sum()

                val ok = onRequest(IncomingRequest(senderName, entries.size, total, already))
                out.writeByte(if (ok) 1 else 0)
                if (ok) {
                    out.writeInt(entries.size)
                    offsets.forEach { out.writeLong(it) }
                }
                out.flush()
                if (!ok) continue
                accepted = true

                sock.soTimeout = 60_000
                val tracker = ProgressTracker(entries.size, total, onProgress, already)
                val buf = ByteArray(Protocol.BUFFER_SIZE)
                entries.forEachIndexed { i, (path, size) ->
                    tracker.startFile(i + 1, path)
                    if (File(stage, "done_$i").exists()) return@forEachIndexed
                    val part = File(stage, "f_$i")
                    var remaining = size - offsets[i]
                    FileOutputStream(part, true).use { os ->
                        while (remaining > 0) {
                            coroutineContext.ensureActive()
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) throw IOException("Conexión cerrada antes de terminar $path")
                            os.write(buf, 0, n)
                            remaining -= n
                            tracker.add(n)
                        }
                    }
                    publish(path, part, buf)
                    part.delete()
                    File(stage, "done_$i").createNewFile()
                }
                tracker.finish()
                out.writeByte(1)
                out.flush()
                stage.deleteRecursively()
                return@withContext Result(entries.size, total)
            } catch (e: IOException) {
                if (accepted) onInterrupted(e.message ?: "conexión perdida")
            } finally {
                runCatching { sock.close() }
                client = null
            }
        }
        throw IllegalStateException()
    }

    private fun parseManifest(json: String): Pair<String, List<Pair<String, Long>>> {
        val root = JSONObject(json)
        val id = root.getString("id")
        if (!Regex("[0-9a-f]{64}").matches(id)) throw IOException("Identificador inválido")
        val arr = root.getJSONArray("files")
        val list = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            o.getString("path") to o.getLong("size").also { if (it < 0) throw IOException("Tamaño inválido") }
        }
        return id to list
    }

    /** Mueve un fichero completo desde el staging a Descargas/DirectTransfer/<ruta>. */
    private suspend fun publish(path: String, src: File, buf: ByteArray) {
        // Evitar rutas maliciosas: se descartan segmentos vacíos, "." y "..".
        val segments = path.split('/').map { it.replace(Regex("[\\\\:*?\"<>|]"), "_") }
            .filter { it.isNotBlank() && it != "." && it != ".." }
        if (segments.isEmpty()) throw IOException("Ruta inválida")
        val name = segments.last()
        val dir = segments.dropLast(1).joinToString("/")
        val sub = if (dir.isEmpty()) RECEIVE_DIR else "$RECEIVE_DIR/$dir"

        if (Build.VERSION.SDK_INT >= 29) {
            val ext = name.substringAfterLast('.', "")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$sub")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("No se pudo crear $name")
            try {
                val os = resolver.openOutputStream(uri) ?: throw IOException("No se pudo escribir $name")
                src.inputStream().use { i -> os.use { o -> copy(i, o, buf) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(base, sub).apply { mkdirs() }
            val stem = name.substringBeforeLast('.', name)
            val extWithDot = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            val dest = uniqueFile(targetDir, stem, extWithDot)
            try {
                src.inputStream().use { i -> FileOutputStream(dest).use { o -> copy(i, o, buf) } }
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        }
    }

    private fun uniqueFile(dir: File, stem: String, extWithDot: String): File {
        var f = File(dir, "$stem$extWithDot")
        var k = 1
        while (f.exists()) {
            f = File(dir, "$stem ($k)$extWithDot")
            k++
        }
        return f
    }

    private suspend fun copy(i: InputStream, o: java.io.OutputStream, buf: ByteArray) {
        while (true) {
            coroutineContext.ensureActive()
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
        }
    }

    companion object {
        const val RECEIVE_DIR = "DirectTransfer"
    }
}
