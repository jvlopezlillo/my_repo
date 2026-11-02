package com.example.iptv.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.example.iptv.cast.DebugLog
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
import com.google.android.gms.cast.CastDevice
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
                    Button(onClick = {
                        val dev = CastDevice.getFromBundle(route.extras)
                        DebugLog.log("Chromecast elegido: «${route.name}» ip=${dev?.inetAddress?.hostAddress} modelo=${dev?.modelName}")
                        CastController.logNetwork(ctx)
                        dev?.inetAddress?.hostAddress?.let { CastController.inspectChromecast(it) }
                        router.selectRoute(route)
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(route.name)
                    }
                }
                var showLog by remember { mutableStateOf(false) }
                if (showLog) CastLogDialog { showLog = false }
                Button(onClick = { showLog = true }) { Text("Ver registro de diagnóstico") }
                DisposableEffect(Unit) { onDispose { CastController.stopNetworkTest() } }
                Button(onClick = {
                    if (CastController.testUrl == null) CastController.startNetworkTest() else CastController.stopNetworkTest()
                }) { Text(if (CastController.testUrl == null) "Probar la red local" else "Detener la prueba") }
                CastController.testUrl?.let {
                    Text(
                        "Desde OTRO dispositivo conectado a la misma Wi-Fi (un portátil u otro móvil) abre esta dirección en el navegador:\n$it\n" +
                            "Si ves un mensaje de «servidor local activo», esa Wi-Fi permite llegar al móvil.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = {
                    try {
                        ctx.startActivity(Intent("android.settings.CAST_SETTINGS"))
                    } catch (e: Exception) {
                        try { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
                    }
                }) { Text("Duplicar pantalla (Android)") }
                Text(
                    "Alternativa si un canal no se puede enviar: muestra en la tele lo que ves en el móvil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Siempre por el móvil")
                        Text(
                            "Por defecto el canal se envía directo al Chromecast (más rápido y sin gastar batería) " +
                                "y el móvil solo hace de puente si hay VPN/proxy, si el canal es MPEG-TS o si el directo falla. " +
                                "Actívalo para usar siempre el móvil.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(CastController.forceRelay, { CastController.updateForceRelay(it) })
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Registro de diagnóstico del envío a Chromecast: se puede compartir o copiar para pasarlo a quien lo revise. */
@Composable
fun CastLogDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = DebugLog.version // se refresca con cada línea nueva
    val text = remember(version) { DebugLog.report() }
    val scroll = rememberScrollState()
    LaunchedEffect(version) { scroll.scrollTo(scroll.maxValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registro de diagnóstico") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(scroll),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Registro IPTV TV")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Compartir registro"))
                    }) { Text("Compartir") }
                    Button(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("registro", text))
                    }) { Text("Copiar") }
                    Button(onClick = { DebugLog.clear() }) { Text("Borrar") }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Cerrar") } },
    )
}
