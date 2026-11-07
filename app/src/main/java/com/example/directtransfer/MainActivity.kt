package com.example.directtransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: TransferViewModel by viewModels()
    private var afterPermissions: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) afterPermissions?.invoke()
            else Toast.makeText(this, "Se necesitan todos los permisos para encontrar y conectar dispositivos", Toast.LENGTH_LONG).show()
            afterPermissions = null
        }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION) // BLE en Android <= 11
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION) // Wi-Fi Direct en Android <= 12
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE) // guardar en Descargas
    }.distinct()

    private fun withPermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else {
            afterPermissions = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Para ver el progreso y los botones Aceptar/Rechazar en segundo plano (Android 13+). No es obligatorio.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }

    @Composable
    private fun App() {
        val state by vm.state.collectAsStateWithLifecycle()
        LocalView.current.let { v -> SideEffect { v.keepScreenOn = state.busy } }
        // Mientras hay una transferencia, Atrás solo la pasa a segundo plano (sigue en el servicio).
        BackHandler(enabled = state.screen != Screen.HOME || state.busy) {
            if (state.busy) moveTaskToBack(true) else vm.goHome()
        }

        Scaffold { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                when (state.screen) {
                    Screen.HOME -> HomeScreen()
                    Screen.SEND -> SendScreen(state)
                    Screen.RECEIVE -> ReceiveScreen(state)
                }
            }
        }

        state.incoming?.let { req ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Transferencia entrante") },
                text = {
                    Text("«${req.senderName}» quiere enviarte ${req.fileCount} archivo(s) (${formatBytes(req.totalBytes)}).\n\n" +
                        (if (req.resumeBytes > 0) "Se reanudará una transferencia anterior: ya tienes ${formatBytes(req.resumeBytes)}.\n\n" else "") +
                        "Se guardarán en Descargas/${FileReceiver.RECEIVE_DIR}.")
                },
                confirmButton = { TextButton(onClick = { vm.answerRequest(true) }) { Text("Aceptar") } },
                dismissButton = { TextButton(onClick = { vm.answerRequest(false) }) { Text("Rechazar") } },
            )
        }
    }

    @Composable
    private fun HomeScreen() {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Direct Transfer", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Envía archivos y carpetas a otro Android sin necesitar Internet ni una red Wi-Fi compartida.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = { withPermissions { vm.openSend() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Enviar")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { withPermissions { vm.startReceiving() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Recibir")
            }
        }
    }

    @Composable
    private fun SendScreen(state: UiState) {
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.addFolder(it) }

        Text("Enviar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        if (state.busy || state.finished) {
            TransferStatus(state)
            return
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Archivos") }
            Button(onClick = { pickFolder.launch(null) }) { Text("Carpeta") }
            if (state.items.isNotEmpty()) TextButton(onClick = { vm.clearItems() }) { Text("Vaciar") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.items.isEmpty()) "Nada seleccionado"
            else "${state.items.size} archivo(s) · ${formatBytes(state.items.sumOf { it.size.coerceAtLeast(0) })}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (state.scanning) vm.stopScan() else vm.startScan() },
                enabled = state.items.isNotEmpty(),
            ) { Text(if (state.scanning) "Detener búsqueda" else "Buscar dispositivos") }
            if (state.scanning) CircularProgressIndicator(Modifier.height(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        if (state.scanning && state.devices.isEmpty()) {
            Text(
                "Buscando… El otro móvil debe tener la app abierta con «Recibir» activo y esperando " +
                    "(no se ve a sí mismo: haz falta dos móviles).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "En el otro dispositivo abre la app y pulsa «Recibir».",
            style = MaterialTheme.typography.bodySmall,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (state.canResume) {
            Button(onClick = { withPermissions { vm.resumeSend() } }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Reanudar envío")
            }
        }
        if (state.status.isNotEmpty()) Text(state.status, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices, key = { it.address }) { d ->
                Card(Modifier.fillMaxWidth().clickable { vm.sendTo(d) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text("Toca para enviar · señal ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    @Composable
    private fun ReceiveScreen(state: UiState) {
        Text("Recibir", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        TransferStatus(state)
    }

    @Composable
    private fun TransferStatus(state: UiState) {
        if (state.busy && state.progress == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.height(28.dp))
                Text(state.status, modifier = Modifier.fillMaxWidth())
            }
        } else if (state.status.isNotEmpty()) {
            Text(state.status, style = MaterialTheme.typography.titleMedium)
        }

        state.progress?.let { p ->
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth().height(10.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${p.percent} %", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("${formatBytes(p.bytesDone)} / ${formatBytes(p.totalBytes)}")
            }
            Text("Archivo ${p.fileIndex} de ${p.fileCount} · ${formatBytes(p.bytesPerSecond)}/s")
            Text(p.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        if (state.busy) {
            OutlinedButton(onClick = { vm.cancelTransfer() }) { Text("Cancelar") }
        } else {
            Button(onClick = { vm.goHome() }) { Text(if (state.finished) "Hecho" else "Volver") }
        }
    }
}
