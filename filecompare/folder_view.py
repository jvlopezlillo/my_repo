"""Vista de comparación de carpetas: árbol con estado y flechas de copia."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QBrush, QColor
from PySide6.QtWidgets import (QCheckBox, QFileDialog, QHBoxLayout, QHeaderView, QLabel,
                               QLineEdit, QMessageBox, QProgressBar, QPushButton, QStyle,
                               QToolButton, QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget)

from .core import (DiffNode, Progress, Status, compare_dirs, copy_path, count_files, iter_nodes,
                   parse_ignore, path_under, summarize)
from .worker import Worker

STATUS_TEXT = {
    Status.IDENTICAL: "Idéntico",
    Status.DIFFERENT: "Diferente",
    Status.LEFT_ONLY: "Sólo a la izquierda",
    Status.RIGHT_ONLY: "Sólo a la derecha",
    Status.TYPE_MISMATCH: "Tipo distinto (fichero/carpeta)",
}
STATUS_COLOR = {
    Status.DIFFERENT: QColor(235, 170, 40, 70),
    Status.LEFT_ONLY: QColor(70, 130, 220, 60),
    Status.RIGHT_ONLY: QColor(70, 175, 95, 60),
    Status.TYPE_MISMATCH: QColor(225, 80, 80, 70),
}
COL_NAME, COL_LEFT, COL_ACT, COL_RIGHT, COL_STATUS = range(5)
NODE_ROLE = Qt.UserRole


def _fmt_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n} B" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return ""


def _info(path: Optional[Path], kind: Optional[str]) -> str:
    if path is None:
        return ""
    try:
        st = path.stat()
    except OSError:
        return "?"
    when = time.strftime("%Y-%m-%d %H:%M", time.localtime(st.st_mtime))
    return f"{when}" if kind == "dir" else f"{_fmt_size(st.st_size)}   {when}"


class FolderView(QWidget):
    openFiles = Signal(object, object, bool)  # ruta izq., ruta der. (pueden no existir), ignorar espacios

    def __init__(self, left: str = "", right: str = "", parent=None):
        super().__init__(parent)
        self.root: Optional[DiffNode] = None
        self._expanded: Optional[set[str]] = None
        self.busy = False
        self._workers: set[Worker] = set()
        self._current_worker: Optional[Worker] = None

        self.left_edit, self.right_edit = QLineEdit(left), QLineEdit(right)
        self.ignore_edit = QLineEdit(".git, __pycache__")
        self.ignore_edit.setToolTip("Patrones a ignorar separados por comas (admite * y ?)")
        self.ws_chk = QCheckBox("Ignorar espacios")
        self.ws_chk.setToolTip("Ignora diferencias de espacios en blanco y tabuladores (y fin de línea)")
        self.eol_chk = QCheckBox("Ignorar fin de línea")
        self.eol_chk.setToolTip("Trata LF y CRLF como iguales")
        self.only_diff = QCheckBox("Sólo diferencias")
        self.confirm = QCheckBox("Confirmar al sobrescribir")
        self.confirm.setChecked(True)
        self.summary = QLabel()
        self.go = QPushButton("Comparar")
        self.go.clicked.connect(self.compare)
        for c in (self.ws_chk, self.eol_chk):
            c.toggled.connect(lambda _: self.root is not None and self.compare())
        for e in (self.left_edit, self.right_edit, self.ignore_edit):
            e.returnPressed.connect(self.compare)
        self.only_diff.toggled.connect(self._apply_filter)

        def row(label, edit, tooltip):
            lay = QHBoxLayout()
            lay.addWidget(QLabel(label))
            lay.addWidget(edit, 1)
            b = QPushButton("Examinar…")
            b.setToolTip(tooltip)
            b.clicked.connect(lambda: self._browse(edit))
            lay.addWidget(b)
            return lay

        paths = QHBoxLayout()
        paths.addLayout(row("Izquierda:", self.left_edit, "Elegir carpeta izquierda"), 1)
        paths.addLayout(row("Derecha:", self.right_edit, "Elegir carpeta derecha"), 1)
        opts = QHBoxLayout()
        opts.addWidget(QLabel("Ignorar:"))
        opts.addWidget(self.ignore_edit, 1)
        opts.addWidget(self.ws_chk)
        opts.addWidget(self.eol_chk)
        opts.addWidget(self.only_diff)
        opts.addWidget(self.confirm)
        opts.addWidget(self.go)
        self.controls = [self.left_edit, self.right_edit, self.ignore_edit, self.ws_chk,
                         self.eol_chk, self.confirm, self.go]

        self.tree = QTreeWidget()
        self.tree.setColumnCount(5)
        self.tree.setHeaderLabels(["Nombre", "Izquierda", "Copiar", "Derecha", "Estado"])
        self.tree.setUniformRowHeights(True)
        h = self.tree.header()
        h.setSectionResizeMode(COL_NAME, QHeaderView.Stretch)
        for c in (COL_LEFT, COL_RIGHT):
            h.setSectionResizeMode(c, QHeaderView.ResizeToContents)
        h.setSectionResizeMode(COL_ACT, QHeaderView.Fixed)
        self.tree.setColumnWidth(COL_ACT, 80)
        h.setSectionResizeMode(COL_STATUS, QHeaderView.ResizeToContents)
        self.tree.itemDoubleClicked.connect(self._double_click)

        lay = QVBoxLayout(self)
        lay.addLayout(paths)
        lay.addLayout(opts)
        lay.addWidget(self.tree, 1)
        lay.addWidget(self.summary)

        self.prog_label, self.bar = QLabel(), QProgressBar()
        self.cancel_btn = QPushButton("Cancelar")
        self.cancel_btn.clicked.connect(self._cancel)
        self.prog_row = QWidget()
        pl = QHBoxLayout(self.prog_row)
        pl.setContentsMargins(0, 0, 0, 0)
        pl.addWidget(self.prog_label)
        pl.addWidget(self.bar, 1)
        pl.addWidget(self.cancel_btn)
        self.prog_row.hide()
        lay.addWidget(self.prog_row)

        if left and right:
            self.compare()

    # ---- helpers ----------------------------------------------------------
    def _browse(self, edit: QLineEdit):
        d = QFileDialog.getExistingDirectory(self, "Elegir carpeta", edit.text() or str(Path.home()))
        if d:
            edit.setText(d)
            if self.left_edit.text() and self.right_edit.text():
                self.compare()

    def _ignore(self):
        return parse_ignore(self.ignore_edit.text())

    def _roots(self) -> tuple[Path, Path]:
        return Path(self.left_edit.text()), Path(self.right_edit.text())

    def _collect_expanded(self) -> set[str]:
        out: set[str] = set()

        def walk(it: QTreeWidgetItem):
            for i in range(it.childCount()):
                c = it.child(i)
                if c.isExpanded():
                    out.add(c.data(0, NODE_ROLE).rel)
                walk(c)

        walk(self.tree.invisibleRootItem())
        return out

    # ---- comparación ------------------------------------------------------
    # ---- hilo de trabajo --------------------------------------------------
    def _start(self, job, on_done, label: str, refresh_after_cancel: bool = False):
        self.busy = True
        for w in self.controls:
            w.setEnabled(False)
        self.tree.setEnabled(False)
        self.prog_label.setText(label)
        self.bar.setRange(0, 0)  # indeterminada hasta conocer el total
        self.prog_row.show()
        worker = Worker(job)
        self._workers.add(worker)
        self._current_worker = worker
        worker.progress.connect(self._on_progress)
        worker.succeeded.connect(lambda r: (self._finish(), on_done(r)))
        worker.failed.connect(lambda m: (self._finish(), QMessageBox.critical(self, "Error", m),
                                         self.compare() if refresh_after_cancel else None))
        worker.canceled.connect(lambda: (self._finish(), self.compare() if refresh_after_cancel else None))
        worker.finished.connect(lambda w=worker: self._workers.discard(w))
        worker.start()

    def _on_progress(self, done: int, total: int):
        if total > 0:
            self.bar.setRange(0, total)
            self.bar.setValue(min(done, total))
            self.bar.setFormat(f"{done} / {total}")

    def _finish(self):
        self.busy = False
        self._current_worker = None
        self.prog_row.hide()
        for w in self.controls:
            w.setEnabled(True)
        self.tree.setEnabled(True)

    def _cancel(self):
        if self._current_worker:
            self._current_worker.prog.cancel()
            self.prog_label.setText("Cancelando…")

    def shutdown(self):
        """Cancela y espera a los hilos (al cerrar la pestaña o la aplicación)."""
        for w in list(self._workers):
            w.prog.cancel()
            w.wait()

    # ---- comparación ------------------------------------------------------
    def compare(self):
        if self.busy:
            return
        left, right = self._roots()
        for p, name in ((left, "izquierda"), (right, "derecha")):
            if not p.is_dir():
                QMessageBox.warning(self, "Carpeta no válida", f"La carpeta {name} no existe:\n{p}")
                return
        if self.root is not None:
            self._expanded = self._collect_expanded()
        ignore, ws, eol = self._ignore(), self.ws_chk.isChecked(), self.eol_chk.isChecked()
        self._start(lambda p: compare_dirs(left, right, ignore, ws, eol, p),
                    self._on_compared, "Comparando…")

    def _on_compared(self, root: DiffNode):
        self.root = root
        self._populate()

    def _populate(self):
        self.tree.setUpdatesEnabled(False)
        self.tree.clear()
        style = self.style()
        dir_icon, file_icon = style.standardIcon(QStyle.SP_DirIcon), style.standardIcon(QStyle.SP_FileIcon)

        def add(parent, node: DiffNode):
            it = QTreeWidgetItem(parent)
            it.setData(0, NODE_ROLE, node)
            it.setText(COL_NAME, node.name)
            it.setIcon(COL_NAME, dir_icon if node.is_dir else file_icon)
            it.setText(COL_LEFT, _info(node.left, node.left_kind))
            it.setText(COL_RIGHT, _info(node.right, node.right_kind))
            it.setText(COL_STATUS, STATUS_TEXT[node.status])
            color = STATUS_COLOR.get(node.status)
            if color:
                for c in range(5):
                    it.setBackground(c, QBrush(color))
            if node.status is not Status.IDENTICAL:
                self.tree.setItemWidget(it, COL_ACT, self._arrows(node))
            for ch in node.children:
                add(it, ch)

        for ch in self.root.children:
            add(self.tree.invisibleRootItem(), ch)

        # expandir: lo que estaba abierto, o las carpetas con diferencias la primera vez
        def restore(it: QTreeWidgetItem):
            for i in range(it.childCount()):
                c = it.child(i)
                n: DiffNode = c.data(0, NODE_ROLE)
                if n.is_dir and (n.rel in self._expanded if self._expanded is not None
                                 else n.status is Status.DIFFERENT):
                    c.setExpanded(True)
                restore(c)

        restore(self.tree.invisibleRootItem())
        self.tree.setUpdatesEnabled(True)
        self._apply_filter()
        s = summarize(self.root)
        self.summary.setText(
            f"Ficheros: {s[Status.DIFFERENT]} diferentes · {s[Status.LEFT_ONLY]} sólo izquierda · "
            f"{s[Status.RIGHT_ONLY]} sólo derecha · {s[Status.TYPE_MISMATCH]} tipo distinto · "
            f"{s[Status.IDENTICAL]} idénticos"
        )

    def _arrows(self, node: DiffNode) -> QWidget:
        w = QWidget()
        lay = QHBoxLayout(w)
        lay.setContentsMargins(2, 0, 2, 0)
        lay.setSpacing(2)
        for text, direction, enabled, tip in (
            ("→", "ltr", node.left is not None, "Copiar de izquierda a derecha (machaca el destino)"),
            ("←", "rtl", node.right is not None, "Copiar de derecha a izquierda (machaca el destino)"),
        ):
            b = QToolButton()
            b.setText(text)
            b.setToolTip(tip)
            b.setEnabled(enabled)
            b.clicked.connect(lambda _=False, n=node, d=direction: self.copy_node(n, d))
            lay.addWidget(b)
        return w

    def _apply_filter(self):
        only = self.only_diff.isChecked()

        def walk(it: QTreeWidgetItem):
            for i in range(it.childCount()):
                c = it.child(i)
                c.setHidden(only and c.data(0, NODE_ROLE).status is Status.IDENTICAL)
                walk(c)

        walk(self.tree.invisibleRootItem())

    # ---- acciones ---------------------------------------------------------
    def copy_node(self, node: DiffNode, direction: str):
        if self.busy:
            return
        left_root, right_root = self._roots()
        if direction == "ltr":
            src, dst, extra_status = node.left, path_under(right_root, node.rel), Status.RIGHT_ONLY
        else:
            src, dst, extra_status = node.right, path_under(left_root, node.rel), Status.LEFT_ONLY
        if src is None:
            return
        if self.confirm.isChecked() and (dst.exists() or dst.is_symlink()):
            if node.is_dir:
                n = sum(1 for x in iter_nodes(node) if x.is_file and x.status is extra_status)
                warn = (f"\n\nLa carpeta destino quedará IDÉNTICA al origen: se sobrescriben los "
                        f"ficheros distintos, se añaden los que faltan y se ELIMINARÁN {n} fichero(s) "
                        f"que sólo existen en el destino.")
            else:
                warn = ""
            r = QMessageBox.question(
                self, "Confirmar copia",
                f"Se sobrescribirá:\n{dst}\n\ncon:\n{src}{warn}\n\n¿Continuar?",
                QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
            if r != QMessageBox.Yes:
                return
        ignore = self._ignore()

        def job(p: Progress):
            p.set_total(max(1, count_files(src, ignore, p)))
            copy_path(src, dst, ignore, p)

        self._start(job, lambda _r: self.compare(), "Copiando…", refresh_after_cancel=True)

    def _double_click(self, item: QTreeWidgetItem, _col: int):
        node: DiffNode = item.data(0, NODE_ROLE)
        if node.is_dir:
            return  # el árbol ya expande/contrae
        if node.status is Status.TYPE_MISMATCH:
            QMessageBox.information(self, "Tipo distinto",
                                    "Uno de los lados es una carpeta y el otro un fichero.")
            return
        left_root, right_root = self._roots()
        self.openFiles.emit(path_under(left_root, node.rel), path_under(right_root, node.rel),
                            self.ws_chk.isChecked())
