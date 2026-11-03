Primer fichero en el primer repositorio de Jose Vicente Lopez Lillo

# CastBrowser – navegador para Android pensado para Chromecast

Navegador mínimo cuyo único objetivo es enviar al Chromecast el vídeo que estés viendo.

## Cómo instalarlo
1. Desde el móvil, abre la pestaña **Releases** del repositorio y descarga `CastBrowser.apk`
   (lo compila GitHub Actions automáticamente en cada cambio).
2. Ábrelo y permite "instalar apps de origen desconocido" cuando Android lo pida.

## Aparatos compatibles
- **Chromecast** y teles con Chromecast integrado (Android TV, Google TV).
- **DLNA**: Smart TV (Samsung, LG, Sony…), Kodi, VLC, BubbleUPnP. El vídeo va a través del móvil
  porque estas teles no pueden usar las cookies de la web.
- **CastBrowser TV**: nuestra app receptora para Android TV / Mi Box (`CastBrowserTV.apk` en
  Releases). Reproduce con ExoPlayer, usa las cookies y cabeceras del navegador y arranca sola al
  encender la TV. En Android 10+ conviene darle el permiso "mostrar sobre otras apps" desde su
  pantalla, para que el vídeo se abra solo.

### Con VPN y la app de la tele: el móvil "empuja" el vídeo
Con la VPN puesta, Android suele impedir que la tele conecte *con* el móvil (así fallan Chromecast y
DLNA, que necesitan pedirle el vídeo al móvil), pero el móvil sí puede conectar *con* la tele. Con la app
de la tele, el móvil baja el vídeo HLS (por la VPN, con sus cookies), lo convierte a MPEG-TS y se lo envía
por la conexión que él abre; la tele lo reproduce desde su propio puerto local. Se usa solo, sin
configurar nada, y para Chromecast/DLNA hay que permitir la red local (LAN) en la VPN.

### Receptor DLNA en la tele (CastBrowser TV)
La app de la tele (Mi Box, Fire TV Stick…) también es un **receptor DLNA**: aparece en la red como
"<nombre de la tele> (DLNA)" y acepta vídeos de cualquier app DLNA (BubbleUPnP, Web Video Caster,
VLC, Windows "Transmitir a dispositivo"…), no sólo de este navegador. Soporta reproducir, pausa,
buscar, volumen, silencio y avisos de estado (eventos UPnP). Ver `DlnaCore.kt`.

## Protección anti-publicidad
Activada por defecto (menú ⋮ para quitarla si una web falla). Bloquea: ventanas emergentes,
enlaces que abren otras apps (Booking, AliExpress, Chrome, Play Store…), saltos automáticos a otra
web sin que toques nada, y una lista de redes publicitarias conocidas. No es un bloqueador perfecto:
las redes de anuncios cambian de dominio. Los bloqueos quedan anotados en Diagnóstico.

## Cómo se usa
- Navega normalmente. En cuanto un vídeo empieza a reproducirse y hay algún Chromecast
  disponible, aparece el **botón de Chromecast** flotante (abajo a la derecha).
- Un Chromecast: un toque y se envía. Varios: sale la lista para elegir.
  Mantén pulsado el botón para ver siempre la lista.
- Mientras se envía: barra de progreso, pausa, ±10/30 s, volumen (también con los botones
  de volumen del móvil) y detener.
- Si una página tiene varios vídeos (p. ej. anuncios), en la lista aparece **Elegir vídeo**.

## Sin Wi-Fi: compartiendo los datos del móvil
Activa el punto de acceso del móvil y conecta la tele (Mi Box, Smart TV o Chromecast) a él.
El navegador detecta la red del punto de acceso, busca ahí las teles y les sirve el vídeo
desde el móvil. Un Chromecast tiene que configurarse antes con la app Google Home en esa red.

## Con VPN
La búsqueda y la conexión se hacen atando los sockets a la red **Wi-Fi** en vez de a la VPN,
y además:
- mDNS por multicast enviado por la interfaz Wi-Fi, NsdManager del sistema, y si no aparece
  nada, escaneo de la subred Wi-Fi.
- Los Chromecast encontrados se **guardan por IP**: con la VPN puesta se comprueban
  directamente aunque falle la búsqueda.
- Menú ⋮ → **Añadir Chromecast por IP** por si nada de lo anterior funciona.
- Modo de envío **automático**: con VPN activa, el vídeo pasa por el móvil (que hace de proxy
  con tus cookies y por la VPN), así funciona aunque el vídeo sólo sea accesible por la VPN.

Si tu VPN bloquea totalmente la red local, activa en ella la opción tipo
"Permitir acceso a la LAN" / "Excluir IPs privadas" (WireGuard, NordVPN, Proton, etc. la tienen).

## Limitaciones
- Vídeos con DRM (Netflix, Disney+, HBO…) y YouTube no se pueden enviar así: no exponen
  una dirección de vídeo normal.
- Si el vídeo pasa por el móvil, el móvil debe seguir encendido y en la misma Wi-Fi.
