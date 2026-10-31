package com.example.iptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF04121C),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF04121C),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFE6EBF5),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EBF5),
    surface = Color(0xFF131C31),
    onSurface = Color(0xFFE6EBF5),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF9FB0CC),
    outline = Color(0xFF64748B),
    error = Color(0xFFFF6B81),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
