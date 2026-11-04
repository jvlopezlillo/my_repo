# FileCompare

Comparador de ficheros y carpetas para **Windows y Linux** (Python + PySide6/Qt).

## Uso

```bash
pip install -r requirements.txt
python main.py                      # abre la ventana vacía
python main.py CARPETA_A CARPETA_B  # compara dos carpetas
python main.py FICHERO_A FICHERO_B  # compara dos ficheros de texto
```

También: `Archivo → Comparar carpetas (Ctrl+D)` / `Comparar ficheros (Ctrl+F)`.

### Comparación de carpetas
- Recorre recursivamente ambas carpetas y compara el **contenido** byte a byte, en un hilo aparte
  con **barra de progreso y botón Cancelar** (también al copiar).
- Cada fila muestra a la izquierda y a la derecha su propio nombre; el lado que no existe queda vacío en la misma fila
  (subcarpetas primero, luego ficheros). Colores: idéntico (verde), diferente (naranja), tipo distinto (rojo); lo que
  está solo en un lado se deja en blanco.
- Flechas **→ / ←** en cada fila copian el fichero o carpeta completo al otro lado, machacando el destino
  (pide confirmación; se puede desactivar). Al copiar una **carpeta, el destino queda idéntico al origen**:
  se sobrescribe lo distinto, se añade lo que falta y **se elimina lo que sobra** (la confirmación indica cuántos
  ficheros se borrarán). Lo que coincida con los patrones de «Ignorar» ni se copia ni se borra.
- Botones **✕** en cada lado eliminan el fichero o la carpeta de ese lado (siempre piden confirmación; es definitivo).
- **Doble clic** en un fichero lo abre en una pestaña con los dos lados, izquierda y derecha.
- Opciones: «Ignorar espacios» (espacios/tabuladores y fin de línea), «Ignorar fin de línea» (LF = CRLF),
  «Sólo diferencias» y patrones a ignorar (por defecto `.git, __pycache__`).

### Comparación de texto
- Paneles **editables** y alineados, con numeración de líneas y bloques distintos resaltados
  (rojo: sólo izquierda, verde: sólo derecha, naranja: modificado). Dentro de las líneas modificadas se resaltan
  las palabras que cambian. Se puede teclear también en las filas grises de relleno.
- Al dejar de teclear se recalcula la comparación. En el canal central, cada bloque tiene flechas **→ / ←**
  para copiarlo en cualquier sentido.
- Deshacer/Rehacer (Ctrl+Z / Ctrl+Y), siguiente/anterior diferencia (F8 / Shift+F8), «Ignorar espacios»
  y guardado independiente de cada lado (**Ctrl+1** izquierda, **Ctrl+2** derecha).
- Se conservan codificación (UTF-8, UTF-8 con BOM, UTF-16, Latin-1), saltos de línea (LF/CRLF) y el salto final.
- Los ficheros binarios se detectan y no se muestran.

## Registro (logs)

La aplicación escribe un log rotativo con las operaciones, tiempos y errores (incluidas excepciones no controladas y
avisos de Qt):

- Windows: `%LOCALAPPDATA%\FileCompare\filecompare.log`
- Linux: `~/.local/state/FileCompare/filecompare.log`

También se abre desde el menú `Ayuda → Abrir carpeta de logs`.

## Desarrollo

```bash
pip install -r requirements-dev.txt
python -m pytest
pyinstaller --onefile --windowed --name FileCompare main.py   # ejecutable en dist/
```

En GitHub Actions (`.github/workflows/build.yml`) se generan los ejecutables de Windows y Linux.
