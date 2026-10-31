package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.example.iptv.MainViewModel
import com.example.iptv.Screen

@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(if (isTv()) 48.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mis listas", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.error = null; vm.editing = null; vm.screen = Screen.ADD }) { Text("+ Añadir lista") }
            Button(onClick = { vm.screen = Screen.SYNC }) { Text(if (vm.syncConfigured) "Sincronización ●" else "Sincronización remota") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.playlists, key = { it.id }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.select(p) }) {
                        Text((if (p.id == vm.active?.id) "● " else "") + p.name + "  (${p.kind.name})")
                    }
                    Button(onClick = { vm.error = null; vm.editing = p; vm.screen = Screen.ADD }) { Text("Editar") }
                    Button(onClick = { vm.deletePlaylist(p) }) { Text("Eliminar") }
                }
            }
        }
    }
}
