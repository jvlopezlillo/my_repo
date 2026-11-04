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
NORMALIZE_LIMIT = 50 * 1024 * 1024  # por encima, se compara byte a byte


class Cancelled(Exception):
    pass


class Progress:
    """Progreso y cancelación compartidos entre el hilo de trabajo y la GUI."""

    def __init__(self, on_update=None):
        self.done = 0
        self.total = 0
        self.on_update = on_update
        self._cancelled = False

    def cancel(self) -> None:
        self._cancelled = True

    def check(self) -> None:
        if self._cancelled:
            raise Cancelled()

    def set_total(self, n: int) -> None:
        self.total = n
        if self.on_update:
            self.on_update(self.done, self.total)

    def advance(self, n: int = 1) -> None:
        self.done += n
        if self.on_update:
            self.on_update(self.done, self.total)


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


def normalize_bytes(data: bytes, ignore_ws: bool = False, ignore_eol: bool = False) -> bytes:
    """Normaliza el contenido para compararlo ignorando espacios y/o fin de línea."""
    if ignore_ws or ignore_eol:
        data = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    if ignore_ws:
        data = b"\n".join(b" ".join(line.split()) for line in data.split(b"\n"))
    return data


def files_equal(a: Path, b: Path, ignore_ws: bool = False, ignore_eol: bool = False) -> bool:
    """Compara el contenido (sin caché). Opcionalmente ignora espacios y/o fin de línea."""
    try:
        sa, sb = a.stat().st_size, b.stat().st_size
        if (ignore_ws or ignore_eol) and max(sa, sb) <= NORMALIZE_LIMIT:
            da, db = a.read_bytes(), b.read_bytes()
            if b"\0" in da[:8192] or b"\0" in db[:8192]:  # binario: exacto
                return da == db
            return normalize_bytes(da, ignore_ws, ignore_eol) == normalize_bytes(db, ignore_ws, ignore_eol)
        if sa != sb:
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


@dataclass
class _Ctx:
    ignore: Sequence[str]
    ws: bool = False
    eol: bool = False
    progress: Optional[Progress] = None

    def tick(self, n: int = 1) -> None:
        if self.progress:
            self.progress.advance(n)

    def check(self) -> None:
        if self.progress:
            self.progress.check()


def count_files(path: Optional[Path], ignore: Sequence[str] = (), progress: Optional[Progress] = None) -> int:
    """Número de ficheros (no carpetas) bajo `path`; 1 si es un fichero."""
    if path is None:
        return 0
    if not _is_real_dir(path):
        return 1
    n = 0
    for p, is_dir in _scan(path, ignore).values():
        if progress:
            progress.check()
        n += count_files(p, ignore, progress) if is_dir else 1
    return n


def _make_child(
    n: str,
    crel: str,
    l: Optional[tuple[Path, bool]],
    r: Optional[tuple[Path, bool]],
    ctx: _Ctx,
) -> DiffNode:
    """Nodo de una entrada que existe en un lado (l / r) o en ambos."""
    if l and r:
        (lp, ldir), (rp, rdir) = l, r
        if ldir and rdir:
            return _build_dir(lp, rp, crel, n, ctx)
        if not ldir and not rdir:
            ctx.check()
            st = Status.IDENTICAL if files_equal(lp, rp, ctx.ws, ctx.eol) else Status.DIFFERENT
            ctx.tick(2)
            return DiffNode(n, crel, lp, rp, "file", "file", st)
        ctx.tick(count_files(lp, ctx.ignore) + count_files(rp, ctx.ignore))
        return DiffNode(n, crel, lp, rp, "dir" if ldir else "file",
                        "dir" if rdir else "file", Status.TYPE_MISMATCH)
    if l:
        lp, ldir = l
        if ldir:
            return _build_dir(lp, None, crel, n, ctx)
        ctx.tick()
        return DiffNode(n, crel, lp, None, "file", None, Status.LEFT_ONLY)
    rp, rdir = r
    if rdir:
        return _build_dir(None, rp, crel, n, ctx)
    ctx.tick()
    return DiffNode(n, crel, None, rp, None, "file", Status.RIGHT_ONLY)


def _build_dir(
    left: Optional[Path], right: Optional[Path], rel: str, name: str, ctx: _Ctx
) -> DiffNode:
    ignore = ctx.ignore
    ctx.check()
    lentries, rentries = _scan(left, ignore), _scan(right, ignore)
    names = set(lentries) | set(rentries)

    def sort_key(n: str):
        is_dir = (lentries.get(n) or rentries.get(n))[1]
        return (not is_dir, n.lower(), n)

    children: list[DiffNode] = []
    for n in sorted(names, key=sort_key):
        crel = f"{rel}/{n}" if rel else n
        children.append(_make_child(n, crel, lentries.get(n), rentries.get(n), ctx))

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


def compare_dirs(
    left: Path,
    right: Path,
    ignore: Sequence[str] = (),
    ignore_ws: bool = False,
    ignore_eol: bool = False,
    progress: Optional[Progress] = None,
) -> DiffNode:
    """Compara recursivamente dos carpetas. Devuelve el nodo raíz.

    Con `progress`, primero cuenta los ficheros (para el total) y luego avanza.
    Puede lanzar `Cancelled`.
    """
    left, right = Path(left), Path(right)
    if progress:
        progress.set_total(count_files(left, ignore, progress) + count_files(right, ignore, progress))
    return _build_dir(left, right, "", "", _Ctx(ignore, ignore_ws, ignore_eol, progress))


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


def copy_path(
    src: Path, dst: Path, ignore: Sequence[str] = (), progress: Optional[Progress] = None
) -> None:
    """Copia un fichero o carpeta machacando el destino por completo.

    - Fichero: el destino se reemplaza.
    - Carpeta: el destino queda idéntico al origen (se sobrescribe lo distinto, se añade lo
      que falta y se ELIMINA lo que sólo exista en destino). Lo que coincida con los patrones
      de `ignore` no se copia ni se borra.
    """
    if progress:
        progress.check()
    if _is_real_dir(src):
        if (dst.exists() or dst.is_symlink()) and not _is_real_dir(dst):
            _remove(dst)
        dst.mkdir(parents=True, exist_ok=True)
        names = set()
        for child in src.iterdir():
            if _ignored(child.name, ignore):
                continue
            names.add(child.name)
            copy_path(child, dst / child.name, ignore, progress)
        for extra in dst.iterdir():
            if extra.name not in names and not _ignored(extra.name, ignore):
                _remove(extra)
    else:
        if dst.exists() or dst.is_symlink():
            _remove(dst)
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        if progress:
            progress.advance()


def delete_path(path: Path, progress: Optional[Progress] = None) -> None:
    """Elimina definitivamente un fichero o una carpeta (con todo su contenido)."""
    if progress:
        progress.check()
    if _is_real_dir(path):
        for child in path.iterdir():
            delete_path(child, progress)
        path.rmdir()
    else:
        path.unlink()
        if progress:
            progress.advance()


def _child_key(node: DiffNode):
    return ((node.left_kind or node.right_kind) != "dir", node.name.lower(), node.name)


def _entry(root: Path, rel: str) -> Optional[tuple[Path, bool]]:
    p = path_under(root, rel)
    if not (p.exists() or p.is_symlink()):
        return None
    return p, _is_real_dir(p)


def refresh_path(
    root: DiffNode,
    rel: str,
    left_root: Path,
    right_root: Path,
    ignore: Sequence[str] = (),
    ignore_ws: bool = False,
    ignore_eol: bool = False,
    progress: Optional[Progress] = None,
) -> None:
    """Vuelve a comparar sólo `rel` (y sus carpetas contenedoras) y actualiza el árbol en su sitio.

    Es mucho más rápido que comparar todo de nuevo tras copiar o borrar un elemento.
    Lanza KeyError si `rel` no cuelga del árbol (el llamante debe comparar todo).
    """
    left_root, right_root = Path(left_root), Path(right_root)
    parts = rel.split("/")
    chain = [root]
    for part in parts[:-1]:
        nxt = next((c for c in chain[-1].children if c.name == part), None)
        if nxt is None:
            raise KeyError(rel)
        chain.append(nxt)
    parent, name = chain[-1], parts[-1]
    l, r = _entry(left_root, rel), _entry(right_root, rel)
    if progress:
        progress.done = 0
        progress.set_total(max(1, count_files(l and l[0], ignore, progress)
                               + count_files(r and r[0], ignore, progress)))
    parent.children = [c for c in parent.children if c.name != name]
    if l or r:
        parent.children.append(_make_child(name, rel, l, r, _Ctx(ignore, ignore_ws, ignore_eol, progress)))
        parent.children.sort(key=_child_key)
    for anc in reversed(chain):  # de dentro hacia fuera
        le, re_ = _entry(left_root, anc.rel), _entry(right_root, anc.rel)
        anc.left, anc.right = (le[0] if le else None), (re_[0] if re_ else None)
        anc.left_kind, anc.right_kind = ("dir" if le else None), ("dir" if re_ else None)
        if not re_:
            anc.status = Status.LEFT_ONLY
        elif not le:
            anc.status = Status.RIGHT_ONLY
        else:
            anc.status = (Status.IDENTICAL if all(c.status is Status.IDENTICAL for c in anc.children)
                          else Status.DIFFERENT)
