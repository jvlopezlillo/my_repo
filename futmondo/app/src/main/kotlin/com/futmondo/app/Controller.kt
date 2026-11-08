package com.futmondo.app

import android.content.Context
import com.futmondo.core.Analysis
import com.futmondo.core.FutmondoApi
import com.futmondo.core.FutmondoService
import com.futmondo.core.History
import com.futmondo.core.HistoryKind
import com.futmondo.core.MarketResult
import com.futmondo.core.Predictions
import com.futmondo.core.SellCandidate

/** Une servicio, historial y predicciones. Lo usan tanto la pantalla como la revisión diaria. */
class Controller(context: Context, val api: FutmondoApi) {
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

    fun analyze(kind: HistoryKind = HistoryKind.ANALISIS, prefix: String = ""): Analysis {
        resolvePast()
        val a = service.analyze(data.calibration())
        a.prediction?.let { data.savePredictions(Predictions.upsert(data.predictions(), it)) }
        data.addHistory(kind, prefix + History.analysisSummary(a))
        return a
    }

    fun reviewMarket(record: Boolean = true): MarketResult {
        resolvePast()
        val m = service.reviewMarket(data.calibration())
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
}
