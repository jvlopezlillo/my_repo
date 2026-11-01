package com.example.iptv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.example.iptv.cast.CastController
import com.google.android.gms.cast.CastMediaControlIntent

/** Lista los Chromecast de la red y conecta con el elegido (sin MediaRouteButton: encaja con Compose). */
@Composable
fun CastDeviceDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val router = remember { MediaRouter.getInstance(ctx) }
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
    }
    var routes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }

    DisposableEffect(Unit) {
        val callback = object : MediaRouter.Callback() {
            fun refresh() {
                routes = router.routes.filter { it.isEnabled && it.matchesSelector(selector) && !it.isDefaultOrBluetooth }
            }
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(
            selector, callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
        callback.refresh()
        onDispose { router.removeCallback(callback) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enviar a…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (routes.isEmpty()) {
                    Text("Buscando Chromecast en tu red Wi-Fi…")
                    if (CastController.vpnActive(ctx)) {
                        Text(
                            "Hay una VPN activa: para ver el Chromecast activa en la VPN la opción de permitir " +
                                "la red local (LAN), o excluye tu red local del túnel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                routes.forEach { route ->
                    Button(onClick = { router.selectRoute(route); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(route.name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Retransmitir por el móvil")
                        Text(
                            "El móvil descarga el vídeo (con tu VPN/proxy) y se lo pasa al Chromecast. " +
                                "Recomendado; el móvil debe seguir encendido y en la misma Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(CastController.relayEnabled, { CastController.setRelayEnabled(it) })
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Cerrar") } },
    )
}
