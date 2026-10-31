package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.iptv.BuildConfig
import com.example.iptv.MainViewModel
import com.example.iptv.Screen

@Composable
fun SyncScreen(vm: MainViewModel) {
    var url by remember { mutableStateOf(vm.syncUrl.ifBlank { BuildConfig.DEFAULT_SYNC_URL }) }

    Column(
        Modifier.fillMaxSize().padding(if (isTv()) 48.dp else 20.dp).widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sincronización remota", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Permite gestionar las listas de este dispositivo desde la web de administración de tu servidor. " +
                "Los cambios se sincronizan en los dos sentidos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!vm.syncConfigured) {
            OutlinedTextField(
                url, { url = it }, label = { Text("Dirección del servidor (https://…)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.connectSync(url) }, enabled = url.isNotBlank() && !vm.syncBusy) { Text("Conectar") }
        } else {
            Text("Servidor: ${vm.syncUrl}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.syncLinked) {
                Text("Vinculado como: ${vm.syncDeviceName}", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Escribe este código en la web de administración (caduca en 15 minutos):")
                Text(
                    vm.syncCode ?: "…",
                    style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 8.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = { vm.newSyncCode() }) { Text("Nuevo código") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.syncNow() }, enabled = !vm.syncBusy) { Text(if (vm.syncBusy) "Sincronizando…" else "Sincronizar ahora") }
                Button(onClick = { vm.disconnectSync() }) { Text("Desconectar") }
            }
        }
        vm.syncStatus?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = { vm.screen = if (vm.playlists.isEmpty()) Screen.ADD else Screen.PLAYLISTS }) { Text("Volver") }
    }
}
