package com.futmondo.core

import org.json.JSONObject

/** Copia de seguridad de datos locales. NUNCA incluye token, contraseña ni ids de sesión. */
data class BackupData(
    val history: List<HistoryEntry>,
    val predictions: List<Prediction>,
    val bids: List<BidObs>,
)

object Backup {
    const val VERSION = 1

    fun export(d: BackupData): String = JSONObject()
        .put("app", "futmondo-asistente").put("version", VERSION)
        .put("history", org.json.JSONArray(History.toJson(d.history)))
        .put("predictions", org.json.JSONArray(Predictions.toJson(d.predictions)))
        .put("bids", org.json.JSONArray(BidModel.toJson(d.bids)))
        .toString(2)

    /** Devuelve null si el archivo no es una copia válida. */
    fun parse(text: String): BackupData? = try {
        val o = JSONObject(text)
        if (o.optString("app") != "futmondo-asistente") null
        else BackupData(
            History.fromJson(o.optJSONArray("history")?.toString()),
            Predictions.fromJson(o.optJSONArray("predictions")?.toString()),
            BidModel.fromJson(o.optJSONArray("bids")?.toString()),
        )
    } catch (e: Exception) {
        null
    }

    /** Fusiona una copia con los datos actuales sin duplicar ni perder datos resueltos. */
    fun merge(cur: BackupData, imp: BackupData): BackupData {
        val hist = (cur.history + imp.history).distinct().sortedByDescending { it.time }.take(History.MAX)
        val byRound = HashMap<Int, Prediction>()
        for (p in cur.predictions + imp.predictions) {
            val old = byRound[p.round]
            val score = { x: Prediction -> x.items.count { it.actual != null } * 1_000_000_000L + x.time / 1000 }
            if (old == null || score(p) > score(old)) byRound[p.round] = p
        }
        val preds = byRound.values.sortedByDescending { it.round }.take(Predictions.MAX)
        return BackupData(hist, preds, BidModel.merge(cur.bids, imp.bids))
    }
}
