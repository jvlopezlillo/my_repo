package com.example.iptv.ui

import android.Manifest
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.example.iptv.cast.CastController
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.example.iptv.MainViewModel
import com.example.iptv.data.UA
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val list = vm.queue
    val channel = list.getOrNull(vm.playingIndex) ?: run { vm.screen = com.example.iptv.Screen.HOME; return }

    var error by remember { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val tv = isTv()

    val player = remember {
        val http = OkHttpDataSource.Factory(vm.videoClient()).setUserAgent(UA)
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { error = "No se pudo reproducir (${e.errorCodeName})" }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) error = null }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Evita que la tele pase al salvapantallas mientras se está viendo un canal.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Si hay un Chromecast conectado el canal se envía allí y el reproductor local se detiene.
    val castName = CastController.connectedName
    val casting = castName != null
    var showCast by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { showCast = true }
    val openCast = {
        if (Build.VERSION.SDK_INT >= 33) {
            permLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS))
        } else showCast = true
    }

    LaunchedEffect(channel.url, castName) {
        error = null
        overlay = true
        if (castName != null) {
            player.stop()
            player.clearMediaItems()
            CastController.load(channel, vm.videoClient())
        } else {
            player.setMediaItem(MediaItem.fromUri(channel.url))
            player.prepare()
        }
        delay(4000)
        overlay = false
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // Táctil: tocar muestra/oculta el banner; deslizar arriba/abajo cambia de canal.
            .pointerInput(Unit) { detectTapGestures(onTap = { overlay = !overlay }) }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onVerticalDrag = { _, dy -> acc += dy },
                    onDragEnd = {
                        if (acc < -120f) vm.zap(+1) else if (acc > 120f) vm.zap(-1)
                    },
                )
            }
            .focusRequester(focus).focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> { vm.zap(+1); true }
                    Key.DirectionDown, Key.ChannelDown -> { vm.zap(-1); true }
                    Key.Menu, Key.ProgramRed -> { vm.toggleFavorite(channel); overlay = true; true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionRight, Key.DirectionLeft -> { overlay = !overlay; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
        )
        if (showCast) CastDeviceDialog { showCast = false }
        if (showLog) CastLogDialog { showLog = false }
        if (overlay || error != null || casting) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color(0xCC000000)).padding(horizontal = 40.dp, vertical = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${vm.playingIndex + 1}. ${channel.name}", style = MaterialTheme.typography.headlineSmall)
                        Text(channel.group, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FavoriteButton(vm.isFavorite(channel), { vm.toggleFavorite(channel) })
                }
                if (casting) Text("Reproduciendo en $castName", color = MaterialTheme.colorScheme.primary)
                CastController.status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                (error ?: CastController.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!tv) {
                    Row(
                        Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { vm.zap(-1) }) { Text("◀ Anterior") }
                        Button(onClick = { vm.zap(+1) }) { Text("Siguiente ▶") }
                        Button(onClick = { vm.screen = com.example.iptv.Screen.HOME }) { Text("Lista") }
                        if (CastController.available) {
                            Button(onClick = { if (casting) CastController.disconnect() else openCast() }) {
                                Text(if (casting) "Dejar de enviar" else "Enviar a TV")
                            }
                            Button(onClick = { showLog = true }) { Text("Registro") }
                        }
                    }
                }
            }
        }
    }
}
