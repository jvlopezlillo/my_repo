#!/usr/bin/env bash
# Genera un ejecutable autónomo en dist/gitree (necesita pyinstaller y PySide6).
set -e
cd "$(dirname "$(readlink -f "$0")")"
python3 -m PyInstaller --noconfirm --clean --onefile --name gitree \
  --exclude-module PySide6.QtNetwork --exclude-module PySide6.QtQml --exclude-module PySide6.QtQuick \
  --exclude-module PySide6.QtPdf --exclude-module PySide6.QtSvg --exclude-module PySide6.QtOpenGL \
  run_gitree.py
echo "Listo: $(pwd)/dist/gitree"
