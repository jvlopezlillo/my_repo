package com.example.iptv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class RemotePlaylist(
    val id: String,
    val name: String,
    val kind: String,
    val url: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class SyncRequest(val playlists: List<RemotePlaylist>)

@Serializable
data class SyncResponse(
    val playlists: List<RemotePlaylist> = emptyList(),
    val linked: Boolean = false,
    val name: String = "",
    val code: String? = null,
)

@Serializable
data class RegisterResponse(val token: String, val deviceId: String, val code: String, val expiresIn: Int)

@Serializable
data class CodeResponse(val code: String, val expiresIn: Int)

fun PlaylistConfig.toRemote() = RemotePlaylist(id, name, kind.name, url, server, user, pass, updatedAt)

fun RemotePlaylist.toConfig() = PlaylistConfig(
    id, name, runCatching { Kind.valueOf(kind) }.getOrDefault(Kind.M3U), url, server, user, pass, updatedAt,
)

/** Cliente del servidor de sincronización (carpeta iptv-sync-server). */
class SyncClient(private val http: OkHttpClient, url: String) {
    class Unauthorized : Exception("Este dispositivo ya no está vinculado al servidor")

    val base = normalize(url)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun register(): RegisterResponse = call(post("/api/device/register", null, ByteArray(0).toRequestBody(null)))

    fun newCode(token: String): CodeResponse = call(post("/api/device/code", token, ByteArray(0).toRequestBody(null)))

    fun sync(token: String, playlists: List<RemotePlaylist>): SyncResponse {
        val body = json.encodeToString(SyncRequest.serializer(), SyncRequest(playlists))
            .toRequestBody("application/json".toMediaType())
        return call(post("/api/device/sync", token, body))
    }

    private fun post(path: String, token: String?, body: RequestBody): Request =
        Request.Builder().url(base + path).post(body).apply {
            header("User-Agent", UA)
            if (token != null) header("Authorization", "Bearer $token")
        }.build()

    private inline fun <reified T> call(req: Request): T =
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw Unauthorized()
            if (!r.isSuccessful) error("Error HTTP ${r.code}")
            json.decodeFromString<T>(r.body!!.string())
        }

    companion object {
        fun normalize(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s.trimEnd('/').removeSuffix("/admin").trimEnd('/')
        }
    }
}
