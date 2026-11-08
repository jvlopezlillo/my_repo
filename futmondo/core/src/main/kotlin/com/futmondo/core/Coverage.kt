package com.futmondo.core

/** Avisos de cobertura: huecos de suplentes y jugadores cuya baja más duele. */
object Coverage {
    /** Cuántos puntos pierdes si falla [Scored] y quién lo sustituiría. */
    data class Risk(val starter: Scored, val drop: Double, val substitute: Scored?, val noValidEleven: Boolean = false)

    fun risks(scored: List<Scored>, plan: LineupPlan): List<Risk> =
        plan.starters.mapNotNull { s ->
            val alt = Analyzer.plans(scored.filter { it.player.id != s.player.id }).firstOrNull()
            // Sin once alternativo no hay "puntos perdidos" que contar: simplemente no se puede alinear a 11.
            val drop = if (alt == null) 0.0 else plan.total - alt.total
            val sub = alt?.starters?.firstOrNull { n -> plan.starters.none { it.player.id == n.player.id } }
            Risk(s, drop, sub, noValidEleven = alt == null)
        }.sortedWith(compareByDescending<Risk> { it.noValidEleven }.thenByDescending { it.drop })

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
        val all = risks(scored, plan)
        // Posiciones en las que una sola baja deja sin once válido (faltan jugadores para cualquier formación).
        for (r in all.filter { it.noValidEleven }.map { it.starter.player.role }.distinct()) {
            val n = scored.count { it.player.role == r && !it.player.isUnavailable }
            out.add("Si falla un ${r.label.lowercase()} te quedas sin once válido: solo tienes $n y no hay formación posible con menos.")
        }
        all.filter { !it.noValidEleven && it.drop >= 2.5 }.take(2).forEach {
            out.add(
                "Si falla ${it.starter.player.name} pierdes ${fmt(it.drop)} pts" +
                    (it.substitute?.let { s -> " (le sustituiría ${s.player.name})" } ?: "") + ".",
            )
        }
        return out
    }
}
