package com.limpiador.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.limpiador.app.system.AppStorage

@Composable
fun AppsScreen(state: UiState, vm: CleanerViewModel) {
    val context = LocalContext.current
    val clearAllCache = rememberClearAllCacheAction(vm)

    if (!state.hasUsageAccess) {
        Column(Modifier.padding(16.dp)) {
            PermissionCard(
                title = "Acceso a datos de uso",
                text = "Android solo deja ver cuánta caché ocupa cada app si activas " +
                    "\"Acceso a datos de uso\" para Limpiador.",
                button = "Abrir ajustes",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
        }
        return
    }

    LaunchedEffect(Unit) { if (state.apps.isEmpty()) vm.loadApps() }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Caché total: ${formatSize(state.apps.sumOf { it.cacheBytes })}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Toca una app para abrir sus ajustes y borrar su caché o sus datos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Incluir apps del sistema", Modifier.weight(1f))
                    Switch(checked = state.includeSystemApps, onCheckedChange = vm::setIncludeSystemApps)
                }
                if (clearAllCache != null) {
                    OutlinedButton(onClick = clearAllCache, enabled = state.hasFileAccess) {
                        Text("Liberar caché de todas las apps")
                    }
                }
            }
        }

        if (state.appsLoading && state.apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.apps, key = { it.packageName }) { app ->
                    AppRow(app) { openAppSettings(context, app.packageName) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppStorage, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (app.icon != null) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Total: ${formatSize(app.totalBytes)}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            formatSize(app.cacheBytes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
