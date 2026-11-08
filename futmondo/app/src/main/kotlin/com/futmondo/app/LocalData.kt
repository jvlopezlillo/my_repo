package com.futmondo.app

import android.content.Context
import com.futmondo.core.BackupData
import com.futmondo.core.BidModel
import com.futmondo.core.BidObs
import com.futmondo.core.Calibration
import com.futmondo.core.History
import com.futmondo.core.HistoryEntry
import com.futmondo.core.HistoryKind
import com.futmondo.core.Prediction
import com.futmondo.core.Predictions

/** Datos locales no sensibles: historial, predicciones y ajustes de la revisión diaria. */
class LocalData(context: Context) {
    private val prefs = context.getSharedPreferences("local_data", Context.MODE_PRIVATE)

    fun history(): List<HistoryEntry> = History.fromJson(prefs.getString("history", null))
    fun clearHistory() { prefs.edit().remove("history").apply() }
    fun addHistory(kind: HistoryKind, summary: String) {
        val l = History.add(history(), HistoryEntry(System.currentTimeMillis(), kind, summary))
        prefs.edit().putString("history", History.toJson(l)).apply()
    }

    fun predictions(): List<Prediction> = Predictions.fromJson(prefs.getString("predictions", null))
    fun savePredictions(l: List<Prediction>) { prefs.edit().putString("predictions", Predictions.toJson(l)).apply() }
    fun calibration(): Calibration = Calibration.from(predictions())

    fun bids(): List<BidObs> = BidModel.fromJson(prefs.getString("bids", null))
    fun saveBids(l: List<BidObs>) { prefs.edit().putString("bids", BidModel.toJson(l)).apply() }

    fun backupData() = BackupData(history(), predictions(), bids())
    fun restore(d: BackupData) {
        prefs.edit()
            .putString("history", History.toJson(d.history))
            .putString("predictions", Predictions.toJson(d.predictions))
            .putString("bids", BidModel.toJson(d.bids))
            .apply()
    }

    /** Evita repetir cada día el aviso de "la API ha cambiado". */
    var apiChangedNotified: Boolean
        get() = prefs.getBoolean("api_changed_notified", false)
        set(v) { prefs.edit().putBoolean("api_changed_notified", v).apply() }

    var dailyEnabled: Boolean
        get() = prefs.getBoolean("daily_enabled", false)
        set(v) { prefs.edit().putBoolean("daily_enabled", v).apply() }

    var dailyHour: Int
        get() = prefs.getInt("daily_hour", 9)
        set(v) { prefs.edit().putInt("daily_hour", v).apply() }
}
