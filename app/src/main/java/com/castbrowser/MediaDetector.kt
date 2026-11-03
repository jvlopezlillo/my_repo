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
