Primer fichero en el primer repositorio de Jose Vicente Lopez Lillo

# Limpiador para Android

App de Android (Kotlin + Jetpack Compose) para liberar espacio y memoria sin root.

## Qué hace

**Limpieza de archivos** (pestaña *Limpieza*): analiza el almacenamiento y agrupa lo que se puede borrar:

| Categoría | Marcado por defecto |
|---|---|
| Caché de apps (en Android 10 o inferior, también la de otras apps) | Sí |
| Temporales y registros (`.tmp`, `.log`, `LOST.DIR`, `Thumbs.db`…) | Sí |
| Miniaturas (`.thumbnails`, `.thumbdata`) | Sí |
| APKs descargados | Solo los ya instalados o dañados |
| Carpetas vacías | Sí (nunca DCIM, Download, Music…) |
| Archivos duplicados (comparados por contenido) | No — siempre se conserva el más antiguo |
| Archivos grandes (> 50 MB) | No |

Antes de borrar se pide confirmación. La app solo borra dentro del almacenamiento compartido y su propia caché.

**Caché de todas las apps** (Android 11+): usa el diálogo oficial del sistema (`ACTION_CLEAR_APP_CACHE`), la única forma de borrar la caché de otras apps sin root.

**Apps**: lista las apps ordenadas por caché ocupada; al tocar una se abren sus ajustes.

**RAM**: cierra procesos en segundo plano de apps de usuario. El efecto es temporal: Android puede volver a abrirlos.

## Permisos

- **Acceso a todos los archivos** (Android 11+) o almacenamiento (Android 8–10): para buscar y borrar basura.
- **Acceso a datos de uso**: solo para ver la caché de cada app. Se activa en Ajustes.

Por usar estos permisos, la app está pensada para instalación directa (APK), no para Google Play.

## Compilar

- **Desde GitHub**: cada push ejecuta la acción *Compilar APK*. Descarga el APK en la pestaña *Actions → la última ejecución → Artifacts → `limpiador-debug-apk`*.
- **En local**: abre el proyecto en Android Studio, o ejecuta `./gradlew assembleDebug` (requiere JDK 17 y el Android SDK). El APK queda en `app/build/outputs/apk/debug/`.

Requiere Android 8.0 (API 26) o superior.
