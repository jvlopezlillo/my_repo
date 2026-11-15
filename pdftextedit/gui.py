"""Interfaz gráfica (PySide6): ver el PDF, pulsar un texto y reemplazarlo."""
from __future__ import annotations

import os
import sys
import urllib.parse
from typing import Optional

from PySide6.QtCore import Qt, QRectF, QUrl
from PySide6.QtGui import (QAction, QBrush, QColor, QDesktopServices, QImage, QKeySequence,
                           QPainter, QPen, QPixmap)
from PySide6.QtWidgets import (QApplication, QDialog, QDockWidget, QFileDialog, QFormLayout,
                               QGraphicsRectItem, QGraphicsScene, QGraphicsView, QLabel,
                               QLineEdit, QMainWindow, QMessageBox, QPushButton, QSpinBox,
                               QToolBar, QVBoxLayout, QWidget)

from .engine import EditCheck, PdfEditor, Span
from .fonts import system_font_dirs, user_font_dir

KIND_TEXT = {
    "embedded": "incrustada en el PDF",
    "installed": "instalada en el sistema",
    "user": "fuente aportada por ti",
    "builtin": "fuente estándar",
}


class SpanItem(QGraphicsRectItem):
    def __init__(self, span: Span, rect: QRectF, view: "PageView"):
        super().__init__(rect)
        self.span, self.view = span, view
        self.setAcceptHoverEvents(True)
        self.setPen(QPen(Qt.NoPen))
        self.setBrush(QBrush(Qt.NoBrush))

    def hoverEnterEvent(self, e):
        self.setBrush(QBrush(QColor(0, 120, 215, 40)))
        self.setCursor(Qt.IBeamCursor)

    def hoverLeaveEvent(self, e):
        if self.view.selected is not self:
            self.setBrush(QBrush(Qt.NoBrush))

    def mousePressEvent(self, e):
        self.view.select(self)


class PageView(QGraphicsView):
    def __init__(self, win: "MainWindow"):
        super().__init__()
        self.win = win
        self.setScene(QGraphicsScene(self))
        self.setRenderHints(QPainter.Antialiasing | QPainter.SmoothPixmapTransform)
        self.setBackgroundBrush(QBrush(QColor(90, 90, 90)))
        self.selected: Optional[SpanItem] = None

    def select(self, item: Optional[SpanItem]):
        if self.selected is not None:
            self.selected.setBrush(QBrush(Qt.NoBrush))
            self.selected.setPen(QPen(Qt.NoPen))
        self.selected = item
        if item is not None:
            item.setBrush(QBrush(QColor(0, 120, 215, 60)))
            item.setPen(QPen(QColor(0, 120, 215), 1.5))
        self.win.on_span_selected(item.span if item else None)

    def wheelEvent(self, e):
        if e.modifiers() & Qt.ControlModifier:
            self.win.set_zoom(self.win.zoom * (1.15 if e.angleDelta().y() > 0 else 1 / 1.15))
        else:
            super().wheelEvent(e)


class MissingFontDialog(QDialog):
    """Se muestra cuando la fuente original no tiene todos los caracteres pedidos."""
    CHOOSE, SEARCH, OPEN_DIR, EQUIVALENT = 1, 2, 3, 4

    def __init__(self, chk: EditCheck, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Fuente original no disponible")
        self.setMinimumWidth(520)
        self.action = 0
        sp = chk.span
        lay = QVBoxLayout(self)
        if chk.font_found:
            why = (f"La fuente <b>{sp.font}</b> ({KIND_TEXT.get(chk.primary.kind, '')}) "
                   f"no contiene estos caracteres: <b>{' '.join(chk.missing)}</b>.<br>"
                   "Los PDF suelen incrustar solo las letras que usan.")
        else:
            why = (f"La fuente <b>{sp.font}</b> no está incrustada en el PDF ni instalada "
                   "en este equipo, así que no puedo reproducirla exactamente.")
        lay.addWidget(self._label(why + "<br><br>Para que el texto quede <b>idéntico</b>, "
                                  "necesitas esa fuente. Opciones:"))
        for text, code in (("Elegir el archivo de la fuente (.ttf / .otf)…", self.CHOOSE),
                           ("Buscar esta fuente en internet", self.SEARCH),
                           ("Abrir la carpeta de fuentes del sistema (para instalarla)", self.OPEN_DIR)):
            b = QPushButton(text); b.clicked.connect(lambda _=False, c=code: self._done(c))
            lay.addWidget(b)
        lay.addWidget(self._label("<br>Si no es posible conseguirla, como último recurso:"))
        eq = QPushButton("Usar una fuente equivalente (mismo estilo y tamaño) solo para los "
                         "caracteres que faltan")
        eq.clicked.connect(lambda: self._done(self.EQUIVALENT))
        lay.addWidget(eq)
        cancel = QPushButton("Cancelar"); cancel.clicked.connect(self.reject)
        lay.addWidget(cancel)

    @staticmethod
    def _label(t):
        l = QLabel(t); l.setWordWrap(True); l.setTextFormat(Qt.RichText)
        return l

    def _done(self, code):
        self.action = code
        self.accept()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("PDF Text Edit")
        self.resize(1200, 800)
        self.ed = PdfEditor()
        self.page = 0
        self.zoom = 1.5
        self.view = PageView(self)
        self.setCentralWidget(self.view)
        self._build_ui()
        self.setAcceptDrops(True)
        self.statusBar().showMessage("Abre un PDF (Ctrl+O)")

    # ---------------------------------------------------------------- UI
    def _build_ui(self):
        tb = QToolBar("Principal"); tb.setMovable(False); self.addToolBar(tb)

        def act(text, slot, key=None):
            a = QAction(text, self); a.triggered.connect(slot)
            if key: a.setShortcut(QKeySequence(key))
            tb.addAction(a); return a
        act("Abrir…", self.open_dialog, QKeySequence.Open)
        self.a_save = act("Guardar", self.save, QKeySequence.Save)
        act("Guardar como…", self.save_as, QKeySequence("Ctrl+Shift+S"))
        tb.addSeparator()
        act("Deshacer", self.undo, QKeySequence.Undo)
        act("Rehacer", self.redo, QKeySequence.Redo)
        tb.addSeparator()
        act("◀", lambda: self.goto(self.page - 1), "PgUp")
        self.page_lbl = QLabel(" - / - "); tb.addWidget(self.page_lbl)
        act("▶", lambda: self.goto(self.page + 1), "PgDown")
        tb.addSeparator()
        act("Zoom −", lambda: self.set_zoom(self.zoom / 1.2), "Ctrl+-")
        act("Zoom +", lambda: self.set_zoom(self.zoom * 1.2), "Ctrl+=")

        dock = QDockWidget("Texto seleccionado", self)
        dock.setFeatures(QDockWidget.NoDockWidgetFeatures)
        w = QWidget(); form = QFormLayout(w)
        self.f_font = QLabel("—"); self.f_size = QLabel("—"); self.f_style = QLabel("—")
        self.f_color = QLabel("—"); self.f_src = QLabel("—")
        for l in (self.f_font, self.f_src):
            l.setWordWrap(True)
        form.addRow("Fuente:", self.f_font)
        form.addRow("Tamaño:", self.f_size)
        form.addRow("Estilo:", self.f_style)
        form.addRow("Color:", self.f_color)
        form.addRow("Origen fuente:", self.f_src)
        self.edit = QLineEdit(); self.edit.setEnabled(False)
        self.edit.returnPressed.connect(self.apply)
        form.addRow("Nuevo texto:", self.edit)
        self.btn = QPushButton("Reemplazar (Enter)"); self.btn.setEnabled(False)
        self.btn.clicked.connect(self.apply)
        form.addRow(self.btn)
        dock.setWidget(w); dock.setMinimumWidth(300)
        self.addDockWidget(Qt.RightDockWidgetArea, dock)

    # ---------------------------------------------------------------- archivo
    def open_dialog(self):
        p, _ = QFileDialog.getOpenFileName(self, "Abrir PDF", "", "PDF (*.pdf)")
        if p:
            self.open_path(p)

    def open_path(self, path: str):
        try:
            self.ed.open(path)
        except Exception as ex:
            QMessageBox.critical(self, "Error", f"No se pudo abrir el PDF:\n{ex}")
            return
        self.page = 0
        self.setWindowTitle(f"PDF Text Edit — {os.path.basename(path)}")
        self.show_page()

    def save(self):
        if not self.ed.doc:
            return
        if not self.ed.path:
            return self.save_as()
        self.ed.save(); self.statusBar().showMessage("Guardado", 3000)

    def save_as(self):
        if not self.ed.doc:
            return
        p, _ = QFileDialog.getSaveFileName(self, "Guardar como", self.ed.path or "", "PDF (*.pdf)")
        if p:
            self.ed.save(p); self.statusBar().showMessage(f"Guardado en {p}", 3000)

    def closeEvent(self, e):
        if self.ed.dirty:
            r = QMessageBox.question(self, "Cambios sin guardar", "¿Guardar los cambios antes de salir?",
                                     QMessageBox.Save | QMessageBox.Discard | QMessageBox.Cancel)
            if r == QMessageBox.Cancel:
                e.ignore(); return
            if r == QMessageBox.Save:
                self.save()
        e.accept()

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e):
        for u in e.mimeData().urls():
            if u.toLocalFile().lower().endswith(".pdf"):
                self.open_path(u.toLocalFile()); break

    # ---------------------------------------------------------------- páginas
    def goto(self, n: int):
        if self.ed.doc and 0 <= n < self.ed.page_count:
            self.page = n; self.show_page()

    def set_zoom(self, z: float):
        self.zoom = max(0.3, min(6.0, z))
        if self.ed.doc:
            self.show_page()

    def show_page(self):
        sc = self.view.scene(); sc.clear(); self.view.selected = None
        pm = self.ed.render(self.page, self.zoom)
        img = QImage(pm.samples, pm.width, pm.height, pm.stride, QImage.Format_RGB888).copy()
        sc.addPixmap(QPixmap.fromImage(img))
        sc.setSceneRect(0, 0, pm.width, pm.height)
        z = self.zoom
        for s in self.ed.spans(self.page):
            x0, y0, x1, y1 = s.bbox
            sc.addItem(SpanItem(s, QRectF(x0 * z, y0 * z, (x1 - x0) * z, (y1 - y0) * z), self.view))
        self.page_lbl.setText(f" {self.page + 1} / {self.ed.page_count} ")
        self.on_span_selected(None)

    # ---------------------------------------------------------------- edición
    def on_span_selected(self, s: Optional[Span]):
        self._span = s
        on = s is not None
        self.edit.setEnabled(on); self.btn.setEnabled(on)
        if not on:
            for l in (self.f_font, self.f_size, self.f_style, self.f_color, self.f_src):
                l.setText("—")
            self.edit.clear(); return
        self.f_font.setText(s.font)
        self.f_size.setText(f"{s.size:.2f} pt")
        self.f_style.setText(s.style_label)
        r, g, b = (int(c * 255) for c in s.color)
        self.f_color.setText(f"<span style='background:rgb({r},{g},{b})'>&nbsp;&nbsp;&nbsp;&nbsp;</span> "
                             f"#{r:02X}{g:02X}{b:02X}")
        chk = self.ed.check_edit(s, s.text)
        self.f_src.setText(KIND_TEXT.get(chk.primary.kind, "") if chk.primary
                           else "<b style='color:#c00'>no disponible</b>")
        self.edit.setText(s.text); self.edit.setFocus(); self.edit.selectAll()

    def apply(self):
        s = getattr(self, "_span", None)
        if s is None:
            return
        new = self.edit.text()
        if new == s.text or not new.strip():
            return
        user_font = None
        while True:
            chk = self.ed.check_edit(s, new, user_font)
            if chk.ok:
                self.ed.apply_edit(chk); break
            dlg = MissingFontDialog(chk, self)
            if dlg.exec() != QDialog.Accepted:
                return
            if dlg.action == dlg.CHOOSE:
                p, _ = QFileDialog.getOpenFileName(self, "Archivo de fuente", "",
                                                   "Fuentes (*.ttf *.otf)")
                if p:
                    user_font = self.ed.install_font_file(p)
            elif dlg.action == dlg.SEARCH:
                q = urllib.parse.quote(f"{s.font} font download")
                QDesktopServices.openUrl(QUrl(f"https://www.google.com/search?q={q}"))
                QMessageBox.information(
                    self, "Instalar la fuente",
                    "Descarga la fuente, luego pulsa «Elegir el archivo de la fuente» "
                    "(o instálala en Windows con doble clic → Instalar) y vuelve a intentarlo.")
            elif dlg.action == dlg.OPEN_DIR:
                QDesktopServices.openUrl(QUrl.fromLocalFile(str(system_font_dirs()[0])))
                QMessageBox.information(
                    self, "Instalar la fuente",
                    "Instala la fuente en el sistema y pulsa Aceptar; la volveré a buscar.")
                self.ed.index.load(force=True)
            elif dlg.action == dlg.EQUIVALENT:
                self.ed.apply_edit(chk, allow_equivalent=True)
                self.statusBar().showMessage(
                    "Se usó una fuente equivalente para: " + " ".join(chk.missing), 8000)
                break
        keep = self.page
        self.show_page(); self.page = keep

    def undo(self):
        if self.ed.undo(): self.show_page()

    def redo(self):
        if self.ed.redo(): self.show_page()


def main():
    app = QApplication(sys.argv)
    w = MainWindow(); w.show()
    if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
        w.open_path(sys.argv[1])
    sys.exit(app.exec())
