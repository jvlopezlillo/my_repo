package com.example.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fila seleccionable: responde al dedo (clickable) y al mando (resaltado al enfocar). */
@Composable
fun ItemRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> primary.copy(alpha = 0.40f)
                    selected -> primary.copy(alpha = 0.20f)
                    else -> Color.Transparent
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = content)
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
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.40f) else Color.Transparent)
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
