package com.example.iptv

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iptv.data.Channel
import com.example.iptv.data.IptvRepository
import com.example.iptv.data.PlaylistConfig
import com.example.iptv.data.ProxyConfig
import com.example.iptv.data.RemotePlaylist
import com.example.iptv.data.SyncClient
import com.example.iptv.data.SyncResponse
import com.example.iptv.data.toConfig
import com.example.iptv.data.toRemote
import com.example.iptv.data.Kind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { HOME, ADD, PLAYLISTS, PLAYER, GROUPS, PROXY, SYNC }

const val ALL = "Todos"
const val FAV = "★ Favoritos"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = IptvRepository(app)

    var screen by mutableStateOf(Screen.HOME)
    var playlists by mutableStateOf(repo.playlists())
        private set
    var active by mutableStateOf<PlaylistConfig?>(null)
        private set
    /** Lista completa tal como se descargó (la caché no se toca al ocultar/borrar grupos). */
    private var rawChannels by mutableStateOf<List<Channel>>(emptyList())
    var hiddenGroups by mutableStateOf<Set<String>>(emptySet())
        private set
    var deletedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Canales que se ven en la app: sin grupos ocultos ni borrados. */
    val channels by derivedStateOf {
        rawChannels.filter { it.group !in hiddenGroups && it.group !in deletedGroups }
    }

    /** Grupos que se pueden gestionar (incluye los ocultos, excluye los borrados). */
    val managedGroups by derivedStateOf {
        val counts = LinkedHashMap<String, Int>()
        rawChannels.forEach { if (it.group !in deletedGroups) counts[it.group] = (counts[it.group] ?: 0) + 1 }
        counts.toList()
    }
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var selectedGroup by mutableStateOf(ALL)
    var playingIndex by mutableStateOf(0)

    /** Último canal reproducido: al volver del reproductor el foco regresa a él. */
    var lastPlayedUrl by mutableStateOf<String?>(null)
        private set

    /** Posición de la lista de canales; vive aquí para sobrevivir al paso por el reproductor. */
    val channelListState = LazyListState()
    var proxy by mutableStateOf(repo.proxy)
        private set
    var proxyTestResult by mutableStateOf<String?>(null)
    var proxyTesting by mutableStateOf(false)
        private set
    /** Lista que se está editando (null = añadiendo una nueva). */
    var editing by mutableStateOf<PlaylistConfig?>(null)

    // ── Sincronización remota ──
    var syncConfigured by mutableStateOf(repo.syncConfigured)
        private set
    var syncUrl by mutableStateOf(repo.syncUrl)
        private set
    var syncCode by mutableStateOf<String?>(null)
        private set
    var syncLinked by mutableStateOf(false)
        private set
    var syncDeviceName by mutableStateOf("")
        private set
    var syncStatus by mutableStateOf<String?>(null)
        private set
    var syncBusy by mutableStateOf(false)
        private set
    private var syncJob: Job? = null

    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Lista sobre la que se hace zapping: copia de la lista visible al empezar a reproducir. */
    var queue by mutableStateOf<List<Channel>>(emptyList())
        private set

    /** Grupos en el orden en que aparecen en la lista, con su número de canales. */
    val groups by derivedStateOf {
        val counts = LinkedHashMap<String, Int>()
        channels.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        listOf(FAV to channels.count { it.url in favorites }, ALL to channels.size) + counts.toList()
    }

    /** Canales visibles: la búsqueda tiene prioridad sobre el grupo seleccionado. */
    val visible by derivedStateOf {
        val q = query.trim()
        when {
            q.isNotEmpty() -> channels.filter { it.name.contains(q, ignoreCase = true) }
            selectedGroup == ALL -> channels
            selectedGroup == FAV -> channels.filter { it.url in favorites }
            else -> channels.filter { it.group == selectedGroup }
        }
    }

    init {
        // Primer arranque sin listas: precarga la lista por defecto (una sola vez).
        if (playlists.isNotEmpty()) {
            repo.defaultSeeded = true
        } else if (!repo.defaultSeeded && BuildConfig.DEFAULT_PLAYLIST_URL.isNotBlank()) {
            repo.defaultSeeded = true
            val seed = PlaylistConfig(
                id = UUID.randomUUID().toString(), name = "Mi lista", kind = Kind.M3U,
                url = BuildConfig.DEFAULT_PLAYLIST_URL, updatedAt = System.currentTimeMillis(),
            )
            playlists = listOf(seed)
            repo.savePlaylists(playlists)
        }
        val id = repo.activeId
        val p = playlists.firstOrNull { it.id == id } ?: playlists.firstOrNull()
        if (p != null) select(p) else screen = Screen.ADD
        // Sincroniza al abrir y cada 5 minutos mientras la app esté viva.
        viewModelScope.launch {
            delay(1500)
            while (true) {
                syncNow()
                delay(5 * 60_000L)
            }
        }
    }

    fun select(p: PlaylistConfig, forceDownload: Boolean = false) {
        active = p
        repo.activeId = p.id
        favorites = repo.favorites(p.id)
        hiddenGroups = repo.groupSet("hid", p.id)
        deletedGroups = repo.groupSet("del", p.id)
        selectedGroup = ALL
        query = ""
        lastPlayedUrl = null
        resetScroll()
        error = null
        viewModelScope.launch {
            loading = true
            try {
                val cached = if (forceDownload) null else repo.cached(p.id)
                rawChannels = cached ?: repo.download(p)
                screen = Screen.HOME
            } catch (e: Exception) {
                error = "No se pudo cargar la lista: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun refresh() = active?.let { select(it, forceDownload = true) }

    fun addPlaylist(p: PlaylistConfig) {
        val full = p.copy(id = UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis())
        playlists = playlists + full
        repo.savePlaylists(playlists)
        select(full, forceDownload = true)
        syncNow()
    }

    fun updatePlaylist(p: PlaylistConfig) {
        val upd = p.copy(updatedAt = System.currentTimeMillis())
        val old = playlists.firstOrNull { it.id == upd.id }
        playlists = playlists.map { if (it.id == upd.id) upd else it }
        repo.savePlaylists(playlists)
        editing = null
        val reload = old == null || !sameConnection(old, upd)
        if (reload) repo.invalidateChannels(upd.id)
        if (active?.id == upd.id) {
            active = upd
            if (reload) select(upd, forceDownload = true) else screen = Screen.PLAYLISTS
        } else {
            screen = Screen.PLAYLISTS
        }
        syncNow()
    }

    private fun sameConnection(a: PlaylistConfig, b: PlaylistConfig) =
        a.kind == b.kind && a.url == b.url && a.server == b.server && a.user == b.user && a.pass == b.pass

    fun deletePlaylist(p: PlaylistConfig) {
        playlists = playlists - p
        repo.savePlaylists(playlists)
        repo.deleteCache(p.id)
        repo.saveTombstones(repo.tombstones() + (p.id to System.currentTimeMillis()))
        if (active?.id == p.id) {
            active = null
            rawChannels = emptyList()
            favorites = emptySet()
            playlists.firstOrNull()?.let { select(it) } ?: run { screen = Screen.ADD }
        }
        syncNow()
    }

    // ───────────── Sincronización remota ─────────────
    fun connectSync(url: String) {
        viewModelScope.launch {
            syncBusy = true
            syncStatus = "Conectando…"
            try {
                val r = repo.syncRegister(url)
                syncConfigured = true
                syncUrl = repo.syncUrl
                syncCode = r.code
                syncLinked = false
                syncStatus = null
            } catch (e: Exception) {
                syncStatus = "No se pudo conectar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                syncBusy = false
            }
            if (syncConfigured) syncNow()
        }
    }

    fun newSyncCode() {
        viewModelScope.launch {
            try { syncCode = repo.syncNewCode().code } catch (e: Exception) { syncStatus = "Error: ${e.message}" }
        }
    }

    fun disconnectSync() {
        repo.syncDisconnect()
        syncConfigured = false
        syncUrl = ""
        syncCode = null
        syncLinked = false
        syncDeviceName = ""
        syncStatus = null
    }

    /** Un solo intercambio a la vez; no hace nada si no hay servidor configurado. */
    fun syncNow() {
        if (!syncConfigured || syncJob?.isActive == true) return
        syncJob = viewModelScope.launch { doSync(0) }
    }

    private suspend fun doSync(attempt: Int) {
        syncBusy = true
        try {
            val local = playlists
            val tomb = repo.tombstones()
            val payload = local.map { it.toRemote() } +
                tomb.map { (id, t) -> RemotePlaylist(id = id, name = "", kind = "M3U", updatedAt = t, deleted = true) }
            val resp = repo.syncExchange(payload)
            // Si hubo cambios locales mientras tanto, se repite en vez de pisarlos.
            if ((playlists != local || repo.tombstones() != tomb) && attempt < 2) {
                doSync(attempt + 1)
                return
            }
            applyRemote(resp)
            syncStatus = "Sincronizado a las " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        } catch (e: SyncClient.Unauthorized) {
            repo.syncDisconnect()
            disconnectSync()
            syncStatus = "El servidor ha desvinculado este dispositivo. Vuelve a conectar."
        } catch (e: Exception) {
            syncStatus = "Sin conexión con el servidor: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            syncBusy = false
        }
    }

    private fun applyRemote(resp: SyncResponse) {
        syncLinked = resp.linked
        syncDeviceName = resp.name
        syncCode = resp.code

        val remoteLive = resp.playlists.filter { !it.deleted }.map { it.toConfig() }
        val byId = remoteLive.associateBy { it.id }
        val oldById = playlists.associateBy { it.id }
        val ordered = playlists.mapNotNull { byId[it.id] } + remoteLive.filter { it.id !in oldById }
        val removed = oldById.keys - byId.keys
        val changed = remoteLive.filter { n -> oldById[n.id]?.let { it != n } == true }

        repo.saveTombstones(resp.playlists.filter { it.deleted }.associate { it.id to it.updatedAt })
        if (ordered == playlists) return
        playlists = ordered
        repo.savePlaylists(ordered)
        removed.forEach { repo.deleteCache(it) }
        changed.filter { n -> !sameConnection(oldById.getValue(n.id), n) }.forEach { repo.invalidateChannels(it.id) }

        // Con el vídeo en marcha no se cambia de pantalla: la lista se recarga al volver.
        if (screen == Screen.PLAYER) return
        val a = active
        when {
            a != null && a.id in removed -> {
                active = null
                rawChannels = emptyList()
                favorites = emptySet()
                playlists.firstOrNull()?.let { select(it) } ?: run { screen = Screen.ADD }
            }
            a != null && changed.any { it.id == a.id } -> {
                val n = byId.getValue(a.id)
                if (sameConnection(a, n)) active = n else select(n, forceDownload = true)
            }
            a == null && playlists.isNotEmpty() -> select(playlists.first())
        }
    }

    fun play(index: Int) {
        queue = visible
        playingIndex = index
        lastPlayedUrl = queue.getOrNull(index)?.url
        screen = Screen.PLAYER
    }

    fun saveProxy(cfg: ProxyConfig) {
        repo.proxy = cfg
        proxy = cfg
    }

    fun testProxy(cfg: ProxyConfig) {
        viewModelScope.launch {
            proxyTesting = true
            proxyTestResult = "Probando…"
            proxyTestResult = try { repo.testProxy(cfg) } catch (e: Exception) { "Error: ${e.message}" }
            proxyTesting = false
        }
    }

    /** Cliente HTTP para el reproductor (con el proxy si está activado para vídeo). */
    fun videoClient() = repo.clientFor(video = true)

    fun toggleHidden(group: String) {
        val id = active?.id ?: return
        hiddenGroups = if (group in hiddenGroups) hiddenGroups - group else hiddenGroups + group
        repo.saveGroupSet("hid", id, hiddenGroups)
        if (selectedGroup == group) selectedGroup = ALL
    }

    fun deleteGroup(group: String) {
        val id = active?.id ?: return
        deletedGroups = deletedGroups + group
        hiddenGroups = hiddenGroups - group
        repo.saveGroupSet("del", id, deletedGroups)
        repo.saveGroupSet("hid", id, hiddenGroups)
        if (selectedGroup == group) selectedGroup = ALL
    }

    fun restoreDeleted() {
        val id = active?.id ?: return
        deletedGroups = emptySet()
        repo.saveGroupSet("del", id, deletedGroups)
    }

    fun isFavorite(ch: Channel) = ch.url in favorites

    /** Añade o quita un canal de favoritos (se guarda al instante). */
    fun toggleFavorite(ch: Channel) {
        val id = active?.id ?: return
        favorites = if (ch.url in favorites) favorites - ch.url else favorites + ch.url
        repo.saveFavorites(id, favorites)
    }

    /** Zapping dentro de la lista visible actual. */
    fun zap(delta: Int) {
        val n = queue.size
        if (n > 0) {
            playingIndex = ((playingIndex + delta) % n + n) % n
            lastPlayedUrl = queue[playingIndex].url
        }
    }

    fun selectGroup(name: String) {
        query = ""
        selectedGroup = name
        resetScroll()
    }

    fun updateQuery(q: String) {
        query = q
        resetScroll()
    }

    private fun resetScroll() {
        viewModelScope.launch { channelListState.scrollToItem(0) }
    }
}
