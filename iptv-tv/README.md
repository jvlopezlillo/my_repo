# IPTV TV

App de listas IPTV para Android TV y móvil/tablet (mismo APK) (Kotlin + Jetpack Compose for TV + Media3/ExoPlayer).

- Añade listas **M3U (URL)** o **Xtream Codes** (servidor + usuario + contraseña).
- Descarga los canales y los agrupa según los grupos de la propia lista (`group-title` / categorías Xtream).
- Buscador siempre visible arriba en el inicio; la búsqueda recorre todos los grupos.
- Zapping en reproducción: ↑/↓ (o CH+/CH−) cambia de canal; OK muestra/oculta el banner; Atrás vuelve a la lista.
- Favoritos: toca el corazón de un canal para añadirlo/quitarlo; el primer grupo siempre es «★ Favoritos». En la tele, en reproducción, la tecla Menú o el botón rojo los alterna. Se guardan por lista.
- Sin EPG (por ahora). Las listas se cachean en local; "Actualizar" las vuelve a descargar.

## Móvil / tablet
Mismo APK. En pantallas estrechas los grupos pasan a chips horizontales sobre la lista. En el reproductor: tocar muestra/oculta el banner, deslizar arriba/abajo cambia de canal y hay botones Anterior/Siguiente/Lista.

## Compilar
- Android Studio: abrir la carpeta `iptv-tv` y ejecutar `app` en un emulador/dispositivo Android TV.
- Sin Android Studio: el workflow `.github/workflows/build-iptv-tv.yml` genera el APK debug (pestaña *Actions* → artefacto), que se instala con `adb install`.

## Siguientes pasos
Últimos canales, EPG, selector de pistas de audio/subtítulos, ordenar/ocultar grupos, VOD/series de Xtream.
