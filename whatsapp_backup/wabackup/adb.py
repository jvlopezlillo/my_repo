"""Envoltorio fino sobre el ejecutable `adb`."""
from __future__ import annotations

import io
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

PLATFORM_TOOLS_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
APP_DIR = Path(__file__).resolve().parent.parent


class AdbError(RuntimeError):
    pass


def find_adb() -> str | None:
    """Busca adb: carpeta platform-tools junto a la app, o en el PATH."""
    exe = "adb.exe" if os.name == "nt" else "adb"
    local = APP_DIR / "platform-tools" / exe
    if local.exists():
        return str(local)
    return shutil.which("adb")


def download_platform_tools(url: str = PLATFORM_TOOLS_URL) -> str:
    """Descarga las platform-tools oficiales de Google junto a la app."""
    with urllib.request.urlopen(url, timeout=120) as r:
        data = r.read()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        z.extractall(APP_DIR)
    path = find_adb()
    if not path:
        raise AdbError("No se pudo instalar adb.")
    return path


class Adb:
    def __init__(self, path: str | None = None, serial: str | None = None):
        self.path = path or find_adb()
        self.serial = serial
        if not self.path:
            raise AdbError("No se encontró adb. Usa 'Descargar ADB' o instálalo en el PATH.")

    def run(self, *args: str, timeout: int | None = None, check: bool = True) -> str:
        cmd = [self.path]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        kw = {}
        if sys.platform == "win32":
            kw["creationflags"] = subprocess.CREATE_NO_WINDOW
        p = subprocess.run(cmd, capture_output=True, timeout=timeout, **kw)
        out = (p.stdout + p.stderr).decode("utf-8", "replace")
        if check and p.returncode != 0:
            raise AdbError(f"adb {' '.join(args)} falló: {out.strip()}")
        return out

    # --- dispositivo ---
    def devices(self) -> list[tuple[str, str]]:
        res = []
        for line in self.run("devices").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                res.append((parts[0], parts[1]))
        return res

    def require_device(self) -> str:
        devs = self.devices()
        ready = [s for s, st in devs if st == "device"]
        if not ready:
            if any(st == "unauthorized" for _, st in devs):
                raise AdbError("El móvil no ha autorizado el PC: acepta el aviso de depuración USB en la pantalla.")
            raise AdbError("No hay ningún móvil conectado con depuración USB activada.")
        if self.serial is None:
            self.serial = ready[0]
        return self.serial

    def shell(self, cmd: str, check: bool = True) -> str:
        return self.run("shell", cmd, check=check)

    def exists(self, remote: str) -> bool:
        return "__yes__" in self.shell(f'[ -e "{remote}" ] && echo __yes__', check=False)

    # --- ficheros ---
    def pull(self, remote: str, local_dir: str | Path) -> None:
        Path(local_dir).mkdir(parents=True, exist_ok=True)
        self.run("pull", remote, str(local_dir))

    def push(self, local: str | Path, remote_dir: str) -> None:
        self.shell(f'mkdir -p "{remote_dir}"')
        self.run("push", str(local), remote_dir)

    # --- apps ---
    def package_paths(self, package: str) -> list[str]:
        out = self.shell(f"pm path {package}", check=False)
        return [l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")]

    def package_version(self, package: str) -> tuple[str, str]:
        out = self.shell(f"dumpsys package {package}", check=False)
        name = code = ""
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("versionName=") and not name:
                name = line.split("=", 1)[1]
            if line.startswith("versionCode=") and not code:
                code = line.split("=", 1)[1].split()[0]
        return name, code

    def install(self, apks: list[str | Path], downgrade: bool = False) -> None:
        flags = ["-r"] + (["-d"] if downgrade else [])
        if len(apks) == 1:
            self.run("install", *flags, str(apks[0]), timeout=600)
        else:
            self.run("install-multiple", *flags, *map(str, apks), timeout=600)
