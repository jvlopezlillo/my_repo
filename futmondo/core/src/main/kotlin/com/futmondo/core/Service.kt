package com.futmondo.core

import java.util.Locale

data class Analysis(
    val current: Lineup,
    val plans: List<LineupPlan>,
    val info: TeamInfo,
    val roster: List<Player>,
    val round: Int = 0,
    val prediction: Prediction? = null,
    /** Avisos de cobertura (suplentes y bajas que más duelen). */
    val warnings: List<String> = emptyList(),
    /** Puntos esperados de tu once actual (null si no se pudo emparejar con la plantilla). */
    val currentTotal: Double? = null,
    /** Hora (ms) del primer partido de la jornada: la alineación se bloquea entonces. */
    val firstMatchMillis: Long? = null,
) {
    val best get() = plans.first()

    /** Puntos esperados que ganarías usando el once recomendado en vez del actual. */
    val improvement: Double? get() = currentTotal?.let { best.total - it }
}

data class MarketResult(
    val sells: List<SellCandidate>,
    val buys: List<BuyRec>,
    val info: TeamInfo,
    val rosterSize: Int,
    val alreadyOnMarket: List<Player>,
    /** Jornadas de calendario tenidas en cuenta para los fichajes. */
    val horizon: Int = 1,
    val warnings: List<String> = emptyList(),
)

/** Casos de uso de los dos botones. Solo leen datos; nada se ejecuta sin que el usuario lo confirme. */
class FutmondoService(private val api: FutmondoApi) {

    companion object {
        const val HORIZON = 3
        private const val MAX_ROUNDS_TO_RESOLVE = 3
    }

    fun analyze(cal: Calibration = Calibration.NONE): Analysis {
        val roster = api.roster()
        val next = api.upcomingRounds(1).first()
        val plans = Analyzer.plans(Analyzer.score(roster, next.byTeam, cal))
        require(plans.isNotEmpty()) { "Plantilla insuficiente para formar un once" }
        // La predicción guarda la estimación SIN calibrar, para no realimentar la corrección.
        val prediction = Prediction(
            next.number, System.currentTimeMillis(),
            plans.first().starters.map { PredItem(it.player.id, it.player.name, it.player.role, it.raw) },
        )
        val scored = Analyzer.score(roster, next.byTeam, cal)
        val lineup = api.lineup()
        val cur = scored.filter { it.player.id in lineup.starterIds }
        val currentTotal = if (lineup.starterIds.isNotEmpty() && cur.size == lineup.starterIds.size) cur.sumOf { it.expected } else null
        return Analysis(
            lineup, plans, api.teamInfo(), roster, next.number, prediction,
            Coverage.warnings(scored, plans.first()), currentTotal, next.firstMatchMillis,
        )
    }

    /** Añade a las observaciones las compras nuevas de la liga. Sin compras nuevas no pide los valores. */
    fun refreshBids(existing: List<BidObs>): List<BidObs> {
        val known = existing.map { it.id }.toSet()
        val fresh = api.leagueSales().filter { it.id !in known }
        if (fresh.isEmpty()) return existing
        return BidModel.merge(existing, BidModel.observations(fresh, api.leagueValues()))
    }

    fun reviewMarket(cal: Calibration = Calibration.NONE, bids: List<BidObs> = emptyList()): MarketResult {
        val roster = api.roster()
        val rounds = api.upcomingRounds(HORIZON)
        val plans = Analyzer.plans(Analyzer.score(roster, rounds.first().byTeam, cal))
        val starters = plans.firstOrNull()?.starters?.map { it.player.id }?.toSet().orEmpty()
        val sells = MarketReview.sellCandidates(roster, starters)
        val info = api.teamInfo()
        val sellIds = sells.filter { it.preselected }.map { it.player.id }.toSet()
        val buys = MarketReview.buyRecommendations(
            api.marketPlayers(), roster, sellIds, rounds.map { it.byTeam }, info, cal, bids = bids,
        )
        val keepScored = Analyzer.score(roster.filter { it.id !in sellIds }, rounds.first().byTeam, cal)
        val warnings = Analyzer.plans(keepScored).firstOrNull()?.let { Coverage.warnings(keepScored, it) }.orEmpty()
        return MarketResult(sells, buys, info, roster.size, roster.filter { it.onMarket }, rounds.size, warnings)
    }

    /**
     * Rellena los puntos reales de las predicciones cuya jornada ya está cerrada (máx. 3 jornadas por llamada).
     * Si un jugador no tiene dato de esa jornada se deja pendiente.
     */
    fun resolve(preds: List<Prediction>): List<Prediction> {
        if (preds.all { it.resolved }) return preds
        val closed = api.closedRounds()
        val targets = preds.filter { !it.resolved && it.round in closed }
            .sortedByDescending { it.round }.take(MAX_ROUNDS_TO_RESOLVE).map { it.round }.toSet()
        if (targets.isEmpty()) return preds
        val cache = HashMap<String, Map<Int, Double>>()
        return preds.map { p ->
            if (p.round !in targets) p
            else p.copy(items = p.items.map { it ->
                if (it.actual != null) it
                else it.copy(actual = cache.getOrPut(it.playerId) { api.playerRoundPoints(it.playerId) }[p.round])
            })
        }
    }

    /** Pone a la venta únicamente los jugadores recibidos (ya confirmados por el usuario). Devuelve fallos. */
    fun putOnSale(chosen: List<SellCandidate>): List<Pair<SellCandidate, String>> {
        val failures = ArrayList<Pair<SellCandidate, String>>()
        for (c in chosen) {
            try { api.putOnMarket(c.player.id, c.price) } catch (e: FutmondoException) { failures.add(c to e.code) }
        }
        return failures
    }

    /** Jugadores de tu plantilla y del mercado, puntuados para la próxima jornada (para compararlos). */
    fun comparisonPool(cal: Calibration = Calibration.NONE): List<Scored> {
        val fixtures = api.upcomingRounds(1).first().byTeam
        val roster = api.roster()
        val ids = roster.map { it.id }.toSet()
        val market = api.marketPlayers().filter { it.id !in ids }
        return Analyzer.score(roster + market, fixtures, cal)
    }
}
