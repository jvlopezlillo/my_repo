package com.castbrowser

import java.util.concurrent.CopyOnWriteArrayList

/** Vídeo que se puede enviar al Chromecast. */
data class MediaCandidate(
    val url: String,
    val headers: Map<String, String>,
    val kind: String, // hls | dash | file
) {
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
            return "${kind.uppercase()} · $path · $host"
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

    fun clear() = items.clear()

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
