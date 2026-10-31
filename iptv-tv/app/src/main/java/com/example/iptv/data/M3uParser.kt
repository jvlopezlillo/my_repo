package com.example.iptv.data

import java.io.BufferedReader

/** Parser M3U en streaming (soporta listas con decenas de miles de canales). */
object M3uParser {
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(reader: BufferedReader): List<Channel> {
        val out = ArrayList<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null

        reader.forEachLine { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    val title = ATTR.replace(line, "").substringAfter(',', "").trim()
                    name = title.ifEmpty { attrs["tvg-name"] ?: "Sin nombre" }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (group == null) group = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("#") -> {}
                else -> {
                    // línea de URL: cierra el canal actual
                    out.add(Channel(name ?: line.substringAfterLast('/'), group ?: NO_GROUP, logo, line))
                    name = null; logo = null; group = null
                }
            }
        }
        return out
    }
}
