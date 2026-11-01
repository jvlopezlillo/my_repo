package com.limpiador.app.ui

import android.app.Application
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limpiador.app.scan.JunkCategory
import com.limpiador.app.scan.JunkCleaner
import com.limpiador.app.scan.JunkGroup
import com.limpiador.app.scan.JunkScanner
import com.limpiador.app.scan.ScanProgress
import com.limpiador.app.system.AppCacheRepository
import com.limpiador.app.system.AppStorage
import com.limpiador.app.system.MemoryBooster
import com.limpiador.app.system.Permissions
import com.limpiador.app.system.RamSnapshot
import com.limpiador.app.system.StorageSnapshot
import com.limpiador.app.system.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ScanStatus { IDLE, SCANNING, DONE }

data class UiState(
    val storage: StorageSnapshot = StorageSnapshot(),
    val ram: RamSnapshot = RamSnapshot(),
    val hasFileAccess: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val scanStatus: ScanStatus = ScanStatus.IDLE,
    val progress: ScanProgress = ScanProgress(),
    val groups: List<JunkGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val cleaning: Boolean = false,
    val apps: List<AppStorage> = emptyList(),
    val appsLoading: Boolean = false,
    val includeSystemApps: Boolean = false,
    val boosting: Boolean = false,
    val message: String? = null,
) {
    val selectedSize: Long
        get() = groups.asSequence().flatMap { it.items }
            .filter { it.path in selected }
            .distinctBy { it.path }
            .sumOf { it.size }

    val foundSize: Long
        get() = groups.asSequence().flatMap { it.items }.distinctBy { it.path }.sumOf { it.size }
}

class CleanerViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = JunkScanner(app)
    private val cleaner = JunkCleaner(app)
    private val appRepo = AppCacheRepository(app)
    private val booster = MemoryBooster(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        refreshSystem()
    }

    fun refreshSystem() {
        val ctx = getApplication<Application>()
        _state.update {
            it.copy(
                storage = SystemInfo.storage(),
                ram = SystemInfo.ram(ctx),
                hasFileAccess = Permissions.hasFileAccess(ctx),
                hasUsageAccess = Permissions.hasUsageAccess(ctx),
            )
        }
    }

    // --- Análisis y limpieza ---

    fun startScan() {
        if (_state.value.scanStatus == ScanStatus.SCANNING) return
        scanJob = viewModelScope.launch {
            _state.update {
                it.copy(scanStatus = ScanStatus.SCANNING, progress = ScanProgress(),
                    groups = emptyList(), selected = emptySet())
            }
            try {
                val groups = scanner.scan { p -> _state.update { it.copy(progress = p) } }
                val preselected = groups.flatMap { it.items }.filter { it.recommended }.map { it.path }.toSet()
                _state.update { it.copy(scanStatus = ScanStatus.DONE, groups = groups, selected = preselected) }
            } catch (e: CancellationException) {
                _state.update { it.copy(scanStatus = ScanStatus.IDLE) }
                throw e
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun toggleItem(path: String) {
        _state.update {
            it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
        }
    }

    fun toggleCategory(category: JunkCategory) {
        _state.update { s ->
            val paths = s.groups.firstOrNull { it.category == category }?.items?.map { it.path }.orEmpty()
            val allSelected = paths.all { it in s.selected }
            s.copy(selected = if (allSelected) s.selected - paths.toSet() else s.selected + paths)
        }
    }

    fun clean() {
        val s = _state.value
        if (s.cleaning || s.selected.isEmpty()) return
        val items = s.groups.flatMap { it.items }.filter { it.path in s.selected }
        viewModelScope.launch {
            _state.update { it.copy(cleaning = true) }
            val result = cleaner.clean(items)
            _state.update { st ->
                val remaining = st.groups
                    .map { g -> g.copy(items = g.items.filterNot { it.path in result.cleanedPaths }) }
                    .filter { it.items.isNotEmpty() }
                val msg = buildString {
                    append("Liberados ${format(result.freedBytes)}")
                    if (result.failed > 0) append(" · ${result.failed} elementos no se pudieron borrar")
                }
                st.copy(cleaning = false, groups = remaining,
                    selected = st.selected - result.cleanedPaths, message = msg)
            }
            refreshSystem()
        }
    }

    // --- Apps ---

    fun loadApps() {
        if (_state.value.appsLoading) return
        viewModelScope.launch {
            _state.update { it.copy(appsLoading = true) }
            val apps = appRepo.load(_state.value.includeSystemApps)
            _state.update { it.copy(apps = apps, appsLoading = false) }
        }
    }

    fun setIncludeSystemApps(include: Boolean) {
        _state.update { it.copy(includeSystemApps = include) }
        loadApps()
    }

    fun onSystemCacheCleared(success: Boolean) {
        val before = _state.value.storage.freeBytes
        refreshSystem()
        val freed = (_state.value.storage.freeBytes - before).coerceAtLeast(0)
        _state.update {
            it.copy(message = if (success) "Caché de apps liberada: ${format(freed)}" else "Operación cancelada")
        }
        if (_state.value.hasUsageAccess) loadApps()
    }

    // --- Memoria ---

    fun boostMemory() {
        if (_state.value.boosting) return
        viewModelScope.launch {
            _state.update { it.copy(boosting = true) }
            val r = booster.boost()
            refreshSystem()
            _state.update {
                it.copy(boosting = false,
                    message = "Cerrados procesos de ${r.appsClosed} apps · ${format(r.freedBytes)} de RAM liberada")
            }
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun format(bytes: Long) = Formatter.formatShortFileSize(getApplication(), bytes)
}
