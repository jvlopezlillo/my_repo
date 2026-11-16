# PDF Text Edit

Editor de PDF cuyo objetivo es **reemplazar texto existente conservando la fuente,
el tamaño, el color y el estilo (negrita/cursiva)** del original.

## Uso (Windows)
```
pip install -r requirements.txt
python -m pdftextedit [archivo.pdf]
```
Pulsa sobre un texto → se muestra su fuente, tamaño, estilo, color y de dónde sale la
fuente → escribe el nuevo texto → Enter. Deshacer/Rehacer y Guardar/Guardar como.
Para un `.exe`: `build_windows.bat`, o descárgalo ya compilado desde la release `pdftextedit-windows-latest` (lo genera GitHub Actions).

## Cómo conserva la fuente
1. Lee de cada fragmento de texto su fuente, tamaño, flags (negrita/cursiva), color y posición.
2. Busca la fuente, por este orden: **la propia fuente incrustada del PDF, reutilizando el mismo
   objeto** (escribe los códigos de glifo directamente; funciona con TrueType/CFF/Type1 y CID
   mediante `ToUnicode`) → fuente incrustada reconstruida → **instalada** en el sistema (o añadida por ti) →
   **estándar** (Helvetica/Times/Courier/Arial).
3. Borra el texto viejo (redacción, sin tocar imágenes ni gráficos) y escribe el nuevo en el
   mismo origen con esa fuente, tamaño y color.
4. Si la fuente no tiene alguna letra (subconjunto) **se bloquea y avisa**, y ofrece: elegir el
   archivo `.ttf/.otf`, buscarla en internet, abrir la carpeta de fuentes del sistema; y solo
   como último recurso, usar una fuente equivalente **solo para los caracteres que faltan**.

## Limitaciones actuales (v0.1)
- Se edita por fragmento (span) con un único formato; un renglón con varias fuentes se edita
  en varios pasos.
- Texto en vertical/rotado: aviso, resultado no garantizado.
- No se ajusta el reflujo: si el texto nuevo es más largo, se extiende hacia la derecha.
- Fuentes incrustadas CFF/Type1 sin tabla sfnt: se usa la instalada/estándar si existe.
- PDF escaneados (imagen) requieren OCR; no incluido.

## Tests
`python -m pytest tests`
