package com.futmondo.app

import android.content.Context
import com.futmondo.core.Analysis
import com.futmondo.core.Backup
import com.futmondo.core.Scored
import com.futmondo.core.FutmondoApi
import com.futmondo.core.FutmondoService
import com.futmondo.core.History
import com.futmondo.core.HistoryKind
import com.futmondo.core.MarketResult
import com.futmondo.core.Predictions
import com.futmondo.core.SellCandidate

/** Une servicio, historial y predicciones. Lo usan tanto la pantalla como la revisión diaria. */
class Controller(private val context: Context, val api: FutmondoApi) {
    val data = LocalData(context)
    private val service = FutmondoService(api)

    /** Compara predicciones pasadas con los puntos reales. Un fallo aquí nunca debe romper el resto. */
    private fun resolvePast() {
        try {
            val cur = data.predictions()
            val upd = service.resolve(cur)
            if (upd != cur) data.savePredictions(upd)
        } catch (e: Exception) {
            // Se reintentará en la siguiente ejecución.
        }
    }

    fun analyze(kind: HistoryKind = HistoryKind.ANALISIS, prefix: String = "", scheduleAlert: Boolean = true): Analysis {
        resolvePast()
        val a = service.analyze(data.calibration())
        a.prediction?.let { data.savePredictions(Predictions.upsert(data.predictions(), it)) }
        data.addHistory(kind, prefix + History.analysisSummary(a))
        // Aviso previo al cierre de la alineación (solo si la revisión diaria está activada).
        if (scheduleAlert && data.dailyEnabled) a.firstMatchMillis?.let { LockAlert.schedule(context, it) }
        return a
    }

    fun reviewMarket(record: Boolean = true): MarketResult {
        resolvePast()
        // Aprende las pujas de la liga; si falla (p. ej. el tablón cambió) se usan los porcentajes por defecto.
        try { data.saveBids(service.refreshBids(data.bids())) } catch (e: Exception) { /* sin datos nuevos */ }
        val m = service.reviewMarket(data.calibration(), data.bids())
        if (record) data.addHistory(HistoryKind.MERCADO, History.marketSummary(m))
        return m
    }

    fun putOnSale(chosen: List<SellCandidate>): List<Pair<SellCandidate, String>> {
        val failures = service.putOnSale(chosen)
        data.addHistory(HistoryKind.VENTA, History.saleSummary(chosen, failures))
        return failures
    }

    fun precisionReport(): String {
        resolvePast()
        return Predictions.report(data.predictions())
    }

    fun comparisonPool(): List<Scored> = service.comparisonPool(data.calibration())

    fun exportBackup(): String = Backup.export(data.backupData())

    /** Fusiona una copia con los datos actuales. Devuelve false si el archivo no es una copia válida. */
    fun importBackup(text: String): Boolean {
        val imp = Backup.parse(text) ?: return false
        data.restore(Backup.merge(data.backupData(), imp))
        return true
    }

    /**
     * Revisión diaria: una sola pasada (cada dato se pide una vez). NO escribe en el historial:
     * lo hace quien la llama, una única vez y solo si termina bien.
     */
    fun dailyReview(): Pair<Analysis, MarketResult> {
        resolvePast()
        try { data.saveBids(service.refreshBids(data.bids())) } catch (e: Exception) { /* sin datos nuevos */ }
        val (a, m) = service.dailyReview(data.calibration(), data.bids())
        a.prediction?.let { data.savePredictions(Predictions.upsert(data.predictions(), it)) }
        a.firstMatchMillis?.let { LockAlert.schedule(context, it) }
        return a to m
    }
}
