"""Lógica de backup y restauración de WhatsApp.

Formato de copia (carpeta):
    manifest.json          metadatos + SHA-256 de cada fichero
    storage/<n>/...        carpetas de /sdcard (chats cifrados, media, backups)
    apk/*.apk              APK(s) de WhatsApp instalados en el móvil
    appdata.ab             (opcional) datos privados de la app vía adb backup
"""
from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path
from typing import Callable

from .adb import Adb, AdbError

PKG = "com.whatsapp"
# Android 10 (y anteriores) usa /sdcard/WhatsApp; Android 11+ usa Android/media.
STORAGE_DIRS = {
    "legacy": "/sdcard/WhatsApp",
    "scoped": "/sdcard/Android/media/com.whatsapp",
}
FORMAT_VERSION = 1

Log = Callable[[str], None]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _hash_tree(root: Path) -> dict[str, str]:
    return {
        p.relative_to(root).as_posix(): sha256(p)
        for p in sorted(root.rglob("*"))
        if p.is_file() and p.name != "manifest.json"
    }


def latest_chat_backup(adb: Adb) -> str:
    """Fecha del backup local de chats más reciente en el móvil."""
    for base in STORAGE_DIRS.values():
        for sub in ("Databases", "WhatsApp/Databases"):
            out = adb.shell(f'ls -l --time-style=long-iso "{base}/{sub}" 2>/dev/null', check=False)
            lines = [l for l in out.splitlines() if "msgstore" in l and ".crypt" in l]
            if lines:
                # el fichero msgstore.db.cryptXX (sin fecha en el nombre) es el más reciente
                return sorted(lines, key=lambda l: " ".join(l.split()[5:7]))[-1]
    return ""


def backup(adb: Adb, dest: Path, log: Log = print, legacy_apk: Path | None = None) -> Path:
    dest = Path(dest)
    if dest.exists() and any(dest.iterdir()):
        raise AdbError(f"La carpeta destino no está vacía: {dest}")
    dest.mkdir(parents=True, exist_ok=True)

    serial = adb.require_device()
    log(f"Móvil: {serial}")
    apks = adb.package_paths(PKG)
    if not apks:
        raise AdbError("WhatsApp no está instalado en el móvil.")
    vname, vcode = adb.package_version(PKG)
    log(f"WhatsApp {vname} (código {vcode})")
    last = latest_chat_backup(adb)
    log("Último backup de chats en el móvil: " + (last or "NO ENCONTRADO (haz uno en WhatsApp > Ajustes > Chats > Copia de seguridad)"))

    stored = []
    for key, remote in STORAGE_DIRS.items():
        if adb.exists(remote):
            log(f"Copiando {remote} (puede tardar mucho)…")
            adb.pull(remote, dest / "storage" / key)
            stored.append({"key": key, "remote": remote})
        else:
            log(f"No existe {remote}, se omite.")
    if not stored:
        raise AdbError("No se encontró ninguna carpeta de WhatsApp en el almacenamiento.")

    log("Copiando APK de WhatsApp…")
    for a in apks:
        adb.pull(a, dest / "apk")

    appdata = False
    if legacy_apk:
        appdata = _backup_appdata(adb, dest, apks, Path(legacy_apk), log)

    log("Calculando sumas de verificación…")
    manifest = {
        "format": FORMAT_VERSION,
        "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "package": PKG,
        "version_name": vname,
        "version_code": vcode,
        "storage": stored,
        "has_appdata": appdata,
        "last_chat_backup": last,
        "files": _hash_tree(dest),
    }
    (dest / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    log(f"Backup completado: {len(manifest['files'])} ficheros en {dest}")
    return dest


def _backup_appdata(adb: Adb, dest: Path, apks: list[str], legacy_apk: Path, log: Log) -> bool:
    """Truco de la versión antigua: permite `adb backup` de los datos privados."""
    tmp = dest / "_orig_apks"
    for a in apks:
        adb.pull(a, tmp)
    originals = sorted(tmp.glob("*.apk"))
    try:
        log("Instalando WhatsApp antiguo (conserva los datos)…")
        adb.install([legacy_apk], downgrade=True)
        log(">>> En el móvil pulsa 'Realizar copia de seguridad de mis datos' SIN contraseña <<<")
        adb.run("backup", "-noapk", PKG, "-f", str(dest / "appdata.ab"), timeout=3600)
    finally:
        log("Reinstalando tu versión original de WhatsApp…")
        adb.install(originals, downgrade=True)
        for f in originals:
            f.unlink()
        tmp.rmdir()
    ok = (dest / "appdata.ab").exists() and (dest / "appdata.ab").stat().st_size > 1024
    log("Datos privados guardados." if ok else "AVISO: appdata.ab vacío; el truco no funcionó en este móvil.")
    return ok


def verify(src: Path, log: Log = print) -> dict:
    src = Path(src)
    mf = src / "manifest.json"
    if not mf.exists():
        raise AdbError("No es una copia válida (falta manifest.json).")
    manifest = json.loads(mf.read_text(encoding="utf-8"))
    bad = [rel for rel, h in manifest["files"].items()
           if not (src / rel).is_file() or sha256(src / rel) != h]
    if bad:
        raise AdbError(f"{len(bad)} ficheros dañados o ausentes, p. ej. {bad[0]}")
    log(f"Copia verificada ({len(manifest['files'])} ficheros).")
    return manifest


def restore(adb: Adb, src: Path, log: Log = print, legacy_apk: Path | None = None) -> None:
    """Úsalo con WhatsApp aún sin abrir tras el reset (o desinstalado)."""
    src = Path(src)
    manifest = verify(src, log)
    adb.require_device()

    if adb.package_paths(PKG):
        log("AVISO: WhatsApp ya está instalado. Si ya lo abriste y creaste un perfil, "
            "desinstálalo y vuelve a ejecutar la restauración.")
    else:
        apks = sorted((src / "apk").glob("*.apk"))
        if apks:
            log("Instalando WhatsApp desde la copia…")
            adb.install(apks)

    for item in manifest["storage"]:
        remote_parent, name = item["remote"].rsplit("/", 1)
        log(f"Copiando al móvil {item['remote']}…")
        adb.push(src / "storage" / item["key"] / name, remote_parent)

    if manifest.get("has_appdata") and legacy_apk:
        log("Restaurando datos privados (adb restore)…")
        adb.install([legacy_apk], downgrade=True)
        adb.run("restore", str(src / "appdata.ab"), timeout=3600)
        adb.install(sorted((src / "apk").glob("*.apk")), downgrade=True)

    log("LISTO. Ahora abre WhatsApp, verifica el MISMO número y pulsa 'RESTAURAR' "
        "cuando ofrezca la copia local encontrada.")
