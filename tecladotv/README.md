# Teclado TV — escribe en el Mi Box 3 desde el móvil

Dos apps:

| APK | Dónde se instala | Qué hace |
|---|---|---|
| `TecladoTV-Receptor-para-MiBox.apk` | Mi Box 3 | Se instala como **teclado de la TV** («Teclado del móvil»). Recibe por Wi-Fi lo que escribes y lo mete directamente en el campo de texto seleccionado. |
| `TecladoTV-Movil.apk` | Móvil | Cada tecla que pulsas se envía al instante a la TV. |

Las APK se compilan solas en GitHub: pestaña **Releases** del repositorio (o en *Actions → artefactos*).

## Por qué las apps genéricas fallan

Las apps de mando (Google TV, Mi Remote…) no escriben en el campo: envían el texto al
«servicio de mando» de la TV, que a su vez se lo pasa al teclado de Google de la TV.
Si la app que tienes abierta usa otro teclado, un campo de texto poco estándar, o la
versión de ese servicio en el Mi Box 3 es antigua, la cadena se rompe y el texto no llega.

Aquí **nuestra app es el teclado de la TV**, así que escribe directamente en el campo con
el mismo mecanismo que usa cualquier teclado de Android. No hay intermediarios.

## Instalación

1. **En el Mi Box 3**: instala `TecladoTV-Receptor-para-MiBox.apk`
   (con un USB y un explorador de archivos, o con la app *Downloader* usando el enlace de la release).
   Si lo pide, permite «orígenes desconocidos».
2. Abre **Teclado TV Receptor** en la TV:
   - Pulsa **1. Activar teclado** y activa «Teclado del móvil» (Android avisa de que un teclado
     puede leer lo que escribes: es normal en cualquier teclado; esta app solo escucha en tu red local).
   - Pulsa **2. Elegir como teclado** y selecciona «Teclado del móvil».
   - La pantalla debe decir «✅ Todo listo» y muestra la IP de la TV.
3. **En el móvil**: instala `TecladoTV-Movil.apk`, ábrela y toca tu TV en la lista
   (o escribe la IP que muestra la TV). Móvil y TV deben estar en la misma Wi-Fi.

## Uso

- Selecciona con el mando un campo de texto en la TV (buscador de YouTube, etc.). En la TV aparece
  una barra fina «📱 Escribe desde el móvil» y en el móvil se abre el teclado automáticamente.
- Escribe: cada letra aparece al momento. Funcionan acentos, ñ, emojis, autocorrector y dictado.
- **Enter** en el móvil = Buscar / Ir / Enviar en la TV.
- Si una app hace cosas raras con el autocorrector, marca **Letra a letra**.
- **Volver al teclado normal de la TV**: botón «Usar el teclado normal de la TV» en el móvil, o el
  botón **Menú (≡)** del mando mientras se ve la barra.

### Alternativa por ADB (opcional)
Si los ajustes de la TV no dejan activar el teclado:
```
adb connect IP_DE_LA_TV
adb shell ime enable dev.tecladotv.receptor/.PhoneKeyboardService
adb shell ime set dev.tecladotv.receptor/.PhoneKeyboardService
```

## Detalles técnicos

- Conexión TCP directa en el puerto **48900**, mensajes JSON por líneas, sin servidores externos.
- La TV se anuncia por mDNS (`_tecladotv._tcp`); el móvil además prueba todas las IP de la red
  por si el router bloquea los anuncios.
- El móvil envía solo la diferencia (borrados + texto nuevo), así que el autocorrector o mover
  el cursor no desincronizan. Si la app de la TV cambia el texto (por ejemplo, lo borra al buscar),
  la TV avisa al móvil y este se actualiza.
- Limitación: las apps que dibujan su propio teclado en pantalla y no usan el teclado del sistema
  no reciben texto de ningún teclado (tampoco del de Google).

Compilar a mano: `./gradlew assembleRelease` (JDK 17 y Android SDK 34).
