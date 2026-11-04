"""Vista de historial: tabla con grafo + detalle de commit + diff."""
from __future__ import annotations

import time
from datetime import datetime

from PySide6.QtCore import QAbstractTableModel, QModelIndex, QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPen
from PySide6.QtWidgets import (QAbstractItemView, QCheckBox, QHBoxLayout, QHeaderView, QLabel, QLineEdit,
                               QListWidget, QListWidgetItem, QMenu, QSplitter, QStackedWidget, QStyle, QStyledItemDelegate,
                               QTableView, QTextBrowser, QVBoxLayout, QWidget)

from .diffview import DiffView
from .filemenu import FileTools
from .git import Commit, Repo
from .graph import GraphRow, build_graph
from .theme import LANE_COLORS, status_badge

ROW_H = 26
LANE_W = 14
GRAPH_ROLE = Qt.ItemDataRole.UserRole + 1
COMMIT_ROLE = Qt.ItemDataRole.UserRole
HEADERS = ["Grafo", "Descripción", "Fecha", "Autor", "Commit"]


class CommitModel(QAbstractTableModel):
    def __init__(self):
        super().__init__()
        self.commits: list[Commit] = []
        self.rows: list[GraphRow] = []
        self.max_lanes = 1

    def set_commits(self, commits: list[Commit]) -> None:
        self.beginResetModel()
        self.commits = commits
        self.rows, self.max_lanes = build_graph(commits)
        self.endResetModel()

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self.commits)

    def columnCount(self, parent=QModelIndex()):
        return len(HEADERS)

    def headerData(self, section, orientation, role=Qt.ItemDataRole.DisplayRole):
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            return HEADERS[section]

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        c = self.commits[index.row()]
        col = index.column()
        if role == COMMIT_ROLE:
            return c
        if role == GRAPH_ROLE:
            return self.rows[index.row()]
        if role == Qt.ItemDataRole.DisplayRole:
            if col == 2:
                return datetime.fromtimestamp(c.date).strftime("%d %b %Y %H:%M")
            if col == 3:
                return "*" if c.wip else c.author
            if col == 4:
                return "*" if c.wip else c.short
        if role == Qt.ItemDataRole.ToolTipRole and col == 1:
            return f"{c.subject}\n\n{c.author} <{c.email}>"
        if role == Qt.ItemDataRole.FontRole and col == 4:
            f = QFont("DejaVu Sans Mono")
            f.setPointSize(9)
            return f


class HistoryDelegate(QStyledItemDelegate):
    def sizeHint(self, option, index):
        s = super().sizeHint(option, index)
        s.setHeight(ROW_H)
        return s

    def paint(self, painter: QPainter, option, index):
        col = index.column()
        if col not in (0, 1):
            super().paint(painter, option, index)
            return
        selected = bool(option.state & QStyle.StateFlag.State_Selected)
        painter.save()
        painter.setClipRect(option.rect)
        if selected:
            painter.fillRect(option.rect, option.palette.highlight())
        if col == 0:
            self._paint_graph(painter, option.rect, index.data(GRAPH_ROLE), selected,
                              index.data(COMMIT_ROLE))
        else:
            self._paint_desc(painter, option, index.data(COMMIT_ROLE), selected)
        painter.restore()

    def _paint_graph(self, p: QPainter, rect, row: GraphRow, selected: bool, commit: Commit):
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        x0, h = rect.left() + 10, rect.height()

        def pt(x, y):
            return QPointF(x0 + x * LANE_W, rect.top() + y * h)

        for sx, sy, ex, ey, color in row.segments:
            pen = QPen(QColor(LANE_COLORS[color % len(LANE_COLORS)]), 2)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            if commit.wip:
                pen.setStyle(Qt.PenStyle.DotLine)
            p.setPen(pen)
            p.drawLine(pt(sx, sy), pt(ex, ey))
        c = pt(row.col, 0.5)
        color = QColor(LANE_COLORS[row.color % len(LANE_COLORS)])
        if commit.wip:  # círculo hueco con punto central (como Sourcetree)
            p.setPen(QPen(color, 2))
            p.setBrush(QColor("white"))
            p.drawEllipse(c, 6, 6)
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(color)
            p.drawEllipse(c, 2.5, 2.5)
            return
        is_head = any(r.is_head for r in commit.refs)
        p.setPen(QPen(QColor("white") if selected else QColor("#ffffff"), 2))
        p.setBrush(color)
        radius = 5.5 if is_head else 4.5
        p.drawEllipse(c, radius, radius)
        if len(commit.parents) > 1:  # commit de merge: punto central blanco
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(QColor("white"))
            p.drawEllipse(c, 1.8, 1.8)

    def _paint_desc(self, p: QPainter, option, c: Commit, selected: bool):
        rect = option.rect
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        small = QFont(option.font)
        small.setPointSizeF(max(option.font.pointSizeF() - 1, 7))
        small.setBold(True)
        fm_small = QFontMetrics(small)
        x = rect.left() + 6
        order = {"local": 0, "head": 0, "remote": 1, "tag": 2}
        for ref in sorted(c.refs, key=lambda r: (not r.is_head, order.get(r.kind, 3))):
            text = ref.name
            if ref.kind == "tag":
                text = "⚑ " + text
            w = fm_small.horizontalAdvance(text) + 12
            if x + w > rect.right() - 20:
                break
            box = QRectF(x, rect.top() + 4, w, rect.height() - 8)
            bg, fg = {
                "local": ("#2e9e5b", "white"), "head": ("#2e9e5b", "white"),
                "remote": ("#e8edf5", "#3b4a63"), "tag": ("#f5d76e", "#5f4b00"),
            }[ref.kind]
            if ref.is_head:
                bg = "#1b6e3c"
            p.setPen(QPen(QColor("#9fb0cc"), 1) if ref.kind == "remote" else Qt.PenStyle.NoPen)
            p.setBrush(QColor(bg))
            p.drawRoundedRect(box, 4, 4)
            p.setFont(small)
            p.setPen(QColor(fg))
            p.drawText(box, Qt.AlignmentFlag.AlignCenter, text)
            x += w + 5
        font = QFont(option.font)
        font.setBold(c.wip)
        p.setFont(font)
        p.setPen(QColor("white") if selected else QColor("#1f2937"))
        fm = QFontMetrics(font)
        text = fm.elidedText(c.subject, Qt.TextElideMode.ElideRight, max(rect.right() - x - 6, 10))
        p.drawText(QRectF(x, rect.top(), rect.right() - x, rect.height()),
                   Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft, text)


class HistoryView(QWidget):
    action = Signal(str, object)  # nombre de la acción, Commit
    wipSelected = Signal(bool)    # la fila «cambios sin confirmar» (des)seleccionada

    def __init__(self, repo: Repo):
        super().__init__()
        self.repo = repo
        self._files: list[tuple[str, str, str | None]] = []
        self.tools = FileTools(repo, self)
        self._wip_count = 0
        self.wip_selected = False

        # barra de filtro
        self.search = QLineEdit(placeholderText="Buscar en el historial (mensaje, autor, hash)… Enter = siguiente")
        self.search.setClearButtonEnabled(True)
        self.all_branches = QCheckBox("Todas las ramas")
        self.all_branches.setChecked(True)
        self.count_label = QLabel()
        bar = QHBoxLayout()
        bar.setContentsMargins(8, 6, 8, 6)
        bar.addWidget(self.all_branches)
        bar.addWidget(self.search, 1)
        bar.addWidget(self.count_label)

        # tabla
        self.model = CommitModel()
        self.table = QTableView()
        self.table.setModel(self.model)
        self.table.setItemDelegate(HistoryDelegate(self.table))
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.setAlternatingRowColors(True)
        self.table.setShowGrid(False)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().hide()
        self.table.verticalHeader().setDefaultSectionSize(ROW_H)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(0, QHeaderView.ResizeMode.Interactive)
        hh.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        for c, w in ((2, 125), (3, 150), (4, 80)):
            hh.setSectionResizeMode(c, QHeaderView.ResizeMode.Interactive)
            self.table.setColumnWidth(c, w)

        # detalle
        self.info = QTextBrowser()
        self.info.setOpenLinks(False)
        self.files = QListWidget()
        self.diff = DiffView()
        left = QSplitter(Qt.Orientation.Vertical)
        left.addWidget(self.info)
        left.addWidget(self.files)
        left.setSizes([170, 130])
        bottom = QSplitter(Qt.Orientation.Horizontal)
        bottom.addWidget(left)
        bottom.addWidget(self.diff)
        bottom.setSizes([360, 700])

        self.bottom_stack = QStackedWidget()
        self.bottom_stack.addWidget(bottom)
        self.split = split = QSplitter(Qt.Orientation.Vertical)
        split.addWidget(self.table)
        split.addWidget(self.bottom_stack)
        split.setSizes([420, 300])

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)
        lay.addLayout(bar)
        lay.addWidget(split, 1)

        self.table.selectionModel().currentRowChanged.connect(self._on_commit_selected)
        self.table.customContextMenuRequested.connect(self._menu)
        self.files.currentRowChanged.connect(self._on_file_selected)
        self.files.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.files.customContextMenuRequested.connect(self._files_menu)
        self.info.anchorClicked.connect(lambda url: self.select_hash(url.toString()))
        self.all_branches.toggled.connect(lambda _: self.reload())
        self.search.returnPressed.connect(self._find_next)

    # ---------------------------------------------------------------- carga
    def reload(self, wip_count: int | None = None) -> None:
        if wip_count is not None:
            self._wip_count = wip_count
        keep = self.selected_commit()
        keep_wip = self.wip_selected
        first_load = self.model.rowCount() == 0
        commits = self.repo.log(all_refs=self.all_branches.isChecked())
        n_real = len(commits)
        if self._wip_count:
            head = self.repo.head_hash()
            wip = Commit("WIP", [head] if head else [], "*", "", int(time.time()),
                         f"Cambios sin confirmar ({self._wip_count})", wip=True)
            commits = [wip] + commits
        self.model.set_commits(commits)
        self.table.setColumnWidth(0, min(max(self.model.max_lanes * LANE_W + 20, 50), 260))
        self.count_label.setText(f"{n_real} commits")
        if not commits:
            self._on_commit_selected(QModelIndex(), QModelIndex())
            return
        row = 0  # por defecto: la fila superior (cambios sin confirmar si existen)
        if keep_wip and self._wip_count:
            row = 0
        elif keep and not first_load:
            row = next((i for i, c in enumerate(commits) if c.hash == keep.hash), 0)
        self.table.selectRow(row)
        self.table.scrollTo(self.model.index(row, 1))
        # selectRow no emite currentRowChanged si la fila no cambió: refrescar el detalle igualmente
        self._on_commit_selected(self.model.index(row, 0), QModelIndex())

    def embed_workspace(self, ws: QWidget) -> None:
        if self.bottom_stack.indexOf(ws) < 0:
            self.bottom_stack.addWidget(ws)
        self.bottom_stack.setCurrentWidget(ws)
        sizes = self.split.sizes()
        if sizes[1] < 400 and sum(sizes) > 600:
            self.split.setSizes([max(sum(sizes) - 440, 140), 440])

    def release_workspace(self, ws: QWidget) -> None:
        if self.bottom_stack.indexOf(ws) >= 0:
            self.bottom_stack.removeWidget(ws)
        self.bottom_stack.setCurrentIndex(0)

    def selected_commit(self) -> Commit | None:
        idx = self.table.currentIndex()
        c = idx.data(COMMIT_ROLE) if idx.isValid() else None
        return None if c is None or c.wip else c

    def select_hash(self, h: str) -> None:
        for i, c in enumerate(self.model.commits):
            if c.hash.startswith(h):
                self.table.selectRow(i)
                self.table.scrollTo(self.model.index(i, 1))
                return

    def _find_next(self) -> None:
        q = self.search.text().strip().lower()
        if not q or not self.model.commits:
            return
        n = len(self.model.commits)
        start = (self.table.currentIndex().row() + 1) if self.table.currentIndex().isValid() else 0
        for k in range(n):
            i = (start + k) % n
            c = self.model.commits[i]
            if c.wip:
                continue
            if q in c.subject.lower() or q in c.author.lower() or c.hash.startswith(q):
                self.select_hash(c.hash)
                return

    # -------------------------------------------------------------- detalle
    def _on_commit_selected(self, cur: QModelIndex, _prev: QModelIndex) -> None:
        c = cur.data(COMMIT_ROLE) if cur.isValid() else None
        wip = bool(c and c.wip)
        changed = wip != self.wip_selected
        self.wip_selected = wip
        if changed or wip:
            self.wipSelected.emit(wip)
        if wip:
            return
        self.files.clear()
        self.diff.show_diff("", None)
        if not c:
            self.info.clear()
            return
        msg = self.repo.commit_message(c.hash).replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
        refs = " ".join(f"<span style='background:#e8edf5;border-radius:3px'>&nbsp;{r.name}&nbsp;</span>" for r in c.refs)
        parents = ", ".join(f"<a href='{p}'>{p[:7]}</a>" for p in c.parents) or "—"
        date = datetime.fromtimestamp(c.date).strftime("%A %d %B %Y, %H:%M")
        self.info.setHtml(
            f"<p><b>Commit:</b> <tt>{c.hash}</tt><br><b>Padres:</b> {parents}<br>"
            f"<b>Autor:</b> {c.author} &lt;{c.email}&gt;<br><b>Fecha:</b> {date}<br>{refs}</p>"
            f"<hr><p>{msg}</p>")
        self._files = self.repo.commit_files(c)
        for st, path, orig in self._files:
            label = f"{orig} → {path}" if orig else path
            it = QListWidgetItem(status_badge(st), label)
            self.files.addItem(it)
        if self._files:
            self.files.setCurrentRow(0)

    def _on_file_selected(self, row: int) -> None:
        c = self.selected_commit()
        if not c or row < 0 or row >= len(self._files):
            return
        _, path, orig = self._files[row]
        self.diff.show_diff(self.repo.commit_file_diff(c, path, orig), "commit")

    # ------------------------------------------------------- menú contextual
    def _files_menu(self, pos) -> None:
        c, row = self.selected_commit(), self.files.currentRow()
        if not c or row < 0 or row >= len(self._files):
            return
        st, path, _orig = self._files[row]
        t = self.tools
        m = QMenu(self)
        m.addAction("Abrir").triggered.connect(lambda: t.open([path]))
        m.addAction("Mostrar en el gestor de ficheros").triggered.connect(lambda: t.show_in_folder([path]))
        m.addAction("Copiar ruta al portapapeles").triggered.connect(lambda: t.copy_paths([path]))
        m.addSeparator()
        a = m.addAction("Diff externo")
        a.setEnabled(bool(c.parents))
        a.triggered.connect(lambda: t.external_diff([c.parents[0], c.hash, "--", path]))
        m.addSeparator()
        m.addAction("Historial del fichero…").triggered.connect(lambda: t.log(path))
        b = m.addAction("Blame del fichero en este commit…")
        b.setEnabled(st != "D")
        b.triggered.connect(lambda: t.blame(path, c.hash))
        m.exec(self.files.viewport().mapToGlobal(pos))

    def _menu(self, pos) -> None:
        c = self.selected_commit()
        if not c:
            return
        m = QMenu(self)
        items = [
            ("checkout", "Cambiar a este commit (checkout)"),
            ("branch", "Crear rama aquí…"),
            ("tag", "Crear etiqueta aquí…"),
            (None, None),
            ("cherry", "Cherry-pick"),
            ("revert", "Revertir commit"),
            ("reset", "Resetear la rama actual a este commit…"),
            (None, None),
            ("copy", "Copiar hash"),
        ]
        for key, text in items:
            if key is None:
                m.addSeparator()
            else:
                m.addAction(text).triggered.connect(lambda _=False, k=key: self.action.emit(k, c))
        m.exec(self.table.viewport().mapToGlobal(pos))
