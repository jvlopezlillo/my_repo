package com.futmondo.core

data class SellCandidate(
    val player: Player,
    val reasons: List<String>,
    /** true = marcado por defecto (mala forma o pérdida de titularidad); lesiones/sanciones van sin marcar. */
    val preselected: Boolean,
    /** Precio al que se pondría a la venta (= valor del jugador, como hace la app de Futmondo). */
    val price: Long,
)

data class BuyRec(
    val player: Player,
    val expected: Double,
    /** Mejora esperada del mejor once al fichar a este jugador. */
    val gain: Double,
    val suggestedBid: Long,
    val fitsBudget: Boolean,
    val reasons: List<String>,
)

object MarketReview {
    const val DEFAULT_MAX_BUYS = 3

    /**
     * Criterios de venta:
     * 1) media últimos 5 < 4,5 y media de temporada < 5,5;
     * 2) perdió la titularidad (0 puntos en ≥3 de las últimas 5);
     * 3) lesión o sanción.
     * No propone a los titulares del once recomendado salvo lesión/sanción. Sin límite de número.
     */
    fun sellCandidates(roster: List<Player>, recommendedStarterIds: Set<String>): List<SellCandidate> =
        roster.filter { !it.onMarket }.mapNotNull { p ->
            val reasons = ArrayList<String>()
            var form = false
            if (p.last5 < 4.5 && p.avg < 5.5) {
                reasons.add("mala forma (últimos 5: ${fmt(p.last5)}, temporada: ${fmt(p.avg)})")
                form = true
            }
            val zeros = p.fitness.count { it == 0.0 }
            if (p.fitness.size >= 5 && zeros >= 3) {
                reasons.add("perdió la titularidad (no puntuó en $zeros de las últimas ${p.fitness.size})")
                form = true
            }
            if (p.isInjured) reasons.add("lesionado (comprueba la duración)")
            if (p.isSuspended) reasons.add("sancionado (comprueba la duración)")
            val unavailable = p.isUnavailable
            val isStarter = p.id in recommendedStarterIds
            when {
                reasons.isEmpty() -> null
                isStarter && !unavailable -> null
                else -> SellCandidate(p, reasons, preselected = form && !isStarter, price = p.value)
            }
        }.sortedWith(compareByDescending<SellCandidate> { it.preselected }.thenBy { it.player.last5 })

    /** Precio orientativo de puja según lo observado en la liga (caros +~8 %, medianos +20 %, baratos +35 %). */
    fun suggestedBid(p: Player): Long {
        val base = maxOf(p.value, 1L)
        val premium = when {
            base > 20_000_000 -> 0.08
            base > 10_000_000 -> 0.20
            else -> 0.35
        }
        return maxOf(p.askPrice, (base * (1 + premium)).toLong())
    }

    /**
     * Recomienda 0-3 fichajes que mejoren el mejor once (ya descontadas las ventas propuestas).
     * Solo recomienda: la app nunca puja.
     */
    fun buyRecommendations(
        market: List<Player>,
        roster: List<Player>,
        sellIds: Set<String>,
        rounds: List<Map<String, Fixture>>,
        info: TeamInfo,
        cal: Calibration = Calibration.NONE,
        maxBuys: Int = DEFAULT_MAX_BUYS,
    ): List<BuyRec> {
        val keep = roster.filter { it.id !in sellIds }
        val ownIds = roster.map { it.id }.toSet()
        val baseline = Analyzer.plans(Analyzer.scoreHorizon(keep, rounds, cal)).firstOrNull()?.total ?: 0.0
        val maxBid = info.budget + info.teamValue / 2
        val recs = market.asSequence()
            .filter { it.id !in ownIds && !it.isUnavailable && !it.isDoubt }
            .filter { Analyzer.playProb(it) >= 0.6 }
            .mapNotNull { c ->
                val total = Analyzer.plans(Analyzer.scoreHorizon(keep + c, rounds, cal)).firstOrNull()?.total ?: return@mapNotNull null
                val gain = total - baseline
                val need = if (c.role == Role.CENTROCAMPISTA) 1.5 else 0.7 // prioridad: portero, defensa, delantero top
                if (gain < need) return@mapNotNull null
                val bid = suggestedBid(c)
                val reasons = ArrayList<String>()
                reasons.add("mejora el once en +${fmt(gain)} pts esperados")
                if (c.last5 > c.avg) reasons.add("en racha (últimos 5: ${fmt(c.last5)} > media ${fmt(c.avg)})")
                val fxs = rounds.map { it.forPlayer(c) }
                val cal3 = fxs.filterNotNull().joinToString(", ") { "${it.opponent} (${if (it.isHome) "c" else "f"})" }
                if (cal3.isNotEmpty()) reasons.add("próximos rivales: $cal3")
                if (c.isWarned) reasons.add("apercibido")
                BuyRec(c, Analyzer.expected(c, fxs.firstOrNull()), gain, bid, bid <= maxBid, reasons)
            }
            .filter { it.fitsBudget }
            .sortedByDescending { it.gain }
            .toList()
        // Prefiere un jugador de nivel antes que varios mediocres: no más de uno por puesto salvo ganancia clara.
        val out = ArrayList<BuyRec>()
        for (r in recs) {
            if (out.size >= maxBuys) break
            if (out.any { it.player.role == r.player.role } && r.gain < 2.0) continue
            out.add(r)
        }
        return out
    }
}

internal fun fmt(d: Double): String = String.format(java.util.Locale.forLanguageTag("es"), "%.1f", d)
