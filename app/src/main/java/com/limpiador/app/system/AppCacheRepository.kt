package com.limpiador.app.system

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppStorage(
    val packageName: String,
    val label: String,
    val cacheBytes: Long,
    val totalBytes: Long,
    val isSystem: Boolean,
    val icon: ImageBitmap?,
)

/** Lee cuánto ocupa la caché de cada app instalada (requiere acceso a datos de uso). */
class AppCacheRepository(private val context: Context) {

    suspend fun load(includeSystem: Boolean): List<AppStorage> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()

        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
            .filter { includeSystem || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .mapNotNull { app ->
                val s = try {
                    stats.queryStatsForPackage(app.storageUuid, app.packageName, user)
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                val icon = try {
                    pm.getApplicationIcon(app).toBitmap(96, 96).asImageBitmap()
                } catch (e: Exception) {
                    null
                }
                AppStorage(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString(),
                    cacheBytes = s.cacheBytes,
                    totalBytes = s.appBytes + s.dataBytes,
                    isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = icon,
                )
            }
            .sortedByDescending { it.cacheBytes }
    }
}
