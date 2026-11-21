"""Vista de comparación de texto: dos paneles editables + canal central con flechas."""

from __future__ import annotations

import re
from typing import Optional

from PySide6.QtCore import QPoint, QRect, Qt, QTimer, Signal
from PySide6.QtGui import (QColor, QFont, QFontDatabase, QKeySequence, QPainter, QPen, QShortcut,
                           QTextCursor, QTextFormat)
from PySide6.QtWidgets import (QCheckBox, QGridLayout, QHBoxLayout, QLabel, QLineEdit, QMessageBox,
                               QPlainTextEdit, QPushButton, QSizePolicy, QTextEdit, QToolButton,
                               QVBoxLayout, QWidget)

from .ruler import GREEN, ORANGE, OverviewRuler
from .textdiff import TextDocument, apply_block, compute_diff, inline_spans, is_comment_line

# naranja = distinto; verde = líneas o bloques que sólo están en un lado (nuevos)
COLORS = {
    "replace": QColor(235, 170, 40),
    "delete": QColor(70, 175, 95),
    "insert": QColor(70, 175, 95),
}
FILLER = QColor(128, 128, 128)
FIND_ALL = QColor(70, 130, 255)  # coincidencias de la búsqueda
FIND_CURRENT = QColor(255, 210, 0)  # la coincidencia actual
MAX_MATCHES = 5000
BTN_W = 22
SIDES = ("left", "right")


def _alpha(c: QColor, a: int) -> QColor:
    c = QColor(c)
    c.setAlpha(a)
    return c


def _row_y(widget: QWidget, edit: QPlainTextEdit, row: int) -> tuple[int, int]:
    """Posición Y (en coordenadas de `widget`) y alto de una fila del editor."""
    blk = edit.document().findBlockByNumber(row)
    geom = edit.blockBoundingGeometry(blk).translated(edit.contentOffset())
    p = widget.mapFromGlobal(edit.viewport().mapToGlobal(QPoint(0, int(geom.top()))))
    return p.y(), max(1, int(geom.height()))


class DiffEdit(QPlainTextEdit):
    undoRequested = Signal()
    redoRequested = Signal()

    def keyPressEvent(self, e):
        if e.matches(QKeySequence.Undo):
            self.undoRequested.emit()
        elif e.matches(QKeySequence.Redo):
            self.redoRequested.emit()
        else:
            super().keyPressEvent(e)


class LineNumbers(QWidget):
    def __init__(self, view: "TextDiffView", side: str):
        super().__init__(view)
        self.view, self.side = view, side
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Expanding)

    def update_width(self):
        digits = max(3, len(str(max([n for n in self.view.nums[self.side] if n] or [0]))))
        self.setFixedWidth(self.fontMetrics().horizontalAdvance("9") * digits + 10)

    def paintEvent(self, _):
        p = QPainter(self)
        p.fillRect(self.rect(), self.palette().alternateBase())
        edit = self.view.edits[self.side]
        nums = self.view.nums[self.side]
        p.setFont(edit.font())
        p.setPen(self.palette().placeholderText().color())
        block = edit.firstVisibleBlock()
        while block.isValid():
            y, h = _row_y(self, edit, block.blockNumber())
            if y > self.height():
                break
            n = nums[block.blockNumber()] if block.blockNumber() < len(nums) else None
            if n:
                p.drawText(QRect(0, y, self.width() - 5, h), Qt.AlignRight | Qt.AlignVCenter, str(n))
            block = block.next()
        p.end()


class Gutter(QWidget):
    """Canal entre los dos paneles: dibuja las flechas de copia de cada bloque."""

    copyRequested = Signal(int, str)  # índice de bloque, "ltr" | "rtl"

    def __init__(self, view: "TextDiffView"):
        super().__init__(view)
        self.view = view
        self.setFixedWidth(BTN_W * 2 + 8)
        self.setMouseTracking(True)
        self._hits: list[tuple[QRect, int, str]] = []

    def paintEvent(self, _):
        p = QPainter(self)
        p.fillRect(self.rect(), self.palette().alternateBase())
        self._hits = []
        if self.view.pending:  # hay ediciones sin procesar: los bloques están obsoletos
            p.end()
            return
        font = QFont(self.font())
        font.setBold(True)
        p.setFont(font)
        for blk in self.view.blocks:
            y0, h = _row_y(self, self.view.left_edit, blk.row_start)
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

    def __init__(self, left: TextDocument, right: TextDocument, ignore_ws: bool = True,
                 ignore_comments: bool = True, parent=None):
        super().__init__(parent)
        self.left_doc, self.right_doc = left, right
        self.saved_lines = {"left": list(left.lines), "right": list(right.lines)}
        self.undo_stack: list[tuple[list[str], list[str]]] = []
        self.redo_stack: list[tuple[list[str], list[str]]] = []
        self.rows, self.blocks = [], []
        self._find_matches: list[tuple[int, int, int, int]] = []  # (fila, lado 0/1, columna, longitud) en UTF-16
        self._find_idx = -1
        self.current = -1
        self._dirty = False
        self._rebuilding = False
        self._edited: set[str] = set()
        self._committed: tuple[list[str], list[str]] = ([], [])
        # por cada bloque (línea) de cada editor: ¿es una fila de relleno?
        self.filler = {"left": [True], "right": [True]}
        self.nums: dict[str, list[Optional[int]]] = {"left": [], "right": []}

        mono = QFontDatabase.systemFont(QFontDatabase.FixedFont)
        self.edits = {s: DiffEdit() for s in SIDES}
        self.left_edit, self.right_edit = self.edits["left"], self.edits["right"]
        self.numbers = {s: LineNumbers(self, s) for s in SIDES}
        for s, e in self.edits.items():
            e.setLineWrapMode(QPlainTextEdit.NoWrap)
            e.setFont(mono)
            e.setUndoRedoEnabled(False)  # deshacer propio, a nivel de documento
            e.setTabStopDistance(4 * e.fontMetrics().horizontalAdvance(" "))
            e.document().contentsChange.connect(lambda p, r, a, s=s: self._on_change(s, p, r, a))
            e.undoRequested.connect(self.undo)
            e.redoRequested.connect(self.redo)
            e.verticalScrollBar().valueChanged.connect(lambda _=0: self._repaint_side())
            e.textChanged.connect(self._repaint_side)
            self.numbers[s].setFont(mono)
        self.gutter = Gutter(self)
        self.gutter.copyRequested.connect(self.copy_block)
        self.ruler = OverviewRuler(self._ruler_data)
        self.ruler.clicked.connect(self._on_ruler_click)
        for a, b in ((self.left_edit, self.right_edit), (self.right_edit, self.left_edit)):
            a.verticalScrollBar().valueChanged.connect(b.verticalScrollBar().setValue)
            a.horizontalScrollBar().valueChanged.connect(b.horizontalScrollBar().setValue)

        self._timer = QTimer(self, singleShot=True, interval=500)
        self._timer.timeout.connect(self.flush)

        self.labels = {s: QLabel() for s in SIDES}
        for lb in self.labels.values():
            lb.setTextInteractionFlags(Qt.TextSelectableByMouse)
            lb.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Preferred)
        self.info = QLabel()

        self.chk_ws = QCheckBox("Ignorar espacios")
        self.chk_ws.setChecked(ignore_ws)
        self.chk_ws.setToolTip("Las líneas que sólo difieren en espacios o tabuladores se consideran iguales")
        self.chk_ws.toggled.connect(lambda _: (self.flush(), self.rebuild()))
        self.chk_cmt = QCheckBox("Ignorar comentarios //")
        self.chk_cmt.setChecked(ignore_comments)
        self.chk_cmt.setToolTip("Las líneas que empiezan por // (tras la indentación) se ignoran en ambos lados: "
                                "se muestran en gris y no cuentan como diferencia")
        self.chk_cmt.toggled.connect(lambda _: (self.flush(), self.rebuild()))
        self.btn_save_l = QPushButton("Guardar izquierda (Ctrl+1)")
        self.btn_save_r = QPushButton("Guardar derecha (Ctrl+2)")
        self.btn_save_l.setShortcut("Ctrl+1")
        self.btn_save_r.setShortcut("Ctrl+2")
        self.btn_undo = QPushButton("Deshacer")
        self.btn_redo = QPushButton("Rehacer")
        self.btn_prev = QPushButton("▲ Anterior (Shift+F8)")
        self.btn_next = QPushButton("▼ Siguiente (F8)")
        self.btn_save_l.clicked.connect(lambda: self.save_side("left"))
        self.btn_save_r.clicked.connect(lambda: self.save_side("right"))
        self.btn_undo.clicked.connect(self.undo)
        self.btn_redo.clicked.connect(self.redo)
        self.btn_prev.clicked.connect(lambda: self.goto(-1))
        self.btn_next.clicked.connect(lambda: self.goto(1))
        self.btn_prev.setShortcut("Shift+F8")
        self.btn_next.setShortcut("F8")
        self.btn_undo.setShortcut(QKeySequence.Undo)
        self.btn_redo.setShortcut(QKeySequence.Redo)

        bar = QHBoxLayout()
        for b in (self.btn_prev, self.btn_next, self.btn_undo, self.btn_redo):
            bar.addWidget(b)
        bar.addWidget(self.chk_ws)
        bar.addWidget(self.chk_cmt)
        bar.addWidget(self.info, 1)
        bar.addWidget(self.btn_save_l)
        bar.addWidget(self.btn_save_r)

        grid = QGridLayout()
        grid.setHorizontalSpacing(0)
        grid.addWidget(self.labels["left"], 0, 0, 1, 2)
        grid.addWidget(self.labels["right"], 0, 3, 1, 2)
        grid.addWidget(self.numbers["left"], 1, 0)
        grid.addWidget(self.left_edit, 1, 1)
        grid.addWidget(self.gutter, 1, 2)
        grid.addWidget(self.numbers["right"], 1, 3)
        grid.addWidget(self.right_edit, 1, 4)
        grid.addWidget(self.ruler, 1, 5)
        grid.setColumnStretch(1, 1)
        grid.setColumnStretch(4, 1)
        grid.setRowStretch(1, 1)

        lay = QVBoxLayout(self)
        self._build_find_bar()
        lay.addLayout(bar)
        lay.addWidget(self.find_bar)
        lay.addLayout(grid, 1)
        self.rebuild()

    # ---- estado ---------------------------------------------------------
    @property
    def pending(self) -> bool:
        return bool(self._edited)

    @property
    def ignore_ws(self) -> bool:
        return self.chk_ws.isChecked()

    @property
    def ignore_comments(self) -> bool:
        return self.chk_cmt.isChecked()

    def _doc(self, side: str) -> TextDocument:
        return self.left_doc if side == "left" else self.right_doc

    @property
    def dirty(self) -> bool:
        return self.pending or any(self._doc(s).lines != self.saved_lines[s] for s in SIDES)

    def _update_dirty(self):
        d = self.dirty
        if d != self._dirty:
            self._dirty = d
            self.dirtyChanged.emit(d)

    def _repaint_side(self):
        self.gutter.update()
        self.ruler.update()
        for n in self.numbers.values():
            n.update()

    def _recompute_nums(self, side: str):
        n, out = 0, []
        for f in self.filler[side]:
            if f:
                out.append(None)
            else:
                n += 1
                out.append(n)
        self.nums[side] = out
        self.numbers[side].update_width()

    # ---- edición manual ---------------------------------------------------
    def _on_change(self, side: str, pos: int, removed: int, added: int):
        if self._rebuilding:
            return
        doc = self.edits[side].document()
        flags = self.filler[side]
        delta = doc.blockCount() - len(flags)
        first = doc.findBlock(pos).blockNumber()
        if delta > 0:
            flags[first + 1:first + 1] = [False] * delta
        elif delta < 0:
            del flags[first + 1:first + 1 - delta]
        if len(flags) != doc.blockCount():  # por seguridad: todo pasa a ser línea real
            flags[:] = [False] * doc.blockCount()
        for k in range(first, min(first + max(delta, 0) + 1, len(flags))):
            flags[k] = False  # lo tocado por el usuario deja de ser relleno
        self._recompute_nums(side)
        self._edited.add(side)
        (self.btn_save_l if side == "left" else self.btn_save_r).setEnabled(True)
        self._timer.start()
        self._update_dirty()
        self._repaint_side()

    def _cursor_state(self):
        for side in SIDES:
            e = self.edits[side]
            if e.hasFocus():
                c = e.textCursor()
                b = c.blockNumber()
                return side, sum(1 for f in self.filler[side][:b] if not f), c.positionInBlock()
        return None

    def flush(self):
        """Vuelca las ediciones de los paneles a los documentos y recalcula el diff."""
        self._timer.stop()
        if not self._edited:
            return
        restore = self._cursor_state()
        before = self._committed
        for side in self._edited:
            text = self.edits[side].toPlainText().split("\n")
            self._doc(side).lines = [t for t, f in zip(text, self.filler[side]) if not f]
        self._edited.clear()
        if before != (self.left_doc.lines, self.right_doc.lines):
            self.undo_stack.append(before)
            self.redo_stack.clear()
        self.rebuild(restore)

    # ---- pintado ----------------------------------------------------------
    def rebuild(self, restore=None):
        self._rebuilding = True
        try:
            lv = self.left_edit.verticalScrollBar().value()
            lh = self.left_edit.horizontalScrollBar().value()
            self.rows, self.blocks = compute_diff(self.left_doc.lines, self.right_doc.lines, self.ignore_ws,
                                                 self.ignore_comments)
            if self.current >= len(self.blocks):
                self.current = len(self.blocks) - 1
            self.left_edit.setPlainText("\n".join(r.left or "" for r in self.rows))
            self.right_edit.setPlainText("\n".join(r.right or "" for r in self.rows))
            self.filler["left"] = [r.left is None for r in self.rows] or [True]
            self.filler["right"] = [r.right is None for r in self.rows] or [True]
            for s in SIDES:
                self._recompute_nums(s)
            self._find_update_matches()
            self._highlight()
            self.left_edit.verticalScrollBar().setValue(lv)
            self.right_edit.verticalScrollBar().setValue(lv)
            self.left_edit.horizontalScrollBar().setValue(lh)
            self.right_edit.horizontalScrollBar().setValue(lh)
            if restore:
                self._restore_cursor(*restore)
        finally:
            self._rebuilding = False
        self._committed = (list(self.left_doc.lines), list(self.right_doc.lines))

        for s in SIDES:
            d = self._doc(s)
            modified = d.lines != self.saved_lines[s]
            # un lado sin fichero se deja vacío hasta que tenga contenido
            if not d.exists and not modified:
                self.labels[s].setText("")
            else:
                self.labels[s].setText(f"{'● ' if modified else ''}{d.path}")
            self.labels[s].setToolTip(str(d.path))
        n = len(self.blocks)
        self._update_info()
        self.btn_undo.setEnabled(bool(self.undo_stack))
        self.btn_redo.setEnabled(bool(self.redo_stack))
        self.btn_prev.setEnabled(n > 0)
        self.btn_next.setEnabled(n > 0)
        self.btn_save_l.setEnabled(self.left_doc.lines != self.saved_lines["left"])
        self.btn_save_r.setEnabled(self.right_doc.lines != self.saved_lines["right"])
        self._repaint_side()
        self._update_dirty()

    def _restore_cursor(self, side: str, line_idx: int, col: int):
        edit = self.edits[side]
        count, target = 0, max(0, len(self.rows) - 1)
        for i, f in enumerate(self.filler[side]):
            if not f:
                if count == line_idx:
                    target = i
                    break
                count += 1
        block = edit.document().findBlockByNumber(min(target, edit.document().blockCount() - 1))
        cur = QTextCursor(block)
        cur.setPosition(block.position() + min(col, max(0, block.length() - 1)))
        edit.setTextCursor(cur)

    def _highlight(self):
        sels = {"left": [], "right": []}
        spans = {"left": [], "right": []}

        def mk(edit, start, length, color, full):
            s = QTextEdit.ExtraSelection()
            s.format.setBackground(color)
            c = QTextCursor(edit.document())
            c.setPosition(start)
            if full:
                s.format.setProperty(QTextFormat.FullWidthSelection, True)
            else:
                c.setPosition(start + length, QTextCursor.KeepAnchor)
            s.cursor = c
            return s

        for blk in self.blocks:
            strong = 110 if blk.index == self.current else 60
            for k in range(blk.row_count):
                n = blk.row_start + k
                row = self.rows[n]
                for side, text in (("left", row.left), ("right", row.right)):
                    edit = self.edits[side]
                    start = edit.document().findBlockByNumber(n).position()
                    color = _alpha(FILLER, 40) if text is None else _alpha(COLORS[blk.tag], strong)
                    sels[side].append(mk(edit, start, 0, color, True))
                if blk.tag == "replace" and row.left is not None and row.right is not None:
                    sa, sb = inline_spans(row.left, row.right)
                    for side, ss, text in (("left", sa, row.left), ("right", sb, row.right)):
                        edit = self.edits[side]
                        start = edit.document().findBlockByNumber(n).position()
                        for a, b in ss:
                            spans[side].append(mk(edit, start + a, b - a, _alpha(COLORS["replace"], 190), False))
        # filas de relleno que no pertenecen a un bloque (hueco frente a un comentario ignorado)
        # y comentarios ignorados en gris
        muted = {"left": [], "right": []}
        for n, row in enumerate(self.rows):
            for side, text in (("left", row.left), ("right", row.right)):
                edit = self.edits[side]
                if text is None:
                    if row.block is None:
                        start = edit.document().findBlockByNumber(n).position()
                        sels[side].append(mk(edit, start, 0, _alpha(FILLER, 40), True))
                elif self.ignore_comments and is_comment_line(text):
                    start = edit.document().findBlockByNumber(n).position()
                    sel = mk(edit, start, 0, QColor(0, 0, 0, 0), True)
                    sel.format.setForeground(QColor(120, 120, 120))
                    muted[side].append(sel)
        for side in SIDES:
            self.edits[side].setExtraSelections(
                sels[side] + muted[side] + spans[side] + self._find_selections(side))

    # ---- búsqueda (Ctrl+F) ------------------------------------------------------------
    def _build_find_bar(self):
        self.find_bar = QWidget()
        fb = QHBoxLayout(self.find_bar)
        fb.setContentsMargins(0, 0, 0, 0)
        self.find_edit = QLineEdit()
        self.find_edit.setPlaceholderText("Buscar en ambos lados…")
        self.find_edit.setClearButtonEnabled(True)
        self.chk_case = QCheckBox("Distinguir mayúsculas")
        self.find_label = QLabel()
        self.btn_fprev, self.btn_fnext, btn_close = QToolButton(), QToolButton(), QToolButton()
        for b, text, tip in ((self.btn_fprev, "▲", "Coincidencia anterior (Mayús+F3 / Mayús+Intro)"),
                             (self.btn_fnext, "▼", "Coincidencia siguiente (F3 / Intro)"),
                             (btn_close, "✕", "Cerrar la búsqueda (Esc)")):
            b.setText(text)
            b.setToolTip(tip)
        fb.addWidget(QLabel("Buscar:"))
        fb.addWidget(self.find_edit, 1)
        for w in (self.btn_fprev, self.btn_fnext, self.chk_case, self.find_label, btn_close):
            fb.addWidget(w)
        self.find_bar.hide()

        self.find_edit.textChanged.connect(self._find_text_changed)
        self.chk_case.toggled.connect(self._find_text_changed)
        self.find_edit.returnPressed.connect(lambda: self.find_step(1))
        self.btn_fnext.clicked.connect(lambda: self.find_step(1))
        self.btn_fprev.clicked.connect(lambda: self.find_step(-1))
        btn_close.clicked.connect(self.hide_find)

        def shortcut(keys: str, widget, slot, context=Qt.WidgetWithChildrenShortcut):
            sc = QShortcut(QKeySequence(keys), widget)
            sc.setContext(context)
            sc.activated.connect(slot)
            return sc

        shortcut("Ctrl+F", self, self.show_find)  # sólo en la pestaña visible
        shortcut("F3", self, lambda: self.find_step(1))
        shortcut("Shift+F3", self, lambda: self.find_step(-1))
        shortcut("Shift+Return", self.find_edit, lambda: self.find_step(-1), Qt.WidgetShortcut)
        shortcut("Escape", self.find_bar, self.hide_find)

    def _cursor_key(self) -> tuple[int, int, int]:
        """(fila, lado, columna) del cursor del panel con foco (o del izquierdo)."""
        edit = self._focused_edit()
        c = edit.textCursor()
        pos = min(c.position(), c.anchor())
        blk = edit.document().findBlock(pos)
        return blk.blockNumber(), SIDES.index("left" if edit is self.left_edit else "right"), pos - blk.position()

    def show_find(self):
        sel = self._focused_edit().textCursor().selectedText()
        if sel and len(sel) < 200 and "\u2029" not in sel:
            self.find_edit.setText(sel)  # parte del texto seleccionado
        was_hidden = not self.find_bar.isVisible()
        self.find_bar.show()
        if was_hidden:
            self._find_origin = self._cursor_key()
        self.find_edit.setFocus()
        self.find_edit.selectAll()
        self._find_text_changed()

    def hide_find(self):
        if not self.find_bar.isVisible():
            return
        self.find_bar.hide()
        self._find_matches, self._find_idx = [], -1
        self._highlight()
        self._focused_edit().setFocus()

    @staticmethod
    def _u16(text: str, index: int) -> int:
        """Posición en unidades UTF-16 (como las de Qt) de un índice de cadena de Python."""
        return len(text[:index].encode("utf-16-le")) // 2

    def _find_update_matches(self):
        """Recalcula las coincidencias en los dos paneles (tal como se ven ahora)."""
        self._find_matches, self._find_idx = [], -1
        q = self.find_edit.text() if self.find_bar.isVisible() else ""
        if q:
            pat = re.compile(re.escape(q), 0 if self.chk_case.isChecked() else re.IGNORECASE)
            for side_i, side in enumerate(SIDES):
                blk, row = self.edits[side].document().begin(), 0
                while blk.isValid() and len(self._find_matches) < MAX_MATCHES:
                    text = blk.text()
                    for m in pat.finditer(text):
                        start = self._u16(text, m.start())
                        self._find_matches.append((row, side_i, start, self._u16(text, m.end()) - start))
                    blk, row = blk.next(), row + 1
            self._find_matches.sort(key=lambda m: (m[0], m[1], m[2]))
        self._find_update_label()

    def _find_update_label(self):
        q, n = self.find_edit.text(), len(self._find_matches)
        if not q:
            self.find_label.setText("")
        elif n == 0:
            self.find_label.setText("Sin resultados")
        else:
            left = sum(1 for m in self._find_matches if m[1] == 0)
            pos = f"{self._find_idx + 1} de " if self._find_idx >= 0 else ""
            more = "+" if n >= MAX_MATCHES else ""
            self.find_label.setText(f"{pos}{n}{more}  (izq. {left} · der. {n - left})")

    def _find_text_changed(self, *_):
        self._find_update_matches()
        if self._find_matches:
            origin = getattr(self, "_find_origin", (0, 0, 0))
            self._select_match(next((i for i, m in enumerate(self._find_matches)
                                     if (m[0], m[1], m[2]) >= origin), 0))  # búsqueda incremental desde donde se abrió
        else:
            self._highlight()

    def find_step(self, step: int):
        """Siguiente (1) o anterior (-1) coincidencia; con la barra cerrada, la abre."""
        if not self.find_bar.isVisible() or not self.find_edit.text():
            self.show_find()
            return
        self.flush()
        if not self._find_matches:
            self._find_update_matches()
        n = len(self._find_matches)
        if n == 0:
            return
        if self._find_idx < 0:  # aún sin coincidencia activa: desde el cursor
            ref = self._cursor_key()
            if step > 0:
                idx = next((i for i, m in enumerate(self._find_matches) if (m[0], m[1], m[2]) >= ref), 0)
            else:
                idx = next((i for i in range(n - 1, -1, -1)
                            if (self._find_matches[i][0], self._find_matches[i][1], self._find_matches[i][2]) < ref), n - 1)
        else:
            idx = (self._find_idx + step) % n  # al final vuelve al principio
        self._select_match(idx)

    def _select_match(self, idx: int):
        """Selecciona la coincidencia `idx` en su panel y la deja a la vista en los dos."""
        row, side_i, col, length = self._find_matches[idx]
        self._find_idx = idx
        edit = self.edits[SIDES[side_i]]
        blk = edit.document().findBlockByNumber(row)
        cur = QTextCursor(blk)
        cur.setPosition(blk.position() + col)  # sólo el cursor: la coincidencia se marca con el resaltado
        for e in (self.left_edit, self.right_edit):  # (una selección real taparía el resaltado y quedaría en el otro panel)
            if e is not edit and e.textCursor().hasSelection():
                c2 = e.textCursor()
                c2.clearSelection()
                e.setTextCursor(c2)
        edit.setTextCursor(cur)
        bar = self.left_edit.verticalScrollBar()
        first = self.left_edit.firstVisibleBlock().blockNumber()
        last = self.left_edit.cursorForPosition(QPoint(0, max(0, self.left_edit.viewport().height() - 1))).blockNumber()
        if row <= first or row >= last:  # fuera de la vista: se coloca a un tercio desde arriba
            bar.setValue(max(0, row - max(1, (last - first)) // 3))
        edit.ensureCursorVisible()  # (y desplaza en horizontal si hace falta)
        self._highlight()
        self._repaint_side()
        self._find_update_label()

    def _find_selections(self, side: str) -> list:
        out = []
        if not self._find_matches:
            return out
        edit = self.edits[side]
        side_i = SIDES.index(side)
        for i, (row, si, col, length) in enumerate(self._find_matches):
            if si != side_i:
                continue
            sel = QTextEdit.ExtraSelection()
            current = i == self._find_idx
            sel.format.setBackground(_alpha(FIND_CURRENT if current else FIND_ALL, 235 if current else 110))
            if current:
                sel.format.setForeground(QColor(0, 0, 0))
            c = QTextCursor(edit.document().findBlockByNumber(row))
            c.setPosition(c.position() + col)
            c.setPosition(c.position() + length, QTextCursor.KeepAnchor)
            sel.cursor = c
            out.append(sel)
        return out

    # ---- acciones -----------------------------------------------------------
    def copy_block(self, index: int, direction: str):
        if self.pending:
            self.flush()
            return  # los bloques cambiaron; hay que volver a pulsar la flecha
        self.undo_stack.append((list(self.left_doc.lines), list(self.right_doc.lines)))
        self.redo_stack.clear()
        self.current = index  # tras copiar, el siguiente bloque ocupa este índice
        apply_block(self.left_doc.lines, self.right_doc.lines, self.blocks[index], direction)
        self.rebuild()

    def undo(self):
        self.flush()
        if self.undo_stack:
            self.redo_stack.append(self._committed)
            l, r = self.undo_stack.pop()
            self.left_doc.lines, self.right_doc.lines = list(l), list(r)
            self.rebuild()

    def redo(self):
        self.flush()
        if self.redo_stack:
            self.undo_stack.append(self._committed)
            l, r = self.redo_stack.pop()
            self.left_doc.lines, self.right_doc.lines = list(l), list(r)
            self.rebuild()

    # ---- navegación entre diferencias ----------------------------------------------------
    def _update_info(self):
        n = len(self.blocks)
        if n == 0:
            self.info.setText("  Los ficheros son idénticos")
        elif self.current >= 0:
            self.info.setText(f"  Diferencia {self.current + 1} de {n}")
        else:
            self.info.setText(f"  {n} diferencia(s)")

    def _ruler_data(self):
        total = self.left_edit.document().blockCount()
        marks = [(b.row_start, b.row_start + b.row_count, ORANGE if b.tag == "replace" else GREEN)
                 for b in self.blocks]
        e = self.left_edit
        first = e.firstVisibleBlock().blockNumber()
        last = e.cursorForPosition(QPoint(0, max(0, e.viewport().height() - 1))).blockNumber()
        return total, marks, (first, last), self.current

    def _focused_edit(self) -> QPlainTextEdit:
        return next((e for e in (self.left_edit, self.right_edit) if e.hasFocus()), self.left_edit)

    def goto(self, step: int):
        """Va a la diferencia siguiente (step=1) o anterior (step=-1) respecto al cursor."""
        self.flush()
        if not self.blocks:
            return
        row = self._focused_edit().textCursor().blockNumber()
        untouched = self.current < 0 and row == 0  # aún no se ha navegado ni pulsado en el texto
        if step > 0:
            ref = -1 if untouched else row
            target = next((b for b in self.blocks if b.row_start > ref), self.blocks[0])  # al final, vuelve al inicio
        else:
            ref = len(self.rows) if untouched else row
            before = [b for b in self.blocks if b.row_start + b.row_count - 1 < ref]
            target = before[-1] if before else self.blocks[-1]  # al principio, salta al final
        self.goto_block(target.index)

    def goto_block(self, index: int):
        """Lleva el cursor y la vista al inicio del bloque `index` en los dos paneles."""
        blk = self.blocks[index]
        self.current = index
        focus = self._focused_edit()
        for e in (self.left_edit, self.right_edit):
            e.setTextCursor(QTextCursor(e.document().findBlockByNumber(blk.row_start)))
        focus.setFocus()
        edit = self.left_edit
        row_h = max(1, edit.blockBoundingGeometry(edit.document().findBlockByNumber(blk.row_start)).height())
        visible = max(1, int(edit.viewport().height() / row_h))
        edit.verticalScrollBar().setValue(max(0, blk.row_start - visible // 4))  # el bloque queda arriba, con contexto
        self._highlight()
        self._repaint_side()
        self._update_info()

    def _on_ruler_click(self, frac: float):
        """Pulsar en la regla: salta a la diferencia más cercana (o desplaza la vista si no hay ninguna cerca)."""
        total = max(1, self.left_edit.document().blockCount())
        row = min(total - 1, int(frac * total))

        def dist(b) -> int:
            if b.row_start <= row < b.row_start + b.row_count:
                return 0
            return min(abs(row - b.row_start), abs(row - (b.row_start + b.row_count - 1)))

        best = min(self.blocks, key=dist, default=None)
        if best is not None and dist(best) <= max(1, int(total * 0.02)):
            self.goto_block(best.index)
            return
        e = self.left_edit
        visible = max(1, int(e.viewport().height() / max(1, e.fontMetrics().lineSpacing())))
        e.verticalScrollBar().setValue(max(0, row - visible // 2))

    def save_side(self, side: str) -> bool:
        self.flush()
        doc = self._doc(side)
        try:
            doc.save()
        except OSError as e:
            QMessageBox.critical(self, "Error al guardar", f"{doc.path}\n\n{e}")
            return False
        self.saved_lines[side] = list(doc.lines)
        self.rebuild()
        self.saved.emit()
        return True

    def save_all(self) -> bool:
        self.flush()
        ok = True
        for s in SIDES:
            if self._doc(s).lines != self.saved_lines[s]:
                ok = self.save_side(s) and ok
        return ok
