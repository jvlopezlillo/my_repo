package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MoreFeaturesTest {
    private val s = Session("t", "u", "c", "ut", "x")
    private var n = 0

    private fun pj(
        name: String, role: String, avg: Double = 5.0, last5: Double = 5.0, fit: List<Double>? = null, status: String = "",
        value: Long = 5_000_000, change: Long = 0, team: String = "Equipo A", teamId: String = "tA", market: Boolean = false,
    ): JSONObject = JSONObject().put("id", "id${n++}").put("name", name).put("role", role).put("team", team).put("teamId", teamId)
        .put("value", value).put("status", status).put("market", market).put("change", change)
        .put("average", JSONObject().put("average", avg).put("homeAverage", avg).put("awayAverage", avg)
            .put("averageLastFive", last5).put("matches", 10).put("fitness", JSONArray(fit ?: List(5) { last5 })))

    private fun players(vararg o: JSONObject) = Parse.players(JSONArray(o.toList()))

    // ---- 14: detector de cambios en la API ----
    @Test fun apiChangeIsReportedClearly() {
        val api = FutmondoApi({ _, _ -> """{"answer":[{"id":"1","name":"X","valor":3}]}""" }, s)
        val e = assertFailsWith<ApiChangedException> { api.roster() }
        assertTrue("role" in e.missing && "value" in e.missing)
        assertTrue(e.userMessage.contains("cambiado su API") && e.userMessage.contains(FutmondoApi.ROSTER))
        val noAvg = FutmondoApi({ _, _ -> """{"answer":[{"id":"1","name":"X","role":"portero","value":3}]}""" }, s)
        assertEquals(listOf("average"), assertFailsWith<ApiChangedException> { noAvg.roster() }.missing)
        val lineup = FutmondoApi({ _, _ -> """{"answer":{"foo":1}}""" }, s)
        assertFailsWith<ApiChangedException> { lineup.lineup() }
        // Una lista vacía no es un cambio de API.
        assertTrue(FutmondoApi({ _, _ -> """{"answer":[]}""" }, s).roster().isEmpty())
    }

    // ---- 2: puja aprendida ----
    @Test fun bidPremiumUsesMedianOfLeagueSalesOnceThereAreEnough() {
        assertEquals(0.35 to 0, BidModel.premium(emptyList(), 5_000_000))
        val obs = (1..5).map { BidObs("o$it", it.toLong(), 5_000_000, 5_000_000 + it * 100_000L) } // +2..+10 %
        val (prem, cnt) = BidModel.premium(obs, 6_000_000)
        assertEquals(5, cnt); assertEquals(0.06, prem, 1e-9)
        assertEquals(0.20 to 0, BidModel.premium(obs, 15_000_000)) // otro tramo: sin muestras
        assertEquals(5, BidModel.merge(obs, obs).size)
        assertEquals(obs, BidModel.fromJson(BidModel.toJson(obs)))
    }

    @Test fun refreshBidsOnlyCountsCompletedPurchasesAndSkipsValueCallWhenNothingNew() {
        val calls = ArrayList<String>()
        val api = FutmondoApi({ path, _ ->
            calls.add(path)
            when (path) {
                FutmondoApi.PRESSROOM -> """{"answer":{"news":[
                  {"_id":"n1","created":"2026-11-08T05:00:00.000Z","_player":{"_id":"p1"},"_buyer":{"_id":"b"},"price":1100000},
                  {"_id":"n2","created":"2026-11-08T06:00:00.000Z","_player":{"_id":"p2"},"_seller":{"_id":"s"},"price":900000}]}}"""
                FutmondoApi.CHAMP_PLAYERS -> """{"answer":{"players":[{"id":"p1","value":1000000},{"id":"p2","value":800000}]}}"""
                else -> error(path)
            }
        }, s)
        val svc = FutmondoService(api)
        val obs = svc.refreshBids(emptyList())
        assertEquals(listOf(BidObs("n1", Instant("2026-11-08T05:00:00.000Z"), 1_000_000, 1_100_000)), obs)
        calls.clear()
        assertEquals(obs, svc.refreshBids(obs))
        assertFalse(FutmondoApi.CHAMP_PLAYERS in calls)
    }

    private fun Instant(s: String) = java.time.Instant.parse(s).toEpochMilli()

    @Test fun suggestedBidReflectsLearnedPremium() {
        val p = players(pj("A", "delantero", value = 30_000_000)).single()
        val default = MarketReview.suggestedBid(p)
        assertEquals(32_400_000L to 0, default)
        val obs = (1..6).map { BidObs("o$it", it.toLong(), 30_000_000, 31_500_000) } // +5 %
        assertEquals(31_500_000L to 6, MarketReview.suggestedBid(p, obs))
    }

    // ---- 3: tendencia de valor ----
    @Test fun trendAppearsInSellAndBuyReasons() {
        val falling = players(pj("Cae", "centrocampista", avg = 3.0, last5 = 2.0, change = -200_000))
        val sell = MarketReview.sellCandidates(falling, emptySet()).single()
        assertTrue(sell.reasons.any { it.contains("su valor baja") })
        val full = arrayListOf(pj("P1", "portero"), pj("P2", "portero"))
        for (i in 1..5) full.add(pj("D$i", "defensa")); for (i in 1..5) full.add(pj("M$i", "centrocampista")); for (i in 1..3) full.add(pj("F$i", "delantero"))
        val roster = players(*full.toTypedArray())
        val rising = players(pj("Sube", "delantero", avg = 9.0, last5 = 9.0, change = 300_000))
        val info = TeamInfo(1_000_000, 100_000_000, 18, 10)
        val buy = MarketReview.buyRecommendations(rising, roster, emptySet(), listOf(emptyMap()), info).first()
        assertTrue(buy.reasons.any { it.contains("su valor sube") })
    }

    // ---- 4: bajas largas estimadas ----
    @Test fun longAbsenceIsPreselectedOnlyWithEvidence() {
        val long = players(pj("Largo", "defensa", status = "injured", fit = listOf(5.0, 6.0, 0.0, 0.0, 0.0)))
        val c = MarketReview.sellCandidates(long, emptySet()).single()
        assertTrue(c.preselected && c.reasons.any { it.contains("baja larga probable") })
        val recent = players(pj("Reciente", "defensa", status = "injured", fit = listOf(0.0, 4.0, 7.0, 4.0, 0.0)))
        val r = MarketReview.sellCandidates(recent, emptySet()).single()
        assertFalse(r.preselected)
        assertTrue(r.reasons.any { it.contains("compruébala") })
    }

    // ---- 1: cobertura ----
    @Test fun coverageWarnsAboutMissingBackupsAndKeyPlayers() {
        val list = ArrayList<JSONObject>()
        list.add(pj("Unico", "portero", 6.0, 6.0))
        for (i in 1..4) list.add(pj("D$i", "defensa", 5.0, 5.0))
        for (i in 1..5) list.add(pj("M$i", "centrocampista", 5.0, 5.0))
        for (i in 1..2) list.add(pj("F$i", "delantero", 5.0, 5.0))
        val scored = Analyzer.score(players(*list.toTypedArray()), emptyMap())
        val plan = Analyzer.plans(scored).first()
        val w = Coverage.warnings(scored, plan)
        assertTrue(w.any { it.contains("Solo tienes 1 portero") })
        assertTrue(w.any { it.contains("Si falla un portero te quedas sin once válido") })
    }

    @Test fun cheapBackupGoalkeeperIsRecommendedToCoverTheHole() {
        val list = arrayListOf(pj("Unico", "portero", 6.0, 6.0))
        for (i in 1..5) list.add(pj("D$i", "defensa", 5.0, 5.0))
        for (i in 1..5) list.add(pj("M$i", "centrocampista", 5.0, 5.0))
        for (i in 1..4) list.add(pj("F$i", "delantero", 5.0, 5.0))
        val roster = players(*list.toTypedArray())
        val market = players(pj("Suplente", "portero", 4.0, 4.0, value = 2_000_000), pj("Caro", "portero", 4.0, 4.0, value = 30_000_000))
        val recs = MarketReview.buyRecommendations(market, roster, emptySet(), listOf(emptyMap()), TeamInfo(5_000_000, 100_000_000, 18, 10))
        val cov = recs.single { it.coverage }
        assertEquals("Suplente", cov.player.name)
        assertTrue(cov.reasons[0].contains("cubre un hueco"))
    }

    // ---- 9: comparar ----
    @Test fun comparisonGivesVerdict() {
        val ps = players(pj("Mejor", "delantero", 8.0, 8.0), pj("Peor", "delantero", 4.0, 4.0))
        val sc = Analyzer.score(ps, emptyMap())
        val txt = Sections.toText(Compare.sections(sc[0], sc[1]))
        assertTrue(txt.contains("Mejor para la próxima jornada: Mejor"))
        assertTrue(txt.contains("Puntos esperados"))
    }

    // ---- 13: copia de seguridad ----
    @Test fun backupHasNoSecretsAndMergesWithoutLosingResolvedData() {
        val pred = Prediction(5, 1, listOf(PredItem("a", "A", Role.DEFENSA, 5.0)))
        val resolved = Prediction(5, 0, listOf(PredItem("a", "A", Role.DEFENSA, 5.0, 7.0)))
        val h = HistoryEntry(10, HistoryKind.VENTA, "x")
        val txt = Backup.export(BackupData(listOf(h), listOf(pred), emptyList()))
        assertFalse(txt.contains("token") || txt.contains("pwd"))
        val back = Backup.parse(txt)!!
        assertEquals(listOf(h), back.history)
        val merged = Backup.merge(back, BackupData(listOf(h), listOf(resolved), emptyList()))
        assertEquals(1, merged.history.size)
        assertEquals(7.0, merged.predictions.single().items[0].actual) // conserva la resuelta
        assertEquals(null, Backup.parse("no es una copia"))
        assertEquals(null, Backup.parse("""{"app":"otra"}"""))
    }

    // ---- 7 + 1: análisis completo, mejora respecto al once actual y hora de bloqueo ----
    @Test fun analysisComputesImprovementOverCurrentLineupAndFirstMatchTime() {
        val roster = JSONArray()
        val ids = HashMap<String, String>()
        fun add(name: String, role: String, avg: Double) { val o = pj(name, role, avg, avg); ids[name] = o.getString("id"); roster.put(o) }
        add("G1", "portero", 6.0); add("G2", "portero", 3.0)
        for (i in 1..5) add("D$i", "defensa", 5.0 + i * 0.1)
        for (i in 1..6) add("M$i", "centrocampista", 5.0 + i * 0.1)
        for (i in 1..4) add("F$i", "delantero", 5.0 + i * 0.1)
        // Once actual 4-4-2 con los peores de cada puesto.
        val current = listOf("G2", "D1", "D2", "D3", "D4", "M1", "M2", "M3", "M4", "F1", "F2")
        val api = FutmondoApi({ path, _ ->
            when (path) {
                FutmondoApi.ROSTER -> """{"answer":$roster}"""
                FutmondoApi.MATCHES -> """{"answer":{"round":{"_id":"6a4af7f1ae633549bd0f0403","number":13},"matches":[
                    {"date":"2026-11-21T20:00:00.000Z","homeTeam":{"id":"tA","name":"A","slug":"a"},"awayTeam":{"id":"tB","name":"B","slug":"b"}},
                    {"date":"2026-11-21T13:00:00.000Z","homeTeam":{"id":"tC","name":"C","slug":"c"},"awayTeam":{"id":"tD","name":"D","slug":"d"}}]}}"""
                FutmondoApi.LINEUP -> """{"answer":{"strategy":"4-4-2","players":[${current.joinToString(",") { """{"id":"${ids[it]}"}""" }}],"bench":{"players":[]}}}"""
                FutmondoApi.TEAM_INFO -> """{"answer":{"budget":1000000,"teamValue":100000000,"position":10,"configuration":{"maxPlayersInRoster":18}}}"""
                else -> error(path)
            }
        }, s)
        val a = FutmondoService(api).analyze()
        assertEquals(13, a.round)
        assertEquals(java.time.Instant.parse("2026-11-21T13:00:00.000Z").toEpochMilli(), a.firstMatchMillis)
        assertNotNull(a.currentTotal)
        assertTrue(a.improvement!! > 1.0)
        val txt = Reports.analysis(a)
        assertTrue(txt.contains("el recomendado gana"))
        assertTrue(Sections.toText(Reports.analysisSections(a)).contains("ONCE RECOMENDADO"))
    }

    @Test fun noValidElevenIsExplainedInsteadOfShowingAbsurdPointLoss() {
        val list = arrayListOf(pj("G1", "portero"), pj("G2", "portero"))
        for (i in 1..3) list.add(pj("D$i", "defensa")) // solo 3: si cae uno no hay formación posible
        for (i in 1..5) list.add(pj("M$i", "centrocampista"))
        for (i in 1..4) list.add(pj("F$i", "delantero"))
        val scored = Analyzer.score(players(*list.toTypedArray()), emptyMap())
        val plan = Analyzer.plans(scored).first()
        val w = Coverage.warnings(scored, plan)
        assertTrue(w.any { it.contains("te quedas sin once válido") && it.contains("defensa") })
        assertTrue(w.none { Regex("pierdes (\\d{2,})").containsMatchIn(it) }) // nada de "pierdes 79 pts"
    }

    private fun rosterOneGk(): List<Player> {
        val list = arrayListOf(pj("Unico", "portero", 6.0, 6.0))
        for (i in 1..5) list.add(pj("D$i", "defensa", 5.0, 5.0))
        for (i in 1..5) list.add(pj("M$i", "centrocampista", 5.0, 5.0))
        for (i in 1..4) list.add(pj("F$i", "delantero", 5.0, 5.0))
        return players(*list.toTypedArray())
    }

    @Test fun explainsWhyNoBackupWhenMarketOnlyHasPlayersWhoRarelyPlay() {
        // Caso real: porteros baratos que casi no juegan no sirven de suplente.
        val market = players(pj("Banquillo", "portero", 4.0, 0.5, fit = listOf(0.0, 0.0, 0.0, 2.0, 0.0), value = 1_000_000))
        val notes = ArrayList<String>()
        val recs = MarketReview.buyRecommendations(market, rosterOneGk(), emptySet(), listOf(emptyMap()),
            TeamInfo(5_000_000, 100_000_000, 18, 10), notes = notes)
        assertTrue(recs.none { it.coverage })
        assertTrue(notes.single().contains("Sin suplente en portero") && notes.single().contains("juega poco") && notes.single().contains("Banquillo"))
        val none = ArrayList<String>()
        MarketReview.buyRecommendations(emptyList(), rosterOneGk(), emptySet(), listOf(emptyMap()), TeamInfo(5_000_000, 100_000_000, 18, 10), notes = none)
        assertTrue(none.single().contains("no hay ningún portero en el mercado"))
    }

    @Test fun backupIsClearlyLabelledInTheReport() {
        val market = players(pj("Suplente", "portero", 4.0, 4.0, value = 2_000_000))
        val info = TeamInfo(5_000_000, 100_000_000, 18, 10)
        val roster = rosterOneGk()
        val buys = MarketReview.buyRecommendations(market, roster, emptySet(), listOf(emptyMap()), info)
        val m = MarketResult(emptyList(), buys, info, roster.size, emptyList())
        val txt = Reports.market(m)
        assertTrue(txt.contains("SUPLENTE RECOMENDADO (cubre un hueco; NO mejora tu once titular)"))
        assertTrue(txt.contains("SUPLENTE · Suplente"))
        assertTrue(txt.contains("FICHAJES RECOMENDADOS (0;")) // no se cuenta como mejora del once
    }
}
