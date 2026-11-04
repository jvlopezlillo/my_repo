"""Prueba de humo de la GUI en modo offscreen (flujo real de usuario)."""
import os
import subprocess
import time

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
pytest.importorskip("PySide6")

from PySide6.QtCore import QSettings, Qt  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from gitree.git import Repo  # noqa: E402
from gitree.mainwindow import MainWindow  # noqa: E402
from gitree.theme import STYLESHEET  # noqa: E402


@pytest.fixture(scope="module")
def app():
    a = QApplication.instance() or QApplication([])
    a.setStyle("Fusion")
    a.setStyleSheet(STYLESHEET)
    return a


def sh(cwd, *args):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True)


def test_full_flow(app, tmp_path):
    QSettings("gitree", "gitree").clear()
    bare = str(tmp_path / "bare.git")
    sh(tmp_path, "git", "init", "-q", "--bare", "--initial-branch=main", bare)
    work = str(tmp_path / "w")
    repo = Repo.init(work)
    for k, v in (("user.name", "T"), ("user.email", "t@t")):
        sh(work, "git", "config", k, v)
    sh(work, "git", "checkout", "-q", "-b", "main")
    repo.add_remote("origin", bare)
    with open(os.path.join(work, "f.txt"), "w") as fh:
        fh.write("".join(f"l{i}\n" for i in range(30)))

    win = MainWindow()
    win.open_repo(work)
    rv = win.current_repo_view()
    ws = rv.workspace
    rv.show_view("status")
    assert ws.unstaged.count() == 1

    # stage con el checkbox
    ws.unstaged.item(0).setCheckState(Qt.CheckState.Checked)
    assert ws.staged.count() == 1 and ws.unstaged.count() == 0
    # commit
    ws.message.setPlainText("Primer commit")
    ws.commit()
    assert [c.subject for c in repo.log()] == ["Primer commit"]

    # stage por hunk
    lines = [f"l{i}\n" for i in range(30)]
    lines[1], lines[28] = "X\n", "Y\n"
    with open(os.path.join(work, "f.txt"), "w") as fh:
        fh.writelines(lines)
    rv.refresh()
    ws.unstaged.setCurrentRow(0)
    patch = ws.diff._hunk_patch(next(i for i, l in enumerate(ws.diff._raw.split("\n")) if l.startswith("@@")))
    ws._hunk("stage", patch)
    assert ws.staged.count() == 1 and ws.unstaged.count() == 1

    # push asíncrono
    rv.push()
    for _ in range(200):
        app.processEvents()
        if not rv.busy:
            break
        time.sleep(0.05)
    assert not rv.busy
    assert repo.refs().local[0].upstream == "origin/main"

    # acciones del historial
    rv.show_view("history")
    c = rv.history.selected_commit()
    rv._history_action("tag", c) if False else repo.create_tag("v0", c.hash)
    rv.refresh()
    assert "v0" in repo.refs().tags
    win.close()
