package com.example.iptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.darkColorScheme
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.tv.material3.MaterialTheme as TvTheme
import androidx.tv.material3.darkColorScheme as tvDark

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // Los TextField son de material3 y el resto de tv-material: se tematizan los dos.
    M3Theme(colorScheme = darkColorScheme()) {
        TvTheme(colorScheme = tvDark(), content = content)
    }
}
