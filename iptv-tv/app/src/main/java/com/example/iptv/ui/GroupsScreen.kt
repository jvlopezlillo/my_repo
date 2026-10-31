package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.iptv.MainViewModel
import com.example.iptv.Screen

@Composable
fun GroupsScreen(vm: MainViewModel) {
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(if (isTv()) 48.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gestionar grupos", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ocultar quita el grupo de la lista y se puede volver a mostrar. " +
                "Borrar lo elimina de esta lista, también al actualizarla; se puede restaurar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.screen = Screen.HOME }) { Text("Volver") }
            if (vm.deletedGroups.isNotEmpty()) {
                Button(onClick = { vm.restoreDeleted() }) { Text("Restaurar borrados (${vm.deletedGroups.size})") }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.managedGroups, key = { it.first }) { (name, count) ->
                val hidden = name in vm.hiddenGroups
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "$count canales" + if (hidden) " · oculto" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { vm.toggleHidden(name) }) { Text(if (hidden) "Mostrar" else "Ocultar") }
                    Button(onClick = { confirm = name }) { Text("Borrar") }
                }
            }
        }
    }

    confirm?.let { g ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("¿Borrar \"$g\"?") },
            text = { Text("Se quitará el grupo y sus canales de esta lista, también al actualizarla. Podrás restaurarlo desde esta pantalla.") },
            confirmButton = { Button(onClick = { vm.deleteGroup(g); confirm = null }) { Text("Borrar") } },
            dismissButton = { Button(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}
