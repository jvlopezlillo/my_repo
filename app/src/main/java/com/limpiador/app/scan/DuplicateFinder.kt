package com.limpiador.app.scan

import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * Busca archivos idénticos en tres pasos para no leer todo el almacenamiento:
 * 1) mismo tamaño, 2) mismo hash de los primeros 64 KB, 3) mismo hash completo.
 */
object DuplicateFinder {
    const val MIN_SIZE = 256L * 1024
    private const val PARTIAL_BYTES = 64 * 1024

    fun find(sizeBuckets: Map<Long, List<File>>, job: CoroutineContext): List<JunkItem> {
        val result = mutableListOf<JunkItem>()
        for ((_, sameSize) in sizeBuckets) {
            if (sameSize.size < 2) continue
            val byPartial = sameSize.groupBy { hash(it, PARTIAL_BYTES, job) }
            for ((partial, candidates) in byPartial) {
                if (partial == null || candidates.size < 2) continue
                val byFull = candidates.groupBy { hash(it, Int.MAX_VALUE, job) }
                for ((full, copies) in byFull) {
                    if (full == null || copies.size < 2) continue
                    // Se conserva el más antiguo; el resto se ofrece para borrar.
                    val sorted = copies.sortedWith(compareBy({ it.lastModified() }, { it.path.length }))
                    val original = sorted.first()
                    sorted.drop(1).forEach { copy ->
                        result += JunkItem(
                            path = copy.path,
                            name = copy.name,
                            size = copy.length(),
                            category = JunkCategory.DUPLICATES,
                            detail = "Copia de ${original.path}",
                            recommended = false,
                            isDirectory = false,
                        )
                    }
                }
            }
        }
        return result
    }

    private fun hash(file: File, limit: Int, job: CoroutineContext): String? = try {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var remaining = limit.toLong()
        file.inputStream().use { input ->
            while (remaining > 0) {
                job.ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: java.io.IOException) {
        null
    }
}
