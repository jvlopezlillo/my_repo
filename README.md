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
- Recorre recursivamente ambas carpetas y compara el **contenido** byte a byte.
- Estados: idéntico, diferente (naranja), sólo izquierda (azul), sólo derecha (verde), tipo distinto (rojo).
- Flechas **→ / ←** en cada fila copian el fichero (o carpeta) completo al otro lado,
  sobrescribiendo el destino (pide confirmación; se puede desactivar).
  En carpetas se *fusiona*: se sobrescribe lo existente y se añade lo que falta, sin borrar nada.
- **Doble clic** en un fichero lo abre en una pestaña con los dos lados, izquierda y derecha.
- Filtro «Sólo diferencias» y patrones a ignorar (por defecto `.git, __pycache__`).

### Comparación de texto
- Paneles alineados con los bloques distintos resaltados (rojo: sólo izquierda, verde: sólo derecha, naranja: modificado).
- En el canal central, cada bloque tiene flechas **→ / ←** para copiarlo en cualquier sentido.
- Deshacer (Ctrl+Z), siguiente/anterior diferencia (F8 / Shift+F8) y guardado independiente de cada lado.
- Se conservan codificación (UTF-8, UTF-8 con BOM, UTF-16, Latin-1), saltos de línea (LF/CRLF) y el salto final.
- Los ficheros binarios se detectan y no se muestran.

## Desarrollo

```bash
pip install -r requirements-dev.txt
python -m pytest
pyinstaller --onefile --windowed --name FileCompare main.py   # ejecutable en dist/
```

En GitHub Actions (`.github/workflows/build.yml`) se generan los ejecutables de Windows y Linux.
