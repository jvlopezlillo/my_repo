package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.example.iptv.MainViewModel
import com.example.iptv.Screen

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        // ── Barra superior: buscador siempre accesible ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = { Text("Buscar canal…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.refresh() }) { Text("Actualizar") }
            Button(onClick = { vm.screen = Screen.PLAYLISTS }) { Text("Listas") }
        }
        Spacer(Modifier.height(8.dp))
        vm.active?.let {
            Text(
                "${it.name} · ${vm.channels.size} canales",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))

        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Descargando lista…") }
            return@Column
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // ── Grupos ──
            LazyColumn(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(vm.groups, key = { i, g -> "$i-${g.first}" }) { _, (name, count) ->
                    ListItem(
                        selected = vm.query.isBlank() && vm.selectedGroup == name,
                        onClick = { vm.query = ""; vm.selectedGroup = name },
                        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text("$count") },
                    )
                }
            }
            // ── Canales ──
            val list = vm.visible
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (vm.query.isBlank()) "Sin canales" else "Sin resultados para \"${vm.query}\"")
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list, key = { i, c -> "$i-${c.url}" }) { i, ch ->
                        ListItem(
                            selected = false,
                            onClick = { vm.play(i) },
                            headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (vm.query.isNotBlank()) ({ Text(ch.group, maxLines = 1) }) else null,
                            leadingContent = {
                                AsyncImage(
                                    model = ch.logo,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
