# Direct Transfer

App Android para enviar archivos y carpetas de un móvil a otro **sin Internet ni una red Wi‑Fi compartida**.

## Cómo funciona

1. **Descubrimiento (Bluetooth LE).** En el receptor se pulsa *Recibir*: crea una red Wi‑Fi Direct propia
   (SSID `DIRECT-dt-xxxx` y clave aleatoria) y se anuncia por BLE. En el emisor se eligen archivos/carpeta y
   se pulsa *Buscar dispositivos*; el receptor aparece en la lista.
2. **Red propia (Wi‑Fi Direct).** Al elegir el receptor, el emisor lee por GATT (BLE) el SSID, la clave y un
   token de sesión, y se une a esa red como cliente Wi‑Fi.
3. **Transferencia (TCP).** El emisor envía un manifiesto (rutas + tamaños); el receptor muestra un diálogo
   para aceptar o rechazar. Después se transmiten los datos con barra de progreso y porcentaje en ambos lados.

Los ficheros se guardan en `Descargas/DirectTransfer/` conservando la estructura de carpetas.

## Requisitos

- Android 10 (API 29) o superior, con Bluetooth LE y Wi‑Fi Direct.
- Bluetooth y Wi‑Fi **activados** en ambos (el Wi‑Fi no necesita estar conectado a ninguna red).
- Concede los permisos de «Dispositivos cercanos» (Android 12+/13+) o ubicación (Android ≤ 12).
- La app debe estar abierta en primer plano en ambos dispositivos durante la transferencia.

## Compilar

```
./gradlew assembleDebug      # APK en app/build/outputs/apk/debug/
```

También hay un workflow de GitHub Actions (`.github/workflows/android.yml`) que compila el APK y lo sube como artefacto.

## Limitaciones conocidas

- Las carpetas vacías no se transfieren (solo ficheros).
- No hay reanudación de transferencias interrumpidas.
- Al unirse a la red del receptor, Android muestra un diálogo de confirmación en el emisor (obligatorio por el sistema).
