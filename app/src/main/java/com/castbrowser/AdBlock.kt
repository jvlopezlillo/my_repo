package com.castbrowser

/** Reglas de la protección anti-publicidad (sin código de Android para poder probarlas). */
object AdBlock {
    /** Redes publicitarias y de ventanas emergentes conocidas; se bloquean ellas y sus subdominios. */
    private val adHosts = setOf(
        // Google / grandes redes
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net", "rubiconproject.com",
        "pubmatic.com", "openx.net", "adform.net", "smartadserver.com", "moatads.com", "yieldmo.com",
        "advertising.com", "zedo.com",
        // Contenido patrocinado
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        // Ventanas emergentes y tráfico de baja calidad
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com", "juicyads.com",
        "trafficjunky.net", "trafficstars.com", "clickadu.com", "hilltopads.net", "onclickads.net",
        "adcash.com", "a-ads.com", "adsterra.com", "popunder.net", "tsyndicate.com", "bidvertiser.com",
        "adf.ly",
    )

    /** true si el host (o un dominio padre suyo) es una red publicitaria conocida. */
    fun isAdHost(host: String?): Boolean {
        var h = host?.lowercase()?.trim('.') ?: return false
        while (true) {
            if (h in adHosts) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    private val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    /** Dominio "de la web" de un host: www.ejemplo.com → ejemplo.com; a.b.ejemplo.co.uk → ejemplo.co.uk. */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trim('.')
        if (Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h) || !h.contains('.')) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val takeThree = labels.last().length == 2 && labels[labels.size - 2] in secondLevel
        return labels.takeLast(if (takeThree) 3 else 2).joinToString(".")
    }

    /** ¿Es un salto a otra web distinta de la actual? */
    fun isCrossSite(fromHost: String?, toHost: String?): Boolean {
        if (fromHost.isNullOrEmpty() || toHost.isNullOrEmpty()) return false
        return registrableDomain(fromHost) != registrableDomain(toHost)
    }
}
