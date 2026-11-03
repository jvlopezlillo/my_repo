"""Ventana principal y punto de entrada."""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QAction, QKeySequence
from PySide6.QtWidgets import (QApplication, QFileDialog, QLabel, QMainWindow, QMessageBox,
                               QTabWidget, QVBoxLayout, QWidget)

from . import __version__
from .folder_view import FolderView
from .text_view import TextDiffView
from .textdiff import BinaryFileError, TextDocument

MAX_TEXT_BYTES = 20 * 1024 * 1024


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(f"FileCompare {__version__}")
        self.resize(1200, 750)
        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.setDocumentMode(True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.welcome = QLabel(
            "<h2>FileCompare</h2><p>Archivo → Comparar carpetas (Ctrl+D)<br>"
            "Archivo → Comparar ficheros (Ctrl+F)</p>")
        self.welcome.setAlignment(Qt.AlignCenter)
        central = QWidget()
        lay = QVBoxLayout(central)
        lay.addWidget(self.welcome)
        lay.addWidget(self.tabs)
        self.setCentralWidget(central)
        self.tabs.hide()

        menu = self.menuBar().addMenu("&Archivo")
        for text, key, slot in (
            ("Comparar &carpetas…", "Ctrl+D", lambda: self.open_folders()),
            ("Comparar &ficheros…", "Ctrl+F", self.pick_files),
            ("&Salir", QKeySequence.Quit, self.close),
        ):
            a = QAction(text, self)
            a.setShortcut(key)
            a.triggered.connect(slot)
            menu.addAction(a)

    # ---- pestañas ---------------------------------------------------------
    def _add_tab(self, widget: QWidget, title: str, tip: str = ""):
        self.welcome.hide()
        self.tabs.show()
        i = self.tabs.addTab(widget, title)
        self.tabs.setTabToolTip(i, tip)
        self.tabs.setCurrentIndex(i)
        return i

    def open_folders(self, left: str = "", right: str = ""):
        view = FolderView(left, right)
        view.openFiles.connect(self.open_text)
        self._add_tab(view, "Carpetas")

    def pick_files(self):
        a, _ = QFileDialog.getOpenFileName(self, "Fichero izquierdo")
        if not a:
            return
        b, _ = QFileDialog.getOpenFileName(self, "Fichero derecho", str(Path(a).parent))
        if b:
            self.open_text(Path(a), Path(b))

    def open_text(self, left: Path, right: Path):
        left, right = Path(left), Path(right)
        for i in range(self.tabs.count()):
            w = self.tabs.widget(i)
            if isinstance(w, TextDiffView) and w.left_doc.path == left and w.right_doc.path == right:
                self.tabs.setCurrentIndex(i)
                return
        for p in (left, right):
            if p.exists() and p.stat().st_size > MAX_TEXT_BYTES:
                QMessageBox.information(self, "Fichero demasiado grande",
                                        f"{p}\nsupera {MAX_TEXT_BYTES // 2**20} MB.")
                return
        try:
            ldoc, rdoc = TextDocument.load(left), TextDocument.load(right)
        except BinaryFileError:
            sizes = "\n".join(f"{p}: {p.stat().st_size} bytes" if p.exists() else f"{p}: (no existe)"
                              for p in (left, right))
            QMessageBox.information(self, "Fichero binario",
                                    f"No se puede mostrar la diferencia de ficheros binarios.\n\n{sizes}")
            return
        except OSError as e:
            QMessageBox.critical(self, "Error al leer", str(e))
            return
        view = TextDiffView(ldoc, rdoc)
        name = left.name if left.name == right.name else f"{left.name} ↔ {right.name}"
        i = self._add_tab(view, name, f"{left}\n{right}")
        view.dirtyChanged.connect(lambda d, v=view, n=name: self._mark(v, n, d))
        view.saved.connect(self.refresh_folders)

    def _mark(self, view: QWidget, name: str, dirty: bool):
        i = self.tabs.indexOf(view)
        if i >= 0:
            self.tabs.setTabText(i, name + (" *" if dirty else ""))

    def refresh_folders(self):
        for i in range(self.tabs.count()):
            w = self.tabs.widget(i)
            if isinstance(w, FolderView) and w.root is not None:
                w.compare()

    def _confirm_close(self, w: QWidget) -> bool:
        if isinstance(w, TextDiffView) and w.dirty:
            r = QMessageBox.question(
                self, "Cambios sin guardar",
                "Hay cambios sin guardar en esta comparación. ¿Guardarlos?",
                QMessageBox.Save | QMessageBox.Discard | QMessageBox.Cancel)
            if r == QMessageBox.Cancel:
                return False
            if r == QMessageBox.Save:
                return w.save_all()
        return True

    def close_tab(self, i: int):
        w = self.tabs.widget(i)
        if self._confirm_close(w):
            self.tabs.removeTab(i)
            w.deleteLater()
            if self.tabs.count() == 0:
                self.tabs.hide()
                self.welcome.show()

    def closeEvent(self, e):
        for i in range(self.tabs.count()):
            self.tabs.setCurrentIndex(i)
            if not self._confirm_close(self.tabs.widget(i)):
                e.ignore()
                return
        e.accept()


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv if argv is None else argv
    app = QApplication(argv)
    app.setApplicationName("FileCompare")
    win = MainWindow()
    args = argv[1:]
    if len(args) == 2:
        a, b = Path(args[0]), Path(args[1])
        if a.is_dir() and b.is_dir():
            win.open_folders(str(a), str(b))
        else:
            win.open_text(a, b)
    elif args:
        print("Uso: filecompare [IZQUIERDA DERECHA]", file=sys.stderr)
        return 2
    win.show()
    return app.exec()
