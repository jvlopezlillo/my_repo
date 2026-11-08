package com.futmondo.core

/** Avisos de cobertura: huecos de suplentes y jugadores cuya baja más duele. */
object Coverage {
    /** Cuántos puntos pierdes si falla [Scored] y quién lo sustituiría. */
    data class Risk(val starter: Scored, val drop: Double, val substitute: Scored?)

    fun risks(scored: List<Scored>, plan: LineupPlan): List<Risk> =
        plan.starters.mapNotNull { s ->
            val alt = Analyzer.plans(scored.filter { it.player.id != s.player.id }).firstOrNull()
            val drop = plan.total - (alt?.total ?: 0.0)
            val sub = alt?.starters?.firstOrNull { n -> plan.starters.none { it.player.id == n.player.id } }
            Risk(s, drop, sub)
        }.sortedByDescending { it.drop }

    /** Posiciones donde no hay suplente sano para el once recomendado (el portero siempre necesita 2). */
    fun holes(scored: List<Scored>, plan: LineupPlan): List<Role> =
        Role.values().filter { r ->
            val healthy = scored.count { it.player.role == r && !it.player.isUnavailable }
            val starters = plan.starters.count { it.player.role == r }
            val need = if (r == Role.PORTERO) 2 else starters + 1
            healthy < need
        }

    fun warnings(scored: List<Scored>, plan: LineupPlan): List<String> {
        val out = ArrayList<String>()
        for (r in holes(scored, plan)) {
            val n = scored.count { it.player.role == r && !it.player.isUnavailable }
            out.add(
                when (r) {
                    Role.PORTERO -> "Solo tienes $n portero${if (n == 1) "" else "s"} sano${if (n == 1) "" else "s"}: sin suplente si falla."
                    else -> "Sin suplente sano en ${r.label.lowercase()} ($n disponibles para ${plan.starters.count { it.player.role == r }} titulares)."
                },
            )
        }
        risks(scored, plan).filter { it.drop >= 2.5 }.take(2).forEach {
            out.add(
                "Si falla ${it.starter.player.name} pierdes ${fmt(it.drop)} pts" +
                    (it.substitute?.let { s -> " (le sustituiría ${s.player.name})" } ?: " (sin sustituto válido)") + ".",
            )
        }
        return out
    }
}
