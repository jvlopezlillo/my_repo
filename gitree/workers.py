"""Ejecución de tareas largas (fetch/pull/push/clone) fuera del hilo de la GUI."""
from __future__ import annotations

from PySide6.QtCore import QObject, QRunnable, Signal


class _Signals(QObject):
    done = Signal(object, object, object)  # task, resultado, excepción


class Task(QRunnable):
    def __init__(self, fn, callback=None):
        super().__init__()
        self.fn = fn
        self.callback = callback
        self.signals = _Signals()
        self.setAutoDelete(False)

    def run(self):
        try:
            res = self.fn()
        except Exception as exc:  # noqa: BLE001 - se reenvía a la GUI
            self.signals.done.emit(self, None, exc)
        else:
            self.signals.done.emit(self, res, None)
