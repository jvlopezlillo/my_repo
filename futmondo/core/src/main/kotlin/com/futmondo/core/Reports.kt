package com.futmondo.core

import java.util.Locale

object Reports {
    private val es = Locale.forLanguageTag("es")

    fun money(v: Long): String =
        if (v >= 1_000_000) String.format(es, "%.2f M", v / 1_000_000.0) else String.format(es, "%,d €", v)

    private fun playerLine(s: Scored): Line {
        val p = s.player
        val warn = when {
            p.isInjured -> " ⚠ lesionado"
            p.isSuspended -> " ⚠ sancionado"
            p.isDoubt -> " ⚠ duda"
            p.isWarned -> " (apercibido)"
            else -> ""
        }
        val tone = when {
            p.isUnavailable -> Tone.BAD
            p.isDoubt -> Tone.WARN
            else -> Tone.NORMAL
        }
        return Line(
            "${p.name} (${p.team}) – ${fmt(s.expected)} pts" +
                (s.fixture?.let { " · ${if (it.isHome) "casa" else "fuera"} vs ${it.opponent}" } ?: "") + warn,
            tone,
        )
    }

    fun analysisSections(a: Analysis): List<Section> {
        val b = a.best
        val out = ArrayList<Section>()
        val head = ArrayList<Line>()
        head.add(Line("Formación actual: ${a.current.strategy}", Tone.MUTED))
        a.plans.take(3).forEach { head.add(Line("${it.formation}: ${fmt(it.total)} pts", Tone.NORMAL)) }
        if (a.plans.size > 1 && a.plans[0].total - a.plans[1].total < 0.5) {
            head.add(Line("${a.plans[0].formation} y ${a.plans[1].formation} están casi empatadas.", Tone.MUTED))
        }
        a.currentTotal?.let { cur ->
            val imp = b.total - cur
            head.add(
                if (imp >= 0.5) Line("Tu once actual: ${fmt(cur)} pts · el recomendado gana +${fmt(imp)}", Tone.WARN)
                else Line("Tu once actual: ${fmt(cur)} pts · ya es casi el óptimo", Tone.GOOD),
            )
        }
        out.add(Section((if (a.round > 0) "JORNADA ${a.round} · " else "") + "MEJOR FORMACIÓN: ${b.formation} (${fmt(b.total)} pts esperados)", head))
        out.add(Section("ONCE RECOMENDADO", Role.values().flatMap { r -> b.starters.filter { it.player.role == r }.map(::playerLine) }))
        out.add(Section("BANQUILLO", b.bench.map(::playerLine)))
        val curStart = a.current.starterIds.toSet()
        val newStart = b.starters.map { it.player.id }.toSet()
        val gone = a.roster.filter { it.id in curStart && it.id !in newStart }
        val coming = b.starters.filter { it.player.id !in curStart }
        if (gone.isNotEmpty() || coming.isNotEmpty() || a.current.strategy != b.formation) {
            out.add(
                Section(
                    "CAMBIOS RESPECTO A TU ALINEACIÓN",
                    gone.map { Line("− Sale: ${it.name}", Tone.BAD) } + coming.map { Line("+ Entra: ${it.player.name}", Tone.GOOD) } +
                        Line("Haz los cambios tú mismo en Futmondo; la app no toca tu alineación.", Tone.MUTED),
                ),
            )
        } else out.add(Section("", listOf(Line("Tu alineación actual ya coincide con la recomendada.", Tone.GOOD))))
        if (a.warnings.isNotEmpty()) out.add(Section("COBERTURA", a.warnings.map { Line(it, Tone.WARN) }))
        out.add(Section("", listOf(Line("Saldo: ${money(a.info.budget)} · Puesto: ${a.info.position}º", Tone.MUTED))))
        return out
    }

    fun analysis(a: Analysis): String = Sections.toText(analysisSections(a))

    fun marketSections(m: MarketResult): List<Section> {
        val out = ArrayList<Section>()
        val head = arrayListOf(Line("Plantilla: ${m.rosterSize} jugadores · Saldo: ${money(m.info.budget)}", Tone.MUTED))
        if (m.alreadyOnMarket.isNotEmpty()) head.add(Line("Ya en venta: ${m.alreadyOnMarket.joinToString { it.name }}", Tone.MUTED))
        out.add(Section("", head))
        out.add(
            Section(
                "POSIBLES VENTAS (${m.sells.size})",
                if (m.sells.isEmpty()) listOf(Line("Ningún jugador cumple los criterios de venta.", Tone.MUTED))
                else m.sells.map {
                    Line(
                        "${it.player.name} (${it.player.role.label}, ${it.player.team}) a ${money(it.price)}: ${it.reasons.joinToString("; ")}",
                        if (it.preselected) Tone.WARN else Tone.NORMAL,
                    )
                },
            ),
        )
        fun buyLine(it: BuyRec): Line {
            val basis = if (it.bidBasis > 0) "según ${it.bidBasis} compras de tu liga" else "estimación por defecto"
            return Line(
                (if (it.coverage) "SUPLENTE · " else "") +
                    "${it.player.name} (${it.player.role.label}, ${it.player.team}) valor ${money(it.player.value)}; " +
                    "puja orientativa ${money(it.suggestedBid)} ($basis): ${it.reasons.joinToString("; ")}",
                if (it.coverage) Tone.WARN else Tone.GOOD,
            )
        }
        val upgrades = m.buys.filter { !it.coverage }
        val backups = m.buys.filter { it.coverage }
        out.add(
            Section(
                "FICHAJES RECOMENDADOS (${upgrades.size}; calendario de ${m.horizon} jornada${if (m.horizon == 1) "" else "s"})",
                if (upgrades.isEmpty()) listOf(Line("Ninguno del mercado actual mejora claramente tu once.", Tone.MUTED))
                else upgrades.map(::buyLine),
            ),
        )
        if (backups.isNotEmpty()) {
            out.add(
                Section(
                    "SUPLENTE RECOMENDADO (cubre un hueco; NO mejora tu once titular)",
                    backups.map(::buyLine),
                ),
            )
        }
        if (m.warnings.isNotEmpty() || m.coverageNotes.isNotEmpty()) {
            out.add(
                Section(
                    "COBERTURA",
                    m.warnings.map { Line(it, Tone.WARN) } + m.coverageNotes.map { Line(it, Tone.MUTED) },
                ),
            )
        }
        if (m.buys.isNotEmpty()) {
            val after = m.rosterSize - m.sells.count { it.preselected }
            out.add(
                Section(
                    "",
                    listOf(
                        Line(
                            "Para pujar necesitas ≤${m.info.maxPlayers - 1} jugadores en plantilla (tienes ${m.rosterSize}; " +
                                "si se venden los marcados, $after). La app no puja: hazlo tú en Futmondo.",
                            Tone.MUTED,
                        ),
                    ),
                ),
            )
        }
        return out
    }

    fun market(m: MarketResult): String = Sections.toText(marketSections(m))
}
