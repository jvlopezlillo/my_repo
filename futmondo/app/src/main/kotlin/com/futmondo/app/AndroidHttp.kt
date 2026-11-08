package com.futmondo.app

import com.futmondo.core.FutmondoException
import com.futmondo.core.HttpPoster
import java.net.HttpURLConnection
import java.net.URL

/** Llama directamente a api.futmondo.com desde el móvil (sin servidor propio). */
class AndroidHttp : HttpPoster {
    override fun post(path: String, jsonBody: String): String {
        val c = URL("https://api.futmondo.com$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && !text.trimStart().startsWith("{")) throw FutmondoException("http.$code")
            return text
        } finally {
            c.disconnect()
        }
    }
}
