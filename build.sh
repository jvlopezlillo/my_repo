#!/usr/bin/env bash
# Genera un ejecutable autónomo en dist/gitree (necesita pyinstaller y PySide6).
set -e
cd "$(dirname "$(readlink -f "$0")")"
# Qt >= 6.5 necesita libxcb-cursor en X11; se incluye en el binario si está instalada
EXTRA=()
for lib in /usr/lib/x86_64-linux-gnu/libxcb-cursor.so.0 /usr/lib64/libxcb-cursor.so.0 /usr/lib/libxcb-cursor.so.0; do
  [ -e "$lib" ] && EXTRA+=(--add-binary "$(readlink -f "$lib"):.") && break
done
python3 -m PyInstaller --noconfirm --clean --onefile --name gitree "${EXTRA[@]}" \
  --exclude-module PySide6.QtNetwork --exclude-module PySide6.QtQml --exclude-module PySide6.QtQuick \
  --exclude-module PySide6.QtPdf --exclude-module PySide6.QtSvg --exclude-module PySide6.QtOpenGL \
  run_gitree.py
echo "Listo: $(pwd)/dist/gitree"
