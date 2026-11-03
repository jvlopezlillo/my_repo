"""Comparación de carpetas y operaciones de copia (sin dependencias de GUI)."""

from __future__ import annotations

import fnmatch
import os
import shutil
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Iterable, Iterator, Optional, Sequence

CHUNK = 1024 * 1024


class Status(Enum):
    IDENTICAL = "identical"
    DIFFERENT = "different"
    LEFT_ONLY = "left_only"
    RIGHT_ONLY = "right_only"
    TYPE_MISMATCH = "type_mismatch"  # fichero en un lado, carpeta en el otro


@dataclass
class DiffNode:
    name: str
    rel: str  # ruta relativa con "/" ("" para la raíz)
    left: Optional[Path]
    right: Optional[Path]
    left_kind: Optional[str]  # "dir" | "file" | None
    right_kind: Optional[str]
    status: Status
    children: list["DiffNode"] = field(default_factory=list)

    @property
    def is_dir(self) -> bool:
        return "dir" in (self.left_kind, self.right_kind) and self.status is not Status.TYPE_MISMATCH

    @property
    def is_file(self) -> bool:
        return self.left_kind == "file" or self.right_kind == "file"


def parse_ignore(text: str) -> list[str]:
    """'.git, __pycache__' -> ['.git', '__pycache__']"""
    return [p.strip() for p in text.replace(";", ",").split(",") if p.strip()]


def _ignored(name: str, patterns: Sequence[str]) -> bool:
    return any(fnmatch.fnmatch(name, p) for p in patterns)


def files_equal(a: Path, b: Path) -> bool:
    """Compara el contenido byte a byte (sin caché)."""
    try:
        if a.stat().st_size != b.stat().st_size:
            return False
        with open(a, "rb") as fa, open(b, "rb") as fb:
            while True:
                ca, cb = fa.read(CHUNK), fb.read(CHUNK)
                if ca != cb:
                    return False
                if not ca:
                    return True
    except OSError:
        return False


def _scan(path: Optional[Path], ignore: Sequence[str]) -> dict[str, tuple[Path, bool]]:
    result: dict[str, tuple[Path, bool]] = {}
    if path is None:
        return result
    try:
        with os.scandir(path) as it:
            for entry in it:
                if _ignored(entry.name, ignore):
                    continue
                try:
                    is_dir = entry.is_dir(follow_symlinks=False)
                except OSError:
                    is_dir = False
                result[entry.name] = (Path(entry.path), is_dir)
    except OSError:
        pass
    return result


def _build_dir(
    left: Optional[Path], right: Optional[Path], rel: str, name: str, ignore: Sequence[str]
) -> DiffNode:
    lentries, rentries = _scan(left, ignore), _scan(right, ignore)
    names = set(lentries) | set(rentries)

    def sort_key(n: str):
        is_dir = (lentries.get(n) or rentries.get(n))[1]
        return (not is_dir, n.lower(), n)

    children: list[DiffNode] = []
    for n in sorted(names, key=sort_key):
        crel = f"{rel}/{n}" if rel else n
        l, r = lentries.get(n), rentries.get(n)
        if l and r:
            (lp, ldir), (rp, rdir) = l, r
            if ldir and rdir:
                children.append(_build_dir(lp, rp, crel, n, ignore))
            elif not ldir and not rdir:
                st = Status.IDENTICAL if files_equal(lp, rp) else Status.DIFFERENT
                children.append(DiffNode(n, crel, lp, rp, "file", "file", st))
            else:
                children.append(
                    DiffNode(n, crel, lp, rp, "dir" if ldir else "file",
                             "dir" if rdir else "file", Status.TYPE_MISMATCH)
                )
        elif l:
            lp, ldir = l
            if ldir:
                children.append(_build_dir(lp, None, crel, n, ignore))
            else:
                children.append(DiffNode(n, crel, lp, None, "file", None, Status.LEFT_ONLY))
        else:
            rp, rdir = r
            if rdir:
                children.append(_build_dir(None, rp, crel, n, ignore))
            else:
                children.append(DiffNode(n, crel, None, rp, None, "file", Status.RIGHT_ONLY))

    if right is None:
        status = Status.LEFT_ONLY
    elif left is None:
        status = Status.RIGHT_ONLY
    else:
        status = (
            Status.IDENTICAL
            if all(c.status is Status.IDENTICAL for c in children)
            else Status.DIFFERENT
        )
    return DiffNode(
        name, rel, left, right,
        "dir" if left is not None else None,
        "dir" if right is not None else None,
        status, children,
    )


def compare_dirs(left: Path, right: Path, ignore: Sequence[str] = ()) -> DiffNode:
    """Compara recursivamente dos carpetas. Devuelve el nodo raíz."""
    return _build_dir(Path(left), Path(right), "", "", ignore)


def iter_nodes(node: DiffNode) -> Iterator[DiffNode]:
    for c in node.children:
        yield c
        yield from iter_nodes(c)


def summarize(root: DiffNode) -> dict[Status, int]:
    """Cuenta ficheros (no carpetas) por estado."""
    counts = {s: 0 for s in Status}
    for n in iter_nodes(root):
        if n.is_file or n.status is Status.TYPE_MISMATCH:
            counts[n.status] += 1
    return counts


def path_under(root: Path, rel: str) -> Path:
    return root.joinpath(*rel.split("/")) if rel else root


def _is_real_dir(p: Path) -> bool:
    return p.is_dir() and not p.is_symlink()


def _remove(p: Path) -> None:
    if _is_real_dir(p):
        shutil.rmtree(p)
    else:
        p.unlink()


def copy_path(src: Path, dst: Path, ignore: Sequence[str] = ()) -> None:
    """Copia un fichero o carpeta machacando el destino.

    - Fichero: el destino se reemplaza por completo.
    - Carpeta: se fusiona (se sobrescriben los ficheros existentes y se añaden
      los que faltan; lo que sólo exista en destino se conserva).
    """
    if _is_real_dir(src):
        if (dst.exists() or dst.is_symlink()) and not _is_real_dir(dst):
            _remove(dst)
        dst.mkdir(parents=True, exist_ok=True)
        for child in src.iterdir():
            if _ignored(child.name, ignore):
                continue
            copy_path(child, dst / child.name, ignore)
    else:
        if dst.exists() or dst.is_symlink():
            _remove(dst)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
