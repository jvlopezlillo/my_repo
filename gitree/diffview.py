"""Visor de diff con colores y acciones por hunk."""
from __future__ import annotations

from PySide6.QtCore import Signal
from PySide6.QtGui import (QAction, QColor, QFont, QFontDatabase, QPainter, QSyntaxHighlighter,
                           QTextCharFormat)
from PySide6.QtWidgets import QPlainTextEdit

# estados de bloque: 0 cabecera de fichero, 1 cabecera de hunk, 2 añadida, 3 borrada, 4 contexto
_BG = {1: QColor("#e8eefc"), 2: QColor("#e6ffed"), 3: QColor("#ffeef0")}
_FG = {0: QColor("#6a737d"), 1: QColor("#5a4fcf"), 2: QColor("#1a7f37"), 3: QColor("#b31d28")}
MAX_CHARS = 400_000


class _Highlighter(QSyntaxHighlighter):
    def highlightBlock(self, text: str) -> None:
        if text.startswith("diff --git") or text.startswith("diff --cc"):
            state = 0
        elif text.startswith("@@"):
            state = 1
        elif self.previousBlockState() >= 1:
            state = {"+": 2, "-": 3}.get(text[:1], 4)
        else:
            state = 0
        self.setCurrentBlockState(state)
        if state in _FG:
            fmt = QTextCharFormat()
            fmt.setForeground(_FG[state])
            if state in (0, 1):
                fmt.setFontWeight(QFont.Weight.DemiBold)
            self.setFormat(0, len(text), fmt)


class DiffView(QPlainTextEdit):
    """mode: None | 'commit' | 'unstaged' | 'staged'"""

    hunkRequested = Signal(str, str)  # acción ('stage'|'unstage'|'discard'), parche

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setReadOnly(True)
        self.setLineWrapMode(QPlainTextEdit.LineWrapMode.NoWrap)
        self.setFont(QFontDatabase.systemFont(QFontDatabase.SystemFont.FixedFont))
        self._hl = _Highlighter(self.document())
        self._raw = ""
        self.mode: str | None = None
        self.hunks_enabled = False

    def show_diff(self, text: str, mode: str | None, hunks: bool = False) -> None:
        self.mode, self.hunks_enabled = mode, hunks
        self._raw = text
        if len(text) > MAX_CHARS:
            text = text[:MAX_CHARS] + "\n\n… diff truncado (demasiado grande) …"
        elif not text.strip():
            text = "(sin cambios que mostrar)"
        self.setPlainText(text)

    # ---- fondo de línea completa
    def paintEvent(self, event):
        p = QPainter(self.viewport())
        block = self.firstVisibleBlock()
        top = self.blockBoundingGeometry(block).translated(self.contentOffset()).top()
        while block.isValid() and top <= event.rect().bottom():
            h = self.blockBoundingRect(block).height()
            color = _BG.get(block.userState())
            if color is not None:
                p.fillRect(0, int(top), self.viewport().width(), int(h), color)
            top += h
            block = block.next()
        p.end()
        super().paintEvent(event)

    # ---- hunks
    def _hunk_patch(self, block_no: int) -> str | None:
        lines = self._raw.split("\n")
        starts = [i for i, l in enumerate(lines) if l.startswith("@@")]
        if not starts:
            return None
        idx = None
        for n, s in enumerate(starts):
            end = starts[n + 1] if n + 1 < len(starts) else len(lines)
            if s <= block_no < end:
                idx = (s, end)
        if idx is None:
            return None
        body = lines[idx[0]:idx[1]]
        while body and body[-1] == "":
            body.pop()
        return "\n".join(lines[:starts[0]] + body) + "\n"

    def contextMenuEvent(self, event):
        menu = self.createStandardContextMenu()
        if self.hunks_enabled and self.mode in ("unstaged", "staged"):
            patch = self._hunk_patch(self.cursorForPosition(event.pos()).blockNumber())
            if patch:
                first = menu.actions()[0]
                if self.mode == "unstaged":
                    specs = [("Añadir hunk al stage", "stage"), ("Descartar hunk", "discard")]
                else:
                    specs = [("Quitar hunk del stage", "unstage")]
                for text, act in specs:
                    a = QAction(text, menu)
                    a.triggered.connect(lambda _=False, act=act: self.hunkRequested.emit(act, patch))
                    menu.insertAction(first, a)
                menu.insertSeparator(first)
        menu.exec(event.globalPos())
