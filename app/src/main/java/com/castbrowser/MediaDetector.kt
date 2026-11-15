package com.castbrowser

import java.util.concurrent.CopyOnWriteArrayList

/** Vídeo que se puede enviar al Chromecast. */
data class MediaCandidate(
    val url: String,
    val headers: Map<String, String>,
    val kind: String, // hls | dash | file
    /** Para pruebas: enviar siempre a través del móvil. */
    val forceProxy: Boolean = false,
) {
    /** Los manifiestos "cenc" (Common Encryption) van cifrados con DRM. */
    val looksEncrypted: Boolean
        get() = url.substringBefore('?').lowercase().let { it.contains("/cenc") || it.contains("cenc.mpd") || it.contains("/drm/") }

    val contentType: String
        get() = when (kind) {
            "hls" -> "application/x-mpegURL"
            "dash" -> "application/dash+xml"
            else -> if (url.substringBefore('?').lowercase().endsWith(".webm")) "video/webm" else "video/mp4"
        }

    val label: String
        get() {
            val path = url.substringBefore('?').substringAfterLast('/').ifEmpty { url }
            val host = url.substringAfter("://").substringBefore('/')
            return "${kind.uppercase()} · $path · $host" + if (forceProxy) " (a través del móvil)" else ""
        }
}

/** Lo que el JavaScript inyectado informa sobre el <video> de la página. */
data class PageVideo(
    val src: String,
    val playing: Boolean,
    val time: Double,
    val duration: Double,
    val live: Boolean,
    val title: String,
    val page: String,
)

object MediaDetector {
    private val manifestHls = Regex("""(\.m3u8($|[?#])|[?&/]format=m3u8|/hls/.*master|\.m3u8/)""", RegexOption.IGNORE_CASE)
    private val manifestDash = Regex("""(\.mpd($|[?#])|[?&/]format=mpd)""", RegexOption.IGNORE_CASE)
    private val fileVideo = Regex("""\.(mp4|m4v|webm|mov|mkv)($|[?#])""", RegexOption.IGNORE_CASE)
    // Trozos de vídeo que no sirven por sí solos.
    private val segment = Regex("""(\.(ts|m4s|aac|vtt)($|[?#])|/seg(ment)?[-_]?\d|/chunk[-_]?\d|[?&](range|bytestart)=|/frag\(|init\.mp4)""", RegexOption.IGNORE_CASE)

    fun classify(url: String): String? {
        if (!url.startsWith("http")) return null
        return when {
            manifestHls.containsMatchIn(url) -> "hls"
            manifestDash.containsMatchIn(url) -> "dash"
            segment.containsMatchIn(url) -> null
            fileVideo.containsMatchIn(url) -> "file"
            else -> null
        }
    }

    // Peticiones que merece la pena enseñar al usuario si no se encuentra el vídeo.
    private val boring = Regex("""\.(js|css|png|jpe?g|gif|svg|webp|ico|woff2?|ttf|map|html?)($|[?#])""", RegexOption.IGNORE_CASE)
    private val mediaHint = Regex(
        """(\.(ts|m4s|m4v|mp4|webm|aac|mp3|m3u8|mpd|flv)($|[?#])|/(hls|dash|live|stream|video|media|manifest|playlist|chunk|seg)|m3u8|mpd|manifest|playlist)""",
        RegexOption.IGNORE_CASE,
    )

    fun isInteresting(url: String): Boolean =
        url.startsWith("http") && !boring.containsMatchIn(url) && mediaHint.containsMatchIn(url)

    /** ¿Es un trozo suelto de vídeo (que no sirve por sí solo)? */
    fun looksLikeSegment(url: String): Boolean = segment.containsMatchIn(url)

    /** Qué es lo que devuelve una dirección, según su cabecera Content-Type y sus primeros bytes. */
    fun kindFromContent(contentType: String?, head: String): String? {
        val ct = contentType.orEmpty().lowercase()
        val t = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("#EXTM3U") -> "hls"
            Regex("""^(<\?xml[^>]*>\s*)?(<!--[\s\S]*?-->\s*)*<MPD[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "dash"
            ct.contains("mpegurl") -> "hls"
            ct.contains("dash+xml") -> "dash"
            ct.startsWith("video/") || ct.startsWith("audio/") -> "file"
            else -> null
        }
    }

    private val playlistNames = listOf(
        "playlist.m3u8", "index.m3u8", "master.m3u8", "chunklist.m3u8", "stream.m3u8",
        "live.m3u8", "manifest.m3u8", "video.m3u8", "prog_index.m3u8",
    )

    /** A partir de un trozo de vídeo, direcciones donde suele estar su lista de reproducción. */
    fun siblingPlaylists(segmentUrl: String): List<String> {
        val query = segmentUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val dir = segmentUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val parent = dir.trimEnd('/').substringBeforeLast('/') + "/"
        val out = LinkedHashSet<String>()
        for (d in listOf(dir, parent)) {
            if (!d.contains("://") || d.substringAfter("://").isEmpty() || d.endsWith("://")) continue
            for (n in playlistNames) {
                if (query.isNotEmpty()) out.add(d + n + query)
                out.add(d + n)
            }
        }
        return out.toList()
    }

    /** JavaScript que se inyecta en todos los marcos (iframes incluidos). */
    val SCRIPT = """
(function(){
  if (window.__castHooked) return; window.__castHooked = true;
  function report(v, ev){
    try {
      var d = v.duration;
      CastBridge.onMedia(JSON.stringify({
        ev: ev,
        src: v.currentSrc || v.src || '',
        playing: !v.paused && !v.ended && v.readyState > 1,
        t: v.currentTime || 0,
        d: isFinite(d) ? d : 0,
        live: d === Infinity,
        title: document.title || '',
        page: location.href,
        top: window === window.top,
        video: v.tagName === 'VIDEO'
      }));
    } catch (e) {}
  }
  ['play','playing','pause','ended','loadedmetadata','timeupdate'].forEach(function(n){
    document.addEventListener(n, function(e){
      var v = e.target;
      if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'AUDIO')) return;
      if (n === 'timeupdate') { var now = Date.now(); if (v.__castT && now - v.__castT < 2000) return; v.__castT = now; }
      report(v, n);
    }, true);
  });
  // Detecta listas HLS/DASH por su CONTENIDO aunque su dirección no acabe en .m3u8 / .mpd.
  var seen = {};
  function abs(u){ try { return new URL(u, location.href).href; } catch (e) { return u; } }
  function reportStream(u, kind){
    try {
      u = abs(u);
      if (!/^https?:/.test(u) || seen[u]) return;
      seen[u] = 1;
      CastBridge.onStream(u, kind, location.href);
    } catch (e) {}
  }
  function check(u, text){
    var t = String(text).replace(/^\uFEFF/, '').replace(/^\s+/, '');
    if (t.indexOf('#EXTM3U') === 0) reportStream(u, 'hls');
    else if (/^(<\?xml[^>]*>\s*)?(<!--[\s\S]*?-->\s*)*<MPD[\s>]/i.test(t)) reportStream(u, 'dash');
  }
  function bytesToText(b){
    var s = '';
    for (var i = 0; i < b.length && i < 256; i++) s += String.fromCharCode(b[i]);
    return s;
  }
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function(){
      var args = arguments;
      return origFetch.apply(this, args).then(function(res){
        try {
          var u = res.url || (typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url));
          var ct = ((res.headers && res.headers.get('content-type')) || '').toLowerCase();
          if (/mpegurl/.test(ct)) reportStream(u, 'hls');
          else if (/dash\+xml/.test(ct)) reportStream(u, 'dash');
          else if (/(text\/plain|octet-stream|\/xml|^$)/.test(ct) && !/\.(ts|m4s|mp4|aac|mp3|webm|jpe?g|png|gif)(\?|$)/i.test(u || '')) {
            var c = res.clone();
            var rd = c.body && c.body.getReader && c.body.getReader();
            if (rd) rd.read().then(function(r){
              if (r && r.value) check(u, bytesToText(r.value));
              try { rd.cancel(); } catch (e) {}
            }).catch(function(){});
          }
        } catch (e) {}
        return res;
      });
    };
  }
  var xhrOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m, u){ this.__castUrl = u; return xhrOpen.apply(this, arguments); };
  var xhrSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(){
    this.addEventListener('load', function(){
      try {
        var u = this.responseURL || this.__castUrl;
        var ct = (this.getResponseHeader('content-type') || '').toLowerCase();
        if (/mpegurl/.test(ct)) { reportStream(u, 'hls'); return; }
        if (/dash\+xml/.test(ct)) { reportStream(u, 'dash'); return; }
        if (this.responseType === '' || this.responseType === 'text') {
          if (this.responseText) check(u, this.responseText.substring(0, 256));
        } else if (this.responseType === 'arraybuffer' && this.response && !/\.(ts|m4s|mp4|aac|mp3|webm)(\?|$)/i.test(u || '')) {
          check(u, bytesToText(new Uint8Array(this.response, 0, Math.min(256, this.response.byteLength))));
        }
      } catch (e) {}
    });
    return xhrSend.apply(this, arguments);
  };

  // ---- Pantalla completa del vídeo, propia del navegador (no depende del botón de la web).
  // Saca el <video> a la raíz de la página y lo pone a pantalla completa con el formato elegido.
  // Si el vídeo está en un iframe, el iframe se expande desde la página que lo contiene.
  var FS = { on: false, video: null, parent: null, next: null, style: null, controls: false,
             mode: 'fit', htmlStyle: null, bodyStyle: null, tag: null, frames: [] };
  var RATIOS = { r43: 4 / 3, r169: 16 / 9, r195: 19.5 / 9, r209: 20 / 9 };
  function fsBox(mode, W, H){
    var w = W, h = H, fit = 'contain';
    if (mode === 'stretch') fit = 'fill';
    else if (mode === 'fill') fit = 'cover';
    else if (RATIOS[mode]) {
      var r = RATIOS[mode];
      if (W / H > r) { h = H; w = H * r; } else { w = W; h = W / r; }
      fit = 'fill';
    }
    return { w: w, h: h, left: (W - w) / 2, top: (H - h) / 2, fit: fit };
  }
  function allVideos(root, out){
    var vs = root.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) out.push(vs[i]);
    var all = root.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) { if (all[j].shadowRoot) allVideos(all[j].shadowRoot, out); }
    return out;
  }
  function pickVideo(){
    var vs = allVideos(document, []), best = null, score = -1;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], r = v.getBoundingClientRect();
      var s = (!v.paused && !v.ended ? 1e9 : 0) + r.width * r.height;
      if (s > score) { score = s; best = v; }
    }
    return best;
  }
  function imp(el, p, val){ el.style.setProperty(p, val, 'important'); }
  function fsLayout(){
    var v = FS.video; if (!v) return;
    var b = fsBox(FS.mode, window.innerWidth, window.innerHeight);
    v.classList.add('__cast_fs_v');
    imp(v, 'position', 'fixed'); imp(v, 'z-index', '2147483647'); imp(v, 'background', '#000');
    imp(v, 'margin', '0'); imp(v, 'padding', '0'); imp(v, 'border', '0');
    imp(v, 'max-width', 'none'); imp(v, 'max-height', 'none'); imp(v, 'transform', 'none');
    imp(v, 'width', b.w + 'px'); imp(v, 'height', b.h + 'px');
    imp(v, 'left', b.left + 'px'); imp(v, 'top', b.top + 'px'); imp(v, 'object-fit', b.fit);
  }
  function fsEnter(){
    var v = (FS.on && FS.video) ? FS.video : pickVideo();
    if (!v) return false;
    if (!FS.on) {
      FS.on = true; FS.video = v; FS.parent = v.parentNode; FS.next = v.nextSibling;
      FS.style = v.getAttribute('style'); FS.controls = v.controls;
      FS.htmlStyle = document.documentElement.getAttribute('style');
      FS.bodyStyle = document.body.getAttribute('style');
      try { document.body.appendChild(v); } catch (e) {}
      imp(document.documentElement, 'overflow', 'hidden'); imp(document.documentElement, 'background', '#000');
      imp(document.body, 'overflow', 'hidden'); imp(document.body, 'background', '#000');
      try {
        FS.tag = document.createElement('style');
        FS.tag.textContent = 'html > body > :not(.__cast_fs_v){visibility:hidden !important}';
        document.head.appendChild(FS.tag);
      } catch (e) {}
      v.controls = false;
      window.addEventListener('resize', fsLayout);
      window.addEventListener('orientationchange', fsLayout);
    }
    fsLayout();
    try { CastBridge.onFs(true); } catch (e) {}
    if (window !== window.top) { try { parent.postMessage({ __castFSUp: 1 }, '*'); } catch (e) {} }
    return true;
  }
  function restoreAttr(el, name, val){ if (val === null || val === undefined) el.removeAttribute(name); else el.setAttribute(name, val); }
  function fsRestoreFrames(){
    while (FS.frames.length) {
      var f = FS.frames.pop();
      restoreAttr(f.el, 'style', f.style);
    }
  }
  function fsExit(){
    if (FS.on) {
      var v = FS.video;
      window.removeEventListener('resize', fsLayout);
      window.removeEventListener('orientationchange', fsLayout);
      try { if (FS.tag && FS.tag.parentNode) FS.tag.parentNode.removeChild(FS.tag); } catch (e) {}
      v.classList.remove('__cast_fs_v');
      restoreAttr(v, 'style', FS.style);
      v.controls = FS.controls;
      try {
        if (FS.parent) {
          if (FS.next && FS.next.parentNode === FS.parent) FS.parent.insertBefore(v, FS.next);
          else FS.parent.appendChild(v);
        }
      } catch (e) {}
      restoreAttr(document.documentElement, 'style', FS.htmlStyle);
      restoreAttr(document.body, 'style', FS.bodyStyle);
      FS.on = false; FS.video = null;
    }
    fsRestoreFrames();
  }
  // El vídeo está en un iframe de esta página: se expande el iframe (y se sueltan los recortes de sus contenedores).
  function fsExpandFrame(source){
    var frames = document.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      if (frames[i].contentWindow !== source) continue;
      var el = frames[i];
      for (var a = el; a && a !== document.documentElement; a = a.parentElement) {
        FS.frames.push({ el: a, style: a.getAttribute('style') });
        if (a !== el) {
          imp(a, 'overflow', 'visible'); imp(a, 'transform', 'none'); imp(a, 'filter', 'none');
          imp(a, 'contain', 'none'); imp(a, 'clip-path', 'none');
        }
      }
      imp(el, 'position', 'fixed'); imp(el, 'left', '0'); imp(el, 'top', '0');
      imp(el, 'width', '100vw'); imp(el, 'height', '100vh'); imp(el, 'max-width', 'none'); imp(el, 'max-height', 'none');
      imp(el, 'border', '0'); imp(el, 'margin', '0'); imp(el, 'z-index', '2147483647'); imp(el, 'background', '#000');
      imp(document.documentElement, 'overflow', 'hidden');
      return;
    }
  }
  function fsCmd(name, arg){
    var v = FS.video || pickVideo(); if (!v) return;
    try {
      if (name === 'toggle') { if (v.paused) v.play(); else v.pause(); }
      else if (name === 'seek') {
        var t = v.currentTime + (arg || 0);
        if (isFinite(v.duration)) t = Math.min(t, v.duration);
        v.currentTime = Math.max(0, t);
      }
    } catch (e) {}
  }
  function fsForward(msg){
    for (var i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage(msg, '*'); } catch (e) {} }
  }
  window.__castFS = {
    enter: function(mode){ if (mode) FS.mode = mode; var ok = fsEnter(); fsForward({ __castFS: 'enter', mode: FS.mode }); return ok; },
    mode: function(mode){ FS.mode = mode; fsLayout(); fsForward({ __castFS: 'mode', mode: mode }); },
    exit: function(){ fsExit(); fsForward({ __castFS: 'exit' }); },
    cmd: function(name, arg){ fsCmd(name, arg); fsForward({ __castFS: 'cmd', name: name, arg: arg }); },
    box: fsBox
  };
  window.addEventListener('message', function(e){
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__castFSUp) {
      fsExpandFrame(e.source);
      if (window !== window.top) { try { parent.postMessage({ __castFSUp: 1 }, '*'); } catch (x) {} }
      return;
    }
    if (!d.__castFS) return;
    if (d.__castFS === 'enter') { if (d.mode) FS.mode = d.mode; fsEnter(); fsForward(d); }
    else if (d.__castFS === 'mode') { FS.mode = d.mode; fsLayout(); fsForward(d); }
    else if (d.__castFS === 'exit') { fsExit(); fsForward(d); }
    else if (d.__castFS === 'cmd') { fsCmd(d.name, d.arg); fsForward(d); }
  });

  function pauseAll(){
    document.querySelectorAll('video,audio').forEach(function(v){ try { v.pause(); } catch(e){} });
    for (var i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage({__castPause:1}, '*'); } catch(e){} }
  }
  window.__castPauseAll = pauseAll;
  window.addEventListener('message', function(e){ if (e.data && e.data.__castPause) pauseAll(); });
})();
""".trimIndent()
}

/** Guarda las URL de vídeo vistas en la red durante la página actual. */
class CaptureLog {
    private val items = CopyOnWriteArrayList<MediaCandidate>()

    fun add(url: String, headers: Map<String, String>) {
        val kind = MediaDetector.classify(url) ?: return
        if (items.any { it.url == url }) return
        items.add(MediaCandidate(url, headers, kind))
        while (items.size > 40) items.removeAt(0)
    }

    private val recentUrls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    /** Una lista HLS/DASH reconocida por su contenido (no por su dirección). */
    fun addKnown(url: String, headers: Map<String, String>, kind: String) {
        if (items.any { it.url == url }) return
        items.add(MediaCandidate(url, headers, kind))
        while (items.size > 40) items.removeAt(0)
    }

    /** Anota una petición que podría ser de vídeo, para enseñarla si no se encuentra nada. */
    fun noteRequest(url: String, headers: Map<String, String>) {
        if (!MediaDetector.isInteresting(url) || recentUrls.any { it.first == url }) return
        recentUrls.add(url to headers)
        while (recentUrls.size > 40) recentUrls.removeAt(0)
    }

    /** Las peticiones de interés, de la más reciente a la más antigua. */
    fun recent(): List<Pair<String, Map<String, String>>> = recentUrls.reversed()

    fun clear() { items.clear(); recentUrls.clear() }

    fun headersFor(url: String): Map<String, String>? = items.firstOrNull { it.url == url }?.headers

    /** Candidatos ordenados de más a menos probable. */
    fun candidates(video: PageVideo?): List<MediaCandidate> {
        val out = mutableListOf<MediaCandidate>()
        val src = video?.src.orEmpty()
        if (src.startsWith("http")) {
            val kind = MediaDetector.classify(src) ?: "file"
            out.add(MediaCandidate(src, headersFor(src) ?: emptyMap(), kind))
        }
        // La primera lista HLS suele ser la "master"; luego DASH; luego ficheros (el último visto primero).
        out.addAll(items.filter { it.kind == "hls" })
        out.addAll(items.filter { it.kind == "dash" })
        out.addAll(items.filter { it.kind == "file" }.reversed())
        return out.distinctBy { it.url }
    }

    val isEmpty: Boolean get() = items.isEmpty()
}
