package com.example.iptv

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iptv.data.Channel
import com.example.iptv.data.IptvRepository
import com.example.iptv.data.PlaylistConfig
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen { HOME, ADD, PLAYLISTS, PLAYER, GROUPS }

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
        val id = repo.activeId
        val p = playlists.firstOrNull { it.id == id } ?: playlists.firstOrNull()
        if (p != null) select(p) else screen = Screen.ADD
    }

    fun select(p: PlaylistConfig, forceDownload: Boolean = false) {
        active = p
        repo.activeId = p.id
        favorites = repo.favorites(p.id)
        hiddenGroups = repo.groupSet("hid", p.id)
        deletedGroups = repo.groupSet("del", p.id)
        selectedGroup = ALL
        query = ""
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
        val full = p.copy(id = UUID.randomUUID().toString())
        playlists = playlists + full
        repo.savePlaylists(playlists)
        select(full, forceDownload = true)
    }

    fun deletePlaylist(p: PlaylistConfig) {
        playlists = playlists - p
        repo.savePlaylists(playlists)
        repo.deleteCache(p.id)
        if (active?.id == p.id) {
            active = null
            rawChannels = emptyList()
            favorites = emptySet()
            playlists.firstOrNull()?.let { select(it) } ?: run { screen = Screen.ADD }
        }
    }

    fun play(index: Int) {
        queue = visible
        playingIndex = index
        screen = Screen.PLAYER
    }

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
        if (n > 0) playingIndex = ((playingIndex + delta) % n + n) % n
    }
}
