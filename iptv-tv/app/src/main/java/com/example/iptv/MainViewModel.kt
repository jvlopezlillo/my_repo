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

enum class Screen { HOME, ADD, PLAYLISTS, PLAYER }

const val ALL = "Todos"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = IptvRepository(app)

    var screen by mutableStateOf(Screen.HOME)
    var playlists by mutableStateOf(repo.playlists())
        private set
    var active by mutableStateOf<PlaylistConfig?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var selectedGroup by mutableStateOf(ALL)
    var playingIndex by mutableStateOf(0)

    /** Grupos en el orden en que aparecen en la lista, con su número de canales. */
    val groups by derivedStateOf {
        val counts = LinkedHashMap<String, Int>()
        channels.forEach { counts[it.group] = (counts[it.group] ?: 0) + 1 }
        listOf(ALL to channels.size) + counts.toList()
    }

    /** Canales visibles: la búsqueda tiene prioridad sobre el grupo seleccionado. */
    val visible by derivedStateOf {
        val q = query.trim()
        when {
            q.isNotEmpty() -> channels.filter { it.name.contains(q, ignoreCase = true) }
            selectedGroup == ALL -> channels
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
        selectedGroup = ALL
        query = ""
        error = null
        viewModelScope.launch {
            loading = true
            try {
                val cached = if (forceDownload) null else repo.cached(p.id)
                channels = cached ?: repo.download(p)
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
            channels = emptyList()
            playlists.firstOrNull()?.let { select(it) } ?: run { screen = Screen.ADD }
        }
    }

    fun play(index: Int) { playingIndex = index; screen = Screen.PLAYER }

    /** Zapping dentro de la lista visible actual. */
    fun zap(delta: Int) {
        val n = visible.size
        if (n > 0) playingIndex = ((playingIndex + delta) % n + n) % n
    }
}
