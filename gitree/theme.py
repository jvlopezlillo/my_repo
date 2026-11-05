"""Estilo visual (inspirado en Sourcetree) e iconos generados por código."""
from __future__ import annotations

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPixmap

LANE_COLORS = ["#2f80ed", "#27ae60", "#e67e22", "#9b51e0", "#eb5757", "#00a6a6", "#d4a017", "#e84393"]
BLUE = "#2b6cb0"

STATUS_COLORS = {
    "M": "#e6a117", "A": "#27ae60", "D": "#d9534f", "R": "#2f80ed", "C": "#2f80ed",
    "?": "#27ae60", "U": "#d9534f", "T": "#e6a117",
}

STYLESHEET = """
QMainWindow, QDialog { background: #f5f6f8; }
QToolBar { background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #fdfdfe, stop:1 #e9ecf1);
           border: 0; border-bottom: 1px solid #c9ced8; spacing: 2px; padding: 3px 6px; }
QToolBar::separator { background: #c9ced8; width: 1px; margin: 6px 6px; }
QToolButton { border: 1px solid transparent; border-radius: 4px; padding: 3px 7px; color: #34495e; font-size: 11px; }
QToolButton:hover { background: #dde6f5; border-color: #b9c9e6; }
QToolButton:pressed { background: #c9d9f2; }
QToolButton:disabled { color: #a5adb9; }
QTabWidget::pane { border: 0; }
QTabBar::tab { background: #e3e6ec; border: 1px solid #c9ced8; border-bottom: 0; padding: 5px 14px;
               margin-right: 1px; border-top-left-radius: 4px; border-top-right-radius: 4px; color: #4a5568; }
QTabBar::tab:selected { background: #ffffff; color: #1a202c; font-weight: 600; }
QTreeWidget#sidebar { background: #eef1f6; border: 0; border-right: 1px solid #c9ced8; outline: 0; font-size: 12px; }
QTreeWidget#sidebar::item { padding: 3px 2px; }
QTreeWidget#sidebar::item:hover { background: #dfe6f1; }
QTreeWidget#sidebar::item:selected { background: #2d6cdf; color: white; }
QTableView, QListWidget, QTextBrowser, QPlainTextEdit { background: white; border: 1px solid #d5d9e0; alternate-background-color: #f7f9fc; }
QTableView { gridline-color: transparent; selection-background-color: #2d6cdf; selection-color: white; outline: 0; }
QListWidget { outline: 0; }
QListWidget::item { padding: 2px; }
QListWidget::item:selected { background: #2d6cdf; color: white; }
QHeaderView::section { background: #f0f2f6; border: 0; border-right: 1px solid #d5d9e0; border-bottom: 1px solid #c9ced8;
                       padding: 4px 6px; color: #4a5568; font-weight: 600; }
QSplitter::handle { background: #d5d9e0; }
QSplitter::handle:horizontal { width: 1px; }
QSplitter::handle:vertical { height: 1px; }
QLabel#sectionTitle { background: #e9ecf1; color: #4a5568; font-weight: 600; padding: 4px 8px; border-bottom: 1px solid #d5d9e0; }
QLabel#banner { background: #fff4ce; color: #7a5c00; padding: 6px 10px; border-bottom: 1px solid #e6cf7a; }
QPushButton { background: qlineargradient(x1:0,y1:0,x2:0,y2:1, stop:0 #ffffff, stop:1 #eceff4);
              border: 1px solid #b7bfcc; border-radius: 4px; padding: 4px 12px; }
QPushButton:hover { border-color: #2d6cdf; }
QPushButton:disabled { color: #a5adb9; }
QPushButton#primary { background: #2d6cdf; color: white; border-color: #2358b8; font-weight: 600; }
QPushButton#primary:disabled { background: #a9c1ef; border-color: #a9c1ef; }
QPushButton.flat, QPushButton#link { border: 0; background: transparent; color: #2d6cdf; padding: 2px 6px; }
QStatusBar { background: #eef1f6; border-top: 1px solid #c9ced8; color: #4a5568; }
QLineEdit { border: 1px solid #b7bfcc; border-radius: 4px; padding: 3px 6px; background: white; }
"""


def _symbol_font(pixel_size: int, bold: bool = False) -> QFont:
    """Fuente con respaldo para los símbolos Unicode (DejaVu en Linux, Segoe UI Symbol en Windows)."""
    f = QFont()
    f.setFamilies(["DejaVu Sans", "Segoe UI Symbol", "Segoe UI", "Noto Sans Symbols2", "Arial Unicode MS"])
    f.setPixelSize(pixel_size)
    f.setBold(bold)
    return f


def _pixmap(size: int, dpr: int = 2) -> QPixmap:
    pm = QPixmap(size * dpr, size * dpr)
    pm.setDevicePixelRatio(dpr)
    pm.fill(Qt.GlobalColor.transparent)
    return pm


def glyph_icon(glyph: str, color: str = BLUE, size: int = 28) -> QIcon:
    pm = _pixmap(size)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setRenderHint(QPainter.RenderHint.TextAntialiasing)
    font = _symbol_font(int(size * 0.78))
    p.setFont(font)
    p.setPen(QColor(color))
    p.drawText(QRectF(0, 0, size, size), Qt.AlignmentFlag.AlignCenter, glyph)
    p.end()
    icon = QIcon(pm)
    # variante desactivada
    dis = _pixmap(size)
    p = QPainter(dis)
    p.setRenderHint(QPainter.RenderHint.TextAntialiasing)
    p.setFont(font)
    p.setPen(QColor("#b4bcc8"))
    p.drawText(QRectF(0, 0, size, size), Qt.AlignmentFlag.AlignCenter, glyph)
    p.end()
    icon.addPixmap(dis, QIcon.Mode.Disabled)
    return icon


_badge_cache: dict[str, QIcon] = {}


def status_badge(letter: str) -> QIcon:
    if letter not in _badge_cache:
        size = 16
        pm = _pixmap(size)
        p = QPainter(pm)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setBrush(QColor(STATUS_COLORS.get(letter, "#7f8c8d")))
        p.setPen(Qt.PenStyle.NoPen)
        p.drawRoundedRect(QRectF(1, 1, size - 2, size - 2), 3, 3)
        f = _symbol_font(11, bold=True)
        p.setFont(f)
        p.setPen(QColor("white"))
        p.drawText(QRectF(0, 0, size, size), Qt.AlignmentFlag.AlignCenter, letter if letter != "?" else "+")
        p.end()
        _badge_cache[letter] = QIcon(pm)
    return _badge_cache[letter]
