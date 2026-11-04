"""Pestaña de un repositorio: sidebar + (estado | historial) y todas las operaciones."""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal, Slot
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (QInputDialog, QMessageBox, QSplitter, QStackedWidget, QVBoxLayout,
                               QWidget)
from PySide6.QtCore import QThreadPool

from .git import Commit, GitError, Repo
from .history import HistoryView
from .sidebar import Sidebar
from .workers import Task
from .workspace import WorkspaceView


class RepoView(QWidget):
    message = Signal(str)      # texto para la barra de estado
    refreshed = Signal()
    busyChanged = Signal(bool)

    def __init__(self, repo: Repo):
        super().__init__()
        self.repo = repo
        self._tasks: set[Task] = set()
        self._view = "history"

        self.sidebar = Sidebar()
        self.workspace = WorkspaceView(repo)
        self.history = HistoryView(repo)
        self.stack = QStackedWidget()
        self.stack.addWidget(self.workspace)
        self.stack.addWidget(self.history)
        self.stack.setCurrentWidget(self.history)

        split = QSplitter(Qt.Orientation.Horizontal)
        split.addWidget(self.sidebar)
        split.addWidget(self.stack)
        split.setSizes([230, 1000])
        split.setStretchFactor(1, 1)
        split.setCollapsible(0, False)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(split)

        self.sidebar.viewRequested.connect(self.show_view)
        self.sidebar.refActivated.connect(self._ref_activated)
        self.sidebar.menuAction.connect(self._sidebar_action)
        self.workspace.changed.connect(self.refresh)
        self.workspace.pushRequested.connect(self.push)
        self.history.action.connect(self._history_action)
        self.history.wipSelected.connect(lambda _on: self._place_workspace())
        self.refresh()

    # ----------------------------------------------------------- utilidades
    @property
    def busy(self) -> bool:
        return bool(self._tasks)

    def show_view(self, name: str) -> None:
        self._view = name
        self.sidebar.set_view(name)
        self._place_workspace()
        self.stack.setCurrentWidget(self.workspace if name == "status" else self.history)
        for i in range(self.sidebar.topLevelItemCount()):
            top = self.sidebar.topLevelItem(i)
            for j in range(top.childCount()):
                d = top.child(j).data(0, Qt.ItemDataRole.UserRole)
                if d and d[0] == "view":
                    top.child(j).setSelected(d[1] == name)

    def _place_workspace(self) -> None:
        """El panel de ficheros vive en la vista «Estado», o bajo el historial si la fila
        «Cambios sin confirmar» está seleccionada (como en Sourcetree)."""
        if self._view == "history" and self.history.wip_selected:
            if self.stack.indexOf(self.workspace) >= 0:
                self.stack.removeWidget(self.workspace)
            self.history.embed_workspace(self.workspace)
        else:
            self.history.release_workspace(self.workspace)
            if self.stack.indexOf(self.workspace) < 0:
                self.stack.insertWidget(0, self.workspace)

    def refresh(self) -> None:
        try:
            status = self.repo.status()
            refs = self.repo.refs()
            stashes = self.repo.stashes()
        except GitError as e:
            self.message.emit(f"Error al leer el repositorio: {e}")
            return
        self.sidebar.populate(refs, stashes, len(status))
        self.workspace.populate(status)
        self.history.reload(len(status))
        self.refreshed.emit()

    def current_branch_label(self) -> str:
        b = self.repo.current_branch()
        return b or "HEAD desacoplado"

    def _error(self, title: str, exc: Exception) -> None:
        QMessageBox.critical(self, title, str(exc))
        self.refresh()

    def _do(self, title: str, fn, ok_msg: str = "") -> bool:
        """Ejecuta síncronamente una operación rápida mostrando errores."""
        try:
            fn()
        except GitError as e:
            self._error(title, e)
            return False
        if ok_msg:
            self.message.emit(ok_msg)
        self.refresh()
        return True

    def run_async(self, label: str, fn, done=None) -> None:
        task = Task(fn, done)
        task.label = label  # type: ignore[attr-defined]
        task.signals.done.connect(self._task_finished, Qt.ConnectionType.QueuedConnection)
        self._tasks.add(task)
        self.message.emit(f"{label}…")
        self.busyChanged.emit(True)
        QThreadPool.globalInstance().start(task)

    @Slot(object, object, object)
    def _task_finished(self, task: Task, result, exc) -> None:
        self._tasks.discard(task)
        self.busyChanged.emit(self.busy)
        label = task.label  # type: ignore[attr-defined]
        if exc is not None:
            self.message.emit(f"{label}: error")
            self._error(label, exc)
            return
        self.message.emit(f"{label}: completado")
        if task.callback:
            task.callback(result)
        self.refresh()

    # ----------------------------------------------------- operaciones remotas
    def fetch(self) -> None:
        self.run_async("Fetch", self.repo.fetch)

    def pull(self) -> None:
        self.run_async("Pull", self.repo.pull)

    def push(self) -> None:
        self.run_async("Push", self.repo.push)

    # ----------------------------------------------------------- operaciones
    def new_branch(self, start: str | None = None) -> None:
        name, ok = QInputDialog.getText(self, "Nueva rama",
                                        "Nombre de la rama" + (f" (desde {start[:7]})" if start else "") + ":")
        if ok and name.strip():
            self._do("Nueva rama", lambda: self.repo.create_branch(name.strip(), start),
                     f"Rama «{name.strip()}» creada")

    def merge_dialog(self) -> None:
        refs = self.repo.refs()
        cur = self.repo.current_branch()
        options = [b.name for b in refs.local if b.name != cur] + refs.remote
        if not options:
            QMessageBox.information(self, "Merge", "No hay otras ramas para fusionar.")
            return
        item, ok = QInputDialog.getItem(self, "Merge", f"Fusionar en «{cur}»:", options, 0, False)
        if ok:
            self.merge(item)

    def merge(self, ref: str) -> None:
        self._do("Merge", lambda: self.repo.merge(ref), f"«{ref}» fusionada")
        if self.repo.merge_in_progress():
            self.show_view("status")

    def new_tag(self, rev: str = "HEAD") -> None:
        name, ok = QInputDialog.getText(self, "Nueva etiqueta", "Nombre de la etiqueta:")
        if not (ok and name.strip()):
            return
        msg, ok = QInputDialog.getText(self, "Nueva etiqueta", "Mensaje (opcional, crea etiqueta anotada):")
        if ok:
            self._do("Etiqueta", lambda: self.repo.create_tag(name.strip(), rev, msg.strip()),
                     f"Etiqueta «{name.strip()}» creada")

    def stash_dialog(self) -> None:
        if not self.workspace.staged.count() and not self.workspace.unstaged.count():
            QMessageBox.information(self, "Stash", "No hay cambios que guardar.")
            return
        msg, ok = QInputDialog.getText(self, "Stash", "Descripción (opcional):")
        if ok:
            self._do("Stash", lambda: self.repo.stash_push(msg.strip()), "Cambios guardados en el stash")

    def checkout_dialog(self) -> None:
        refs = self.repo.refs()
        cur = self.repo.current_branch()
        options = [b.name for b in refs.local if b.name != cur] + refs.remote + refs.tags
        if not options:
            QMessageBox.information(self, "Checkout", "No hay otras ramas o etiquetas.")
            return
        item, ok = QInputDialog.getItem(self, "Checkout", "Cambiar a:", options, 0, False)
        if ok:
            kind = "remote" if item in refs.remote else ("tag" if item in refs.tags else "local")
            self._ref_activated(kind, item)

    def discard_all(self) -> None:
        files = self.workspace.unstaged.all_files()
        if not files:
            QMessageBox.information(self, "Descartar", "No hay cambios sin stage.")
            return
        self.workspace._discard(files)

    def _ref_activated(self, kind: str, name: str) -> None:
        if kind == "remote":
            fn = lambda: self.repo.checkout_remote(name)
        else:
            fn = lambda: self.repo.checkout(name)
        self._do("Checkout", fn, f"Ahora en «{name}»")

    def _sidebar_action(self, action: str, kind: str, name: str) -> None:
        r = self.repo
        if action == "checkout":
            self._ref_activated(kind, name)
        elif action == "merge":
            self.merge(name)
        elif action == "rename":
            new, ok = QInputDialog.getText(self, "Renombrar rama", "Nuevo nombre:", text=name)
            if ok and new.strip():
                self._do("Renombrar", lambda: r.rename_branch(name, new.strip()))
        elif action == "delete" and kind == "local":
            if QMessageBox.question(self, "Eliminar rama", f"¿Eliminar la rama «{name}»?") == QMessageBox.StandardButton.Yes:
                try:
                    r.delete_branch(name)
                except GitError as e:
                    if QMessageBox.question(self, "La rama no está fusionada",
                                            f"{e}\n\n¿Forzar el borrado?") == QMessageBox.StandardButton.Yes:
                        self._do("Eliminar rama", lambda: r.delete_branch(name, force=True))
                        return
                self.refresh()
        elif action == "delete" and kind == "tag":
            if QMessageBox.question(self, "Eliminar etiqueta", f"¿Eliminar la etiqueta «{name}»?") == QMessageBox.StandardButton.Yes:
                self._do("Eliminar etiqueta", lambda: r.delete_tag(name))
        elif action == "delete_remote":
            if QMessageBox.question(self, "Eliminar del remoto", f"¿Eliminar «{name}» del servidor?") == QMessageBox.StandardButton.Yes:
                self.run_async("Eliminar rama remota", lambda: r.delete_remote_branch(name))
        elif action == "push_tag":
            self.run_async("Push de etiqueta", lambda: r.push_tag(name))
        elif action == "apply":
            self._do("Stash", lambda: r.stash_apply(name))
        elif action == "pop":
            self._do("Stash", lambda: r.stash_pop(name))
        elif action == "drop":
            if QMessageBox.question(self, "Eliminar stash", "¿Eliminar este stash?") == QMessageBox.StandardButton.Yes:
                self._do("Stash", lambda: r.stash_drop(name))

    def _history_action(self, action: str, c: Commit) -> None:
        r = self.repo
        if action == "checkout":
            self._do("Checkout", lambda: r.checkout(c.hash), f"HEAD en {c.short} (desacoplado)")
        elif action == "branch":
            self.new_branch(c.hash)
        elif action == "tag":
            self.new_tag(c.hash)
        elif action == "cherry":
            self._do("Cherry-pick", lambda: r.cherry_pick(c.hash), f"{c.short} aplicado")
        elif action == "revert":
            self._do("Revertir", lambda: r.revert(c.hash), f"{c.short} revertido")
        elif action == "copy":
            QGuiApplication.clipboard().setText(c.hash)
            self.message.emit("Hash copiado")
        elif action == "reset":
            modes = ["mixed — mantiene los cambios sin stage", "soft — mantiene los cambios en stage",
                     "hard — DESCARTA todos los cambios"]
            item, ok = QInputDialog.getItem(self, "Reset", f"Mover la rama actual a {c.short}:", modes, 0, False)
            if not ok:
                return
            mode = item.split(" ")[0]
            if mode == "hard" and QMessageBox.warning(
                    self, "Reset --hard", "Se perderán todos los cambios locales. ¿Continuar?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
            self._do("Reset", lambda: r.reset(c.hash, mode))
