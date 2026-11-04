import sys

from PySide6.QtWidgets import QApplication

from .mainwindow import MainWindow
from .theme import STYLESHEET


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Gitree")
    app.setDesktopFileName("gitree")
    app.setStyle("Fusion")
    app.setStyleSheet(STYLESHEET)
    win = MainWindow()
    for arg in sys.argv[1:]:
        win.open_repo(arg)
    win.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
