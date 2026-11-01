Primer fichero en el primer repositorio de Jose Vicente Lopez Lillo

# CastBrowser – navegador para Android pensado para Chromecast

Navegador mínimo cuyo único objetivo es enviar al Chromecast el vídeo que estés viendo.

## Cómo instalarlo
1. Desde el móvil, abre la pestaña **Releases** del repositorio y descarga `CastBrowser.apk`
   (lo compila GitHub Actions automáticamente en cada cambio).
2. Ábrelo y permite "instalar apps de origen desconocido" cuando Android lo pida.

## Cómo se usa
- Navega normalmente. En cuanto un vídeo empieza a reproducirse y hay algún Chromecast
  disponible, aparece el **botón de Chromecast** flotante (abajo a la derecha).
- Un Chromecast: un toque y se envía. Varios: sale la lista para elegir.
  Mantén pulsado el botón para ver siempre la lista.
- Mientras se envía: barra de progreso, pausa, ±10/30 s, volumen (también con los botones
  de volumen del móvil) y detener.
- Si una página tiene varios vídeos (p. ej. anuncios), en la lista aparece **Elegir vídeo**.

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
