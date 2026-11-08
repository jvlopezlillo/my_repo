package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject

/** Una compra observada en la liga: lo que se pagó frente al valor del jugador. */
data class BidObs(val id: String, val time: Long, val value: Long, val price: Long)

/**
 * Aprende cuánto sobrepaga la gente en TU liga. Por tramo de valor usa la mediana de (precio/valor − 1)
 * de las compras observadas; con pocas muestras vuelve a los porcentajes por defecto.
 * Nota: el valor es el actual, no el del día de la compra, así que es una aproximación.
 */
object BidModel {
    const val MIN_SAMPLES = 5
    const val MAX_OBS = 300
    private val DEFAULTS = doubleArrayOf(0.08, 0.20, 0.35)

    private fun bucket(value: Long) = when {
        value > 20_000_000 -> 0
        value > 10_000_000 -> 1
        else -> 2
    }

    /** Devuelve (prima, nº de muestras usadas). Si el nº es 0 la prima es la de por defecto. */
    fun premium(obs: List<BidObs>, value: Long): Pair<Double, Int> {
        val b = bucket(value)
        val ratios = obs.filter { it.value > 0 && bucket(it.value) == b }
            .map { (it.price.toDouble() / it.value - 1).coerceIn(-0.1, 1.0) }.sorted()
        if (ratios.size < MIN_SAMPLES) return DEFAULTS[b] to 0
        val med = if (ratios.size % 2 == 1) ratios[ratios.size / 2] else (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2
        return med to ratios.size
    }

    fun merge(existing: List<BidObs>, new: List<BidObs>): List<BidObs> =
        (new + existing).distinctBy { it.id }.sortedByDescending { it.time }.take(MAX_OBS)

    /** Convierte compras de la liga en observaciones, usando los valores actuales de los jugadores. */
    fun observations(sales: List<Sale>, values: Map<String, Long>): List<BidObs> =
        sales.mapNotNull { s -> values[s.playerId]?.takeIf { it > 0 }?.let { BidObs(s.id, s.time, it, s.price) } }

    fun toJson(l: List<BidObs>): String = JSONArray().also { a ->
        l.forEach { a.put(JSONObject().put("i", it.id).put("t", it.time).put("v", it.value).put("p", it.price)) }
    }.toString()

    fun fromJson(s: String?): List<BidObs> {
        if (s.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getJSONObject(it) }.map { BidObs(it.getString("i"), it.optLong("t"), it.optLong("v"), it.optLong("p")) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
