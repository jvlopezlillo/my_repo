"""Vista «Estado de ficheros»: stage / unstage, diff y commit."""
from __future__ import annotations

import os

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QGuiApplication, QKeySequence, QShortcut
from PySide6.QtWidgets import (QAbstractItemView, QCheckBox, QHBoxLayout, QLabel, QListWidget,
                               QListWidgetItem, QMenu, QMessageBox, QPlainTextEdit, QPushButton,
                               QSplitter, QVBoxLayout, QWidget)

from .diffview import DiffView
from .git import FileStatus, GitError, Repo
from .theme import status_badge


def _letter(fs: FileStatus, staged: bool) -> str:
    if fs.conflicted:
        return "U"
    if fs.untracked:
        return "?"
    return fs.index if staged else fs.worktree


class FileList(QListWidget):
    toggled = Signal(list, bool)  # [FileStatus], nuevo estado «staged»
    selected = Signal(object)
    menuRequested = Signal(list, object)

    def __init__(self, staged: bool):
        super().__init__()
        self.staged = staged
        self._loading = False
        self.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.itemChanged.connect(self._changed)
        self.itemSelectionChanged.connect(self._sel)
        self.customContextMenuRequested.connect(
            lambda pos: self.menuRequested.emit(self.selected_files(), self.viewport().mapToGlobal(pos)))

    def populate(self, files: list[FileStatus]) -> None:
        keep = {fs.path for fs in self.selected_files()}
        self._loading = True
        self.blockSignals(True)
        self.clear()
        for fs in files:
            label = f"{fs.orig} → {fs.path}" if fs.orig else fs.path
            it = QListWidgetItem(status_badge(_letter(fs, self.staged)), label)
            it.setData(Qt.ItemDataRole.UserRole, fs)
            it.setFlags(it.flags() | Qt.ItemFlag.ItemIsUserCheckable)
            it.setCheckState(Qt.CheckState.Checked if self.staged else Qt.CheckState.Unchecked)
            it.setToolTip(fs.path)
            self.addItem(it)
            if fs.path in keep:
                it.setSelected(True)
        self.blockSignals(False)
        self._loading = False

    def selected_files(self) -> list[FileStatus]:
        return [i.data(Qt.ItemDataRole.UserRole) for i in self.selectedItems()]

    def all_files(self) -> list[FileStatus]:
        return [self.item(i).data(Qt.ItemDataRole.UserRole) for i in range(self.count())]

    def _changed(self, item: QListWidgetItem) -> None:
        if self._loading:
            return
        fs = item.data(Qt.ItemDataRole.UserRole)
        group = self.selected_files() if item.isSelected() else [fs]
        self.toggled.emit(group, item.checkState() == Qt.CheckState.Checked)

    def _sel(self) -> None:
        files = self.selected_files()
        if files:
            self.selected.emit(files[0])


def _section(title: str, button_text: str, button_cb):
    box = QWidget()
    lay = QVBoxLayout(box)
    lay.setContentsMargins(0, 0, 0, 0)
    lay.setSpacing(0)
    head = QWidget()
    head.setObjectName("sectionTitle")
    head.setStyleSheet("#sectionTitle{background:#e9ecf1;border-bottom:1px solid #d5d9e0;}")
    hl = QHBoxLayout(head)
    hl.setContentsMargins(8, 2, 4, 2)
    label = QLabel(title)
    label.setStyleSheet("font-weight:600;color:#4a5568;")
    btn = QPushButton(button_text)
    btn.setObjectName("link")
    btn.setCursor(Qt.CursorShape.PointingHandCursor)
    btn.clicked.connect(button_cb)
    hl.addWidget(label, 1)
    hl.addWidget(btn)
    lay.addWidget(head)
    return box, label, lay


class WorkspaceView(QWidget):
    changed = Signal()          # el repositorio cambió: hay que refrescar
    pushRequested = Signal()

    def __init__(self, repo: Repo):
        super().__init__()
        self.repo = repo
        self._current: tuple[FileStatus, bool] | None = None

        self.staged = FileList(True)
        self.unstaged = FileList(False)
        sbox, self.staged_label, sl = _section("Ficheros en stage", "Quitar todos", self.unstage_all)
        ubox, self.unstaged_label, ul = _section("Ficheros sin stage", "Añadir todos", self.stage_all)
        sl.addWidget(self.staged)
        ul.addWidget(self.unstaged)

        self.message = QPlainTextEdit()
        self.message.setPlaceholderText("Mensaje del commit… (Ctrl+Intro para confirmar)")
        self.message.setMaximumHeight(110)
        self.amend = QCheckBox("Enmendar último commit")
        self.push_after = QCheckBox("Hacer push al confirmar")
        self.commit_btn = QPushButton("Commit")
        self.commit_btn.setObjectName("primary")
        self.commit_btn.setEnabled(False)
        row = QHBoxLayout()
        row.addWidget(self.amend)
        row.addWidget(self.push_after)
        row.addStretch(1)
        row.addWidget(self.commit_btn)
        cbox = QWidget()
        cl = QVBoxLayout(cbox)
        cl.setContentsMargins(6, 6, 6, 6)
        cl.addWidget(self.message)
        cl.addLayout(row)

        left = QSplitter(Qt.Orientation.Vertical)
        left.addWidget(sbox)
        left.addWidget(ubox)
        left.addWidget(cbox)
        left.setSizes([200, 240, 150])
        left.setStretchFactor(0, 1)
        left.setStretchFactor(1, 1)
        left.setStretchFactor(2, 0)

        self.diff_title = QLabel("Selecciona un fichero para ver sus cambios")
        self.diff_title.setObjectName("sectionTitle")
        self.diff = DiffView()
        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.setSpacing(0)
        rl.addWidget(self.diff_title)
        rl.addWidget(self.diff, 1)

        split = QSplitter(Qt.Orientation.Horizontal)
        split.addWidget(left)
        split.addWidget(right)
        split.setSizes([380, 760])

        self.banner = QWidget()
        bl = QHBoxLayout(self.banner)
        bl.setContentsMargins(10, 4, 10, 4)
        self.banner.setStyleSheet("background:#fff4ce;border-bottom:1px solid #e6cf7a;")
        bl.addWidget(QLabel("⚠ Merge en curso: resuelve los conflictos, añádelos al stage y haz commit."), 1)
        abort = QPushButton("Abortar merge")
        abort.clicked.connect(self._abort_merge)
        bl.addWidget(abort)
        self.banner.hide()

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)
        lay.addWidget(self.banner)
        lay.addWidget(split, 1)

        self.staged.toggled.connect(lambda fs, on: self._toggle(fs, on))
        self.unstaged.toggled.connect(lambda fs, on: self._toggle(fs, on))
        self.staged.selected.connect(lambda fs: self._show(fs, True, self.unstaged))
        self.unstaged.selected.connect(lambda fs: self._show(fs, False, self.staged))
        self.staged.menuRequested.connect(lambda f, p: self._menu(f, p, True))
        self.unstaged.menuRequested.connect(lambda f, p: self._menu(f, p, False))
        self.diff.hunkRequested.connect(self._hunk)
        self.commit_btn.clicked.connect(self.commit)
        self.message.textChanged.connect(self._update_button)
        self.amend.toggled.connect(self._amend_toggled)
        QShortcut(QKeySequence("Ctrl+Return"), self.message, activated=self.commit)

    # -------------------------------------------------------------- poblar
    def populate(self, statuses: list[FileStatus]) -> None:
        self.staged.populate([f for f in statuses if f.staged])
        self.unstaged.populate([f for f in statuses if f.unstaged])
        self.staged_label.setText(f"Ficheros en stage ({self.staged.count()})")
        self.unstaged_label.setText(f"Ficheros sin stage ({self.unstaged.count()})")
        merging = self.repo.merge_in_progress()
        self.banner.setVisible(merging)
        if merging and not self.message.toPlainText().strip():
            self.message.setPlainText(self.repo.merge_message())
        self._update_button()
        self._reload_diff()

    def _update_button(self) -> None:
        ok = self.staged.count() > 0 and bool(self.message.toPlainText().strip())
        self.commit_btn.setEnabled(ok or self.amend.isChecked())

    def _reload_diff(self) -> None:
        cur = self._current
        if not cur:
            return
        path, staged = cur[0].path, cur[1]
        lst = self.staged if staged else self.unstaged
        match = next((f for f in lst.all_files() if f.path == path), None)
        if match:
            self._render(match, staged)
        else:
            self._current = None
            self.diff_title.setText("Selecciona un fichero para ver sus cambios")
            self.diff.show_diff("", None)

    # ---------------------------------------------------------------- diff
    def _show(self, fs: FileStatus, staged: bool, other: FileList) -> None:
        other.blockSignals(True)
        other.clearSelection()
        other.blockSignals(False)
        self._render(fs, staged)

    def _render(self, fs: FileStatus, staged: bool) -> None:
        self._current = (fs, staged)
        self.diff_title.setText(f"{'Stage' if staged else 'Sin stage'} · {fs.path}")
        text = self.repo.diff_file(fs, staged)
        self.diff.show_diff(text, "staged" if staged else "unstaged",
                            hunks=not fs.untracked and not fs.conflicted)

    def _hunk(self, action: str, patch: str) -> None:
        try:
            if action == "discard":
                if QMessageBox.question(self, "Descartar hunk",
                                        "¿Descartar este bloque de cambios? No se puede deshacer.") \
                        != QMessageBox.StandardButton.Yes:
                    return
                self.repo.apply_patch(patch, reverse=True)
            elif action == "stage":
                self.repo.apply_patch(patch, cached=True)
            else:
                self.repo.apply_patch(patch, cached=True, reverse=True)
        except GitError as e:
            QMessageBox.critical(self, "Error al aplicar el hunk", str(e))
        self.changed.emit()

    # ------------------------------------------------------------- acciones
    def _toggle(self, files: list[FileStatus], on: bool) -> None:
        paths = [f.path for f in files]
        try:
            (self.repo.stage if on else self.repo.unstage)(paths)
        except GitError as e:
            QMessageBox.critical(self, "Error", str(e))
        self.changed.emit()

    def stage_all(self) -> None:
        self._toggle(self.unstaged.all_files(), True) if self.unstaged.count() else None

    def unstage_all(self) -> None:
        self._toggle(self.staged.all_files(), False) if self.staged.count() else None

    def _menu(self, files: list[FileStatus], pos, staged: bool) -> None:
        if not files:
            return
        m = QMenu(self)
        if staged:
            m.addAction("Quitar del stage").triggered.connect(lambda: self._toggle(files, False))
        else:
            m.addAction("Añadir al stage").triggered.connect(lambda: self._toggle(files, True))
            m.addAction("Descartar cambios…").triggered.connect(lambda: self._discard(files))
            if any(f.conflicted for f in files):
                m.addSeparator()
                m.addAction("Resolver usando «nuestra» versión").triggered.connect(
                    lambda: self._resolve(files, "ours"))
                m.addAction("Resolver usando «su» versión").triggered.connect(
                    lambda: self._resolve(files, "theirs"))
            if any(f.untracked for f in files):
                m.addSeparator()
                m.addAction("Ignorar").triggered.connect(lambda: self._ignore(files))
        m.addSeparator()
        m.addAction("Copiar ruta").triggered.connect(
            lambda: QGuiApplication.clipboard().setText("\n".join(f.path for f in files)))
        m.exec(pos)

    def _discard(self, files: list[FileStatus]) -> None:
        names = "\n".join(f.path for f in files[:8]) + ("\n…" if len(files) > 8 else "")
        if QMessageBox.warning(self, "Descartar cambios",
                               f"Se perderán los cambios de:\n\n{names}\n\nEsta acción no se puede deshacer.",
                               QMessageBox.StandardButton.Discard | QMessageBox.StandardButton.Cancel,
                               QMessageBox.StandardButton.Cancel) != QMessageBox.StandardButton.Discard:
            return
        try:
            for f in files:
                self.repo.discard(f)
        except GitError as e:
            QMessageBox.critical(self, "Error", str(e))
        self.changed.emit()

    def _resolve(self, files: list[FileStatus], side: str) -> None:
        try:
            for f in files:
                if f.conflicted:
                    self.repo.resolve_with(f.path, side)
        except GitError as e:
            QMessageBox.critical(self, "Error", str(e))
        self.changed.emit()

    def _ignore(self, files: list[FileStatus]) -> None:
        for f in files:
            self.repo.add_to_gitignore("/" + f.path)
        self.changed.emit()

    def _abort_merge(self) -> None:
        try:
            self.repo.merge_abort()
            self.message.clear()
        except GitError as e:
            QMessageBox.critical(self, "Error", str(e))
        self.changed.emit()

    def _amend_toggled(self, on: bool) -> None:
        if on and not self.message.toPlainText().strip() and self.repo.has_commits():
            self.message.setPlainText(self.repo.commit_message("HEAD"))
        self._update_button()

    def commit(self) -> None:
        if not self.commit_btn.isEnabled():
            return
        try:
            self.repo.commit(self.message.toPlainText(), amend=self.amend.isChecked())
        except GitError as e:
            QMessageBox.critical(self, "No se pudo hacer commit", str(e))
            return
        self.message.clear()
        self.amend.setChecked(False)
        self.changed.emit()
        if self.push_after.isChecked():
            self.pushRequested.emit()
