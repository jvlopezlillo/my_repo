package com.castbrowser.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Pantalla completa con el reproductor (ExoPlayer). Se maneja con el mando o desde el móvil. */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity(), PlayerControl {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var titleView: TextView

    private val ticker = object : Runnable {
        override fun run() {
            player?.let { p ->
                PlayerHub.position = p.currentPosition.coerceAtLeast(0) / 1000.0
                val d = p.duration
                PlayerHub.duration = if (d == C.TIME_UNSET || d < 0) 0.0 else d / 1000.0
            }
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3000
            keepScreenOn = true
        }
        root.addView(view)
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(48, 32, 48, 32)
        }
        root.addView(titleView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)

        PlayerHub.player = this
        PlayerHub.pending?.let { PlayerHub.pending = null; load(it) }
        main.post(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PlayerHub.pending?.let { PlayerHub.pending = null; load(it) }
    }

    override fun load(req: MediaRequest) {
        player?.release()
        PlayerHub.state = "loading"
        PlayerHub.error = ""

        // Cabeceras del navegador (Referer, cookies…) para que la web entregue el vídeo.
        val headers = req.headers.filterKeys { !it.equals("User-Agent", true) }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers)
        req.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { http.setUserAgent(it.value) }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(
                androidx.media3.datasource.DataSource.Factory { RoutingDataSource(http.createDataSource(), PushDataSource()) }
            ))
            .build()
        player = p
        view.player = p

        val mime = when {
            req.contentType.contains("mpegurl", true) -> MimeTypes.APPLICATION_M3U8
            req.contentType.contains("dash", true) -> MimeTypes.APPLICATION_MPD
            req.contentType.contains("mp2t", true) -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        val item = MediaItem.Builder()
            .setUri(playableUri(req.url))
            .setMimeType(mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(req.title).build())
            .build()

        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0) PlayerHub.video = "${videoSize.width}x${videoSize.height}"
            }
            override fun onPlaybackStateChanged(state: Int) {
                PushStreams.event("ExoPlayer estado " + when (state) {
                    Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> state.toString()
                })
                updateState()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateState()
            override fun onPlayerError(error: PlaybackException) {
                val detail = error.errorCodeName + ": " + (error.cause?.message ?: error.message)
                PushStreams.event("ExoPlayer error $detail")
                PlayerHub.state = "error"
                PlayerHub.error = describe(error) + " [" + detail + "]"
                titleView.text = "No se pudo reproducir: ${PlayerHub.error}"
                titleView.visibility = View.VISIBLE
            }
        })
        if (!req.live && req.position > 1) p.setMediaItem(item, (req.position * 1000).toLong()) else p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true

        titleView.text = req.title
        titleView.visibility = View.VISIBLE
        main.postDelayed({ if (PlayerHub.state != "error") titleView.visibility = View.GONE }, 5000)
    }

    /** Un vídeo empujado desde el móvil (http://127.0.0.1:…/live/<id>.ts) se lee directamente de la memoria. */
    private fun playableUri(url: String): Uri {
        val m = Regex("^http://127\\.0\\.0\\.1:\\d+/live/([^./?]+)").find(url)
        return if (m != null) Uri.parse("push://${m.groupValues[1]}") else Uri.parse(url)
    }

    private fun updateState() {
        val p = player ?: return
        if (PlayerHub.state == "error") return
        PlayerHub.state = when (p.playbackState) {
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_ENDED -> "ended"
            Player.STATE_READY -> if (p.isPlaying || p.playWhenReady) "playing" else "paused"
            else -> "idle"
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "la web rechazó la petición (${e.cause?.message ?: "HTTP"})"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "no hay conexión con el servidor del vídeo"
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "vídeo protegido con DRM"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "formato no soportado por la TV"
        else -> e.errorCodeName
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun seek(seconds: Double) { player?.seekTo((seconds * 1000).toLong().coerceAtLeast(0)) }
    override val active: Boolean get() = !isFinishing && !isDestroyed

    override fun stop() {
        // Soltamos el puesto ya: si llega otro vídeo enseguida (DLNA manda Stop y luego Play),
        // se abrirá una pantalla nueva en vez de cargarlo en esta, que se está cerrando.
        if (PlayerHub.player === this) PlayerHub.player = null
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Con el mando: el controlador aparece al pulsar cualquier tecla.
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible &&
            event.keyCode != KeyEvent.KEYCODE_BACK
        ) {
            view.showController()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        val owner = PlayerHub.player === this
        if (owner) PlayerHub.player = null
        player?.release()
        player = null
        // Si ya hay otra pantalla de reproducción, no pisamos su estado.
        if (owner && PlayerHub.state != "error") PlayerHub.state = "idle"
        super.onDestroy()
    }
}
