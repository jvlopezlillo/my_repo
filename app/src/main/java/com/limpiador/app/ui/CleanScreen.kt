package com.limpiador.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limpiador.app.scan.JunkCategory
import com.limpiador.app.scan.JunkGroup
import com.limpiador.app.scan.JunkItem

@Composable
fun CleanScreen(state: UiState, vm: CleanerViewModel) {
    val requestFiles = rememberFileAccessRequest(vm)

    when {
        !state.hasFileAccess -> Column(Modifier.padding(16.dp)) {
            PermissionCard(
                title = "Permiso necesario",
                text = "Sin acceso a los archivos no se puede buscar basura en el almacenamiento.",
                button = "Conceder acceso",
                onClick = requestFiles,
            )
        }
        state.scanStatus == ScanStatus.SCANNING -> Scanning(state, vm)
        state.scanStatus == ScanStatus.IDLE -> Centered {
            Text("Busca caché, temporales, miniaturas, APKs, carpetas vacías, duplicados y archivos grandes.")
            Button(onClick = vm::startScan) { Text("Analizar ahora") }
        }
        state.groups.isEmpty() -> Centered {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("¡Todo limpio!", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = vm::startScan) { Text("Analizar de nuevo") }
        }
        else -> Results(state, vm)
    }

    if (state.cleaning) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Limpiando…") },
            text = { CircularProgressIndicator() },
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

@Composable
private fun Scanning(state: UiState, vm: CleanerViewModel) = Centered {
    CircularProgressIndicator(Modifier.size(72.dp))
    Text("Analizando…", style = MaterialTheme.typography.headlineSmall)
    Text("${state.progress.filesScanned} archivos revisados")
    Text(
        state.progress.currentPath,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    OutlinedButton(onClick = vm::cancelScan) { Text("Cancelar") }
}

@Composable
private fun Results(state: UiState, vm: CleanerViewModel) {
    val expanded = remember { mutableStateListOf<JunkCategory>() }
    var confirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Encontrado: ${formatSize(state.foundSize)}", style = MaterialTheme.typography.titleLarge)
                    Text("Seleccionado: ${formatSize(state.selectedSize)}")
                }
                TextButton(onClick = vm::startScan) { Text("Reanalizar") }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            for (group in state.groups) {
                val isOpen = group.category in expanded
                item(key = "h-${group.category}") {
                    GroupHeader(group, state.selected, isOpen,
                        onToggle = { vm.toggleCategory(group.category) },
                        onExpand = { if (isOpen) expanded -= group.category else expanded += group.category })
                }
                if (isOpen) {
                    items(group.items, key = { "${group.category}-${it.path}" }) { item ->
                        ItemRow(item, item.path in state.selected) { vm.toggleItem(item.path) }
                    }
                }
                item(key = "d-${group.category}") { HorizontalDivider() }
            }
        }

        Button(
            onClick = { confirm = true },
            enabled = state.selected.isNotEmpty() && !state.cleaning,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            Text("Limpiar ${formatSize(state.selectedSize)}", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Borrar ${state.selected.size} elementos?") },
            text = { Text("Se liberarán ${formatSize(state.selectedSize)}. Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.clean() }) { Text("Borrar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun GroupHeader(
    group: JunkGroup,
    selected: Set<String>,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    val count = group.items.count { it.path in selected }
    val toggle = when (count) {
        0 -> ToggleableState.Off
        group.items.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = toggle, onClick = onToggle)
        Column(Modifier.weight(1f)) {
            Text(group.category.title, style = MaterialTheme.typography.titleMedium)
            val sizeText = if (group.category == JunkCategory.EMPTY_FOLDERS) "${group.items.size} carpetas"
            else "${formatSize(group.totalSize)} · ${group.items.size} elementos"
            Text(sizeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(group.category.description, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ArrowDropDown, null, Modifier.rotate(if (isOpen) 180f else 0f))
    }
}

@Composable
private fun ItemRow(item: JunkItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.size(8.dp))
        if (item.size > 0) Text(formatSize(item.size), style = MaterialTheme.typography.bodySmall)
    }
}
