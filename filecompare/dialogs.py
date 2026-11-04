"""Opciones comunes para los diálogos de selección de ficheros."""

from __future__ import annotations

import sys

from PySide6.QtWidgets import QFileDialog

LINUX = sys.platform.startswith("linux")


def file_dialog_options(*extra: QFileDialog.Option) -> QFileDialog.Option:
    """En Linux se usa el diálogo propio de Qt.

    El diálogo nativo (GTK) aborta el programa cuando el ejecutable empaquetado mezcla librerías de
    imagen propias con las del sistema ("Failed to load .../image-missing.png"). En Windows se usa el nativo.
    """
    opts = QFileDialog.Option(0)
    for o in extra:
        opts |= o
    if LINUX:
        opts |= QFileDialog.Option.DontUseNativeDialog
    return opts
