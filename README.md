Primer fichero en el primer repositorio de Jose Vicente Lopez Lillo
---

# Gitree

Cliente Git gráfico para **Linux** inspirado en Sourcetree (Python + Qt/PySide6).

## Funciones

- **Pestañas por repositorio** y pantalla de *Marcadores* (abrir, clonar, crear). Se restauran al reiniciar.
- **Barra lateral**: espacio de trabajo, ramas (con ↑/↓ respecto al upstream), etiquetas, remotos y stashes, con menús contextuales (checkout, merge, renombrar, eliminar, push de tag, apply/pop/drop…).
- **Cambios sin confirmar**: fila superior del historial (como en Sourcetree) que, al seleccionarla, muestra bajo la tabla los ficheros en stage / sin stage, el diff y la caja de commit.
- **Historial** con grafo de ramas coloreado, etiquetas de rama/tag, búsqueda, detalle del commit, lista de ficheros y diff.
  Menú contextual: checkout, crear rama/etiqueta, cherry-pick, revert, reset (soft/mixed/hard), copiar hash.
- **Estado de ficheros**: stage/unstage con casillas, diff coloreado, **stage/unstage/descartar por hunk** (clic derecho en el diff),
  descartar, ignorar, resolver conflictos (ours/theirs), amend, «push al confirmar» y aviso de merge en curso.
- **Barra de herramientas**: Commit, Checkout, Descartar, Stash, Fetch, Pull, Push, Rama, Merge, Etiqueta, Terminal, Carpeta.
  Fetch/Pull/Push/Clone corren en segundo plano.
- Se refresca solo al volver a la ventana (y con `F5`).

## Uso

Requiere `git` y Python ≥ 3.9.

```bash
./gitree.sh                 # crea .venv e instala PySide6 la primera vez
./gitree.sh /ruta/al/repo   # abre un repositorio directamente
```

O manualmente: `pip install -r requirements.txt && python -m gitree`.
Para tener un lanzador en el menú de aplicaciones, edita la ruta de `gitree.desktop` y cópialo a `~/.local/share/applications/`.

Atajos: `Ctrl+O` abrir · `Ctrl+Shift+F/L/P` fetch/pull/push · `Ctrl+Shift+B` rama · `Ctrl+Shift+M` merge · `Ctrl+1/2` estado/historial · `F5` actualizar · `Ctrl+Intro` commit.

## Ejecutable autónomo

```bash
pip install -r requirements.txt pyinstaller
./build.sh          # genera dist/gitree (~60 MB, no necesita Python ni PySide6)
```

La acción de GitHub *Build Gitree* (pestaña Actions → Run workflow, o al subir un tag `v*`) compila el binario y lo
deja como artefacto `gitree-linux-x86_64`; con un tag también lo adjunta a la release. Requiere `git` instalado en el sistema. Si al ejecutarlo en Debian/Ubuntu aparece un error sobre `xcb-cursor0`, instala `sudo apt install libxcb-cursor0`.

## Limitaciones conocidas

- Para remotos HTTPS se necesita un *credential helper* configurado (no hay diálogo de contraseña); SSH con agente funciona.
- Aún no hay: rebase interactivo, git-flow, submódulos, stage por líneas sueltas, editor de conflictos integrado.
- La interfaz está en español.

## Tests

```bash
pip install pytest && python -m pytest tests
```
