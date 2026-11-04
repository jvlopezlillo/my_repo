"""Acciones compartidas del menú contextual de ficheros + diálogos de Log y Blame."""
from __future__ import annotations

import os
from datetime import datetime

from PySide6.QtCore import QUrl, Qt
from PySide6.QtGui import QDesktopServices, QFontDatabase, QGuiApplication
from PySide6.QtWidgets import (QAbstractItemView, QDialog, QHeaderView, QMessageBox, QPlainTextEdit,
                               QSplitter, QTableWidget, QTableWidgetItem, QVBoxLayout)

from .diffview import DiffView
from .git import GitError, Repo


class FileHistoryDialog(QDialog):
    def __init__(self, repo: Repo, path: str, parent=None):
        super().__init__(parent)
        self.repo, self.path = repo, path
        self.setWindowTitle(f"Historial de {path}")
        self.resize(1050, 680)
        self.commits = repo.file_log(path)
        self.table = QTableWidget(len(self.commits), 4)
        self.table.setHorizontalHeaderLabels(["Commit", "Fecha", "Autor", "Descripción"])
        self.table.verticalHeader().hide()
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setAlternatingRowColors(True)
        self.table.setShowGrid(False)
        for i, (h, author, ts, subj) in enumerate(self.commits):
            for j, text in enumerate((h[:7], datetime.fromtimestamp(ts).strftime("%d %b %Y %H:%M"), author, subj)):
                self.table.setItem(i, j, QTableWidgetItem(text))
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(3, QHeaderView.ResizeMode.Stretch)
        for c, w in ((0, 80), (1, 130), (2, 150)):
            self.table.setColumnWidth(c, w)
        self.diff = DiffView()
        split = QSplitter(Qt.Orientation.Vertical)
        split.addWidget(self.table)
        split.addWidget(self.diff)
        split.setSizes([260, 400])
        lay = QVBoxLayout(self)
        lay.addWidget(split)
        self.table.currentCellChanged.connect(lambda row, *_: self._show(row))
        if self.commits:
            self.table.selectRow(0)
        else:
            self.diff.show_diff("(este fichero no tiene historial)", None)

    def _show(self, row: int) -> None:
        if 0 <= row < len(self.commits):
            self.diff.show_diff(self.repo.file_commit_diff(self.commits[row][0], self.path), "commit")


class BlameDialog(QDialog):
    def __init__(self, title: str, text: str, parent=None):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.resize(1100, 700)
        view = QPlainTextEdit(text)
        view.setReadOnly(True)
        view.setLineWrapMode(QPlainTextEdit.LineWrapMode.NoWrap)
        view.setFont(QFontDatabase.systemFont(QFontDatabase.SystemFont.FixedFont))
        lay = QVBoxLayout(self)
        lay.addWidget(view)


class FileTools:
    """Acciones que no dependen de si el fichero está en el árbol de trabajo o en un commit."""

    def __init__(self, repo: Repo, parent):
        self.repo, self.parent = repo, parent

    def abs(self, path: str) -> str:
        return os.path.join(self.repo.path, path)

    def _info(self, title: str, text: str) -> None:
        QMessageBox.information(self.parent, title, text)

    def open(self, paths: list[str]) -> None:
        for p in paths:
            if not os.path.exists(self.abs(p)):
                self._info("Abrir", f"«{p}» no existe en el disco.")
                return
            QDesktopServices.openUrl(QUrl.fromLocalFile(self.abs(p)))

    def show_in_folder(self, paths: list[str]) -> None:
        folder = os.path.dirname(self.abs(paths[0]))
        while folder and not os.path.isdir(folder):
            folder = os.path.dirname(folder)
        QDesktopServices.openUrl(QUrl.fromLocalFile(folder or self.repo.path))

    def copy_paths(self, paths: list[str]) -> None:
        QGuiApplication.clipboard().setText("\n".join(self.abs(p) for p in paths))

    def external_diff(self, args: list[str]) -> None:
        try:
            self.repo.external_diff(args)
        except GitError as e:
            QMessageBox.warning(self.parent, "Diff externo", str(e))

    def log(self, path: str) -> None:
        FileHistoryDialog(self.repo, path, self.parent).exec()

    def blame(self, path: str, rev: str | None = None) -> None:
        try:
            text = self.repo.blame(path, rev)
        except GitError as e:
            QMessageBox.warning(self.parent, "Blame", str(e))
            return
        BlameDialog(f"Blame de {path}" + (f" @ {rev[:7]}" if rev else ""), text, self.parent).exec()
