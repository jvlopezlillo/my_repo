package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.example.iptv.MainViewModel
import com.example.iptv.data.Kind
import com.example.iptv.data.PlaylistConfig

@Composable
fun AddPlaylistScreen(vm: MainViewModel) {
    val editing = remember { vm.editing }
    var kind by remember { mutableStateOf(editing?.kind ?: Kind.M3U) }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var url by remember { mutableStateOf(editing?.url ?: "") }
    var server by remember { mutableStateOf(editing?.server ?: "") }
    var user by remember { mutableStateOf(editing?.user ?: "") }
    var pass by remember { mutableStateOf(editing?.pass ?: "") }

    val valid = if (kind == Kind.M3U) url.isNotBlank() else server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()

    Column(
        Modifier.fillMaxSize().padding(if (isTv()) 48.dp else 20.dp).widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (editing != null) "Editar lista" else "Añadir lista", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { kind = Kind.M3U }) { Text(if (kind == Kind.M3U) "● URL M3U" else "URL M3U") }
            Button(onClick = { kind = Kind.XTREAM }) { Text(if (kind == Kind.XTREAM) "● Xtream Codes" else "Xtream Codes") }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Nombre (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (kind == Kind.M3U) {
            OutlinedTextField(url, { url = it }, label = { Text("URL de la lista M3U") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(server, { server = it }, label = { Text("Servidor (http://host:puerto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val n = name.ifBlank { if (kind == Kind.M3U) "Lista M3U" else "Xtream $user" }
                    val cfg = PlaylistConfig(editing?.id ?: "", n, kind, url.trim(), server.trim(), user.trim(), pass.trim())
                    if (editing != null) vm.updatePlaylist(cfg) else vm.addPlaylist(cfg)
                },
                enabled = valid && !vm.loading,
            ) { Text(if (vm.loading) "Descargando…" else if (editing != null) "Guardar" else "Guardar y cargar") }
            if (vm.active != null || editing != null) {
                Button(onClick = { vm.editing = null; vm.screen = if (editing != null) com.example.iptv.Screen.PLAYLISTS else com.example.iptv.Screen.HOME }) { Text("Cancelar") }
            }
        }
        if (vm.active == null && editing == null) {
            Button(onClick = { vm.screen = com.example.iptv.Screen.SYNC }) { Text("Sincronización remota (código)") }
        }
    }
}
