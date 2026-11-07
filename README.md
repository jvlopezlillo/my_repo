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

- Android 5.0 (API 21) o superior, con Bluetooth LE y Wi‑Fi Direct. El receptor debe poder anunciarse por BLE
  (algunos móviles muy antiguos no pueden; ahí solo podrán actuar de emisor).
- Bluetooth y Wi‑Fi **activados** en ambos (el Wi‑Fi no necesita estar conectado a ninguna red).
- Concede los permisos de «Dispositivos cercanos» (Android 12+/13+) o ubicación (Android ≤ 12).
- Las transferencias siguen en **segundo plano** gracias a un servicio en primer plano (notificación con progreso,
  y botones Aceptar/Rechazar/Cancelar). En Android 13+ conviene conceder el permiso de notificaciones.
- En Android 6–11 el sistema exige además tener la **ubicación activada** para escanear BLE / Wi‑Fi Direct.

## Compilar

```
./gradlew assembleDebug      # APK en app/build/outputs/apk/debug/
```

También hay un workflow de GitHub Actions (`.github/workflows/android.yml`) que compila el APK y lo sube como artefacto.

## Reanudación

Si la transferencia se corta, el receptor conserva lo recibido (en `Android/data/<app>/files/incoming/`, se borra
a los 7 días) y sigue esperando. En el emisor aparece **Reanudar envío**; si la app se reinició, basta con
volver a elegir los mismos archivos y el mismo dispositivo. La transferencia se identifica por rutas + tamaños,
así que solo continúa si los archivos no han cambiado de tamaño. Lo ya enviado no se repite.

## Limitaciones conocidas

- Las carpetas vacías no se transfieren (solo ficheros).
- Al unirse a la red del receptor, Android muestra un diálogo de confirmación en el emisor (obligatorio por el sistema).
