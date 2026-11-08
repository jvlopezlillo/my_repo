package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject

/** Un jugador del once recomendado y lo que se esperaba de él ([expected] sin calibrar). */
data class PredItem(
    val playerId: String,
    val name: String,
    val role: Role,
    val expected: Double,
    val actual: Double? = null,
)

data class Prediction(val round: Int, val time: Long, val items: List<PredItem>) {
    val resolved get() = items.isNotEmpty() && items.all { it.actual != null }
}

/** Corrección aditiva por puesto, aprendida de los fallos pasados del modelo. */
class Calibration(private val offsets: Map<Role, Double>) {
    fun adjust(role: Role, expected: Double): Double =
        if (expected <= 0.0) expected else (expected + (offsets[role] ?: 0.0)).coerceAtLeast(0.0)

    fun offset(role: Role): Double = offsets[role] ?: 0.0

    companion object {
        val NONE = Calibration(emptyMap())
        const val MIN_SAMPLES = 8
        const val MAX_OFFSET = 1.5
        private const val SHRINK = 10.0

        /** Con pocas muestras la corrección se encoge hacia 0 y por debajo de [MIN_SAMPLES] no se aplica. */
        fun from(preds: List<Prediction>): Calibration {
            val offsets = HashMap<Role, Double>()
            for (role in Role.values()) {
                val errs = samples(preds, role).map { it.actual!! - it.expected }
                if (errs.size < MIN_SAMPLES) continue
                val n = errs.size
                offsets[role] = (errs.average() * n / (n + SHRINK)).coerceIn(-MAX_OFFSET, MAX_OFFSET)
            }
            return Calibration(offsets)
        }

        /** Se excluyen los que se esperaba que no jugaran (expected = 0: lesionados/sancionados). */
        fun samples(preds: List<Prediction>, role: Role? = null): List<PredItem> =
            preds.flatMap { it.items }.filter { it.actual != null && it.expected > 0.0 && (role == null || it.role == role) }
    }
}

object Predictions {
    const val MAX = 30

    /** Sustituye la predicción de la misma jornada (la última es la más informada). */
    fun upsert(list: List<Prediction>, p: Prediction): List<Prediction> =
        (list.filter { it.round != p.round } + p).sortedByDescending { it.round }.take(MAX)

    fun toJson(list: List<Prediction>): String = JSONArray().also { arr ->
        list.forEach { p ->
            arr.put(JSONObject().put("r", p.round).put("t", p.time).put("i", JSONArray().also { ia ->
                p.items.forEach {
                    ia.put(JSONObject().put("id", it.playerId).put("n", it.name).put("ro", it.role.api)
                        .put("e", it.expected).also { o -> it.actual?.let { a -> o.put("a", a) } })
                }
            }))
        }
    }.toString()

    fun fromJson(s: String?): List<Prediction> {
        if (s.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val ia = o.getJSONArray("i")
                Prediction(o.getInt("r"), o.optLong("t"), (0 until ia.length()).mapNotNull { k ->
                    val x = ia.getJSONObject(k)
                    val role = Role.from(x.optString("ro")) ?: return@mapNotNull null
                    PredItem(x.optString("id"), x.optString("n"), role, x.optDouble("e"), if (x.has("a")) x.optDouble("a") else null)
                })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Informe de precisión del modelo en español. */
    fun report(preds: List<Prediction>): String {
        val all = Calibration.samples(preds)
        if (all.isEmpty()) {
            val pending = preds.count { !it.resolved }
            return "Aún no hay datos para medir la precisión. Pulsa Analizar antes de cada jornada " +
                "(o activa la revisión diaria); cuando la jornada se cierre se compararán los puntos reales." +
                if (pending > 0) "\nPredicciones pendientes: $pending." else ""
        }
        val sb = StringBuilder()
        val rounds = preds.filter { p -> p.items.any { it.actual != null } }.map { it.round }.distinct().size
        sb.append("PRECISIÓN DEL MODELO (${rounds} jornadas, ${all.size} jugadores)\n")
        sb.append("Error medio absoluto: ${fmt(all.map { Math.abs(it.actual!! - it.expected) }.average())} pts por jugador\n")
        sb.append("Sesgo (real − esperado): ${fmt(all.map { it.actual!! - it.expected }.average())} pts\n\n")
        val cal = Calibration.from(preds)
        for (role in Role.values()) {
            val s = Calibration.samples(preds, role)
            if (s.isEmpty()) continue
            val applied = if (s.size >= Calibration.MIN_SAMPLES) "corrección aplicada ${fmt(cal.offset(role))}" else "pocas muestras, sin corrección"
            sb.append("  ${role.label}: error ${fmt(s.map { Math.abs(it.actual!! - it.expected) }.average())}, " +
                "sesgo ${fmt(s.map { it.actual!! - it.expected }.average())} (${s.size}) – $applied\n")
        }
        val pending = preds.count { !it.resolved }
        if (pending > 0) sb.append("\nPredicciones pendientes de jornada cerrada: $pending")
        return sb.toString().trimEnd()
    }
}
