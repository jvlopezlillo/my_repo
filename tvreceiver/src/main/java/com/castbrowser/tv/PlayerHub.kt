package com.castbrowser.tv

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Vídeo que manda el navegador del móvil. */
data class MediaRequest(
    val url: String,
    val contentType: String,
    val title: String,
    val position: Double,
    val live: Boolean,
    val headers: Map<String, String>,
)

/** Lo que el reproductor sabe hacer; lo implementa [PlayerActivity]. */
interface PlayerControl {
    fun load(req: MediaRequest)
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun stop()

    /** false si la pantalla se está cerrando y ya no puede cargar vídeos. */
    val active: Boolean
}

/**
 * Punto de encuentro entre el servidor HTTP (hilos de red) y el reproductor
 * (hilo principal). Guarda el último estado para responder a /status.
 */
object PlayerHub {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var pending: MediaRequest? = null
    @Volatile var player: PlayerControl? = null

    @Volatile var state = "idle"
    @Volatile var position = 0.0
    @Volatile var duration = 0.0
    @Volatile var error = ""
    @Volatile var title = ""
    @Volatile var video = ""

    fun play(ctx: Context, req: MediaRequest) {
        // Un vídeo nuevo que no es el empujado desde el móvil cierra los flujos empujados anteriores.
        if (!req.url.contains("/live/")) PushStreams.closeAll()
        state = "loading"
        error = ""
        video = ""
        title = req.title
        position = req.position
        duration = 0.0
        main.post {
            val p = player
            if (p != null && p.active) {
                p.load(req)
            } else {
                pending = req
                ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }

    fun control(ctx: Context, action: String, value: Double?) {
        main.post {
            val p = player
            when (action) {
                "play" -> p?.play()
                "pause" -> p?.pause()
                "seek" -> p?.seek(value ?: 0.0)
                "stop" -> { p?.stop(); state = "idle"; PushStreams.closeAll() }
                "volume" -> setVolume(ctx, value ?: 0.5)
            }
        }
    }

    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun volumePercent(ctx: Context): Int {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }

    fun setVolumePercent(ctx: Context, percent: Int) {
        main.post { setVolume(ctx, percent.coerceIn(0, 100) / 100.0) }
    }

    fun isMuted(ctx: Context): Boolean = audio(ctx).isStreamMute(AudioManager.STREAM_MUSIC)

    fun setMuted(ctx: Context, mute: Boolean) {
        main.post {
            audio(ctx).adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
        }
    }

    private fun setVolume(ctx: Context, level: Double) {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0.0, 1.0) * max).toInt(), AudioManager.FLAG_SHOW_UI)
    }

    fun status(ctx: Context): JSONObject {
        val am = audio(ctx)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() /
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return JSONObject()
            .put("state", state)
            .put("position", position)
            .put("duration", duration)
            .put("volume", vol)
            .put("error", error)
            .put("title", title)
            .put("video", video)
            .put("detail", PushStreams.eventsText())
    }
}
