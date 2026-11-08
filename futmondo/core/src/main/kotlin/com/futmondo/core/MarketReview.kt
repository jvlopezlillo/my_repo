package com.futmondo.core

data class SellCandidate(
    val player: Player,
    val reasons: List<String>,
    /** true = marcado por defecto (mala forma, pérdida de titularidad o baja larga probable). */
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
    /** Nº de compras de la liga en que se basa la puja (0 = porcentaje por defecto). */
    val bidBasis: Int = 0,
    /** true = se recomienda para cubrir un hueco de suplentes, no por mejorar el once. */
    val coverage: Boolean = false,
)

object MarketReview {
    const val DEFAULT_MAX_BUYS = 3
    private const val TREND = 0.015
    private const val COVERAGE_MAX_VALUE = 8_000_000L

    private fun pct(d: Double) = fmt(d * 100)

    /**
     * Criterios de venta:
     * 1) media últimos 5 < 4,5 y media de temporada < 5,5;
     * 2) perdió la titularidad (0 puntos en ≥3 de las últimas 5);
     * 3) lesión o sanción: se marca por defecto solo si es probablemente larga (≥3 jornadas sin puntuar).
     * No propone a los titulares del once recomendado salvo lesión/sanción. Sin límite de número.
     */
    fun sellCandidates(roster: List<Player>, recommendedStarterIds: Set<String>): List<SellCandidate> =
        roster.filter { !it.onMarket }.mapNotNull { p ->
            val reasons = ArrayList<String>()
            var mark = false
            if (p.last5 < 4.5 && p.avg < 5.5) {
                reasons.add("mala forma (últimos 5: ${fmt(p.last5)}, temporada: ${fmt(p.avg)})")
                mark = true
            }
            val zeros = p.fitness.count { it == 0.0 }
            if (p.fitness.size >= 5 && zeros >= 3) {
                reasons.add("perdió la titularidad (no puntuó en $zeros de las últimas ${p.fitness.size})")
                mark = true
            }
            if (p.isUnavailable) {
                val kind = if (p.isInjured) "lesionado" else "sancionado"
                if (p.probableLongAbsence) {
                    reasons.add("$kind y lleva ${p.trailingZeros} jornadas sin puntuar (baja larga probable, estimado)")
                    mark = true
                } else {
                    reasons.add("$kind (duración desconocida: compruébala)")
                }
            }
            if (reasons.isNotEmpty() && p.trendPct <= -TREND) {
                reasons.add("su valor baja (${pct(p.trendPct)} %): si vendes, cuanto antes mejor")
            }
            val isStarter = p.id in recommendedStarterIds
            when {
                reasons.isEmpty() -> null
                isStarter && !p.isUnavailable -> null
                else -> SellCandidate(p, reasons, preselected = mark && !isStarter, price = p.value)
            }
        }.sortedWith(compareByDescending<SellCandidate> { it.preselected }.thenBy { it.player.last5 })

    /** Precio orientativo de puja: valor + prima aprendida de la liga (o por defecto). Devuelve (puja, nº muestras). */
    fun suggestedBid(p: Player, obs: List<BidObs> = emptyList()): Pair<Long, Int> {
        val base = maxOf(p.value, 1L)
        val (premium, n) = BidModel.premium(obs, base)
        return maxOf(p.askPrice, (base * (1 + premium)).toLong()) to n
    }

    /**
     * Recomienda 0-3 fichajes que mejoren el mejor once (ya descontadas las ventas propuestas) valorando varias
     * jornadas, y, si queda hueco, uno barato que cubra una posición sin suplente. Solo recomienda: nunca puja.
     */
    fun buyRecommendations(
        market: List<Player>,
        roster: List<Player>,
        sellIds: Set<String>,
        rounds: List<Map<String, Fixture>>,
        info: TeamInfo,
        cal: Calibration = Calibration.NONE,
        maxBuys: Int = DEFAULT_MAX_BUYS,
        bids: List<BidObs> = emptyList(),
    ): List<BuyRec> {
        val keep = roster.filter { it.id !in sellIds }
        val ownIds = roster.map { it.id }.toSet()
        val keepScored = Analyzer.scoreHorizon(keep, rounds, cal)
        val basePlan = Analyzer.plans(keepScored).firstOrNull()
        val baseline = basePlan?.total ?: 0.0
        val maxBid = info.budget + info.teamValue / 2
        val healthy = market.filter { it.id !in ownIds && !it.isUnavailable && !it.isDoubt && Analyzer.playProb(it) >= 0.6 }

        fun build(c: Player, gain: Double, extra: String?, coverage: Boolean): BuyRec {
            val (bid, n) = suggestedBid(c, bids)
            val reasons = ArrayList<String>()
            reasons.add(extra ?: "mejora el once en +${fmt(gain)} pts esperados")
            if (c.last5 > c.avg) reasons.add("en racha (últimos 5: ${fmt(c.last5)} > media ${fmt(c.avg)})")
            if (c.trendPct >= TREND) reasons.add("su valor sube (+${pct(c.trendPct)} %): fichar antes de que suba más")
            if (c.trendPct <= -TREND) reasons.add("su valor baja (${pct(c.trendPct)} %): puja con prudencia")
            val fxs = rounds.map { it.forPlayer(c) }
            val cal3 = fxs.filterNotNull().joinToString(", ") { "${it.opponent} (${if (it.isHome) "c" else "f"})" }
            if (cal3.isNotEmpty()) reasons.add("próximos rivales: $cal3")
            if (c.isWarned) reasons.add("apercibido")
            return BuyRec(c, Analyzer.expected(c, fxs.firstOrNull()), gain, bid, bid <= maxBid, reasons, n, coverage)
        }

        val recs = healthy.asSequence()
            .mapNotNull { c ->
                val total = Analyzer.plans(Analyzer.scoreHorizon(keep + c, rounds, cal)).firstOrNull()?.total ?: return@mapNotNull null
                val gain = total - baseline
                val need = if (c.role == Role.CENTROCAMPISTA) 1.5 else 0.7 // prioridad: portero, defensa, delantero top
                if (gain < need) null else build(c, gain, null, false)
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
        // Cobertura: si queda hueco en la lista y hay una posición sin suplente, un jugador barato y sano.
        if (out.size < maxBuys && basePlan != null) {
            val holes = Coverage.holes(keepScored, basePlan).filter { r -> out.none { it.player.role == r } }
            val order = listOf(Role.PORTERO, Role.DEFENSA, Role.DELANTERO, Role.CENTROCAMPISTA)
            for (r in order.filter { it in holes }) {
                val pick = healthy.filter { it.role == r && it.value <= COVERAGE_MAX_VALUE }
                    .maxByOrNull { Analyzer.expected(it, null) } ?: continue
                val rec = build(pick, 0.0, "cubre un hueco: sin suplente en ${r.label.lowercase()}", true)
                if (rec.fitsBudget) { out.add(rec); break }
            }
        }
        return out
    }
}

internal fun fmt(d: Double): String = String.format(java.util.Locale.forLanguageTag("es"), "%.1f", d)
