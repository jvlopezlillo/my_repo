package com.castbrowser.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/** Lee de un flujo empujado por el móvil (push://<id>) directamente de la memoria, sin pasar por HTTP. */
@UnstableApi
class PushDataSource : BaseDataSource(false) {
    private var reader: PushStream.Reader? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host.orEmpty()
        reader = PushStreams.getOrCreate(id).reader(dataSpec.position)
        PushStreams.event("el reproductor abre push://$id en ${dataSpec.position}")
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = reader ?: return C.RESULT_END_OF_INPUT
        var waited = 0L
        while (true) {
            val n = try {
                r.read(buffer, offset, length, 1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            if (n > 0) { bytesTransferred(n); return n }
            if (n < 0) return C.RESULT_END_OF_INPUT
            waited += 1000
            if (waited >= 60_000) throw IOException("no llegan datos del móvil desde hace 60 s")
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
            PushStreams.event("el reproductor cierra la lectura")
        }
        reader = null
        uri = null
    }
}

/** Reparte según el esquema: push:// va a [PushDataSource]; todo lo demás, al origen HTTP normal. */
@UnstableApi
class RoutingDataSource(private val http: DataSource, private val push: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
        push.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        current = if (dataSpec.uri.scheme == "push") push else http
        return current!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try { current?.close() } finally { current = null }
    }
}
