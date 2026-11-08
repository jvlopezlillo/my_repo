package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

enum class Role(val api: String, val label: String) {
    PORTERO("portero", "Portero"),
    DEFENSA("defensa", "Defensa"),
    CENTROCAMPISTA("centrocampista", "Centrocampista"),
    DELANTERO("delantero", "Delantero");

    companion object {
        fun from(s: String?): Role? = values().firstOrNull { it.api == s }
    }
}

data class Player(
    val id: String,
    val name: String,
    val role: Role,
    val team: String,
    val teamId: String,
    val value: Long,
    val status: String,
    val avg: Double,
    val homeAvg: Double,
    val awayAvg: Double,
    val last5: Double,
    val matches: Int,
    /** Puntos de las últimas jornadas (0 = no jugó), de más antigua a más reciente. */
    val fitness: List<Double>,
    val onMarket: Boolean,
    /** Precio pedido en el mercado (solo jugadores del mercado de fichajes). */
    val askPrice: Long,
) {
    val isInjured get() = status == "injured" || status == "injured2"
    val isSuspended get() = status == "redcard"
    val isDoubt get() = status == "doubt"
    val isWarned get() = status == "yellowcard"
    val isUnavailable get() = isInjured || isSuspended
}

data class Session(
    val token: String,
    val userId: String,
    val championshipId: String,
    val userteamId: String,
    val teamName: String,
)

data class TeamInfo(val budget: Long, val teamValue: Long, val maxPlayers: Int, val position: Int)

data class Lineup(val strategy: String, val starterIds: List<String>, val benchIds: List<String>)

/** Cómo juega un equipo la próxima jornada. */
data class Fixture(val isHome: Boolean, val opponent: String, val winProb: Double?)

object Parse {
    fun player(o: JSONObject): Player? {
        val role = Role.from(o.optString("role")) ?: return null
        val avg = o.optJSONObject("average")
        val fit = ArrayList<Double>()
        avg?.optJSONArray("fitness")?.let { for (i in 0 until it.length()) fit.add(it.optDouble(i, 0.0)) }
        return Player(
            id = o.optString("id"),
            name = o.optString("name"),
            role = role,
            team = o.optString("team"),
            teamId = o.optString("teamId"),
            value = o.optLong("value"),
            status = o.optString("status"),
            avg = avg?.optDouble("average", 0.0) ?: 0.0,
            homeAvg = avg?.optDouble("homeAverage", 0.0) ?: 0.0,
            awayAvg = avg?.optDouble("awayAverage", 0.0) ?: 0.0,
            last5 = avg?.optDouble("averageLastFive", 0.0) ?: 0.0,
            matches = avg?.optInt("matches", 0) ?: 0,
            fitness = fit,
            onMarket = o.optBoolean("market", false),
            askPrice = o.optLong("price", 0),
        )
    }

    fun players(a: JSONArray?): List<Player> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { player(a.getJSONObject(it)) }
    }

    fun lineup(o: JSONObject): Lineup {
        val starters = o.optJSONArray("players") ?: JSONArray()
        val bench = o.optJSONObject("bench")?.optJSONArray("players") ?: JSONArray()
        return Lineup(
            strategy = o.optString("strategy"),
            starterIds = (0 until starters.length()).map { starters.getJSONObject(it).optString("id") },
            benchIds = (0 until bench.length()).map { bench.getJSONObject(it).optString("id") },
        )
    }

    fun teamInfo(o: JSONObject) = TeamInfo(
        budget = o.optLong("budget"),
        teamValue = o.optLong("teamValue"),
        maxPlayers = o.optJSONObject("configuration")?.optInt("maxPlayersInRoster", 18) ?: 18,
        position = o.optInt("position"),
    )

    /** Probabilidad implícita de victoria local/visitante a partir de las cuotas medias (1 X 2). */
    private fun probs(odds: JSONObject?): Triple<Double, Double, Double>? {
        val sels = odds?.optJSONArray("sels") ?: return null
        val mean = HashMap<String, Double>()
        for (i in 0 until sels.length()) {
            val s = sels.getJSONObject(i)
            val list = s.optJSONArray("odds") ?: continue
            val cs = (0 until list.length()).map { list.getJSONObject(it).optDouble("c", 0.0) }.filter { it > 1.0 }
            if (cs.isNotEmpty()) mean[s.optString("ssn")] = cs.average()
        }
        val h = mean["1"] ?: return null
        val d = mean["X"] ?: return null
        val a = mean["2"] ?: return null
        val inv = listOf(1 / h, 1 / d, 1 / a)
        val sum = inv.sum()
        return Triple(inv[0] / sum, inv[1] / sum, inv[2] / sum)
    }

    /** Devuelve un mapa clave-de-equipo -> Fixture (claves: id y nombre/slug normalizados). */
    fun fixtures(answer: JSONObject): Map<String, Fixture> {
        val out = HashMap<String, Fixture>()
        val ms = answer.optJSONArray("matches") ?: return out
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val home = m.optJSONObject("homeTeam") ?: continue
            val away = m.optJSONObject("awayTeam") ?: continue
            val p = probs(m.optJSONObject("odds"))
            val fh = Fixture(true, away.optString("name"), p?.first)
            val fa = Fixture(false, home.optString("name"), p?.third)
            for (k in teamKeys(home)) out[k] = fh
            for (k in teamKeys(away)) out[k] = fa
        }
        return out
    }

    private fun teamKeys(t: JSONObject) = listOf(t.optString("id"), key(t.optString("name")), key(t.optString("slug")))
        .filter { it.isNotEmpty() }

    fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]"), "")
}

fun Map<String, Fixture>.forPlayer(p: Player): Fixture? =
    this[p.teamId] ?: this[Parse.key(p.team)]
