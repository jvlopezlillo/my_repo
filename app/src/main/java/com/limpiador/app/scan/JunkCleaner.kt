package com.limpiador.app.scan

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CleanResult(val cleanedPaths: Set<String>, val freedBytes: Long, val failed: Int)

/** Borra los elementos elegidos, solo dentro del almacenamiento compartido o la caché propia. */
class JunkCleaner(private val context: Context) {

    suspend fun clean(items: List<JunkItem>): CleanResult = withContext(Dispatchers.IO) {
        val storageRoot = Environment.getExternalStorageDirectory().canonicalPath
        val ownCacheDirs = listOfNotNull(
            context.cacheDir.canonicalPath,
            context.externalCacheDir?.canonicalPath,
        )

        val cleaned = mutableSetOf<String>()
        val removedFiles = mutableListOf<String>()
        var freed = 0L
        var failed = 0

        for (item in items.distinctBy { it.path }) {
            val file = File(item.path)
            if (!file.exists()) {
                cleaned += item.path
                continue
            }
            val canonical = file.canonicalPath
            // Nunca se toca la raíz del almacenamiento ni nada fuera de él.
            val insideAllowed = canonical.startsWith("$storageRoot/") ||
                ownCacheDirs.any { canonical == it || canonical.startsWith("$it/") }
            if (!insideAllowed) {
                failed++
                continue
            }

            val ok = when {
                item.category == JunkCategory.EMPTY_FOLDERS -> deleteEmptyTree(file)
                file.isDirectory -> {
                    // Carpetas de caché/miniaturas: se vacían, pero la carpeta se conserva.
                    val before = JunkScanner.dirSize(file)
                    file.listFiles()?.forEach { it.deleteRecursively() }
                    freed += before - JunkScanner.dirSize(file)
                    true
                }
                else -> {
                    val size = file.length()
                    file.delete().also { if (it) { freed += size; removedFiles += file.path } }
                }
            }
            if (ok) cleaned += item.path else failed++
        }

        if (removedFiles.isNotEmpty()) {
            // Avisa a la galería y otras apps de que los archivos ya no existen.
            MediaScannerConnection.scanFile(context, removedFiles.toTypedArray(), null, null)
        }
        CleanResult(cleaned, freed, failed)
    }

    /** Borra una carpeta solo si de verdad no contiene ningún archivo. */
    private fun deleteEmptyTree(dir: File): Boolean {
        if (dir.walkTopDown().any { it.isFile }) return false
        return dir.walkBottomUp().all { it.delete() }
    }
}
