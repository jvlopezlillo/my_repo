"""Vista de comparación de texto: dos paneles + canal central con flechas."""

from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QPoint, QRect, Qt, Signal
from PySide6.QtGui import QColor, QFont, QFontDatabase, QPainter, QPen, QTextCursor, QTextFormat
from PySide6.QtWidgets import (QHBoxLayout, QLabel, QMessageBox, QPlainTextEdit,
                               QPushButton, QTextEdit, QVBoxLayout, QWidget)

from .textdiff import Block, TextDocument, apply_block, compute_diff

COLORS = {
    "replace": QColor(235, 170, 40),
    "delete": QColor(225, 80, 80),
    "insert": QColor(70, 175, 95),
}
FILLER = QColor(128, 128, 128)
BTN_W = 22


def _alpha(c: QColor, a: int) -> QColor:
    c = QColor(c)
    c.setAlpha(a)
    return c


class Gutter(QWidget):
    """Canal entre los dos paneles: dibuja las flechas de copia de cada bloque."""

    copyRequested = Signal(int, str)  # índice de bloque, "ltr" | "rtl"

    def __init__(self, view: "TextDiffView"):
        super().__init__(view)
        self.view = view
        self.setFixedWidth(BTN_W * 2 + 8)
        self.setMouseTracking(True)
        self._hits: list[tuple[QRect, int, str]] = []

    def _row_top(self, row: int) -> tuple[int, int]:
        edit = self.view.left_edit
        blk = edit.document().findBlockByNumber(row)
        geom = edit.blockBoundingGeometry(blk).translated(edit.contentOffset())
        p = self.mapFromGlobal(edit.viewport().mapToGlobal(QPoint(0, int(geom.top()))))
        return p.y(), max(1, int(geom.height()))

    def paintEvent(self, _):
        p = QPainter(self)
        p.fillRect(self.rect(), self.palette().alternateBase())
        self._hits = []
        font = QFont(self.font())
        font.setBold(True)
        p.setFont(font)
        for blk in self.view.blocks:
            y0, h = self._row_top(blk.row_start)
            y1 = y0 + h * blk.row_count
            if y1 < 0 or y0 > self.height():
                continue
            cur = blk.index == self.view.current
            p.fillRect(0, y0, self.width(), y1 - y0, _alpha(COLORS[blk.tag], 110 if cur else 60))
            bh = min(h, 22)
            for x, direction, text in ((2, "ltr", "→"), (self.width() - BTN_W - 2, "rtl", "←")):
                r = QRect(x, y0, BTN_W, bh)
                self._hits.append((r, blk.index, direction))
                p.setPen(QPen(self.palette().mid().color()))
                p.setBrush(self.palette().button())
                p.drawRoundedRect(r.adjusted(0, 0, -1, -1), 3, 3)
                p.setPen(self.palette().buttonText().color())
                p.drawText(r, Qt.AlignCenter, text)
        p.end()

    def _hit(self, pos):
        for r, idx, d in self._hits:
            if r.contains(pos):
                return idx, d
        return None

    def mouseMoveEvent(self, e):
        self.setCursor(Qt.PointingHandCursor if self._hit(e.position().toPoint()) else Qt.ArrowCursor)

    def mousePressEvent(self, e):
        hit = self._hit(e.position().toPoint())
        if hit and e.button() == Qt.LeftButton:
            self.copyRequested.emit(*hit)


class TextDiffView(QWidget):
    dirtyChanged = Signal(bool)
    saved = Signal()

    def __init__(self, left: TextDocument, right: TextDocument, parent=None):
        super().__init__(parent)
        self.left_doc, self.right_doc = left, right
        self.saved_left, self.saved_right = list(left.lines), list(right.lines)
        self.undo_stack: list[tuple[list[str], list[str]]] = []
        self.rows, self.blocks = [], []
        self.current = -1
        self._dirty = False

        mono = QFontDatabase.systemFont(QFontDatabase.FixedFont)
        self.left_edit, self.right_edit = QPlainTextEdit(), QPlainTextEdit()
        for e in (self.left_edit, self.right_edit):
            e.setReadOnly(True)
            e.setLineWrapMode(QPlainTextEdit.NoWrap)
            e.setFont(mono)
            e.setTabStopDistance(4 * e.fontMetrics().horizontalAdvance(" "))
        self.gutter = Gutter(self)
        self.gutter.copyRequested.connect(self.copy_block)

        # sincronizar desplazamientos
        for a, b in ((self.left_edit, self.right_edit), (self.right_edit, self.left_edit)):
            a.verticalScrollBar().valueChanged.connect(b.verticalScrollBar().setValue)
            a.horizontalScrollBar().valueChanged.connect(b.horizontalScrollBar().setValue)
        self.left_edit.verticalScrollBar().valueChanged.connect(lambda _: self.gutter.update())
        self.left_edit.verticalScrollBar().rangeChanged.connect(lambda *_: self.gutter.update())

        self.left_label, self.right_label = QLabel(), QLabel()
        for lb in (self.left_label, self.right_label):
            lb.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.info = QLabel()

        self.btn_save_l = QPushButton("Guardar izquierda")
        self.btn_save_r = QPushButton("Guardar derecha")
        self.btn_undo = QPushButton("Deshacer")
        self.btn_prev = QPushButton("▲ Anterior (Shift+F8)")
        self.btn_next = QPushButton("▼ Siguiente (F8)")
        self.btn_save_l.clicked.connect(lambda: self.save_side("left"))
        self.btn_save_r.clicked.connect(lambda: self.save_side("right"))
        self.btn_undo.clicked.connect(self.undo)
        self.btn_prev.clicked.connect(lambda: self.goto(-1))
        self.btn_next.clicked.connect(lambda: self.goto(1))
        self.btn_prev.setShortcut("Shift+F8")
        self.btn_next.setShortcut("F8")
        self.btn_undo.setShortcut("Ctrl+Z")

        bar = QHBoxLayout()
        for b in (self.btn_prev, self.btn_next, self.btn_undo):
            bar.addWidget(b)
        bar.addWidget(self.info, 1)
        bar.addWidget(self.btn_save_l)
        bar.addWidget(self.btn_save_r)

        heads = QHBoxLayout()
        heads.addWidget(self.left_label, 1)
        heads.addSpacing(self.gutter.width())
        heads.addWidget(self.right_label, 1)

        body = QHBoxLayout()
        body.setSpacing(0)
        body.addWidget(self.left_edit, 1)
        body.addWidget(self.gutter)
        body.addWidget(self.right_edit, 1)

        lay = QVBoxLayout(self)
        lay.addLayout(bar)
        lay.addLayout(heads)
        lay.addLayout(body, 1)
        self.rebuild()

    # ---- estado -------------------------------------------------------
    @property
    def dirty(self) -> bool:
        return self.left_doc.lines != self.saved_left or self.right_doc.lines != self.saved_right

    def _update_dirty(self):
        d = self.dirty
        if d != self._dirty:
            self._dirty = d
            self.dirtyChanged.emit(d)

    # ---- pintado --------------------------------------------------------
    def rebuild(self):
        lv, rv = self.left_edit.verticalScrollBar().value(), self.right_edit.verticalScrollBar().value()
        lh = self.left_edit.horizontalScrollBar().value()
        self.rows, self.blocks = compute_diff(self.left_doc.lines, self.right_doc.lines)
        if self.current >= len(self.blocks):
            self.current = len(self.blocks) - 1
        self.left_edit.setPlainText("\n".join(r.left or "" for r in self.rows))
        self.right_edit.setPlainText("\n".join(r.right or "" for r in self.rows))
        self._highlight()
        for e, v in ((self.left_edit, lv), (self.right_edit, rv)):
            e.verticalScrollBar().setValue(v)
        self.left_edit.horizontalScrollBar().setValue(lh)

        mark = lambda d, s: f"{'● ' if d.lines != s else ''}{d.path}" + ("" if d.exists else "  (no existe: se creará al guardar)")
        self.left_label.setText(mark(self.left_doc, self.saved_left))
        self.right_label.setText(mark(self.right_doc, self.saved_right))
        n = len(self.blocks)
        self.info.setText("  Los ficheros son idénticos" if n == 0 else f"  {n} bloque(s) distinto(s)")
        self.btn_undo.setEnabled(bool(self.undo_stack))
        self.btn_prev.setEnabled(n > 0)
        self.btn_next.setEnabled(n > 0)
        self.btn_save_l.setEnabled(self.left_doc.lines != self.saved_left)
        self.btn_save_r.setEnabled(self.right_doc.lines != self.saved_right)
        self.gutter.update()
        self._update_dirty()

    def _highlight(self):
        sels = {"left": [], "right": []}
        for blk in self.blocks:
            strong = 110 if blk.index == self.current else 60
            for k in range(blk.row_count):
                row = self.rows[blk.row_start + k]
                for side, edit, text in (("left", self.left_edit, row.left),
                                         ("right", self.right_edit, row.right)):
                    color = _alpha(FILLER, 40) if text is None else _alpha(COLORS[blk.tag], strong)
                    s = QTextEdit.ExtraSelection()
                    s.format.setBackground(color)
                    s.format.setProperty(QTextFormat.FullWidthSelection, True)
                    s.cursor = QTextCursor(edit.document().findBlockByNumber(blk.row_start + k))
                    sels[side].append(s)
        self.left_edit.setExtraSelections(sels["left"])
        self.right_edit.setExtraSelections(sels["right"])

    # ---- acciones ---------------------------------------------------------
    def copy_block(self, index: int, direction: str):
        self.undo_stack.append((list(self.left_doc.lines), list(self.right_doc.lines)))
        self.current = index  # tras copiar, el siguiente bloque ocupa este índice
        apply_block(self.left_doc.lines, self.right_doc.lines, self.blocks[index], direction)
        self.rebuild()

    def undo(self):
        if self.undo_stack:
            self.left_doc.lines, self.right_doc.lines = self.undo_stack.pop()
            self.rebuild()

    def goto(self, step: int):
        if not self.blocks:
            return
        self.current = (self.current + step) % len(self.blocks) if self.current >= 0 else (0 if step > 0 else len(self.blocks) - 1)
        blk = self.blocks[self.current]
        bar = self.left_edit.verticalScrollBar()
        line_h = max(1, self.left_edit.fontMetrics().lineSpacing())
        bar.setValue(max(0, blk.row_start * line_h - self.left_edit.viewport().height() // 3))
        self._highlight()
        self.gutter.update()

    def save_side(self, side: str) -> bool:
        doc, saved = (self.left_doc, "saved_left") if side == "left" else (self.right_doc, "saved_right")
        try:
            doc.save()
        except OSError as e:
            QMessageBox.critical(self, "Error al guardar", f"{doc.path}\n\n{e}")
            return False
        setattr(self, saved, list(doc.lines))
        self.rebuild()
        self.saved.emit()
        return True

    def save_all(self) -> bool:
        ok = True
        if self.left_doc.lines != self.saved_left:
            ok = self.save_side("left") and ok
        if self.right_doc.lines != self.saved_right:
            ok = self.save_side("right") and ok
        return ok
