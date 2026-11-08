package com.futmondo.core

/** [expected] ya incluye la calibración; [raw] es la estimación del modelo sin calibrar. */
data class Scored(val player: Player, val expected: Double, val fixture: Fixture?, val raw: Double = expected)

data class LineupPlan(val formation: String, val starters: List<Scored>, val bench: List<Scored>) {
    val total get() = starters.sumOf { it.expected }
}

object Analyzer {
    /** defensas-medios-delanteros */
    val FORMATIONS: Map<String, Triple<Int, Int, Int>> = linkedMapOf(
        "4-4-2" to Triple(4, 4, 2),
        "4-3-3" to Triple(4, 3, 3),
        "4-5-1" to Triple(4, 5, 1),
        "3-4-3" to Triple(3, 4, 3),
        "3-5-2" to Triple(3, 5, 2),
        "5-4-1" to Triple(5, 4, 1),
        "5-3-2" to Triple(5, 3, 2),
    )

    /** Fracción de las últimas jornadas en las que sumó puntos (proxy de "juega de titular"). */
    fun playProb(p: Player): Double {
        if (p.fitness.isEmpty()) return 0.7
        val played = p.fitness.count { it != 0.0 }.toDouble() / p.fitness.size
        return played.coerceIn(0.05, 1.0)
    }

    private fun statusFactor(p: Player) = when {
        p.isUnavailable -> 0.0
        p.isDoubt -> 0.6
        else -> 1.0
    }

    /**
     * Puntos esperados: media de (media de temporada si juega, media de los últimos 5),
     * ajustada por probabilidad de jugar, estado, local/visitante y favoritismo del partido;
     * a defensas y porteros se les suma el efecto de la portería a cero.
     */
    fun expected(p: Player, fx: Fixture?): Double {
        var seasonIfPlays = p.avg
        if (fx != null && p.matches >= 4) {
            val venue = if (fx.isHome) p.homeAvg else p.awayAvg
            seasonIfPlays = 0.7 * p.avg + 0.3 * venue
        }
        var base = 0.5 * seasonIfPlays * playProb(p) + 0.5 * p.last5
        val pw = fx?.winProb
        if (pw != null) {
            base *= 1.0 + 0.25 * (pw - 0.36)
            if (p.role == Role.DEFENSA || p.role == Role.PORTERO) {
                // P(portería a cero) ~ 0.15 + 0.45*P(victoria); +3 pts en defensas respecto al 30 % medio.
                val cs = 0.15 + 0.45 * pw
                base += (if (p.role == Role.DEFENSA) 3.0 else 1.0) * (cs - 0.30) * playProb(p)
            }
        }
        return (base * statusFactor(p)).coerceAtLeast(0.0)
    }

    /** Pesos de las próximas jornadas al valorar fichajes (la más cercana pesa más). */
    val HORIZON_WEIGHTS = listOf(1.0, 0.6, 0.4)

    fun score(players: List<Player>, fixtures: Map<String, Fixture>, cal: Calibration = Calibration.NONE): List<Scored> =
        players.map {
            val fx = fixtures.forPlayer(it)
            val raw = expected(it, fx)
            Scored(it, cal.adjust(it.role, raw), fx, raw)
        }

    /** Puntos esperados repartidos en varias jornadas (media ponderada). La primera es la que se muestra. */
    fun scoreHorizon(
        players: List<Player>,
        rounds: List<Map<String, Fixture>>,
        cal: Calibration = Calibration.NONE,
    ): List<Scored> {
        if (rounds.size <= 1) return score(players, rounds.firstOrNull() ?: emptyMap(), cal)
        val w = HORIZON_WEIGHTS.take(rounds.size)
        return players.map { p ->
            val raw = rounds.indices.sumOf { w[it] * expected(p, rounds[it].forPlayer(p)) } / w.sum()
            Scored(p, cal.adjust(p.role, raw), rounds[0].forPlayer(p), raw)
        }
    }

    /** Mejor once para cada formación posible, de mayor a menor suma esperada. */
    fun plans(scored: List<Scored>): List<LineupPlan> {
        val byRole = scored.groupBy { it.player.role }.mapValues { e -> e.value.sortedByDescending { it.expected } }
        val gk = byRole[Role.PORTERO].orEmpty()
        val df = byRole[Role.DEFENSA].orEmpty()
        val mf = byRole[Role.CENTROCAMPISTA].orEmpty()
        val fw = byRole[Role.DELANTERO].orEmpty()
        val out = ArrayList<LineupPlan>()
        for ((name, t) in FORMATIONS) {
            val (d, m, f) = t
            if (gk.isEmpty() || df.size < d || mf.size < m || fw.size < f) continue
            val starters = gk.take(1) + df.take(d) + mf.take(m) + fw.take(f)
            val ids = starters.map { it.player.id }.toSet()
            val bench = scored.filter { it.player.id !in ids }.sortedByDescending { it.expected }
            out.add(LineupPlan(name, starters, bench))
        }
        return out.sortedByDescending { it.total }
    }
}
