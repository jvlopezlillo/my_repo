package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Datos 100 % sintéticos: ni credenciales ni datos reales. */
class CoreTest {
    private var n = 0
    private fun pj(
        name: String, role: String, avg: Double, last5: Double, fit: List<Double>? = null,
        status: String = "", value: Long = 5_000_000, team: String = "Equipo A", market: Boolean = false,
        teamId: String = "tA", matches: Int = 10,
    ): JSONObject = JSONObject()
        .put("id", "id${n++}").put("name", name).put("role", role).put("team", team).put("teamId", teamId)
        .put("value", value).put("status", status).put("market", market).put("price", value)
        .put("average", JSONObject().put("average", avg).put("homeAverage", avg).put("awayAverage", avg)
            .put("averageLastFive", last5).put("matches", matches)
            .put("fitness", JSONArray(fit ?: List(5) { last5 })))

    private fun roster(): JSONArray = JSONArray().apply {
        put(pj("Portero1", "portero", 5.5, 5.5)); put(pj("Portero2", "portero", 4.0, 4.0))
        for (i in 1..4) put(pj("Def$i", "defensa", 5.0 + i * 0.1, 5.0))
        for (i in 1..6) put(pj("Med$i", "centrocampista", 4.0 + i * 0.3, 4.0 + i * 0.3))
        for (i in 1..4) put(pj("Del$i", "delantero", 5.0 + i * 0.2, 5.0))
        put(pj("Malo", "centrocampista", 3.0, 2.0, fit = listOf(0.0, 0.0, 6.0, 0.0, 4.0)))
    }

    private val fixture = JSONObject("""{"matches":[{"homeTeam":{"id":"tA","name":"Equipo A","slug":"equipo-a"},
        "awayTeam":{"id":"tB","name":"Equipo B","slug":"equipo-b"},
        "odds":{"sels":[{"ssn":"1","odds":[{"c":2.0}]},{"ssn":"X","odds":[{"c":3.4}]},{"ssn":"2","odds":[{"c":4.0}]}]}}]}""")

    @Test fun parsesFixturesAndOdds() {
        val f = Parse.fixtures(fixture)
        val a = f["tA"]!!
        assertTrue(a.isHome); assertEquals("Equipo B", a.opponent)
        assertTrue(a.winProb!! > 0.45 && a.winProb!! < 0.55)
        assertFalse(f["equipob"]!!.isHome)
    }

    @Test fun unavailablePlayersExpectNothing() {
        val p = Parse.player(pj("Lesionado", "delantero", 7.0, 7.0, status = "injured"))!!
        assertEquals(0.0, Analyzer.expected(p, null))
        val d = Parse.player(pj("Duda", "delantero", 7.0, 7.0, status = "doubt"))!!
        assertTrue(Analyzer.expected(d, null) < 7.0)
    }

    @Test fun picksBestFormationWithinRosterLimits() {
        val players = Parse.players(roster())
        val plans = Analyzer.plans(Analyzer.score(players, Parse.fixtures(fixture)))
        assertTrue(plans.isNotEmpty())
        // Solo hay 4 defensas: ninguna formación de 5 defensas es posible.
        assertTrue(plans.none { it.formation.startsWith("5") })
        for (p in plans) {
            assertEquals(11, p.starters.size)
            assertEquals(1, p.starters.count { it.player.role == Role.PORTERO })
        }
        assertTrue(plans.zipWithNext().all { (a, b) -> a.total >= b.total })
    }

    @Test fun sellsBadFormAndLostStarterButNotRecommendedStarters() {
        val players = Parse.players(roster())
        val plan = Analyzer.plans(Analyzer.score(players, emptyMap())).first()
        val starters = plan.starters.map { it.player.id }.toSet()
        val sells = MarketReview.sellCandidates(players, starters)
        val malo = sells.first { it.player.name == "Malo" }
        assertTrue(malo.preselected)
        assertEquals(malo.player.value, malo.price)
        assertTrue(sells.none { it.player.id in starters })
    }

    @Test fun injuredStarterIsListedButNotPreselected() {
        val list = JSONArray().put(pj("Roto", "delantero", 7.0, 7.0, status = "injured"))
        val players = Parse.players(list)
        val s = MarketReview.sellCandidates(players, setOf(players[0].id)).single()
        assertFalse(s.preselected)
    }

    @Test fun recommendsAtMostThreeBuysThatImproveTheEleven() {
        val players = Parse.players(roster())
        val market = Parse.players(JSONArray()
            .put(pj("Crack", "delantero", 9.0, 9.5, value = 30_000_000))
            .put(pj("Mediocre", "centrocampista", 3.0, 3.0))
            .put(pj("Roto", "delantero", 9.0, 9.0, status = "injured")))
        val info = TeamInfo(budget = 1_000_000, teamValue = 200_000_000, maxPlayers = 18, position = 10)
        val buys = MarketReview.buyRecommendations(market, players, emptySet(), listOf(emptyMap()), info)
        assertEquals(listOf("Crack"), buys.map { it.player.name })
        assertTrue(buys[0].suggestedBid > 30_000_000)
    }

    @Test fun apiRefusesAnyPathOutsideTheAllowList() {
        val seen = ArrayList<String>()
        val api = FutmondoApi({ path, _ -> seen.add(path); """{"answer":{"code":"api.general.ok"}}""" },
            Session("t", "u", "c", "ut", "x"))
        api.putOnMarket("p1", 100)
        assertEquals(listOf(FutmondoApi.PUT_ON_MARKET), seen)
        // No existen en la lista: venta directa, pujas, ofertas.
        for (bad in listOf("/1/market/bid", "/1/market/directsale", "/1/market/acceptbid", "/2/userteam/moveplayer")) {
            assertFalse(bad in FutmondoApi.READ_PATHS || bad in FutmondoApi.WRITE_PATHS)
        }
        assertEquals(setOf(FutmondoApi.PUT_ON_MARKET), FutmondoApi.WRITE_PATHS)
    }

    @Test fun loginKeepsOnlyTokenAndSurfacesApiErrors() {
        val bodies = ArrayList<String>()
        val api = FutmondoApi({ path, body ->
            bodies.add(body)
            when (path) {
                FutmondoApi.LOGIN -> """{"answer":{"mobile":{"token":"tok","userid":"uid"}}}"""
                else -> """{"answer":{"championships":[{"id":"c1","finished":false,"userteam":{"id":"ut1","name":"yo"}}]}}"""
            }
        })
        val s = api.login("a@b.c", "secreto")
        assertEquals(Session("tok", "uid", "c1", "ut1", "yo"), s)
        assertFalse(s.toString().contains("secreto"))
        assertTrue(bodies[1].contains("\"token\":\"tok\""))

        val failing = FutmondoApi({ _, _ -> """{"answer":{"error":true,"code":"api.market.max_number_players_in_roster"}}""" },
            Session("t", "u", "c", "ut", "x"))
        val e = assertFailsWith<FutmondoException> { failing.putOnMarket("p", 1) }
        assertEquals("api.market.max_number_players_in_roster", e.code)
    }

    @Test fun reportsAreInSpanish() {
        val players = Parse.players(roster())
        val plans = Analyzer.plans(Analyzer.score(players, emptyMap()))
        val a = Analysis(Lineup("4-4-2", emptyList(), emptyList()), plans, TeamInfo(883_177, 294_000_000, 18, 10), players)
        val txt = Reports.analysis(a)
        assertTrue(txt.contains("MEJOR FORMACIÓN") && txt.contains("BANQUILLO"))
        assertEquals("35,05 M", Reports.money(35_050_000))
    }
}
