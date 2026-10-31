package com.example.iptv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class IptvRepository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val xtream = XtreamClient(http)
    private val plSer = ListSerializer(PlaylistConfig.serializer())
    private val chSer = ListSerializer(Channel.serializer())

    fun playlists(): List<PlaylistConfig> =
        prefs.getString("playlists", null)
            ?.let { runCatching { json.decodeFromString(plSer, it) }.getOrNull() } ?: emptyList()

    fun savePlaylists(list: List<PlaylistConfig>) {
        prefs.edit().putString("playlists", json.encodeToString(plSer, list)).apply()
    }

    var activeId: String?
        get() = prefs.getString("active", null)
        set(v) { prefs.edit().putString("active", v).apply() }

    private fun cacheFile(id: String) = File(ctx.filesDir, "channels_$id.json")

    suspend fun cached(id: String): List<Channel>? = withContext(Dispatchers.IO) {
        val f = cacheFile(id)
        if (!f.exists()) null else runCatching { json.decodeFromString(chSer, f.readText()) }.getOrNull()
    }

    fun deleteCache(id: String) {
        cacheFile(id).delete()
        prefs.edit().remove("fav_$id").apply()
    }

    /** Favoritos por lista: se guardan las URLs de los canales. */
    fun favorites(id: String): Set<String> = prefs.getStringSet("fav_$id", emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(id: String, urls: Set<String>) {
        prefs.edit().putStringSet("fav_$id", urls).apply()
    }

    /** Descarga la lista (M3U o Xtream), la cachea y la devuelve. */
    suspend fun download(p: PlaylistConfig): List<Channel> = withContext(Dispatchers.IO) {
        val channels = when (p.kind) {
            Kind.XTREAM -> xtream.loadLive(p)
            Kind.M3U -> {
                val req = Request.Builder().url(p.url.trim()).header("User-Agent", UA).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("Error HTTP ${r.code}")
                    M3uParser.parse(r.body!!.charStream().buffered())
                }
            }
        }
        if (channels.isEmpty()) error("La lista no contiene canales")
        cacheFile(p.id).writeText(json.encodeToString(chSer, channels))
        channels
    }
}
