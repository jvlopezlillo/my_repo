package com.example.iptv.data

import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
)

enum class Kind { M3U, XTREAM }

@Serializable
data class PlaylistConfig(
    val id: String,
    val name: String,
    val kind: Kind,
    val url: String = "",      // M3U
    val server: String = "",   // Xtream
    val user: String = "",
    val pass: String = "",
)

const val NO_GROUP = "Sin grupo"
