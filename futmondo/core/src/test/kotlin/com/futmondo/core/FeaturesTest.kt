package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeaturesTest {
    private val s = Session("t", "u", "c", "ut", "x")

    private fun item(i: Int, role: Role = Role.DEFENSA, e: Double = 5.0, a: Double? = null) =
        PredItem("p$i", "J$i", role, e, a)

    @Test fun roundIdsAdvanceBy11() {
        // Observado: jornada 12 -> 13 cambia la marca de tiempo (f0 -> f1) y el contador suma 11.
        assertTrue("6a4af7f1ae633549bd0f0403" in Rounds.candidates("6a4af7f0ae633549bd0f03f8", 1))
        assertTrue("6a4af7f1ae633549bd0f040e" in Rounds.candidates("6a4af7f1ae633549bd0f0403", 1))
        assertTrue(Rounds.candidates("corto", 1).isEmpty())
    }

    private fun matchList(number: Int, id: String) = """{"answer":{"round":{"_id":"$id","number":$number},"matches":[
        {"homeTeam":{"id":"tA","name":"A","slug":"a"},"awayTeam":{"id":"tB","name":"B","slug":"b"}}]}}"""

    @Test fun upcomingRoundsStopsWhenNumberDoesNotMatch() {
        val ids = ArrayList<Any>()
        val api = FutmondoApi({ path, body ->
            assertEquals(FutmondoApi.MATCHES, path)
            val rid = JSONObject(body).getJSONObject("query").get("roundId")
            ids.add(rid)
            when (rid) {
                JSONObject.NULL -> matchList(13, "6a4af7f1ae633549bd0f0403")
                "6a4af7f2ae633549bd0f040e" -> matchList(14, rid as String) // solo acierta la marca de tiempo +1
                else -> matchList(99, rid as String) // número inesperado -> se descarta
            }
        }, s)
        val r = api.upcomingRounds(3)
        assertEquals(listOf(13, 14), r.map { it.number })
        assertTrue(ids.size >= 4) // varios candidatos probados para la jornada 14 y 15
    }

    @Test fun upcomingRoundsToleratesApiErrorForFutureRounds() {
        val api = FutmondoApi({ _, body ->
            if (JSONObject(body).getJSONObject("query").get("roundId") == JSONObject.NULL) matchList(13, "6a4af7f1ae633549bd0f0403")
            else """{"answer":{"error":true,"code":"api.round.not_found"}}"""
        }, s)
        assertEquals(1, api.upcomingRounds(3).size)
    }

    @Test fun calibrationNeedsEnoughSamplesAndIsClamped() {
        val few = listOf(Prediction(1, 0, (1..5).map { item(it, a = 9.0) }))
        assertEquals(0.0, Calibration.from(few).offset(Role.DEFENSA))
        val many = listOf(Prediction(1, 0, (1..20).map { item(it, a = 15.0) })) // error +10 -> se recorta
        assertEquals(Calibration.MAX_OFFSET, Calibration.from(many).offset(Role.DEFENSA))
        val low = listOf(Prediction(1, 0, (1..20).map { item(it, e = 5.0, a = 4.0) }))
        val off = Calibration.from(low).offset(Role.DEFENSA)
        assertTrue(off < 0 && off > -1.0) // sesgo -1 encogido por pocas muestras
        assertEquals(0.0, Calibration.from(low).adjust(Role.DEFENSA, 0.0)) // los no disponibles siguen en 0
        assertTrue(Calibration.from(low).adjust(Role.DEFENSA, 5.0) < 5.0)
    }

    @Test fun unavailablePlayersDoNotPolluteCalibration() {
        val p = Prediction(1, 0, (1..20).map { item(it, e = 0.0, a = 6.0) })
        assertEquals(0.0, Calibration.from(listOf(p)).offset(Role.DEFENSA))
    }

    @Test fun predictionsRoundTripAndReplaceSameRound() {
        val p1 = Prediction(5, 1, listOf(item(1, a = 3.0), item(2)))
        val back = Predictions.fromJson(Predictions.toJson(listOf(p1)))
        assertEquals(listOf(p1), back)
        assertEquals(null, back[0].items[1].actual)
        val up = Predictions.upsert(back, Prediction(5, 2, listOf(item(3))))
        assertEquals(1, up.size); assertEquals(2L, up[0].time)
        assertTrue(Predictions.fromJson("basura").isEmpty())
    }

    @Test fun resolveFillsOnlyClosedRoundsAndLeavesMissingPending() {
        val calls = ArrayList<String>()
        val api = FutmondoApi({ path, body ->
            calls.add(path)
            when (path) {
                FutmondoApi.ROUNDS -> """{"answer":[{"number":12,"status":"closed"},{"number":13,"status":"running"}]}"""
                FutmondoApi.PLAYER_SUMMARY -> {
                    val id = JSONObject(body).getJSONObject("query").getString("playerId")
                    if (id == "p1") """{"answer":{"points":[{"round":12,"points":7.5},{"round":11,"points":1}]}}"""
                    else """{"answer":{"points":[{"round":11,"points":2}]}}"""
                }
                else -> error(path)
            }
        }, s)
        val preds = listOf(
            Prediction(12, 0, listOf(item(1), item(2))),
            Prediction(13, 0, listOf(item(1))), // jornada en curso: no se toca
        )
        val out = FutmondoService(api).resolve(preds)
        assertEquals(7.5, out[0].items[0].actual)
        assertEquals(null, out[0].items[1].actual)   // sin dato de esa jornada -> pendiente
        assertEquals(null, out[1].items[0].actual)
        assertEquals(1, calls.count { it == FutmondoApi.ROUNDS })
    }

    @Test fun horizonScoreAveragesFixtures() {
        val p = Parse.player(JSONObject().put("id", "x").put("name", "X").put("role", "delantero").put("team", "A").put("teamId", "tA")
            .put("average", JSONObject().put("average", 5.0).put("homeAverage", 5.0).put("awayAverage", 5.0)
                .put("averageLastFive", 5.0).put("matches", 10).put("fitness", JSONArray(List(5) { 5.0 }))))!!
        val easy = mapOf("tA" to Fixture(true, "B", 0.8))
        val hard = mapOf("tA" to Fixture(false, "C", 0.1))
        val one = Analyzer.scoreHorizon(listOf(p), listOf(easy)).single().expected
        val three = Analyzer.scoreHorizon(listOf(p), listOf(easy, hard, hard)).single().expected
        assertTrue(three < one)
        assertEquals(Analyzer.expected(p, easy["tA"]), Analyzer.scoreHorizon(listOf(p), listOf(easy, hard)).single().raw.let { Analyzer.expected(p, easy["tA"]) })
    }

    @Test fun precisionReportInSpanish() {
        assertTrue(Predictions.report(emptyList()).startsWith("Aún no hay datos"))
        val txt = Predictions.report(listOf(Prediction(1, 0, (1..9).map { item(it, e = 5.0, a = 6.0) })))
        assertTrue(txt.contains("PRECISIÓN DEL MODELO") && txt.contains("Defensa"))
    }
}
