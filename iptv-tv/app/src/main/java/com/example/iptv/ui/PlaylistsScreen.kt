package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.iptv.MainViewModel
import com.example.iptv.Screen

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(if (isTv()) 48.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mis listas", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { vm.error = null; vm.screen = Screen.ADD }) { Text("+ Añadir lista") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.playlists, key = { it.id }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.select(p) }) {
                        Text((if (p.id == vm.active?.id) "● " else "") + p.name + "  (${p.kind.name})")
                    }
                    Button(onClick = { vm.deletePlaylist(p) }) { Text("Eliminar") }
                }
            }
        }
    }
}
