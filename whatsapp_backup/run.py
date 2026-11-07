"""Arranque: `python run.py` (GUI) o `python run.py backup|restore <carpeta> [--legacy-apk x.apk]`."""
import argparse
from pathlib import Path

from wabackup import core
from wabackup.adb import Adb


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", nargs="?", choices=["backup", "restore", "verify"])
    ap.add_argument("folder", nargs="?")
    ap.add_argument("--legacy-apk")
    a = ap.parse_args()
    if not a.cmd:
        from wabackup.gui import main as gui
        return gui()
    legacy = Path(a.legacy_apk) if a.legacy_apk else None
    folder = Path(a.folder)
    if a.cmd == "backup":
        core.backup(Adb(), folder, print, legacy)
    elif a.cmd == "restore":
        core.restore(Adb(), folder, print, legacy)
    else:
        core.verify(folder)


if __name__ == "__main__":
    main()
