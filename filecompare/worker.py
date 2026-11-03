"""Hilo de trabajo con progreso y cancelación."""

from __future__ import annotations

import time
from typing import Callable

from PySide6.QtCore import QThread, Signal

from .core import Cancelled, Progress


class Worker(QThread):
    progress = Signal(int, int)  # hecho, total
    succeeded = Signal(object)
    failed = Signal(str)
    canceled = Signal()

    def __init__(self, fn: Callable[[Progress], object], parent=None):
        super().__init__(parent)
        self.prog = Progress(self._on_update)
        self._fn = fn
        self._last = 0.0

    def _on_update(self, done: int, total: int) -> None:
        now = time.monotonic()
        if now - self._last > 0.05 or done >= total:  # limita la frecuencia de señales
            self._last = now
            self.progress.emit(done, total)

    def run(self) -> None:
        try:
            result = self._fn(self.prog)
        except Cancelled:
            self.canceled.emit()
        except Exception as e:  # noqa: BLE001 - se informa a la GUI
            self.failed.emit(f"{type(e).__name__}: {e}")
        else:
            self.succeeded.emit(result)
