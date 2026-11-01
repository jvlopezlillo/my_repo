package com.limpiador.app.scan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Recorre el almacenamiento compartido y clasifica lo que se puede borrar.
 * No borra nada: solo devuelve la lista de candidatos.
 */
class JunkScanner(private val context: Context) {

    suspend fun scan(onProgress: (ScanProgress) -> Unit): List<JunkGroup> =
        withContext(Dispatchers.IO) {
            val session = Session(coroutineContext, onProgress)
            session.run()
            session.result()
        }

    private inner class Session(
        private val job: CoroutineContext,
        private val onProgress: (ScanProgress) -> Unit,
    ) {
        private val root: File = Environment.getExternalStorageDirectory()
        private val found = LinkedHashMap<JunkCategory, MutableList<JunkItem>>()
        private val sizeBuckets = HashMap<Long, MutableList<File>>()
        private val emptyDirs = mutableListOf<File>()
        private var filesSeen = 0

        fun run() {
            scanOwnCache()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) scanLegacyAppCaches()
            if (root.canRead()) visit(root, 0)
            addEmptyDirs()
            onProgress(ScanProgress(filesSeen, "Buscando duplicados…"))
            DuplicateFinder.find(sizeBuckets, job).forEach { add(it) }
        }

        fun result(): List<JunkGroup> =
            JunkCategory.entries.mapNotNull { cat ->
                found[cat]?.takeIf { it.isNotEmpty() }?.let { items ->
                    JunkGroup(cat, items.sortedByDescending { it.size })
                }
            }

        private fun add(item: JunkItem) {
            found.getOrPut(item.category) { mutableListOf() }.add(item)
        }

        // --- Caché propia y de otras apps (solo Android 10 o inferior) ---

        private fun scanOwnCache() {
            listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
                val size = dirSize(dir)
                if (size > 0) {
                    add(JunkItem(dir.path, "Limpiador (esta app)", size, JunkCategory.APP_CACHE,
                        dir.path, recommended = true, isDirectory = true))
                }
            }
        }

        private fun scanLegacyAppCaches() {
            val dataDir = File(root, "Android/data")
            val pm = context.packageManager
            dataDir.listFiles()?.forEach { pkgDir ->
                if (pkgDir.name == context.packageName) return@forEach
                val cache = File(pkgDir, "cache")
                val size = dirSize(cache)
                if (size > 0) {
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkgDir.name, 0)).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        "${pkgDir.name} (desinstalada)"
                    }
                    add(JunkItem(cache.path, label, size, JunkCategory.APP_CACHE, cache.path,
                        recommended = true, isDirectory = true))
                }
            }
        }

        // --- Recorrido del almacenamiento ---

        /** Devuelve true si la carpeta no contiene ningún archivo (aunque tenga subcarpetas vacías). */
        private fun visit(dir: File, depth: Int): Boolean {
            job.ensureActive()
            if (depth > MAX_DEPTH) return false
            val children = dir.listFiles() ?: return false
            var empty = true
            for (child in children) {
                if (child.isDirectory) {
                    when {
                        depth == 0 && child.name == "Android" -> empty = false
                        child.name.equals(".thumbnails", ignoreCase = true) -> {
                            empty = false
                            val size = dirSize(child)
                            if (size > 0) add(JunkItem(child.path, child.name, size,
                                JunkCategory.THUMBNAILS, child.parent, true, true))
                        }
                        depth == 0 && child.name == "LOST.DIR" -> {
                            empty = false
                            val size = dirSize(child)
                            if (size > 0) add(JunkItem(child.path, child.name, size,
                                JunkCategory.TEMP, "Fragmentos recuperados por el sistema", true, true))
                        }
                        else -> if (!visit(child, depth + 1)) empty = false
                    }
                } else {
                    empty = false
                    classify(child)
                }
            }
            if (empty && depth > 0 && !isProtected(dir)) emptyDirs += dir
            return empty
        }

        private fun classify(file: File) {
            filesSeen++
            if (filesSeen % 250 == 0) onProgress(ScanProgress(filesSeen, file.parent ?: ""))

            val name = file.name.lowercase()
            val size = file.length()
            val ext = name.substringAfterLast('.', "")

            when {
                ext in TEMP_EXTENSIONS || name in TEMP_NAMES ->
                    add(JunkItem(file.path, file.name, size, JunkCategory.TEMP, file.parent, true, false))

                name.startsWith(".thumbdata") ->
                    add(JunkItem(file.path, file.name, size, JunkCategory.THUMBNAILS, file.parent, true, false))

                ext in APK_EXTENSIONS -> {
                    val (detail, recommended) = describeApk(file)
                    add(JunkItem(file.path, file.name, size, JunkCategory.APK, detail, recommended, false))
                }

                size >= LARGE_FILE_BYTES ->
                    add(JunkItem(file.path, file.name, size, JunkCategory.LARGE_FILES, file.parent, false, false))
            }

            if (size >= DuplicateFinder.MIN_SIZE) {
                sizeBuckets.getOrPut(size) { mutableListOf() }.add(file)
            }
        }

        private fun describeApk(file: File): Pair<String, Boolean> {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(file.path, 0)
                ?: return "APK dañado o incompleto" to true
            val installed = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(archive.packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            return if (installed != null &&
                PackageInfoCompat.getLongVersionCode(installed) >= PackageInfoCompat.getLongVersionCode(archive)
            ) {
                "Ya instalado · ${archive.packageName}" to true
            } else {
                "No instalado · ${archive.packageName}" to false
            }
        }

        /** Solo muestra la carpeta vacía de nivel más alto, no cada subcarpeta. */
        private fun addEmptyDirs() {
            val paths = emptyDirs.map { it.path }.toHashSet()
            emptyDirs
                .filter { dir -> dir.parentFile?.path !in paths }
                .forEach { dir ->
                    add(JunkItem(dir.path, dir.name, 0, JunkCategory.EMPTY_FOLDERS, dir.parent,
                        recommended = true, isDirectory = true))
                }
        }

        private fun isProtected(dir: File): Boolean =
            dir.parentFile?.path == root.path && dir.name in STANDARD_DIRS
    }

    companion object {
        private const val MAX_DEPTH = 24
        private const val LARGE_FILE_BYTES = 50L * 1024 * 1024

        private val TEMP_EXTENSIONS = setOf("tmp", "temp", "log", "dmp", "chk")
        private val TEMP_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini")
        private val APK_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm")

        /** Carpetas estándar de Android que nunca se borran aunque estén vacías. */
        private val STANDARD_DIRS = setOf(
            "Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
            "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones",
        )

        fun dirSize(dir: File): Long =
            if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
