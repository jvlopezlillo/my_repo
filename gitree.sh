#!/usr/bin/env bash
# Lanzador: crea un venv local la primera vez y abre Gitree.
set -e
cd "$(dirname "$(readlink -f "$0")")"
if ! python3 -c "import PySide6" 2>/dev/null; then
  [ -d .venv ] || python3 -m venv .venv
  . .venv/bin/activate
  pip install -q -r requirements.txt
fi
exec python3 -m gitree "$@"
