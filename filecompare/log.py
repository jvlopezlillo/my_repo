"""Registro de actividad en un fichero (para diagnosticar problemas)."""

from __future__ import annotations

import logging
import os
import sys
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path

logger = logging.getLogger("filecompare")


def log_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or os.environ.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return Path(base) / "FileCompare"


def setup_logging() -> Path:
    """Configura el log rotativo y captura excepciones no controladas. Devuelve la ruta del fichero."""
    d = log_dir()
    path = d / "filecompare.log"
    try:
        d.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(path, maxBytes=1_000_000, backupCount=3, encoding="utf-8")
    except OSError:
        handler = logging.NullHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)-7s [%(threadName)s] %(message)s"))
    logger.setLevel(logging.INFO)
    logger.handlers[:] = [handler]

    def excepthook(t, v, tb):
        logger.critical("Excepción no controlada", exc_info=(t, v, tb))
        sys.__excepthook__(t, v, tb)

    sys.excepthook = excepthook
    threading.excepthook = lambda a: logger.critical(
        "Excepción no controlada en hilo", exc_info=(a.exc_type, a.exc_value, a.exc_traceback))
    logger.info("=== FileCompare iniciado (python %s, %s) ===", sys.version.split()[0], sys.platform)
    return path
