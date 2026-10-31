package com.example.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** true si la app corre en una Android TV (mando) y no en móvil/tablet (táctil). */
@Composable
fun isTv(): Boolean {
    val ctx = LocalContext.current
    return remember {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
