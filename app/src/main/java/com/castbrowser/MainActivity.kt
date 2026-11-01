package com.castbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var net: NetworkHelper
    private lateinit var discovery: DeviceDiscovery

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fullscreen: FrameLayout
    private lateinit var castButton: ImageButton
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelStatus: TextView
    private lateinit var seek: SeekBar
    private lateinit var playPause: Button

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val captures = CaptureLog()
    @Volatile private var lastVideo: PageVideo? = null
    private var videoSeen = false
    private var userAgent = ""

    @Volatile private var client: CastClient? = null
    private var castDevice: CastDevice? = null
    private var proxy: MediaProxy? = null
    private var connecting = false
    private var seeking = false

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val periodic = object : Runnable {
        override fun run() {
            if (client == null) discovery.discoverNow()
            main.postDelayed(this, 45_000)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        net = NetworkHelper(this)
        discovery = DeviceDiscovery(this, net)
        discovery.onDevicesChanged = { updateCastButton() }
        net.onChanged = { main.postDelayed({ discovery.discoverNow() }, 1500) }

        buildUi()
        setupWebView()

        net.start()
        discovery.start()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val start = intent?.dataString ?: prefs.getString("home", HOME)!!
        webView.loadUrl(start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { webView.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        main.removeCallbacks(periodic)
        main.post(periodic)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(periodic)
        // Si estamos enviando al Chromecast seguimos vivos; si no, pausamos la web.
        if (client == null) webView.onPause()
    }

    override fun onDestroy() {
        client?.stop()
        proxy?.close()
        discovery.stop()
        net.stop()
        KeepAliveService.stop(this)
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            panel.visibility == View.VISIBLE && client == null -> panel.visibility = View.GONE
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    /** Con un vídeo en el Chromecast, los botones de volumen controlan la tele. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = client
        if (c != null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05 else -0.05
                val v = (c.lastStatus.volume + step).coerceIn(0.0, 1.0)
                Thread { try { c.setVolume(v) } catch (_: Exception) {} }.start()
                toast("Volumen Chromecast: ${(v * 100).toInt()}%")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ interfaz

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.parseColor("#202124"))
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
    }

    private fun buildUi() {
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bar.addView(barButton("←") { if (webView.canGoBack()) webView.goBack() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        urlBar = EditText(this).apply {
            setSingleLine()
            textSize = 15f
            hint = "Buscar o escribir dirección"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F1F3F4"))
            }
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    navigate(text.toString()); true
                } else false
            }
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(barButton("⟳") { webView.reload() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        lateinit var menuButton: TextView
        menuButton = barButton("⋮") { showMenu(menuButton) }
        bar.addView(menuButton, LinearLayout.LayoutParams(dp(40), dp(44)))
        column.addView(bar)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.parseColor("#1A73E8"))
        }
        column.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        webView = WebView(this)
        column.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column)

        fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreen)

        // Panel de control del Chromecast
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#EE202124"))
            }
            visibility = View.GONE
            elevation = dp(8).toFloat()
        }
        panelTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        panelStatus = TextView(this).apply { setTextColor(Color.parseColor("#BDC1C6")); textSize = 13f }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8AB4F8"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#8AB4F8"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) panelStatus.text = "Ir a ${fmt(p.toDouble())}"
                }
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    client?.seek(sb.progress.toDouble())
                }
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun ctl(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#3C4043")) }
            setOnClickListener { action() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        ctl("−10s") { client?.let { it.seek(it.lastStatus.currentTime - 10) } }
        playPause = ctl("⏸") {
            client?.let { if (it.lastStatus.playerState == "PLAYING") it.pause() else it.play() }
        }
        ctl("+30s") { client?.let { it.seek(it.lastStatus.currentTime + 30) } }
        ctl("🔉") { changeVolume(-0.1) }
        ctl("🔊") { changeVolume(0.1) }
        ctl("⏹") { stopCasting() }
        panel.addView(panelTitle)
        panel.addView(panelStatus)
        panel.addView(seek)
        panel.addView(row)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(96)) })

        // Botón de Chromecast flotante
        castButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_cast)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A73E8"))
            }
            elevation = dp(10).toFloat()
            contentDescription = "Enviar a Chromecast"
            visibility = View.GONE
            setOnClickListener { onCastButton(false) }
            setOnLongClickListener { onCastButton(true); true }
        }
        root.addView(castButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(20), dp(24)) })

        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        userAgent = webView.settings.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(Bridge(), "CastBridge")
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) {
            WebViewCompat.addDocumentStartJavaScript(webView, MediaDetector.SCRIPT, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
                try {
                    val intent = if (scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                } catch (_: Exception) {
                    // intent:// con URL alternativa
                    try {
                        val fallback = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                            .getStringExtra("browser_fallback_url")
                        if (fallback != null) view.loadUrl(fallback)
                    } catch (_: Exception) {}
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                captures.clear()
                lastVideo = null
                videoSeen = false
                updateCastButton()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
                if (!docStart) view.evaluateJavascript(MediaDetector.SCRIPT, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (MediaDetector.classify(url) != null) {
                    val h = HashMap<String, String>()
                    request.requestHeaders.forEach { (k, v) ->
                        if (k.equals("Referer", true) || k.equals("User-Agent", true) || k.equals("Origin", true)) h[k] = v
                    }
                    captures.add(url, h)
                    main.post { onStreamCaptured() }
                }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreen.visibility = View.VISIBLE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() = hideCustomView()
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun navigate(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        webView.loadUrl(url)
        urlBar.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
        webView.requestFocus()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Buscar Chromecast ahora")
        m.menu.add(0, 2, 0, "Añadir Chromecast por IP")
        m.menu.add(0, 3, 0, "Chromecast guardados")
        m.menu.add(0, 4, 0, "Modo de envío: " + modeLabel(mode()))
        m.menu.add(0, 5, 0, "Usar esta página como inicio")
        m.menu.add(0, 6, 0, "Ir a inicio")
        m.menu.add(0, 7, 0, "Probar con vídeo de ejemplo")
        m.menu.add(0, 8, 0, "Diagnóstico (registro)")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { toast("Buscando Chromecast…"); discovery.discoverNow(forceSubnetScan = true) }
                2 -> askManualIp()
                3 -> showSaved()
                4 -> chooseMode()
                5 -> { prefs.edit().putString("home", webView.url ?: HOME).apply(); toast("Página de inicio guardada") }
                6 -> webView.loadUrl(prefs.getString("home", HOME)!!)
                7 -> showDevicePicker(SAMPLES, 0, sample = true)
                8 -> showDiagnostics()
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------------------------ detección de vídeo

    private inner class Bridge {
        @JavascriptInterface
        fun onMedia(json: String) {
            try {
                val o = JSONObject(json)
                val isTop = o.optBoolean("top")
                val v = PageVideo(
                    src = o.optString("src"),
                    playing = o.optBoolean("playing"),
                    time = o.optDouble("t", 0.0),
                    duration = o.optDouble("d", 0.0),
                    live = o.optBoolean("live"),
                    title = if (isTop) o.optString("title") else "",
                    page = o.optString("page"),
                )
                val ev = o.optString("ev")
                val isVideo = o.optBoolean("video")
                main.post { onPageVideo(v, ev, isVideo) }
            } catch (_: Exception) {}
        }
    }

    private fun onPageVideo(v: PageVideo, ev: String, isVideo: Boolean) {
        val prev = lastVideo
        // Preferimos el <video> que se está reproduciendo.
        if (prev == null || v.playing || prev.src == v.src || !prev.playing) {
            if (isVideo || prev == null) lastVideo = v
        }
        if (v.playing || ev == "play" || ev == "playing") {
            if (!videoSeen) {
                videoSeen = true
                if (discovery.availableDevices().isEmpty() &&
                    System.currentTimeMillis() - discovery.lastScan > 15_000
                ) discovery.discoverNow()
            }
        }
        updateCastButton()
    }

    private fun onStreamCaptured() {
        updateCastButton()
    }

    private fun updateCastButton() {
        val casting = client != null || connecting
        val show = casting || (videoSeen && discovery.availableDevices().isNotEmpty())
        val wasHidden = castButton.visibility != View.VISIBLE
        castButton.visibility = if (show) View.VISIBLE else View.GONE
        (castButton.background as GradientDrawable).setColor(
            Color.parseColor(if (client != null) "#188038" else "#1A73E8")
        )
        if (show && wasHidden) {
            castButton.scaleX = 0.3f; castButton.scaleY = 0.3f
            castButton.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    // ------------------------------------------------------------------ envío

    private fun onCastButton(longPress: Boolean) {
        if (client != null || connecting) {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        val candidates = captures.candidates(lastVideo)
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No se puede enviar este vídeo")
                .setMessage("La página no expone una dirección de vídeo normal (suele pasar con vídeos con DRM, como Netflix, o con YouTube). Prueba a darle al play primero y vuelve a pulsar.")
                .setPositiveButton("Vale", null)
                .show()
            return
        }
        val devices = discovery.availableDevices()
        if (devices.size == 1 && !longPress) {
            startCast(devices[0], candidates[0])
            return
        }
        showDevicePicker(candidates, 0)
    }

    private fun showDevicePicker(candidates: List<MediaCandidate>, chosen: Int, sample: Boolean = false) {
        val devices = discovery.availableDevices()
        val labels = devices.map { d ->
            val extra = when (d.via) { "recordado" -> " · guardado"; "manual" -> " · manual"; else -> "" }
            "📺  ${d.name}\n      ${d.ip}$extra"
        }.toTypedArray()
        val b = AlertDialog.Builder(this)
            .setTitle(if (devices.isEmpty()) "No hay Chromecast a la vista" else "Enviar a…")
            .setNeutralButton("Buscar") { _, _ ->
                toast("Buscando Chromecast…")
                discovery.discoverNow(forceSubnetScan = true)
            }
        if (devices.isEmpty()) {
            val vpnHint = if (net.isVpnActive())
                "\n\nTienes una VPN activa: activa en la VPN la opción de permitir la red local (LAN / \"Excluir IPs privadas\"), o añade el Chromecast por IP desde el menu ⋮."
            else ""
            b.setMessage("Comprueba que estás en la misma Wi-Fi que el Chromecast.$vpnHint")
            b.setPositiveButton("Añadir por IP") { _, _ -> askManualIp() }
        } else {
            b.setItems(labels) { _, which -> startCast(devices[which], candidates[chosen], sample) }
        }
        if (candidates.size > 1) {
            b.setNegativeButton("Elegir vídeo (${candidates.size})") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Vídeos detectados")
                    .setSingleChoiceItems(candidates.map { it.label }.toTypedArray(), chosen) { d, w ->
                        d.dismiss()
                        showDevicePicker(candidates, w, sample)
                    }
                    .show()
            }
        }
        b.show()
    }

    private fun mode(): String = prefs.getString("mode", "auto")!!

    private fun modeLabel(m: String) = when (m) {
        "direct" -> "directo"
        "proxy" -> "a través del móvil"
        else -> "automático"
    }

    private fun chooseMode() {
        val keys = arrayOf("auto", "direct", "proxy")
        val labels = arrayOf(
            "Automático (recomendado): con VPN el vídeo pasa por el móvil",
            "Directo: el Chromecast descarga el vídeo él solo",
            "Siempre a través del móvil (para vídeos que sólo van por la VPN)",
        )
        AlertDialog.Builder(this)
            .setTitle("Modo de envío")
            .setSingleChoiceItems(labels, keys.indexOf(mode()).coerceAtLeast(0)) { d, w ->
                prefs.edit().putString("mode", keys[w]).apply()
                d.dismiss()
            }
            .show()
    }

    private fun headersFor(c: MediaCandidate): Map<String, String> {
        val h = LinkedHashMap<String, String>(c.headers)
        if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = userAgent
        if (h.keys.none { it.equals("Referer", true) }) (lastVideo?.page ?: webView.url)?.let { h["Referer"] = it }
        return h
    }

    private fun startCast(device: CastDevice, cand: MediaCandidate, sample: Boolean = false) {
        val video = if (sample) null else lastVideo
        val title = if (sample) "Vídeo de prueba" else video?.title?.ifEmpty { null } ?: webView.title ?: "Vídeo"
        val start = video?.time ?: 0.0
        val live = video?.live == true
        val headers = headersFor(cand)
        val mode = mode()

        connecting = true
        castDevice = device
        webView.evaluateJavascript("window.__castPauseAll && window.__castPauseAll()", null)
        diag("──── Enviar a ${device.name} (${device.ip}:${device.port}, ${device.via})")
        diag("Vídeo: ${cand.kind} ${cand.url.take(200)}")
        diag("VPN activa: ${net.isVpnActive()} · IP Wi-Fi: ${net.localIpv4()?.first?.hostAddress ?: "ninguna"} · modo: $mode")
        panelTitle.text = "📺 ${device.name}"
        panelStatus.text = "Conectando…"
        seek.progress = 0
        panel.visibility = View.VISIBLE
        updateCastButton()

        Thread {
            var c: CastClient? = null
            try {
                client?.stop()
                val self = arrayOfNulls<CastClient>(1)
                val cc = CastClient(
                    device.ip, device.port,
                    rawSocket = { h, p -> net.connect(h, p, 5000) },
                    onStatus = { s -> main.post { if (client === self[0]) showStatus(s) } },
                    onClosed = { r -> main.post { if (client === self[0] || client == null) onCastEnded(r) } },
                    log = ::diag,
                )
                self[0] = cc
                c = cc
                cc.connect()
                val vpn = net.isVpnActive()
                val order = when (mode) {
                    "direct" -> listOf(false)
                    "proxy" -> listOf(true)
                    else -> if (vpn || prefs.getBoolean("viaPhone:${device.key}", false)) listOf(true, false)
                    else listOf(false, true)
                }
                val fmp4 = cand.kind == "hls" && detectFmp4(cand.url, headers)
                if (cand.kind == "hls") diag("HLS con segmentos fMP4: $fmp4")
                proxy?.lastUpstreamError = null
                proxy?.okLogged = 0
                var error: Exception? = null
                var ok = false
                for (useProxy in order) {
                    val url = if (useProxy) {
                        val p = ensureProxy() ?: continue
                        p.rewriter.defaultHeaders = headers
                        p.rewriter.proxify(cand.url, headers)
                    } else cand.url
                    main.post { panelStatus.text = if (useProxy) "Cargando (a través del móvil)…" else "Cargando…" }
                    try {
                        cc.load(url, cand.contentType, title, start, live, fmp4)
                        ok = true
                        // Recordamos qué camino funciona con esta TV para probarlo primero la próxima vez.
                        if (mode == "auto" && !vpn) prefs.edit().putBoolean("viaPhone:${device.key}", useProxy).apply()
                        break
                    } catch (e: CastException) {
                        diag("Fallo (${if (useProxy) "vía móvil" else "directo"}): ${e.message}")
                        error = e
                    }
                }
                if (!ok) throw error ?: CastException("No se pudo iniciar el vídeo (no hay IP Wi-Fi para el modo 'a través del móvil')")
                diag("Reproduciendo ✔")
                val connected = cc
                main.post {
                    client = connected
                    connecting = false
                    discovery.remember(device)
                    KeepAliveService.start(this, device.name)
                    updateCastButton()
                    showStatus(connected.lastStatus)
                }
            } catch (e: Exception) {
                val msg = when (e) {
                    is CastException -> proxy?.lastUpstreamError?.let { code ->
                        "La web del vídeo se niega a dárselo a la tele (error $code). Suele pasar cuando el enlace ha caducado o la web protege el vídeo. Recarga la página, dale al play y vuelve a intentarlo."
                    } ?: e.message
                    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                        "No se puede llegar al Chromecast (${device.ip})." +
                            if (net.isVpnActive()) " La VPN está bloqueando la red local: activa \"permitir LAN\" en la VPN." else ""
                    else -> "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                diag("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                try { c?.stop() } catch (_: Exception) {}
                main.post {
                    connecting = false
                    if (client == null) panel.visibility = View.GONE
                    updateCastButton()
                    AlertDialog.Builder(this).setTitle("No se pudo enviar").setMessage(msg)
                        .setPositiveButton("Vale", null)
                        .setNeutralButton("Ver diagnóstico") { _, _ -> showDiagnostics() }
                        .show()
                }
            }
        }.start()
    }

    /** Las listas HLS con segmentos fMP4 necesitan avisarse al receptor. */
    private fun detectFmp4(url: String, headers: Map<String, String>): Boolean {
        fun fetch(u: String): String? = try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            CookieManager.getInstance().getCookie(u)?.let { c.setRequestProperty("Cookie", it) }
            c.inputStream.use { String(it.readBytes().take(256 * 1024).toByteArray()) }
        } catch (_: Exception) { null }

        var text = fetch(url) ?: return false
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val variant = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
            text = fetch(URI(url).resolve(variant.trim()).toString()) ?: return false
        }
        return text.contains("#EXT-X-MAP") || Regex("""\.(m4s|mp4|cmfv)(\?|$)""", RegexOption.MULTILINE).containsMatchIn(text)
    }

    private fun ensureProxy(): MediaProxy? {
        val ip = net.localIpv4()?.first ?: return null
        proxy?.let { if (it.baseUrl.contains("//${ip.hostAddress}:")) return it }
        proxy?.close()
        return try {
            MediaProxy(ip) { u -> CookieManager.getInstance().getCookie(u) }.also {
                it.log = ::diag
                diag("Proxy escuchando en ${it.baseUrl}")
                proxy = it
            }
        } catch (_: Exception) { null }
    }

    private fun showStatus(s: CastStatus) {
        val state = when (s.playerState) {
            "PLAYING" -> "Reproduciendo"
            "PAUSED" -> "En pausa"
            "BUFFERING" -> "Cargando…"
            "IDLE" -> if (s.idleReason == "FINISHED") "Terminado" else if (s.idleReason == "ERROR") "Error al reproducir" else "Preparado"
            else -> s.playerState
        }
        val dur = s.duration
        panelStatus.text = if (dur > 0) "$state · ${fmt(s.currentTime)} / ${fmt(dur)}" else "$state · ${fmt(s.currentTime)}"
        playPause.text = if (s.playerState == "PLAYING" || s.playerState == "BUFFERING") "⏸" else "▶"
        if (dur > 0) {
            seek.isEnabled = true
            seek.max = dur.toInt()
            if (!seeking) seek.progress = s.currentTime.toInt()
        } else {
            seek.isEnabled = false
        }
    }

    private fun changeVolume(delta: Double) {
        val c = client ?: return
        val v = (c.lastStatus.volume + delta).coerceIn(0.0, 1.0)
        Thread { try { c.setVolume(v) } catch (_: Exception) {} }.start()
        toast("Volumen Chromecast: ${(v * 100).toInt()}%")
    }

    private fun stopCasting() {
        val c = client
        Thread { c?.stop() }.start()
        if (c == null) onCastEnded(null)
    }

    private fun onCastEnded(reason: String?) {
        client = null
        KeepAliveService.stop(this)
        if (connecting) return
        castDevice = null
        panel.visibility = View.GONE
        KeepAliveService.stop(this)
        proxy?.close()
        proxy = null
        updateCastButton()
        reason?.let { toast(it) }
    }

    // ------------------------------------------------------------------ dispositivos

    private fun askManualIp() {
        val input = EditText(this).apply {
            hint = "192.168.1.50"
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("IP del Chromecast")
            .setMessage("La ves en la app Google Home → dispositivo → Ajustes → Información del dispositivo.")
            .setView(input)
            .setPositiveButton("Añadir") { _, _ ->
                val ip = input.text.toString().trim()
                if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) { toast("IP no válida"); return@setPositiveButton }
                toast("Comprobando $ip…")
                discovery.addManual(ip) { d ->
                    if (d != null) toast("Añadido: ${d.name}")
                    else toast("No responde ningún Chromecast en $ip" + if (net.isVpnActive()) " (¿la VPN bloquea la red local?)" else "")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSaved() {
        val all = discovery.allDevices()
        if (all.isEmpty()) { toast("Todavía no hay Chromecast guardados"); return }
        val labels = all.map { "${if (it.available) "🟢" else "⚪"}  ${it.name} (${it.ip})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chromecast guardados")
            .setItems(labels) { _, w ->
                val d = all[w]
                AlertDialog.Builder(this)
                    .setTitle(d.name)
                    .setMessage("IP: ${d.ip}\nModelo: ${d.model ?: "—"}")
                    .setPositiveButton("Olvidar") { _, _ -> discovery.forget(d) }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
            .show()
    }

    // ------------------------------------------------------------------ diagnóstico

    private val diagLines = java.util.ArrayDeque<String>()
    private var lastProxyLine = ""

    /** Guarda una línea en el registro (las peticiones repetidas del proxy se resumen). */
    fun diag(line: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        synchronized(diagLines) {
            if (line.startsWith("Proxy: TV pide") && line == lastProxyLine) return
            if (line.startsWith("Proxy:")) lastProxyLine = line
            diagLines.addLast("$time $line")
            while (diagLines.size > 400) diagLines.removeFirst()
        }
    }

    private fun showDiagnostics() {
        val text = synchronized(diagLines) { diagLines.joinToString("\n") }.ifEmpty { "Todavía no hay nada registrado. Intenta enviar un vídeo y vuelve aquí." }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico")
            .setView(scroll)
            .setPositiveButton("Copiar último intento") { _, _ ->
                // Sólo desde el último "────": así cabe en el chat.
                val i = text.lastIndexOf("────")
                val last = if (i >= 0) text.substring(text.lastIndexOf('\n', i) + 1) else text
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("diagnostico", last))
                toast("Copiado: pégalo en el chat")
            }
            .setNegativeButton("Cerrar", null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------ utilidades

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmt(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    companion object {
        const val HOME = "https://www.google.com"
        /** Vídeos públicos de prueba (MP4 y HLS). */
        val SAMPLES = listOf(
            MediaCandidate("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", emptyMap(), "hls"),
            MediaCandidate("https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4", emptyMap(), "file"),
        )
    }
}
