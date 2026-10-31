# Remote_Android

Aplicación web para controlar en remoto un Amazon Fire TV Stick (Fire OS) vía ADB sobre Tailscale.
El puerto 5555 nunca se expone a internet: todo va por la tailnet.

## Fase 0 - validación (estado actual)

Ejecuta en **tu PC** (con `adb` y `scrcpy` instalados, y en la misma tailnet que el Fire Stick):

```bash
cd Remote_Android/phase0
python3 validate.py --host 100.x.x.x            # IP de Tailscale del Fire Stick
# opciones útiles:
#   --apps paquete.extra ...    probar más apps
#   --scrcpy-server RUTA        si no se autodetecta scrcpy-server (misma versión que scrcpy)
#   --non-interactive           sin preguntas (solo compara capturas antes/después)
#   --skip scrcpy               saltar un bloque (screencap | input | scrcpy)
```

Durante la prueba de entrada, mira la tele: el script te preguntará si el foco, el volumen,
el tap y el scroll reaccionaron. Al terminar imprime el resumen con el diseño recomendado y
guarda `phase0/out/report.json` y `phase0/out/screencap.png` (ignorados por git, contienen tu IP).

Pega el resumen en la conversación para decidir el diseño final.
