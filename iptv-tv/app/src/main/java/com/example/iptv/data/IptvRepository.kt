package com.example.iptv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class IptvRepository(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init { ProxySupport.current = proxy }

    var proxy: ProxyConfig
        get() = prefs.getString("proxy", null)
            ?.let { runCatching { json.decodeFromString(ProxyConfig.serializer(), it) }.getOrNull() } ?: ProxyConfig()
        set(v) {
            prefs.edit().putString("proxy", json.encodeToString(ProxyConfig.serializer(), v)).apply()
            ProxySupport.current = v
        }

    /** Cliente HTTP con el proxy aplicado según el uso (listas o vídeo). */
    fun clientFor(video: Boolean): OkHttpClient = ProxySupport.apply(baseHttp, proxy, video)

    /** Compara la IP pública directa con la que se ve a través del proxy indicado (sin guardarlo). */
    suspend fun testProxy(cfg: ProxyConfig): String = withContext(Dispatchers.IO) {
        ProxySupport.current = cfg
        try {
            fun ip(c: OkHttpClient): String = runCatching {
                c.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url("https://api.ipify.org").build())
                    .execute().use { it.body!!.string().trim() }
            }.getOrElse { "error (${it.message ?: it.javaClass.simpleName})" }
            val direct = ip(baseHttp)
            val via = ip(ProxySupport.apply(baseHttp, cfg.copy(enabled = true, forLists = true, forVideo = true), false))
            "IP directa: $direct\nIP con proxy: $via"
        } finally {
            ProxySupport.current = proxy
        }
    }
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

    /** Descarta solo los canales descargados (se vuelven a bajar al abrir la lista). */
    fun invalidateChannels(id: String) { cacheFile(id).delete() }

    fun deleteCache(id: String) {
        cacheFile(id).delete()
        prefs.edit().remove("fav_$id").remove("hid_$id").remove("del_$id").apply()
    }

    // ── Sincronización remota ──
    private val tombSer = MapSerializer(String.serializer(), Long.serializer())

    /** Listas borradas en este dispositivo (id → momento), para propagar el borrado al servidor. */
    fun tombstones(): Map<String, Long> =
        prefs.getString("tombstones", null)?.let { runCatching { json.decodeFromString(tombSer, it) }.getOrNull() } ?: emptyMap()

    fun saveTombstones(m: Map<String, Long>) {
        prefs.edit().putString("tombstones", json.encodeToString(tombSer, m)).apply()
    }

    var syncUrl: String
        get() = prefs.getString("sync_url", "") ?: ""
        private set(v) { prefs.edit().putString("sync_url", v).apply() }

    private var syncToken: String?
        get() = prefs.getString("sync_token", null)
        set(v) { prefs.edit().putString("sync_token", v).apply() }

    val syncConfigured: Boolean get() = syncUrl.isNotBlank() && syncToken != null

    private fun syncClient(url: String = syncUrl) =
        SyncClient(baseHttp.newBuilder().callTimeout(30, TimeUnit.SECONDS).build(), url)

    suspend fun syncRegister(url: String): RegisterResponse = withContext(Dispatchers.IO) {
        val client = syncClient(url)
        val r = client.register()
        syncUrl = client.base
        syncToken = r.token
        r
    }

    suspend fun syncNewCode(): CodeResponse = withContext(Dispatchers.IO) {
        syncClient().newCode(syncToken ?: error("Sin conexión con el servidor"))
    }

    suspend fun syncExchange(payload: List<RemotePlaylist>): SyncResponse = withContext(Dispatchers.IO) {
        syncClient().sync(syncToken ?: error("Sin conexión con el servidor"), payload)
    }

    fun syncDisconnect() {
        prefs.edit().remove("sync_url").remove("sync_token").remove("tombstones").apply()
    }

    /** Grupos ocultos ("hid") o borrados ("del") por lista. */
    fun groupSet(kind: String, id: String): Set<String> =
        prefs.getStringSet("${kind}_$id", emptySet())?.toSet() ?: emptySet()

    fun saveGroupSet(kind: String, id: String, groups: Set<String>) {
        prefs.edit().putStringSet("${kind}_$id", groups).apply()
    }

    /** Favoritos por lista: se guardan las URLs de los canales. */
    fun favorites(id: String): Set<String> = prefs.getStringSet("fav_$id", emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(id: String, urls: Set<String>) {
        prefs.edit().putStringSet("fav_$id", urls).apply()
    }

    /** Descarga la lista (M3U o Xtream), la cachea y la devuelve. */
    suspend fun download(p: PlaylistConfig): List<Channel> = withContext(Dispatchers.IO) {
        val http = clientFor(video = false)
        val channels = when (p.kind) {
            Kind.XTREAM -> XtreamClient(http).loadLive(p)
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
