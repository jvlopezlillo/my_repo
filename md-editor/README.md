# MD Editor

Editor Markdown para Windows con vista previa en vivo (Electron).

- Izquierda: editor con barra de iconos (negrita, cursiva, tachado, H1–H3, listas, tareas, cita, código, enlace, imagen, tabla, línea)
- Derecha: vista previa en tiempo real con scroll sincronizado
- Abrir / guardar `.md`, exportar a HTML, tema claro/oscuro, divisor redimensionable
- Atajos: Ctrl+B, Ctrl+I, Ctrl+K, Ctrl+S, Ctrl+Mayús+S, Ctrl+O, Ctrl+N, Tab / Mayús+Tab

## Ejecutar (necesita Node.js)

```
npm install
npm start
```

## Crear el instalador de Windows (.exe)

Ejecútalo en Windows:

```
npm run dist
```

El instalador y la versión portable quedan en `dist/`.
