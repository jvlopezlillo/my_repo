"""Regla de resumen junto a la barra de desplazamiento: muestra dónde están las diferencias."""

from __future__ import annotations

from typing import Callable, Optional

from PySide6.QtCore import QRect, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen
from PySide6.QtWidgets import QWidget

ORANGE = QColor(235, 150, 20)  # diferencia en un punto concreto
GREEN = QColor(60, 170, 90)  # líneas, bloques o ficheros nuevos (sólo en un lado)
MIN_MARK = 3  # alto mínimo de una marca, en píxeles
WIDTH = 14


class OverviewRuler(QWidget):
    """Dibuja una marca por diferencia, en la posición proporcional que ocupa en el contenido.

    `provider()` devuelve (total, marcas, vista, actual):
      - total: número de filas del contenido
      - marcas: lista de (fila_inicial, fila_final_exclusiva, QColor)
      - vista: (primera_fila_visible, última_fila_visible) o None
      - actual: índice de la marca resaltada (o -1)
    """

    clicked = Signal(float)  # fracción (0..1) de la altura donde se ha pulsado

    def __init__(self, provider: Callable, top_margin: Optional[Callable[[], int]] = None, parent=None):
        super().__init__(parent)
        self.provider = provider
        self.top_margin = top_margin or (lambda: 0)
        self.setFixedWidth(WIDTH)
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip("Diferencias: naranja = distinto, verde = nuevo / sólo en un lado. Pulsa para ir.")

    def _area(self) -> QRect:
        top = self.top_margin()
        return QRect(0, top, self.width(), max(1, self.height() - top))

    def paintEvent(self, _):
        p = QPainter(self)
        area = self._area()
        p.fillRect(area, self.palette().alternateBase())
        total, marks, view, current = self.provider()
        if total <= 0:
            p.end()
            return
        per = area.height() / total
        for i, (start, end, color) in enumerate(marks):
            y0 = area.top() + start * per
            h = max(MIN_MARK, (end - start) * per)
            r = QRect(1, int(y0), self.width() - 2, int(h))
            p.fillRect(r, color)
            if i == current:
                p.setPen(QPen(self.palette().text().color(), 1))
                p.drawRect(r.adjusted(0, 0, -1, -1))
        if view is not None:
            first, last = view
            y0 = area.top() + first * per
            y1 = area.top() + (last + 1) * per
            c = QColor(self.palette().text().color())
            c.setAlpha(40)
            p.fillRect(QRect(0, int(y0), self.width(), max(2, int(y1 - y0))), c)
            c.setAlpha(140)
            p.setPen(QPen(c, 1))
            p.drawRect(QRect(0, int(y0), self.width() - 1, max(2, int(y1 - y0)) - 1))
        p.end()

    def _emit(self, y: float):
        area = self._area()
        self.clicked.emit(min(1.0, max(0.0, (y - area.top()) / area.height())))

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self._emit(e.position().y())

    def mouseMoveEvent(self, e):
        if e.buttons() & Qt.LeftButton:
            self._emit(e.position().y())
