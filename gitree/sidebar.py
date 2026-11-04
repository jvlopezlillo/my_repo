"""Barra lateral estilo Sourcetree."""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QBrush, QColor, QFont
from PySide6.QtWidgets import QMenu, QTreeWidget, QTreeWidgetItem

from .git import RefsInfo, Stash

ROLE = Qt.ItemDataRole.UserRole


class Sidebar(QTreeWidget):
    viewRequested = Signal(str)            # 'status' | 'history'
    refActivated = Signal(str, str)        # tipo, nombre (doble clic)
    menuAction = Signal(str, str, str)     # acción, tipo, nombre

    def __init__(self):
        super().__init__()
        self.setObjectName("sidebar")
        self.setHeaderHidden(True)
        self.setIndentation(14)
        self.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.setMinimumWidth(190)
        self._collapsed: set[str] = set()
        self._view = "history"
        self.itemClicked.connect(self._clicked)
        self.itemDoubleClicked.connect(self._double)
        self.customContextMenuRequested.connect(self._menu)
        self.itemExpanded.connect(lambda it: self._collapsed.discard(it.text(0)))
        self.itemCollapsed.connect(lambda it: self._collapsed.add(it.text(0)))

    def _section(self, title: str) -> QTreeWidgetItem:
        it = QTreeWidgetItem([title])
        f = QFont(self.font())
        f.setBold(True)
        f.setPointSizeF(max(f.pointSizeF() - 1.5, 7))
        it.setFont(0, f)
        it.setForeground(0, QBrush(QColor("#7a8699")))
        it.setFlags(Qt.ItemFlag.ItemIsEnabled)
        self.addTopLevelItem(it)
        return it

    def _leaf(self, parent, text: str, kind: str, name: str, bold=False, color: str | None = None):
        it = QTreeWidgetItem(parent, [text])
        it.setData(0, ROLE, (kind, name))
        if bold:
            f = QFont(self.font())
            f.setBold(True)
            it.setFont(0, f)
        if color:
            it.setForeground(0, QBrush(QColor(color)))
        return it

    def populate(self, refs: RefsInfo, stashes: list[Stash], changes: int) -> None:
        self.blockSignals(True)
        self.clear()
        ws = self._section("ESPACIO DE TRABAJO")
        status_text = "Estado de ficheros" + (f"  ({changes})" if changes else "")
        s = self._leaf(ws, status_text, "view", "status", bold=bool(changes))
        h = self._leaf(ws, "Historial", "view", "history")
        (s if self._view == "status" else h).setSelected(True)

        br = self._section("RAMAS")
        for b in refs.local:
            text = ("● " if b.is_current else "   ") + b.name
            if b.ahead:
                text += f"  ↑{b.ahead}"
            if b.behind:
                text += f"  ↓{b.behind}"
            self._leaf(br, text, "local", b.name, bold=b.is_current)

        tg = self._section("ETIQUETAS")
        for t in sorted(refs.tags, reverse=True):
            self._leaf(tg, "   " + t, "tag", t)

        rm = self._section("REMOTOS")
        groups: dict[str, QTreeWidgetItem] = {}
        for r in refs.remote:
            remote, name = r.split("/", 1)
            if remote not in groups:
                groups[remote] = QTreeWidgetItem(rm, [remote])
                groups[remote].setFlags(Qt.ItemFlag.ItemIsEnabled)
            self._leaf(groups[remote], name, "remote", r)

        st = self._section("STASHES")
        for s_ in stashes:
            self._leaf(st, "   " + s_.message, "stash", s_.ref)

        for i in range(self.topLevelItemCount()):
            top = self.topLevelItem(i)
            top.setExpanded(top.text(0) not in self._collapsed)
            for j in range(top.childCount()):
                top.child(j).setExpanded(True)
        self.blockSignals(False)

    def set_view(self, name: str) -> None:
        self._view = name

    # ---------------------------------------------------------- interacción
    def _data(self, item):
        return item.data(0, ROLE) if item else None

    def _clicked(self, item, _col):
        d = self._data(item)
        if d and d[0] == "view":
            self._view = d[1]
            self.viewRequested.emit(d[1])

    def _double(self, item, _col):
        d = self._data(item)
        if d and d[0] in ("local", "remote", "tag"):
            self.refActivated.emit(*d)

    def _menu(self, pos):
        d = self._data(self.itemAt(pos))
        if not d or d[0] == "view":
            return
        kind, name = d
        m = QMenu(self)
        acts: list[tuple[str, str] | None] = []
        if kind == "local":
            acts = [("checkout", "Cambiar a esta rama"), ("merge", "Fusionar en la rama actual"),
                    None, ("rename", "Renombrar…"), ("delete", "Eliminar…")]
        elif kind == "remote":
            acts = [("checkout", "Cambiar a esta rama (crea rama local)"),
                    ("merge", "Fusionar en la rama actual"), None,
                    ("delete_remote", "Eliminar del remoto…")]
        elif kind == "tag":
            acts = [("checkout", "Cambiar a esta etiqueta"), ("push_tag", "Subir etiqueta"),
                    None, ("delete", "Eliminar etiqueta…")]
        elif kind == "stash":
            acts = [("apply", "Aplicar stash"), ("pop", "Aplicar y borrar (pop)"), None,
                    ("drop", "Eliminar stash…")]
        for a in acts:
            if a is None:
                m.addSeparator()
            else:
                m.addAction(a[1]).triggered.connect(lambda _=False, k=a[0]: self.menuAction.emit(k, kind, name))
        m.exec(self.viewport().mapToGlobal(pos))
