# IPTV TV

App de listas IPTV para Android TV y móvil/tablet (mismo APK) (Kotlin + Jetpack Compose for TV + Media3/ExoPlayer).

- Añade listas **M3U (URL)** o **Xtream Codes** (servidor + usuario + contraseña).
- Descarga los canales y los agrupa según los grupos de la propia lista (`group-title` / categorías Xtream).
- Buscador siempre visible arriba en el inicio; la búsqueda recorre todos los grupos.
- Zapping en reproducción: ↑/↓ (o CH+/CH−) cambia de canal; OK muestra/oculta el banner; Atrás vuelve a la lista.
- Favoritos: toca el corazón de un canal para añadirlo/quitarlo; el primer grupo siempre es «★ Favoritos». En la tele, en reproducción, la tecla Menú o el botón rojo los alterna. Se guardan por lista.
- Grupos: botón «Grupos» para ocultarlos (reversible) o borrarlos de la lista (persiste al actualizar; se puede restaurar). Por lista.
- Proxy SOCKS5 (botón «Proxy»): para canales bloqueados por país, el tráfico puede salir por tu VPS (p. ej. Dante). Se aplica por separado a la descarga de listas y al vídeo; incluye botón de prueba que compara la IP directa y la del proxy.
- Sincronización remota (opcional): gestiona las listas de un dispositivo (p. ej. el Firestick de tu padre) desde una web de administración en tu propio servidor, sin usar la MAC (código de vinculación de 6 cifras). Cambios en los dos sentidos: se pueden editar en el dispositivo y desde la web. Ver [`../iptv-sync-server`](../iptv-sync-server/README.md).
- Enviar a Chromecast (solo móvil/tablet con Google Play Services): botón «Enviar a TV» en el reproductor. Ver la sección *Chromecast* más abajo.
- Sin EPG (por ahora). Las listas se cachean en local; "Actualizar" las vuelve a descargar.

## Móvil / tablet
Mismo APK. En pantallas estrechas los grupos pasan a chips horizontales sobre la lista. En el reproductor: tocar muestra/oculta el banner, deslizar arriba/abajo cambia de canal y hay botones Anterior/Siguiente/Lista.

## Chromecast
Botón **Enviar a TV** en el reproductor (solo móvil/tablet con Google Play Services; no aparece en Android TV / Fire TV).

- **El Chromecast solo reproduce HLS (`.m3u8`).** Antes de enviar, la app comprueba qué sirve realmente el canal y prueba variantes HLS (`.ts` → `.m3u8`, URL Xtream `/live/…`). Un directo MPEG-TS sin versión HLS se convierte a HLS en el móvil (sin recodificar: el códec lo decide el canal; MPEG-2 o audio MP2 no se verían/oirían).
- **Dos caminos:**
  - *Directo* (por defecto): el Chromecast descarga el canal por su cuenta. Rápido y sin gastar batería del móvil; usa la conexión del Chromecast.
  - *Por el móvil*: el móvil descarga el vídeo (con su VPN/proxy) y lo sirve al Chromecast por la Wi-Fi local, con CORS. Se usa si activas «Siempre por el móvil», si el vídeo va por el proxy de la app, para convertir MPEG-TS, o como alternativa si el directo falla. Mantiene un servicio en primer plano con wake lock para seguir con la pantalla apagada.
- **VPN en el móvil:** debe permitir la red local (LAN) para ver el Chromecast.
- **VPN en el Chromecast:** si el Chromecast tiene una VPN sin acceso a la red local, no puede conectar con el móvil (la app lo detecta por la IP que él mismo publica y avisa). Permite la red local en esa VPN o desconéctala.
- **Si el proveedor rechaza (403/429):** se reintenta; suele ser el límite de conexiones simultáneas de la lista. Si un directo en marcha se corta, se reenvía solo (máx. 2 veces).
- **Registro de diagnóstico:** botón *Registro* → Compartir/Copiar. Las direcciones salen sin rutas ni credenciales (sí el dominio del servidor y el nombre de la Wi-Fi del Chromecast).
- **Alternativa universal:** «Duplicar pantalla (Android)» en el selector abre los ajustes de transmitir pantalla del sistema.

## Compilar
- Android Studio: abrir la carpeta `iptv-tv` y ejecutar `app` en un emulador/dispositivo Android TV.
- Sin Android Studio: el workflow `.github/workflows/build-iptv-tv.yml` genera el APK debug (pestaña *Actions* → artefacto), que se instala con `adb install`.

## Lista precargada
En el primer arranque, si no hay listas, la app añade una lista M3U por defecto (una sola vez; si la borras no vuelve). Se define al compilar: `-PdefaultPlaylistUrl=https://…/lista.m3u` (vacío = ninguna). Por defecto apunta al gist del proyecto.

## Servidor de sincronización
El APK puede llevar precargada la dirección de tu servidor: define la variable de repositorio `SYNC_URL` (Settings → Secrets and variables → Actions → Variables) o compila con `-PsyncUrl=https://iptv.tudominio.com`. En el dispositivo: Listas → Sincronización remota → Conectar.

## Siguientes pasos
Últimos canales, EPG, selector de pistas de audio/subtítulos, ordenar grupos, VOD/series de Xtream.
