package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Móvil en vertical (o pantalla estrecha): grupos como chips arriba; si no, panel lateral.
        val compact = maxWidth < 720.dp
        val padding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        else PaddingValues(horizontal = 40.dp, vertical = 24.dp)

        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── Buscador siempre accesible ──
            val search: @Composable (Modifier) -> Unit = { m ->
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    placeholder = { Text("Buscar canal…") },
                    singleLine = true,
                    modifier = m,
                )
            }
            val actions: @Composable () -> Unit = {
                Button(onClick = { vm.refresh() }) { Text("Actualizar") }
                Button(onClick = { vm.screen = Screen.PLAYLISTS }) { Text("Listas") }
            }
            if (compact) {
                search(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    search(Modifier.weight(1f))
                    actions()
                }
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
            Spacer(Modifier.height(8.dp))

            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Descargando lista…") }
            } else if (compact) {
                GroupChips(vm)
                Spacer(Modifier.height(8.dp))
                ChannelList(vm, Modifier.weight(1f).fillMaxWidth())
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    GroupList(vm, Modifier.width(300.dp).fillMaxHeight())
                    ChannelList(vm, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun GroupChips(vm: MainViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(vm.groups, key = { i, g -> "$i-${g.first}" }) { _, (name, count) ->
            FilterChip(
                selected = vm.query.isBlank() && vm.selectedGroup == name,
                onClick = { vm.query = ""; vm.selectedGroup = name },
                label = { Text("$name ($count)", maxLines = 1) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GroupList(vm: MainViewModel, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(vm.groups, key = { i, g -> "$i-${g.first}" }) { _, (name, count) ->
            ListItem(
                selected = vm.query.isBlank() && vm.selectedGroup == name,
                onClick = { vm.query = ""; vm.selectedGroup = name },
                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text("$count") },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelList(vm: MainViewModel, modifier: Modifier) {
    val list = vm.visible
    if (list.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(if (vm.query.isBlank()) "Sin canales" else "Sin resultados para \"${vm.query}\"")
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
