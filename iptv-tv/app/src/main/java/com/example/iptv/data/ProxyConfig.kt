package com.example.iptv.data

import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy

@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 1080,
    val user: String = "",
    val pass: String = "",
    val forLists: Boolean = true,   // descarga de listas M3U / API Xtream
    val forVideo: Boolean = true,   // reproducción del vídeo
)

/** Proxy SOCKS5 para OkHttp. El DNS lo resuelve el propio proxy (dirección sin resolver). */
object ProxySupport {
    @Volatile var current: ProxyConfig = ProxyConfig()

    init {
        // Credenciales SOCKS5 (usuario/contraseña): la pila de red de Java las pide por aquí.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val c = current
                return if (c.user.isNotBlank() && requestingProtocol?.contains("SOCKS", ignoreCase = true) == true)
                    PasswordAuthentication(c.user, c.pass.toCharArray())
                else null
            }
        })
    }

    fun apply(client: OkHttpClient, cfg: ProxyConfig, video: Boolean): OkHttpClient {
        val use = cfg.enabled && cfg.host.isNotBlank() && cfg.port in 1..65535 &&
            (if (video) cfg.forVideo else cfg.forLists)
        if (!use) return client
        return client.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(cfg.host.trim(), cfg.port)))
            .build()
    }
}
