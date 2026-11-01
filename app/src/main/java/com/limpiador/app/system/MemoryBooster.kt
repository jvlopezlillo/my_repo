package com.limpiador.app.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class BoostResult(val appsClosed: Int, val freedBytes: Long)

/**
 * Cierra procesos en segundo plano de apps de usuario.
 * Android puede volver a abrirlos; el efecto es temporal, pero útil en móviles con poca RAM.
 */
class MemoryBooster(private val context: Context) {

    suspend fun boost(): BoostResult = withContext(Dispatchers.Default) {
        val am = context.getSystemService(ActivityManager::class.java)
        val before = SystemInfo.ram(context).availableBytes

        @Suppress("DEPRECATION")
        val targets = context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }

        var closed = 0
        for (app in targets) {
            try {
                am.killBackgroundProcesses(app.packageName)
                closed++
            } catch (e: SecurityException) {
                // Algunos fabricantes lo bloquean para ciertas apps; se ignora.
            }
        }
        delay(1500) // deja que el sistema actualice las cifras de memoria
        val after = SystemInfo.ram(context).availableBytes
        BoostResult(closed, (after - before).coerceAtLeast(0))
    }
}
