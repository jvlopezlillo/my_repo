package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class HistoryKind(val label: String) {
    ANALISIS("Análisis"),
    MERCADO("Revisión de mercado"),
    VENTA("Puesta a la venta"),
    AUTOMATICA("Revisión diaria"),
}

data class HistoryEntry(val time: Long, val kind: HistoryKind, val summary: String)

/** Historial de lo que hace la app. Solo guarda resúmenes (nombres y cifras), nunca credenciales. */
object History {
    const val MAX = 100

    /** Más reciente primero; conserva como mucho [MAX] entradas. */
    fun add(list: List<HistoryEntry>, e: HistoryEntry): List<HistoryEntry> = (listOf(e) + list).take(MAX)

    fun toJson(list: List<HistoryEntry>): String = JSONArray().also { arr ->
        list.forEach {
            arr.put(JSONObject().put("t", it.time).put("k", it.kind.name).put("s", it.summary))
        }
    }.toString()

    fun fromJson(s: String?): List<HistoryEntry> {
        if (s.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull {
                val o = a.getJSONObject(it)
                val k = HistoryKind.values().firstOrNull { k -> k.name == o.optString("k") } ?: return@mapNotNull null
                HistoryEntry(o.optLong("t"), k, o.optString("s"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun format(list: List<HistoryEntry>, zone: TimeZone = TimeZone.getDefault()): String {
        if (list.isEmpty()) return "El historial está vacío."
        val f = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("es")).apply { timeZone = zone }
        return list.joinToString("\n\n") { "${f.format(Date(it.time))} · ${it.kind.label}\n${it.summary}" }
    }

    fun analysisSummary(a: Analysis): String =
        "Mejor formación ${a.best.formation} (${fmt(a.best.total)} pts esperados); la tuya: ${a.current.strategy}."

    fun marketSummary(m: MarketResult): String =
        "Ventas propuestas: ${m.sells.joinToString { it.player.name }.ifEmpty { "ninguna" }}. " +
            "Fichajes recomendados: ${m.buys.joinToString { it.player.name }.ifEmpty { "ninguno" }}."

    fun saleSummary(chosen: List<SellCandidate>, failures: List<Pair<SellCandidate, String>>): String {
        val failed = failures.map { it.first.player.id }.toSet()
        val ok = chosen.filter { it.player.id !in failed }
        return "Puestos a la venta: ${ok.joinToString { "${it.player.name} (${Reports.money(it.price)})" }.ifEmpty { "ninguno" }}." +
            if (failures.isEmpty()) "" else " Fallaron: ${failures.joinToString { "${it.first.player.name} (${it.second})" }}."
    }
}
