package com.example.iptv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.iptv.MainViewModel
import com.example.iptv.Screen
import com.example.iptv.data.ProxyConfig

@Composable
fun ProxyScreen(vm: MainViewModel) {
    val saved = vm.proxy
    var enabled by remember { mutableStateOf(saved.enabled) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var user by remember { mutableStateOf(saved.user) }
    var pass by remember { mutableStateOf(saved.pass) }
    var forLists by remember { mutableStateOf(saved.forLists) }
    var forVideo by remember { mutableStateOf(saved.forVideo) }

    val portNum = port.toIntOrNull()
    val valid = host.isNotBlank() && portNum != null && portNum in 1..65535
    fun cfg() = ProxyConfig(enabled, host.trim(), portNum ?: 1080, user.trim(), pass, forLists, forVideo)

    Column(
        Modifier.fillMaxSize().padding(if (isTv()) 48.dp else 20.dp).widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Proxy SOCKS5", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Para canales bloqueados por país: el tráfico sale por tu servidor (p. ej. un VPS con Dante). " +
                "El vídeo pasa por el VPS, así que consume su ancho de banda.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Usar proxy", enabled) { enabled = it }
        OutlinedTextField(host, { host = it }, label = { Text("Servidor (IP o dominio)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Puerto (Dante: 1080)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(user, { user = it }, label = { Text("Usuario (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Contraseña (opcional)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow("Aplicar a la reproducción de vídeo", forVideo) { forVideo = it }
        SwitchRow("Aplicar a la descarga de listas", forLists) { forLists = it }

        vm.proxyTestResult?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.saveProxy(cfg()); vm.screen = Screen.HOME }, enabled = valid || !enabled) { Text("Guardar") }
            Button(onClick = { vm.testProxy(cfg()) }, enabled = valid && !vm.proxyTesting) { Text("Probar") }
            Button(onClick = { vm.proxyTestResult = null; vm.screen = Screen.HOME }) { Text("Volver") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, null)
    }
}
