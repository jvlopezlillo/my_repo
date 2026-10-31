package com.example.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Borde blanco grueso del elemento enfocado: se distingue bien en la tele a distancia. */
private fun Modifier.focusBorder(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (focused) this.border(3.dp, Color.White, shape) else this

/**
 * Fila seleccionable. La zona principal (logo + texto) y el [trailing] (p. ej. el corazón) son
 * elementos enfocables HERMANOS: con el mando, "derecha" pasa de la fila al corazón y "izquierda" vuelve.
 * (Si el corazón estuviera dentro de la fila clicable, el mando no podría llegar a él.)
 */
@Composable
fun ItemRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier
                .weight(1f)
                .clip(shape)
                .background(
                    when {
                        focused -> primary.copy(alpha = 0.35f)
                        selected -> primary.copy(alpha = 0.20f)
                        else -> Color.Transparent
                    }
                )
                .focusBorder(focused, shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), content = content)
            badge?.invoke()
        }
        trailing?.invoke()
    }
}

/** Corazón para marcar/desmarcar favorito: táctil y enfocable con el mando. */
@Composable
fun FavoriteButton(isFavorite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color.Transparent)
            .focusBorder(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos" }
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (isFavorite) "♥" else "♡",
            fontSize = 26.sp,
            color = if (isFavorite) Color(0xFFFF4D6D) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Botón de Material 3 con foco muy visible (borde blanco + ligero zoom) para el mando de la tele. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val s = if (focused) 1.06f else 1f
                scaleX = s
                scaleY = s
            }
            .focusBorder(focused, ButtonDefaults.shape),
        content = content,
    )
}
