package com.limpiador.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(state: UiState, vm: CleanerViewModel, onGoToClean: () -> Unit) {
    val requestFiles = rememberFileAccessRequest(vm)
    val clearAllCache = rememberClearAllCacheAction(vm)

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.hasFileAccess) {
            PermissionCard(
                title = "Permiso necesario",
                text = "Para encontrar y borrar archivos basura, permite el acceso a todos los archivos.",
                button = "Conceder acceso",
                onClick = requestFiles,
            )
        }

        StorageCard(state)

        Button(
            onClick = { vm.startScan(); onGoToClean() },
            enabled = state.hasFileAccess,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Analizar basura", style = MaterialTheme.typography.titleMedium)
        }

        if (clearAllCache != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Caché de todas las apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android pedirá confirmación y borrará la caché de las apps instaladas. " +
                            "No se pierden datos ni sesiones.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = clearAllCache, enabled = state.hasFileAccess) {
                        Text("Liberar caché")
                    }
                }
            }
        }

        RamCard(state, vm)
    }
}

@Composable
private fun StorageCard(state: UiState) {
    val s = state.storage
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { s.usedFraction },
                    modifier = Modifier.size(96.dp),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${(s.usedFraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Almacenamiento", style = MaterialTheme.typography.titleMedium)
                Text("Usado: ${formatSize(s.usedBytes)} de ${formatSize(s.totalBytes)}")
                Text(
                    "Libre: ${formatSize(s.freeBytes)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun RamCard(state: UiState, vm: CleanerViewModel) {
    val r = state.ram
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Memoria RAM", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { r.usedFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text("Disponible: ${formatSize(r.availableBytes)} de ${formatSize(r.totalBytes)}")
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::boostMemory, enabled = !state.boosting) {
                if (state.boosting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text("Cerrar apps en segundo plano")
            }
        }
    }
}
