"""Vista de comparación de carpetas: árbol con estado y flechas de copia."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Optional

from PySide6.QtCore import QEvent, QPoint, QRect, Qt, QTimer, Signal
from PySide6.QtGui import QBrush, QColor
from PySide6.QtWidgets import (QApplication, QCheckBox, QStyleOptionButton, QStyledItemDelegate, QToolTip, QFileDialog, QHBoxLayout, QHeaderView, QLabel,
                               QLineEdit, QMessageBox, QProgressBar, QPushButton, QStyle,
                               QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget)

from .core import (DiffNode, Progress, Status, compare_dirs, copy_path, count_files, delete_path,
                   iter_nodes, parse_ignore, path_under, refresh_path, summarize)
from .dialogs import file_dialog_options
from .log import logger
from .ruler import GREEN, ORANGE, OverviewRuler
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
    Status.IDENTICAL: QColor(70, 175, 95, 60),  # coincide en ambas carpetas
    Status.TYPE_MISMATCH: QColor(225, 80, 80, 70),
}
COL_LNAME, COL_LEFT, COL_ACT, COL_RNAME, COL_RIGHT, COL_STATUS = range(6)
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


class ActionDelegate(QStyledItemDelegate):
    """Dibuja los botones ✕ → ← ✕ de cada fila sin crear un widget por fila (mucho más rápido)."""

    BTN_W, GAP = 28, 2

    def __init__(self, parent, on_action):
        super().__init__(parent)
        self.on_action = on_action  # (nodo, acción)

    def _buttons(self, rect: QRect, node: DiffNode):
        differs = node.status is not Status.IDENTICAL
        specs = (
            ("✕", "del_left", node.left is not None, True, "Eliminar de la izquierda"),
            ("→", "ltr", node.left is not None, differs, "Copiar de izquierda a derecha (machaca el destino)"),
            ("←", "rtl", node.right is not None, differs, "Copiar de derecha a izquierda (machaca el destino)"),
            ("✕", "del_right", node.right is not None, True, "Eliminar de la derecha"),
        )
        total = len(specs) * self.BTN_W + (len(specs) - 1) * self.GAP
        x = rect.x() + max(0, (rect.width() - total) // 2)
        h = min(rect.height() - 2, 22)
        y = rect.y() + (rect.height() - h) // 2
        out = []
        for i, (glyph, action, enabled, visible, tip) in enumerate(specs):
            if visible:
                out.append((QRect(x + i * (self.BTN_W + self.GAP), y, self.BTN_W, h), glyph, action, enabled, tip))
        return out

    def paint(self, painter, option, index):
        super().paint(painter, option, index)  # fondo (color de estado / selección)
        node = index.data(NODE_ROLE)
        if node is None:
            return
        style = option.widget.style() if option.widget else QApplication.style()
        for r, glyph, action, enabled, _tip in self._buttons(option.rect, node):
            opt = QStyleOptionButton()
            opt.rect, opt.text = r, glyph
            opt.state = QStyle.State_Raised | (QStyle.State_Enabled if enabled else QStyle.State_None)
            opt.palette = option.palette
            if glyph == "✕" and enabled:
                opt.palette.setColor(opt.palette.ColorRole.ButtonText, QColor(192, 57, 43))
            painter.save()
            if not enabled:
                painter.setOpacity(0.35)
            style.drawControl(QStyle.CE_PushButton, opt, painter)
            painter.restore()

    def _hit(self, rect, node, pos: QPoint):
        for r, _g, action, enabled, tip in self._buttons(rect, node):
            if r.contains(pos):
                return action, enabled, tip
        return None

    def editorEvent(self, event, model, option, index):
        node = index.data(NODE_ROLE)
        if node is not None and event.type() == QEvent.MouseButtonRelease and event.button() == Qt.LeftButton:
            hit = self._hit(option.rect, node, event.position().toPoint())
            if hit and hit[1]:
                # diferido: la acción puede reconstruir el árbol y no debe hacerse dentro del evento
                QTimer.singleShot(0, lambda n=node, a=hit[0]: self.on_action(n, a))
                return True
        return False

    def helpEvent(self, event, view, option, index):
        node = index.data(NODE_ROLE)
        hit = self._hit(option.rect, node, event.pos()) if node is not None else None
        if hit:
            QToolTip.showText(event.globalPos(), hit[2], view)
            return True
        QToolTip.hideText()
        return False


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
        self.ws_chk.setChecked(True)  # activadas por defecto
        self.eol_chk.setChecked(True)
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
        self.tree.setColumnCount(6)
        self.tree.setHeaderLabels(["Izquierda", "Tamaño / fecha", "Copiar", "Derecha", "Tamaño / fecha", "Estado"])
        self.tree.setUniformRowHeights(True)
        h = self.tree.header()
        for c in (COL_LNAME, COL_RNAME):
            h.setSectionResizeMode(c, QHeaderView.Stretch)
        for c in (COL_LEFT, COL_RIGHT):
            h.setSectionResizeMode(c, QHeaderView.ResizeToContents)
        h.setSectionResizeMode(COL_ACT, QHeaderView.Fixed)
        self.tree.setColumnWidth(COL_ACT, 130)
        h.setSectionResizeMode(COL_STATUS, QHeaderView.ResizeToContents)
        self.tree.setItemDelegateForColumn(COL_ACT, ActionDelegate(self.tree, self._on_action))
        self.tree.setMouseTracking(True)
        self.tree.itemDoubleClicked.connect(self._double_click)

        lay = QVBoxLayout(self)
        lay.addLayout(paths)
        lay.addLayout(opts)
        self._ruler_rows: Optional[list] = None  # filas visibles (caché de la regla)
        self.ruler = OverviewRuler(self._ruler_data, top_margin=lambda: self.tree.header().height())
        self.ruler.clicked.connect(self._on_ruler_click)
        tree_row = QHBoxLayout()
        tree_row.setSpacing(0)
        tree_row.addWidget(self.tree, 1)
        tree_row.addWidget(self.ruler)
        lay.addLayout(tree_row, 1)
        self.tree.itemExpanded.connect(self._ruler_invalidate)
        self.tree.itemCollapsed.connect(self._ruler_invalidate)
        self.tree.verticalScrollBar().valueChanged.connect(lambda _=0: self.ruler.update())
        self.tree.verticalScrollBar().rangeChanged.connect(lambda *_: self.ruler.update())
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
        d = QFileDialog.getExistingDirectory(
            self, "Elegir carpeta", edit.text() or str(Path.home()),
            file_dialog_options(QFileDialog.Option.ShowDirsOnly))
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
        self.prog_label.setText(label)
        self.bar.setRange(0, 0)  # indeterminada hasta conocer el total
        self.prog_row.show()
        t0 = time.perf_counter()
        logger.info("%s inicio", label)
        worker = Worker(job)
        self._workers.add(worker)
        self._current_worker = worker
        worker.progress.connect(self._on_progress)
        worker.succeeded.connect(lambda r: (logger.info("%s hecho en %.2fs", label, time.perf_counter() - t0),
                                            self._finish(), on_done(r)))
        worker.failed.connect(lambda m: (logger.error("%s falló: %s", label, m), self._finish(),
                                         QMessageBox.critical(self, "Error", m),
                                         self.compare() if refresh_after_cancel else None))
        worker.canceled.connect(lambda: (logger.warning("%s cancelado", label), self._finish(),
                                         self.compare() if refresh_after_cancel else None))
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

    def _refresh_job(self, rel: str):
        """Trabajo para el hilo: vuelve a comparar sólo `rel` (o todo si el árbol no lo permite)."""
        left, right = self._roots()
        ignore, ws, eol = self._ignore(), self.ws_chk.isChecked(), self.eol_chk.isChecked()
        root = self.root

        def job(p: Progress):
            try:
                refresh_path(root, rel, left, right, ignore, ws, eol, p)
                return root
            except KeyError:
                logger.warning("refresh_path no pudo ubicar %r; se compara todo", rel)
                return compare_dirs(left, right, ignore, ws, eol, p)

        return job

    def _on_compared(self, root: DiffNode):
        self.root = root
        self._populate()

    def _populate(self):
        t0 = time.perf_counter()
        self.tree.setUpdatesEnabled(False)
        style = self.style()
        dir_icon, file_icon = style.standardIcon(QStyle.SP_DirIcon), style.standardIcon(QStyle.SP_FileIcon)

        def make(node: DiffNode) -> QTreeWidgetItem:
            it = QTreeWidgetItem()  # sin padre: no genera señales hasta añadirlo al árbol
            it.setData(0, NODE_ROLE, node)
            it.setData(COL_ACT, NODE_ROLE, node)
            # cada lado en su columna; si no existe, se deja vacío en la misma fila
            for path, kind, col in ((node.left, node.left_kind, COL_LNAME),
                                    (node.right, node.right_kind, COL_RNAME)):
                if path is not None:
                    it.setText(col, node.name)
                    it.setIcon(col, dir_icon if kind == "dir" else file_icon)
            it.setText(COL_LEFT, _info(node.left, node.left_kind))
            it.setText(COL_RIGHT, _info(node.right, node.right_kind))
            it.setText(COL_STATUS, STATUS_TEXT[node.status])
            color = STATUS_COLOR.get(node.status)
            if color:
                brush = QBrush(color)
                for c in range(6):
                    it.setBackground(c, brush)
            it.addChildren([make(ch) for ch in node.children])
            return it

        items = [make(ch) for ch in self.root.children]
        t1 = time.perf_counter()
        self.tree.clear()
        self.tree.addTopLevelItems(items)

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
        self._apply_filter()
        self._ruler_invalidate()
        self.tree.setUpdatesEnabled(True)
        logger.info("Lista pintada: %d filas, construir=%.2fs, resto=%.2fs",
                    sum(1 for _ in iter_nodes(self.root)), t1 - t0, time.perf_counter() - t1)
        s = summarize(self.root)
        self.summary.setText(
            f"Ficheros: {s[Status.DIFFERENT]} diferentes · {s[Status.LEFT_ONLY]} sólo izquierda · "
            f"{s[Status.RIGHT_ONLY]} sólo derecha · {s[Status.TYPE_MISMATCH]} tipo distinto · "
            f"{s[Status.IDENTICAL]} idénticos"
        )

    def _on_action(self, node: DiffNode, action: str):
        if action in ("ltr", "rtl"):
            self.copy_node(node, action)
        else:
            self.delete_node(node, "left" if action == "del_left" else "right")

    # ---- regla de diferencias -------------------------------------------------------
    def _ruler_invalidate(self, *_):
        self._ruler_rows = None
        self.ruler.update()

    def _visible_rows(self) -> list:
        """Filas del árbol en el orden en que se ven (las contraídas ocultan sus hijas)."""
        if self._ruler_rows is None:
            rows: list = []

            def walk(parent: QTreeWidgetItem):
                for i in range(parent.childCount()):
                    c = parent.child(i)
                    if c.isHidden():
                        continue
                    rows.append(c)
                    if c.isExpanded():
                        walk(c)

            walk(self.tree.invisibleRootItem())
            self._ruler_rows = rows
        return self._ruler_rows

    def _ruler_data(self):
        rows = self._visible_rows()
        marks = []
        for i, it in enumerate(rows):
            node: DiffNode = it.data(0, NODE_ROLE)
            if node.status is Status.IDENTICAL:
                continue
            if node.is_dir and it.isExpanded() and it.childCount():
                continue  # sus hijos llevan las marcas
            color = GREEN if node.status in (Status.LEFT_ONLY, Status.RIGHT_ONLY) else ORANGE
            marks.append((i, i + 1, color))
        row_h = max(1, self.tree.sizeHintForRow(0)) if rows else 1
        first = self.tree.verticalScrollBar().value()
        last = first + max(1, self.tree.viewport().height() // row_h) - 1
        return len(rows), marks, (first, min(last, max(0, len(rows) - 1))) if rows else None, -1

    def _on_ruler_click(self, frac: float):
        rows = self._visible_rows()
        if rows:
            item = rows[min(len(rows) - 1, int(frac * len(rows)))]
            self.tree.scrollToItem(item, QTreeWidget.PositionAtCenter)
            self.tree.setCurrentItem(item)

    def _apply_filter(self):
        only = self.only_diff.isChecked()

        def walk(it: QTreeWidgetItem):
            for i in range(it.childCount()):
                c = it.child(i)
                c.setHidden(only and c.data(0, NODE_ROLE).status is Status.IDENTICAL)
                walk(c)

        walk(self.tree.invisibleRootItem())
        self._ruler_invalidate()

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

        refresh = self._refresh_job(node.rel)
        self._expanded = self._collect_expanded()
        logger.info("Copiar %s -> %s", src, dst)

        def job(p: Progress):
            p.set_total(max(1, count_files(src, ignore, p)))
            copy_path(src, dst, ignore, p)
            return refresh(p)  # sólo se vuelve a comparar lo copiado

        self._start(job, self._on_compared, "Copiando…", refresh_after_cancel=True)

    def delete_node(self, node: DiffNode, side: str):
        """Elimina el fichero/carpeta de un lado. Siempre pide confirmación."""
        if self.busy:
            return
        target = node.left if side == "left" else node.right
        if target is None:
            return
        side_name = "izquierda" if side == "left" else "derecha"
        if node.is_dir:
            n = sum(1 for x in [node, *iter_nodes(node)]
                    if x.is_file and (x.left if side == "left" else x.right) is not None)
            what = f"la carpeta (con {n} fichero(s))"
        else:
            what = "el fichero"
        r = QMessageBox.warning(
            self, "Confirmar eliminación",
            f"Se ELIMINARÁ DEFINITIVAMENTE {what} de la {side_name}:\n{target}\n\n"
            f"Esta acción no se puede deshacer. ¿Continuar?",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if r != QMessageBox.Yes:
            return

        refresh = self._refresh_job(node.rel)
        self._expanded = self._collect_expanded()
        logger.info("Eliminar %s", target)

        def job(p: Progress):
            p.set_total(max(1, count_files(target, (), p)))
            delete_path(target, p)
            return refresh(p)  # sólo se vuelve a comparar lo afectado

        self._start(job, self._on_compared, "Eliminando…", refresh_after_cancel=True)

    def _double_click(self, item: QTreeWidgetItem, _col: int):
        if self.busy:
            return
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
