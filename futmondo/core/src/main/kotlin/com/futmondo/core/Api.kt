package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject

/** Transporte HTTP: en Android lo implementa HttpURLConnection; en pruebas, un doble. */
fun interface HttpPoster {
    /** POST JSON a https://api.futmondo.com + path; devuelve el cuerpo de la respuesta. */
    fun post(path: String, jsonBody: String): String
}

class FutmondoException(val code: String) : Exception(code)

/**
 * Cliente de la API NO oficial de Futmondo.
 *
 * Regla de seguridad: la única operación que modifica datos es [putOnMarket]. No hay venta directa,
 * ni ofertas, ni pujas, ni cambios de alineación. [post] rechaza cualquier ruta que no esté en la lista.
 */
class FutmondoApi(private val http: HttpPoster, private var session: Session? = null) {

    companion object {
        const val LOGIN = "/5/login/with_mail"
        const val ACTIVE = "/2/user/activechampionships"
        const val TEAM_INFO = "/1/userteam/information"
        const val ROSTER = "/1/userteam/roster"
        const val LINEUP = "/1/userteam/lineup"
        const val MATCHES = "/1/match/list"
        const val MARKET = "/1/market/players"
        const val MY_MARKET = "/1/market/myplayers"
        const val PUT_ON_MARKET = "/1/market/putonmarket"

        /** Rutas de solo lectura permitidas. */
        val READ_PATHS = setOf(LOGIN, ACTIVE, TEAM_INFO, ROSTER, LINEUP, MATCHES, MARKET, MY_MARKET)

        /** Única ruta de escritura permitida: poner a la venta. */
        val WRITE_PATHS = setOf(PUT_ON_MARKET)
    }

    fun setSession(s: Session?) { session = s }

    private fun post(path: String, query: JSONObject): JSONObject {
        require(path in READ_PATHS || path in WRITE_PATHS) { "Ruta no permitida: $path" }
        val s = session
        val body = JSONObject()
            .put("header", JSONObject().put("token", s?.token ?: "").put("userid", s?.userId ?: ""))
            .put("query", query)
            .put("answer", JSONObject())
        val answer = JSONObject(http.post(path, body.toString())).opt("answer")
        if (answer is JSONObject && answer.optBoolean("error", false)) {
            throw FutmondoException(answer.optString("code", "error"))
        }
        return when (answer) {
            is JSONObject -> answer
            is JSONArray -> JSONObject().put("_list", answer)
            else -> JSONObject()
        }
    }

    private fun list(o: JSONObject): JSONArray = o.optJSONArray("_list") ?: JSONArray()

    private fun q(): JSONObject {
        val s = requireNotNull(session) { "Sin sesión" }
        return JSONObject().put("championshipId", s.championshipId).put("userteamId", s.userteamId)
    }

    /** Inicia sesión. La contraseña no se guarda: solo se devuelve token/userId. */
    fun login(mail: String, pwd: String): Session {
        session = null
        val a = post(LOGIN, JSONObject().put("mail", mail).put("pwd", pwd))
        val m = a.optJSONObject("mobile") ?: throw FutmondoException("login.sin_datos")
        val token = m.optString("token")
        val userId = m.optString("userid")
        if (token.isEmpty() || userId.isEmpty()) throw FutmondoException("login.sin_token")
        session = Session(token, userId, "", "", "")
        val act = post(ACTIVE, JSONObject().put("excludeGeneral", false).put("includeProphets", true))
        val champs = act.optJSONArray("championships") ?: JSONArray()
        val c = (0 until champs.length()).map { champs.getJSONObject(it) }
            .firstOrNull { !it.optBoolean("finished", false) && it.optJSONObject("userteam") != null }
            ?: throw FutmondoException("login.sin_liga")
        val ut = c.getJSONObject("userteam")
        val s = Session(token, userId, c.getString("id"), ut.getString("id"), ut.optString("name"))
        session = s
        return s
    }

    fun teamInfo(): TeamInfo = Parse.teamInfo(post(TEAM_INFO, q().put("type", "market")))

    fun roster(): List<Player> = Parse.players(list(post(ROSTER, q())))

    fun lineup(): Lineup = Parse.lineup(post(LINEUP, q()))

    /** Partidos de la próxima jornada (roundId nulo = siguiente jornada). */
    fun nextFixtures(): Map<String, Fixture> =
        Parse.fixtures(post(MATCHES, q().put("roundId", JSONObject.NULL)))

    fun marketPlayers(): List<Player> = Parse.players(list(post(MARKET, q().put("type", "market"))))

    fun myMarketPlayers(): List<Player> = Parse.players(list(post(MY_MARKET, q().put("type", "market"))))

    /** Pone a la venta (NO venta directa). Solo llamar tras confirmación explícita del usuario. */
    fun putOnMarket(playerId: String, price: Long) {
        post(
            PUT_ON_MARKET,
            q().put("price", price).put("player_id", playerId)
                .put("isClause", JSONObject.NULL).put("mode", JSONObject.NULL).put("toLoan", JSONObject.NULL),
        )
    }
}
