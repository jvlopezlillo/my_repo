package com.example.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.iptv.ui.AddPlaylistScreen
import com.example.iptv.ui.AppTheme
import com.example.iptv.ui.GroupsScreen
import com.example.iptv.ui.HomeScreen
import com.example.iptv.ui.PlayerScreen
import com.example.iptv.ui.PlaylistsScreen
import com.example.iptv.ui.ProxyScreen
import com.example.iptv.ui.SyncScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1220), contentColor = Color.White) {
                    when (vm.screen) {
                        Screen.HOME -> HomeScreen(vm)
                        Screen.ADD -> AddPlaylistScreen(vm)
                        Screen.PLAYLISTS -> PlaylistsScreen(vm)
                        Screen.PLAYER -> PlayerScreen(vm)
                        Screen.GROUPS -> GroupsScreen(vm)
                        Screen.PROXY -> ProxyScreen(vm)
                        Screen.SYNC -> SyncScreen(vm)
                    }
                }
                // Atrás: vuelve a Home desde cualquier pantalla secundaria.
                BackHandler(enabled = vm.screen != Screen.HOME) {
                    when {
                        vm.screen == Screen.ADD && vm.active == null && vm.editing == null -> finish()
                        vm.screen == Screen.SYNC -> vm.screen = if (vm.playlists.isEmpty()) Screen.ADD else Screen.PLAYLISTS
                        else -> { vm.editing = null; vm.screen = Screen.HOME }
                    }
                }
            }
        }
    }
}
