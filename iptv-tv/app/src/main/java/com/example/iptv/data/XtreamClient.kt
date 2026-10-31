package com.example.iptv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Cliente de la API Xtream Codes (solo TV en directo; sin EPG). */
class XtreamClient(private val http: OkHttpClient) {

    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        for (marker in listOf("/player_api.php", "/get.php", "/panel_api.php")) {
            if (marker in s) s = s.substringBefore(marker)
        }
        return s.trimEnd('/')
    }

    fun loadLive(p: PlaylistConfig): List<Channel> {
        val base = normalizeServer(p.server)
        val cats = api(base, p, "get_live_categories")
        val streams = api(base, p, "get_live_streams")
        if (cats !is JsonArray || streams !is JsonArray) {
            error("Servidor, usuario o contraseña incorrectos")
        }
        val catNames = cats.jsonArray.associate {
            val o = it.jsonObject
            (o.str("category_id") ?: "") to (o.str("category_name") ?: NO_GROUP)
        }
        return streams.jsonArray.map {
            val o = it.jsonObject
            val id = o.str("stream_id") ?: ""
            Channel(
                name = o.str("name") ?: "Sin nombre",
                group = catNames[o.str("category_id")] ?: NO_GROUP,
                logo = o.str("stream_icon")?.takeIf { l -> l.isNotBlank() },
                url = "$base/live/${p.user}/${p.pass}/$id.m3u8",
            )
        }
    }

    private fun api(base: String, p: PlaylistConfig, action: String): JsonElement {
        val url = (base + "/player_api.php").toHttpUrl().newBuilder()
            .addQueryParameter("username", p.user)
            .addQueryParameter("password", p.pass)
            .addQueryParameter("action", action)
            .build()
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Error HTTP ${r.code}")
            return Json.parseToJsonElement(r.body!!.string())
        }
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
}

const val UA = "VLC/3.0.18 LibVLC/3.0.18"
