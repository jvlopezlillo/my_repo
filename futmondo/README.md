# Futmondo Asistente (Android)

App Android con dos botones para la liga "Hombres libres x":

- **Analizar**: lee plantilla, lesiones, rachas y partidos de la próxima jornada (con cuotas) y propone la mejor formación y once.
- **Revisar mercado**: propone ventas y 0-3 fichajes. Muestra una lista con casillas y **solo pone a la venta lo que marques y confirmes**.

## Reglas que cumple
- Solo **pone a la venta** (nunca venta directa, ofertas, pujas ni cambios de alineación). `FutmondoApi` rechaza cualquier ruta que no esté en su lista blanca; la única de escritura es `/1/market/putonmarket`.
- Contraseña **no guardada**: solo se guarda el token y los ids, cifrados con Android Keystore (AES-GCM). Sin credenciales en el código.
- Resultados en español.
- ⚠ Usa la API **no oficial** de Futmondo: puede romperse y podría ir contra sus condiciones de uso.

## Estructura
- `core/`: lógica en Kotlin/JVM (API, análisis, informes) con pruebas: `./gradlew -PcoreOnly :core:test`
- `app/`: interfaz Android (vistas nativas, sin dependencias externas).
- `.github/workflows/futmondo-android.yml`: compila el APK de depuración en GitHub Actions (artefacto `futmondo-debug-apk`).

## Estado
Probado: lógica del núcleo con datos sintéticos. **No probado**: compilación del módulo `app` y llamadas reales a `api.futmondo.com` (el entorno de desarrollo no tenía Android SDK ni acceso a esa API).
Supuestos por verificar con datos reales: significado exacto de `status` (`injured`, `injured2`, `redcard`, `doubt`, `yellowcard`), que `fitness` son los puntos de las últimas 5 jornadas (0 = no jugó), y que el precio de venta = valor del jugador.
Pendiente: cambio de formación y ofertas recibidas (no capturados; la app no debe ejecutarlos); calendario de más de una jornada.
