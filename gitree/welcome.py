"""Pestaña «Marcadores»: repositorios recientes, abrir, clonar y crear."""
from __future__ import annotations

import os

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QMenu, QPushButton,
                               QVBoxLayout, QWidget)


class WelcomeView(QWidget):
    openRequested = Signal(str)
    openDialog = Signal()
    cloneDialog = Signal()
    initDialog = Signal()
    removeRequested = Signal(str)

    def __init__(self):
        super().__init__()
        title = QLabel("Marcadores")
        title.setStyleSheet("font-size:20px;font-weight:600;color:#2d3748;")
        sub = QLabel("Doble clic en un repositorio para abrirlo en una pestaña.")
        sub.setStyleSheet("color:#7a8699;")
        self.list = QListWidget()
        self.list.setAlternatingRowColors(True)
        self.list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.list.setStyleSheet("QListWidget::item{padding:8px 6px;}")
        btn_open = QPushButton("Abrir existente…")
        btn_clone = QPushButton("Clonar…")
        btn_new = QPushButton("Crear nuevo…")
        row = QHBoxLayout()
        for b in (btn_open, btn_clone, btn_new):
            row.addWidget(b)
        row.addStretch(1)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(28, 22, 28, 22)
        lay.addWidget(title)
        lay.addWidget(sub)
        lay.addSpacing(8)
        lay.addLayout(row)
        lay.addWidget(self.list, 1)

        btn_open.clicked.connect(self.openDialog)
        btn_clone.clicked.connect(self.cloneDialog)
        btn_new.clicked.connect(self.initDialog)
        self.list.itemDoubleClicked.connect(lambda it: self.openRequested.emit(it.data(Qt.ItemDataRole.UserRole)))
        self.list.customContextMenuRequested.connect(self._menu)

    def set_bookmarks(self, paths: list[str]) -> None:
        self.list.clear()
        for p in paths:
            it = QListWidgetItem(f"{os.path.basename(p.rstrip('/')) or p}\n{p}")
            it.setData(Qt.ItemDataRole.UserRole, p)
            self.list.addItem(it)

    def _menu(self, pos) -> None:
        it = self.list.itemAt(pos)
        if not it:
            return
        p = it.data(Qt.ItemDataRole.UserRole)
        m = QMenu(self)
        m.addAction("Abrir").triggered.connect(lambda: self.openRequested.emit(p))
        m.addAction("Quitar de marcadores").triggered.connect(lambda: self.removeRequested.emit(p))
        m.exec(self.list.viewport().mapToGlobal(pos))
