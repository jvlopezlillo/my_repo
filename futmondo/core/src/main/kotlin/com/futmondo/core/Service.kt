package com.futmondo.core

import java.util.Locale

data class Analysis(val current: Lineup, val plans: List<LineupPlan>, val info: TeamInfo, val roster: List<Player>) {
    val best get() = plans.first()
}

data class MarketResult(
    val sells: List<SellCandidate>,
    val buys: List<BuyRec>,
    val info: TeamInfo,
    val rosterSize: Int,
    val alreadyOnMarket: List<Player>,
)

/** Casos de uso de los dos botones. Solo leen datos; nada se ejecuta sin que el usuario lo confirme. */
class FutmondoService(private val api: FutmondoApi) {

    fun analyze(): Analysis {
        val roster = api.roster()
        val fixtures = api.nextFixtures()
        val plans = Analyzer.plans(Analyzer.score(roster, fixtures))
        require(plans.isNotEmpty()) { "Plantilla insuficiente para formar un once" }
        return Analysis(api.lineup(), plans, api.teamInfo(), roster)
    }

    fun reviewMarket(): MarketResult {
        val roster = api.roster()
        val fixtures = api.nextFixtures()
        val plans = Analyzer.plans(Analyzer.score(roster, fixtures))
        val starters = plans.firstOrNull()?.starters?.map { it.player.id }?.toSet().orEmpty()
        val sells = MarketReview.sellCandidates(roster, starters)
        val info = api.teamInfo()
        val sellIds = sells.filter { it.preselected }.map { it.player.id }.toSet()
        val buys = MarketReview.buyRecommendations(api.marketPlayers(), roster, sellIds, fixtures, info)
        return MarketResult(sells, buys, info, roster.size, roster.filter { it.onMarket })
    }

    /** Pone a la venta únicamente los jugadores recibidos (ya confirmados por el usuario). Devuelve fallos. */
    fun putOnSale(chosen: List<SellCandidate>): List<Pair<SellCandidate, String>> {
        val failures = ArrayList<Pair<SellCandidate, String>>()
        for (c in chosen) {
            try { api.putOnMarket(c.player.id, c.price) } catch (e: FutmondoException) { failures.add(c to e.code) }
        }
        return failures
    }
}

object Reports {
    private val es = Locale.forLanguageTag("es")

    fun money(v: Long): String =
        if (v >= 1_000_000) String.format(es, "%.2f M", v / 1_000_000.0) else String.format(es, "%,d €", v)

    private fun line(s: Scored) =
        "  • ${s.player.name} (${s.player.team}) – ${fmt(s.expected)} pts" +
            (s.fixture?.let { " · ${if (it.isHome) "casa" else "fuera"} vs ${it.opponent}" } ?: "") +
            when {
                s.player.isInjured -> " ⚠ lesionado"
                s.player.isSuspended -> " ⚠ sancionado"
                s.player.isDoubt -> " ⚠ duda"
                s.player.isWarned -> " (apercibido)"
                else -> ""
            }

    fun analysis(a: Analysis): String {
        val b = a.best
        val sb = StringBuilder()
        sb.append("MEJOR FORMACIÓN: ${b.formation}  (${fmt(b.total)} pts esperados)\n")
        sb.append("Formación actual: ${a.current.strategy}\n\n")
        a.plans.take(3).forEach { sb.append("  ${it.formation}: ${fmt(it.total)}\n") }
        if (a.plans.size > 1 && a.plans[0].total - a.plans[1].total < 0.5) {
            sb.append("→ ${a.plans[0].formation} y ${a.plans[1].formation} están casi empatadas.\n")
        }
        sb.append("\nONCE RECOMENDADO\n")
        for (role in Role.values()) {
            b.starters.filter { it.player.role == role }.forEach { sb.append(line(it)).append('\n') }
        }
        sb.append("\nBANQUILLO\n")
        b.bench.forEach { sb.append(line(it)).append('\n') }
        val curStart = a.current.starterIds.toSet()
        val newStart = b.starters.map { it.player.id }.toSet()
        val out = a.roster.filter { it.id in curStart && it.id !in newStart }
        val inn = b.starters.filter { it.player.id !in curStart }
        if (out.isNotEmpty() || inn.isNotEmpty() || a.current.strategy != b.formation) {
            sb.append("\nCAMBIOS RESPECTO A TU ALINEACIÓN\n")
            out.forEach { sb.append("  − Sale: ${it.name}\n") }
            inn.forEach { sb.append("  + Entra: ${it.player.name}\n") }
            sb.append("Haz los cambios tú mismo en Futmondo; la app no toca tu alineación.\n")
        } else sb.append("\nTu alineación actual ya coincide con la recomendada.\n")
        sb.append("\nSaldo: ${money(a.info.budget)} · Puesto: ${a.info.position}º")
        return sb.toString()
    }

    fun market(m: MarketResult): String {
        val sb = StringBuilder()
        sb.append("Plantilla: ${m.rosterSize} jugadores · Saldo: ${money(m.info.budget)}\n")
        if (m.alreadyOnMarket.isNotEmpty()) {
            sb.append("Ya en venta: ${m.alreadyOnMarket.joinToString { it.name }}\n")
        }
        sb.append("\nPOSIBLES VENTAS (${m.sells.size})\n")
        if (m.sells.isEmpty()) sb.append("  Ningún jugador cumple los criterios de venta.\n")
        m.sells.forEach {
            sb.append("  • ${it.player.name} (${it.player.role.label}, ${it.player.team}) a ${money(it.price)}: ${it.reasons.joinToString("; ")}\n")
        }
        sb.append("\nFICHAJES RECOMENDADOS (${m.buys.size})\n")
        if (m.buys.isEmpty()) sb.append("  Ninguno del mercado actual mejora claramente tu once.\n")
        m.buys.forEach {
            sb.append("  • ${it.player.name} (${it.player.role.label}, ${it.player.team}) valor ${money(it.player.value)}; " +
                "puja orientativa ${money(it.suggestedBid)}: ${it.reasons.joinToString("; ")}\n")
        }
        val after = m.rosterSize - m.sells.count { it.preselected }
        if (m.buys.isNotEmpty()) {
            sb.append("\nPara pujar necesitas ≤${m.info.maxPlayers - 1} jugadores en plantilla (tienes ${m.rosterSize}; ")
            sb.append("tras las ventas marcadas, ${after}). La app no puja: hazlo tú en Futmondo.")
        }
        return sb.toString()
    }
}
