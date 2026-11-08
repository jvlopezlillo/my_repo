package com.futmondo.core

/** Comparación lado a lado de dos jugadores. */
object Compare {
    private fun status(p: Player) = when {
        p.isInjured -> "lesionado"
        p.isSuspended -> "sancionado"
        p.isDoubt -> "duda"
        p.isWarned -> "apercibido"
        else -> "disponible"
    }

    private fun trend(p: Player) = when {
        p.trendPct >= 0.015 -> "sube (+${fmt(p.trendPct * 100)} %)"
        p.trendPct <= -0.015 -> "baja (${fmt(p.trendPct * 100)} %)"
        else -> "estable"
    }

    private fun rival(s: Scored) = s.fixture?.let { "${if (it.isHome) "casa" else "fuera"} vs ${it.opponent}" } ?: "sin dato"

    fun sections(a: Scored, b: Scored): List<Section> {
        val pa = a.player
        val pb = b.player
        fun row(label: String, x: String, y: String) = Line("$label: $x  |  $y")
        val rows = listOf(
            row("Puesto", pa.role.label, pb.role.label),
            row("Equipo", pa.team, pb.team),
            row("Valor", Reports.money(pa.value), Reports.money(pb.value)),
            row("Media temporada", fmt(pa.avg), fmt(pb.avg)),
            row("Casa / fuera", "${fmt(pa.homeAvg)} / ${fmt(pa.awayAvg)}", "${fmt(pb.homeAvg)} / ${fmt(pb.awayAvg)}"),
            row("Últimos 5", fmt(pa.last5), fmt(pb.last5)),
            row("Últimas jornadas", pa.fitness.joinToString(" ") { fmt(it) }, pb.fitness.joinToString(" ") { fmt(it) }),
            row("Estado", status(pa), status(pb)),
            row("Valor de mercado", trend(pa), trend(pb)),
            row("Próximo partido", rival(a), rival(b)),
            row("Puntos esperados", fmt(a.expected), fmt(b.expected)),
        )
        val diff = a.expected - b.expected
        val verdict = when {
            kotlin.math.abs(diff) < 0.3 -> Line("Prácticamente iguales para la próxima jornada.", Tone.MUTED)
            diff > 0 -> Line("Mejor para la próxima jornada: ${pa.name} (+${fmt(diff)} pts).", Tone.GOOD)
            else -> Line("Mejor para la próxima jornada: ${pb.name} (+${fmt(-diff)} pts).", Tone.GOOD)
        }
        return listOf(Section("${pa.name}  |  ${pb.name}", rows), Section("Veredicto", listOf(verdict)))
    }
}
