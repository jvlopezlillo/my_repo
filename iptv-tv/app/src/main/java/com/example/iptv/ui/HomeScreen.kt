package com.example.iptv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import com.example.iptv.FAV
import com.example.iptv.cast.CastController
import com.example.iptv.MainViewModel
import com.example.iptv.Screen

@Composable
fun HomeScreen(vm: MainViewModel) {
    val tv = isTv()
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val list = vm.visible
    // Posición (en la lista visible) del último canal visto, o -1.
    val target = remember(list, vm.lastPlayedUrl) { list.indexOfFirst { it.url == vm.lastPlayedUrl } }

    // En la tele el foco inicial NO debe caer en el buscador (abriría el teclado):
    // vuelve al último canal visto, o al grupo seleccionado si no hay ninguno.
    if (tv) {
        LaunchedEffect(vm.loading) {
            if (vm.loading) return@LaunchedEffect
            if (target >= 0) vm.channelListState.scrollToItem((target - 3).coerceAtLeast(0))
            repeat(2) { withFrameNanos { } }
            runCatching { if (target >= 0) channelFocus.requestFocus() else groupFocus.requestFocus() }
        }
    }

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
                    onValueChange = { vm.updateQuery(it) },
                    placeholder = { Text("Buscar canal…") },
                    singleLine = true,
                    modifier = m,
                )
            }
            val actions: @Composable () -> Unit = {
                Button(onClick = { vm.refresh() }) { Text("Actualizar") }
                Button(onClick = { vm.screen = Screen.PLAYLISTS }) { Text("Listas") }
                Button(onClick = { vm.screen = Screen.GROUPS }) { Text("Grupos") }
                Button(onClick = { vm.screen = Screen.PROXY }) { Text(if (vm.proxy.enabled) "Proxy ●" else "Proxy") }
            }
            if (compact) {
                search(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions() }
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
            CastController.connectedName?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enviando a $name", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f, fill = false))
                    Button(onClick = { CastController.disconnect() }) { Text("Detener") }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Descargando lista…") }
            } else if (compact) {
                GroupChips(vm)
                Spacer(Modifier.height(8.dp))
                ChannelList(vm, target, channelFocus, Modifier.weight(1f).fillMaxWidth())
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    GroupList(vm, groupFocus, Modifier.width(300.dp).fillMaxHeight())
                    ChannelList(vm, target, channelFocus, Modifier.weight(1f).fillMaxHeight())
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
                onClick = { vm.selectGroup(name) },
                label = { Text("$name ($count)", maxLines = 1) },
            )
        }
    }
}

@Composable
private fun GroupList(vm: MainViewModel, groupFocus: FocusRequester, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(vm.groups, key = { i, g -> "$i-${g.first}" }) { _, (name, count) ->
            val isSelected = vm.query.isBlank() && vm.selectedGroup == name
            ItemRow(
                selected = isSelected,
                onClick = { vm.selectGroup(name) },
                modifier = if (isSelected) Modifier.focusRequester(groupFocus) else Modifier,
                badge = { Text("$count") },
            ) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun ChannelList(vm: MainViewModel, target: Int, channelFocus: FocusRequester, modifier: Modifier) {
    val list = vm.visible
    if (list.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                when {
                    vm.query.isNotBlank() -> "Sin resultados para \"${vm.query}\""
                    vm.selectedGroup == FAV -> "Aún no tienes favoritos.\nToca ♡ en un canal para añadirlo."
                    else -> "Sin canales"
                },
            )
        }
        return
    }
    LazyColumn(modifier, state = vm.channelListState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(list, key = { i, c -> "$i-${c.url}" }) { i, ch ->
            ItemRow(
                selected = false,
                onClick = { vm.play(i) },
                modifier = if (i == target) Modifier.focusRequester(channelFocus) else Modifier,
                trailing = { FavoriteButton(vm.isFavorite(ch), { vm.toggleFavorite(ch) }) },
                leading = {
                    AsyncImage(
                        model = ch.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(4.dp)),
                    )
                },
            ) {
                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (vm.query.isNotBlank()) {
                    Text(ch.group, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
