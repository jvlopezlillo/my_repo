package com.futmondo.core

import org.json.JSONArray
import org.json.JSONObject

/** Transporte HTTP: en Android lo implementa HttpURLConnection; en pruebas, un doble. */
fun interface HttpPoster {
    /** POST JSON a https://api.futmondo.com + path; devuelve el cuerpo de la respuesta. */
    fun post(path: String, jsonBody: String): String
}

open class FutmondoException(val code: String) : Exception(code) {
    /** true si el error indica que la sesión ya no es válida. */
    val isAuth get() = code == "http.401" || code == "http.403" || code.contains("auth") || code.contains("token")

    /** Mensaje para el usuario, en español. */
    open val userMessage: String
        get() = if (isAuth) "La sesión ha caducado. Cierra sesión y vuelve a entrar." else "Futmondo devolvió un error: $code"
}

/** La respuesta de la API no tiene la forma esperada: probablemente Futmondo cambió algo. */
class ApiChangedException(val endpoint: String, val missing: List<String>) : FutmondoException("api.cambio:$endpoint") {
    override val userMessage: String
        get() = "Futmondo parece haber cambiado su API ($endpoint; falta: ${missing.joinToString()}). " +
            "Es probable que haga falta actualizar la app; no se ha hecho ningún cambio en tu cuenta."
}

/** Comprobaciones de forma de las respuestas, para dar un aviso claro si la API cambia. */
internal object Shape {
    fun keys(endpoint: String, o: JSONObject?, keys: List<String>, prefix: String = "") {
        val missing = keys.filter { o == null || !o.has(it) }.map { prefix + it }
        if (missing.isNotEmpty()) throw ApiChangedException(endpoint, missing)
    }

    /** Comprueba el primer elemento; `average` solo se exige si ningún elemento lo trae. */
    fun players(endpoint: String, a: JSONArray) {
        if (a.length() == 0) return
        keys(endpoint, a.optJSONObject(0), listOf("id", "name", "role", "value"))
        val withAvg = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("average") }
        if (withAvg.isEmpty()) throw ApiChangedException(endpoint, listOf("average"))
        keys(endpoint, withAvg[0], listOf("average", "averageLastFive", "fitness"), "average.")
    }
}

/** Compra de un jugador entre usuarios de la liga (del tablón de noticias). */
data class Sale(val id: String, val time: Long, val playerId: String, val price: Long)

/**
 * Ids de jornada candidatos. Son ObjectId: el contador (últimos 6 hex) avanza de 11 en 11, pero la marca
 * de tiempo (primeros 8 hex) puede variar en unos segundos entre jornadas (visto en el HAR: ...f0 -> ...f1).
 * Por eso se devuelven varios candidatos y quien los usa debe verificar el número de jornada.
 */
object Rounds {
    fun candidates(id: String, k: Int, tsSpread: Int = 3): List<String> {
        if (id.length != 24) return emptyList()
        val ts = id.substring(0, 8).toLong(16)
        val mid = id.substring(8, 18)
        val counter = id.substring(18).toLong(16) + 11L * k
        return (0 until tsSpread).map { "%08x".format(ts + it) + mid + "%06x".format(counter) }
    }
}

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
        const val ROUNDS = "/1/userteam/rounds"
        const val PLAYER_SUMMARY = "/1/player/summary"
        const val PRESSROOM = "/1/locker/pressroom"
        const val CHAMP_PLAYERS = "/5/league/championshipplayers"

        /** Rutas de solo lectura permitidas. */
        val READ_PATHS = setOf(LOGIN, ACTIVE, TEAM_INFO, ROSTER, LINEUP, MATCHES, MARKET, MY_MARKET, ROUNDS, PLAYER_SUMMARY, PRESSROOM, CHAMP_PLAYERS)

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
        val m = a.optJSONObject("mobile") ?: throw ApiChangedException(LOGIN, listOf("mobile"))
        val token = m.optString("token")
        val userId = m.optString("userid")
        if (token.isEmpty() || userId.isEmpty()) throw FutmondoException("login.sin_token")
        session = Session(token, userId, "", "", "")
        val act = post(ACTIVE, JSONObject().put("excludeGeneral", false).put("includeProphets", true))
        val champs = act.optJSONArray("championships") ?: throw ApiChangedException(ACTIVE, listOf("championships"))
        val c = (0 until champs.length()).map { champs.getJSONObject(it) }
            .firstOrNull { !it.optBoolean("finished", false) && it.optJSONObject("userteam") != null }
            ?: throw FutmondoException("login.sin_liga")
        val ut = c.getJSONObject("userteam")
        val s = Session(token, userId, c.getString("id"), ut.getString("id"), ut.optString("name"))
        session = s
        return s
    }

    fun teamInfo(): TeamInfo {
        val a = post(TEAM_INFO, q().put("type", "market"))
        Shape.keys(TEAM_INFO, a, listOf("budget", "teamValue"))
        return Parse.teamInfo(a)
    }

    fun roster(): List<Player> {
        val a = list(post(ROSTER, q()))
        Shape.players(ROSTER, a)
        return Parse.players(a)
    }

    fun lineup(): Lineup {
        val a = post(LINEUP, q())
        Shape.keys(LINEUP, a, listOf("strategy", "players"))
        return Parse.lineup(a)
    }

    /**
     * Partidos de la próxima jornada y de las siguientes (hasta [count]). Para las siguientes se deduce el id
     * de jornada probando candidatos; si ninguno devuelve el número esperado, se corta ahí.
     */
    fun upcomingRounds(count: Int): List<RoundFixtures> {
        val firstAnswer = post(MATCHES, q().put("roundId", JSONObject.NULL))
        Shape.keys(MATCHES, firstAnswer, listOf("round", "matches"))
        val first = Parse.round(firstAnswer)
        val out = arrayListOf(first)
        if (first.id.isEmpty()) return out
        for (k in 1 until count) {
            val r = Rounds.candidates(first.id, k).firstNotNullOfOrNull { cand ->
                try {
                    Parse.round(post(MATCHES, q().put("roundId", cand))).takeIf { it.number == first.number + k }
                } catch (e: FutmondoException) { null } catch (e: org.json.JSONException) { null }
            } ?: break
            out.add(r)
        }
        return out
    }

    /** Números de las jornadas ya cerradas. */
    fun closedRounds(): Set<Int> {
        val a = list(post(ROUNDS, q()))
        if (a.length() > 0) Shape.keys(ROUNDS, a.getJSONObject(0), listOf("number", "status"))
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("status") == "closed" }.map { it.optInt("number") }.toSet()
    }

    /** Puntos reales de un jugador por jornada (número de jornada -> puntos). */
    fun playerRoundPoints(playerId: String): Map<Int, Double> {
        val ans = post(PLAYER_SUMMARY, q().put("playerId", playerId))
        Shape.keys(PLAYER_SUMMARY, ans, listOf("points"))
        val pts = ans.getJSONArray("points")
        return (0 until pts.length()).map { pts.getJSONObject(it) }.associate { it.optInt("round") to it.optDouble("points", 0.0) }
    }

    fun marketPlayers(): List<Player> {
        val a = list(post(MARKET, q().put("type", "market")))
        Shape.players(MARKET, a)
        return Parse.players(a)
    }

    fun myMarketPlayers(): List<Player> {
        val a = list(post(MY_MARKET, q().put("type", "market")))
        Shape.players(MY_MARKET, a)
        return Parse.players(a)
    }

    /** Compras entre usuarios de la liga recientes (el tablón solo guarda unos ~6 días). */
    fun leagueSales(): List<Sale> {
        val a = post(PRESSROOM, JSONObject().put("championshipId", requireNotNull(session).championshipId).put("from", ""))
        Shape.keys(PRESSROOM, a, listOf("news"))
        val news = a.getJSONArray("news")
        return (0 until news.length()).mapNotNull { i ->
            val n = news.getJSONObject(i)
            if (n.optJSONObject("_buyer") == null) return@mapNotNull null // solo compras completadas
            val price = n.optLong("price")
            val pid = n.optJSONObject("_player")?.optString("_id").orEmpty()
            if (price <= 0 || pid.isEmpty()) return@mapNotNull null
            val t = try { java.time.Instant.parse(n.optString("created")).toEpochMilli() } catch (e: Exception) { 0L }
            Sale(n.optString("_id"), t, pid, price)
        }
    }

    /** Valor actual de todos los jugadores de la liga (id -> valor). */
    fun leagueValues(): Map<String, Long> {
        val a = post(CHAMP_PLAYERS, JSONObject().put("championshipId", requireNotNull(session).championshipId))
        Shape.keys(CHAMP_PLAYERS, a, listOf("players"))
        val ps = a.getJSONArray("players")
        return (0 until ps.length()).associate { ps.getJSONObject(it).optString("id") to ps.getJSONObject(it).optLong("value") }
    }

    /** Pone a la venta (NO venta directa). Solo llamar tras confirmación explícita del usuario. */
    fun putOnMarket(playerId: String, price: Long) {
        post(
            PUT_ON_MARKET,
            q().put("price", price).put("player_id", playerId)
                .put("isClause", JSONObject.NULL).put("mode", JSONObject.NULL).put("toLoan", JSONObject.NULL),
        )
    }
}
