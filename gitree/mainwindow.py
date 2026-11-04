from __future__ import annotations

import os
import shutil
import subprocess

from PySide6.QtCore import QEvent, QSettings, QTimer, Qt, QThreadPool
from PySide6.QtGui import QAction, QKeySequence
from PySide6.QtWidgets import (QDialog, QDialogButtonBox, QFileDialog, QFormLayout, QHBoxLayout, QLineEdit,
                               QMainWindow, QMessageBox, QPushButton, QTabBar, QTabWidget, QToolBar,
                               QToolButton, QWidget)

from . import __version__
from .git import GitError, Repo
from .repoview import RepoView
from .theme import glyph_icon
from .welcome import WelcomeView
from .workers import Task


class CloneDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Clonar repositorio")
        self.setMinimumWidth(520)
        self.url = QLineEdit(placeholderText="https://github.com/usuario/proyecto.git  ó  git@host:usuario/proyecto.git")
        self.dest = QLineEdit(os.path.expanduser("~"))
        browse = QPushButton("Examinar…")
        browse.clicked.connect(self._browse)
        self.name = QLineEdit()
        drow = QHBoxLayout()
        drow.addWidget(self.dest, 1)
        drow.addWidget(browse)
        form = QFormLayout(self)
        form.addRow("URL de origen:", self.url)
        form.addRow("Carpeta destino:", drow)
        form.addRow("Nombre:", self.name)
        bb = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        form.addRow(bb)
        self.url.textChanged.connect(self._guess_name)

    def _browse(self):
        d = QFileDialog.getExistingDirectory(self, "Carpeta destino", self.dest.text())
        if d:
            self.dest.setText(d)

    def _guess_name(self, text: str):
        base = text.rstrip("/").rsplit("/", 1)[-1].rsplit(":", 1)[-1]
        self.name.setText(base[:-4] if base.endswith(".git") else base)

    def target(self) -> tuple[str, str]:
        return self.url.text().strip(), os.path.join(self.dest.text().strip(), self.name.text().strip())


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Gitree")
        self.resize(1320, 820)
        self.settings = QSettings("gitree", "gitree")
        self._clone_tasks: set[Task] = set()

        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.setDocumentMode(True)
        self.tabs.setMovable(True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.setCentralWidget(self.tabs)

        self.welcome = WelcomeView()
        self.tabs.addTab(self.welcome, "Marcadores")
        self.tabs.tabBar().setTabButton(0, self.tabs.tabBar().ButtonPosition.RightSide, None)
        self.welcome.openRequested.connect(self.open_repo)
        self.welcome.openDialog.connect(self.open_dialog)
        self.welcome.cloneDialog.connect(self.clone_dialog)
        self.welcome.initDialog.connect(self.init_dialog)
        self.welcome.removeRequested.connect(self._remove_bookmark)

        self._build_actions()
        self.tabs.currentChanged.connect(self._tab_changed)
        self.statusBar().showMessage("Listo")
        self._refresh_bookmarks()

        self._debounce = QTimer(self, singleShot=True, interval=300)
        self._debounce.timeout.connect(self._refresh_current)

        for p in self.settings.value("open_repos", [], type=list):
            if os.path.isdir(p):
                self.open_repo(p, quiet=True)
        self.tabs.setCurrentIndex(min(self.settings.value("tab", 0, type=int), self.tabs.count() - 1))
        self._tab_changed()

    # --------------------------------------------------------------- acciones
    def _act(self, text, glyph, slot, shortcut=None, tip=None) -> QAction:
        a = QAction(glyph_icon(glyph), text, self)
        if shortcut:
            a.setShortcut(QKeySequence(shortcut))
        a.setToolTip(tip or text)
        a.triggered.connect(lambda _=False: slot())
        return a

    def _build_actions(self):
        cur = self._on_repo
        self.a_commit = self._act("Commit", "✓", lambda: cur(lambda r: r.show_view("status")), "Ctrl+Shift+C",
                                  "Ir a «Estado de ficheros» para hacer commit")
        self.a_checkout = self._act("Checkout", "⎇", lambda: cur(lambda r: r.checkout_dialog()))
        self.a_discard = self._act("Descartar", "↺", lambda: cur(lambda r: r.discard_all()))
        self.a_stash = self._act("Stash", "▣", lambda: cur(lambda r: r.stash_dialog()))
        self.a_fetch = self._act("Fetch", "⇣", lambda: cur(lambda r: r.fetch()), "Ctrl+Shift+F")
        self.a_pull = self._act("Pull", "↓", lambda: cur(lambda r: r.pull()), "Ctrl+Shift+L")
        self.a_push = self._act("Push", "↑", lambda: cur(lambda r: r.push()), "Ctrl+Shift+P")
        self.a_branch = self._act("Rama", "⑂", lambda: cur(lambda r: r.new_branch()), "Ctrl+Shift+B")
        self.a_merge = self._act("Merge", "⇉", lambda: cur(lambda r: r.merge_dialog()), "Ctrl+Shift+M")
        self.a_tag = self._act("Etiqueta", "⚑", lambda: cur(lambda r: r.new_tag()))
        self.a_term = self._act("Terminal", "▸", lambda: cur(self._terminal))
        self.a_files = self._act("Carpeta", "▤", lambda: cur(self._open_folder))
        self.a_refresh = self._act("Actualizar", "⟳", self._refresh_current, "F5")

        tb = QToolBar("Principal")
        tb.setMovable(False)
        tb.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        tb.setIconSize(tb.iconSize() * 1.15)
        for group in ([self.a_commit, self.a_checkout, self.a_discard, self.a_stash],
                      [self.a_fetch, self.a_pull, self.a_push],
                      [self.a_branch, self.a_merge, self.a_tag],
                      [self.a_term, self.a_files, self.a_refresh]):
            for a in group:
                tb.addAction(a)
            tb.addSeparator()
        self.addToolBar(tb)
        self.repo_actions = [self.a_commit, self.a_checkout, self.a_discard, self.a_stash, self.a_fetch,
                             self.a_pull, self.a_push, self.a_branch, self.a_merge, self.a_tag, self.a_term,
                             self.a_files, self.a_refresh]

        mb = self.menuBar()
        f = mb.addMenu("&Archivo")
        f.addAction("Abrir repositorio…", self.open_dialog, QKeySequence("Ctrl+O"))
        f.addAction("Clonar…", self.clone_dialog, QKeySequence("Ctrl+Shift+N"))
        f.addAction("Crear repositorio nuevo…", self.init_dialog)
        f.addSeparator()
        f.addAction("Cerrar pestaña", lambda: self.close_tab(self.tabs.currentIndex()), QKeySequence("Ctrl+W"))
        f.addAction("Salir", self.close, QKeySequence("Ctrl+Q"))
        v = mb.addMenu("&Ver")
        v.addAction("Estado de ficheros", lambda: cur(lambda r: r.show_view("status")), QKeySequence("Ctrl+1"))
        v.addAction("Historial", lambda: cur(lambda r: r.show_view("history")), QKeySequence("Ctrl+2"))
        v.addAction(self.a_refresh)
        r = mb.addMenu("&Repositorio")
        for a in (self.a_fetch, self.a_pull, self.a_push, None, self.a_branch, self.a_merge, self.a_tag,
                  self.a_stash, None, self.a_term, self.a_files):
            r.addSeparator() if a is None else r.addAction(a)
        h = mb.addMenu("A&yuda")
        h.addAction("Acerca de Gitree", lambda: QMessageBox.about(
            self, "Gitree", f"<b>Gitree {__version__}</b><br>Cliente Git para Linux inspirado en Sourcetree."))

    # ---------------------------------------------------------------- pestañas
    def current_repo_view(self) -> RepoView | None:
        w = self.tabs.currentWidget()
        return w if isinstance(w, RepoView) else None

    def _on_repo(self, fn):
        rv = self.current_repo_view()
        if rv:
            fn(rv)

    def _tab_changed(self, *_):
        rv = self.current_repo_view()
        for a in self.repo_actions:
            a.setEnabled(rv is not None)
        if rv:
            self._update_title(rv)
            rv.refresh()
        else:
            self.setWindowTitle("Gitree")
            self.statusBar().showMessage("Listo")

    def _update_title(self, rv: RepoView):
        self.setWindowTitle(f"{rv.repo.name} — Gitree")
        self.statusBar().showMessage(f"{rv.repo.path}   ·   rama: {rv.current_branch_label()}")

    def open_repo(self, path: str, quiet: bool = False):
        try:
            repo = Repo.discover(path)
        except GitError as e:
            if not quiet:
                QMessageBox.warning(self, "No se puede abrir", str(e))
            return
        for i in range(1, self.tabs.count()):
            w = self.tabs.widget(i)
            if isinstance(w, RepoView) and w.repo.path == repo.path:
                self.tabs.setCurrentIndex(i)
                return
        rv = RepoView(repo)
        rv.message.connect(lambda m, rv=rv: self.statusBar().showMessage(m, 6000))
        rv.refreshed.connect(lambda rv=rv: self.current_repo_view() is rv and self._update_title(rv))
        idx = self.tabs.addTab(rv, repo.name)
        self.tabs.setTabToolTip(idx, repo.path)
        close = QToolButton(text="✕", autoRaise=True, toolTip="Cerrar pestaña")
        close.setStyleSheet("QToolButton{color:#7a8699;padding:0 4px;border:0;font-size:11px;}"
                            "QToolButton:hover{color:white;background:#d9534f;border-radius:3px;}")
        close.clicked.connect(lambda _=False, w=rv: self.close_tab(self.tabs.indexOf(w)))
        self.tabs.tabBar().setTabButton(idx, QTabBar.ButtonPosition.RightSide, close)
        self.tabs.setCurrentIndex(idx)
        self._add_bookmark(repo.path)

    def close_tab(self, i: int):
        if i > 0:
            w = self.tabs.widget(i)
            self.tabs.removeTab(i)
            w.deleteLater()

    # ------------------------------------------------------------- marcadores
    def _bookmarks(self) -> list[str]:
        return [p for p in self.settings.value("bookmarks", [], type=list)]

    def _add_bookmark(self, path: str):
        b = [p for p in self._bookmarks() if p != path]
        self.settings.setValue("bookmarks", [path] + b)
        self._refresh_bookmarks()

    def _remove_bookmark(self, path: str):
        self.settings.setValue("bookmarks", [p for p in self._bookmarks() if p != path])
        self._refresh_bookmarks()

    def _refresh_bookmarks(self):
        self.welcome.set_bookmarks(self._bookmarks())

    # ---------------------------------------------------------------- diálogos
    def open_dialog(self):
        d = QFileDialog.getExistingDirectory(self, "Abrir repositorio", os.path.expanduser("~"))
        if d:
            self.open_repo(d)

    def init_dialog(self):
        d = QFileDialog.getExistingDirectory(self, "Carpeta del nuevo repositorio", os.path.expanduser("~"))
        if d:
            try:
                Repo.init(d)
            except GitError as e:
                QMessageBox.critical(self, "Error", str(e))
                return
            self.open_repo(d)

    def clone_dialog(self):
        dlg = CloneDialog(self)
        if dlg.exec() != QDialog.DialogCode.Accepted:
            return
        url, dest = dlg.target()
        if not url or not dest:
            return
        task = Task(lambda: Repo.clone(url, dest).path)
        task.signals.done.connect(self._clone_done, Qt.ConnectionType.QueuedConnection)
        self._clone_tasks.add(task)
        self.statusBar().showMessage(f"Clonando {url}…")
        QThreadPool.globalInstance().start(task)

    def _clone_done(self, task, path, exc):
        self._clone_tasks.discard(task)
        if exc:
            QMessageBox.critical(self, "Error al clonar", str(exc))
            self.statusBar().showMessage("Clonado fallido")
        else:
            self.statusBar().showMessage("Clonado completado", 5000)
            self.open_repo(path)

    # ----------------------------------------------------------------- sistema
    def _terminal(self, rv: RepoView):
        path = rv.repo.path
        for term, args in (("x-terminal-emulator", []), ("gnome-terminal", ["--working-directory", path]),
                           ("konsole", ["--workdir", path]), ("xfce4-terminal", ["--working-directory", path]),
                           ("kitty", ["--directory", path]), ("alacritty", ["--working-directory", path]),
                           ("xterm", [])):
            if shutil.which(term):
                subprocess.Popen([term, *args], cwd=path)
                return
        QMessageBox.information(self, "Terminal", "No se encontró ningún emulador de terminal.")

    def _open_folder(self, rv: RepoView):
        if shutil.which("xdg-open"):
            subprocess.Popen(["xdg-open", rv.repo.path])

    def _refresh_current(self):
        rv = self.current_repo_view()
        if rv and not rv.busy:
            rv.refresh()

    def changeEvent(self, e):
        if e.type() == QEvent.Type.ActivationChange and self.isActiveWindow():
            self._debounce.start()
        super().changeEvent(e)

    def closeEvent(self, e):
        repos = [self.tabs.widget(i).repo.path for i in range(1, self.tabs.count())]
        self.settings.setValue("open_repos", repos)
        self.settings.setValue("tab", self.tabs.currentIndex())
        QThreadPool.globalInstance().waitForDone(3000)
        super().closeEvent(e)
