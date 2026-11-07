# WhatsApp Backup & Restore (Android, desde Windows)

App de PC (Python + tkinter, sin dependencias externas) que usa ADB por USB.

## Uso
1. Instala Python 3.10+ en Windows. Ejecuta `python run.py` (abre la interfaz).
2. Pulsa **Descargar ADB** (baja platform-tools oficiales de Google).
3. Prepara el móvil (Xiaomi/MIUI 12): activa Depuración USB, "Instalar mediante USB",
   y en WhatsApp haz *Ajustes > Chats > Copia de seguridad > Copiar ahora*.
4. **Hacer BACKUP…** → carpeta vacía. Se guardan chats cifrados, fotos, vídeos, audios,
   documentos, stickers y el APK de WhatsApp, con `manifest.json` y SHA-256.
5. Tras el reset: NO abras WhatsApp. **RESTAURAR…** → instala el APK, devuelve los
   ficheros al móvil. Abre WhatsApp, mismo número, pulsa *Restaurar*.

Modo consola: `python run.py backup D:\bk`, `python run.py restore D:\bk`, `python run.py verify D:\bk`.

## Avanzado: datos privados (`--legacy-apk`)
Con un APK antiguo de WhatsApp (p. ej. 2.11.431) se hace `adb backup` de `/data/data`
(en Android 10 funciona). Es opcional y experimental; el móvil pedirá confirmar sin contraseña.

## Limitaciones
- Probado solo con un móvil simulado (tests); no con un dispositivo real todavía.
- La restauración depende de que WhatsApp acepte el backup local: mismo número y,
  si usas copia cifrada de extremo a extremo, la clave de 64 dígitos/contraseña.
- Tests: `python tests/test_core.py`.
