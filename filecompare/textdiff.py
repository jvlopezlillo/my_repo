"""Documentos de texto y diff por bloques (sin dependencias de GUI)."""

from __future__ import annotations

import difflib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


class BinaryFileError(Exception):
    pass


@dataclass
class TextDocument:
    path: Path
    lines: list[str] = field(default_factory=list)
    encoding: str = "utf-8"
    eol: str = "\n"
    trailing_newline: bool = True
    exists: bool = True

    @classmethod
    def load(cls, path: Path) -> "TextDocument":
        path = Path(path)
        if not path.exists():
            return cls(path=path, exists=False)
        data = path.read_bytes()
        if data.startswith(b"\xef\xbb\xbf"):
            encoding, text = "utf-8-sig", data.decode("utf-8-sig")
        elif data.startswith((b"\xff\xfe", b"\xfe\xff")):
            encoding, text = "utf-16", data.decode("utf-16")
        else:
            if b"\0" in data[:8192]:
                raise BinaryFileError(str(path))
            try:
                encoding, text = "utf-8", data.decode("utf-8")
            except UnicodeDecodeError:
                encoding, text = "latin-1", data.decode("latin-1")
        eol = "\r\n" if "\r\n" in text else ("\r" if "\r" in text else "\n")
        if text == "":
            return cls(path, [], encoding, eol, False, True)
        parts = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        trailing = parts[-1] == ""
        if trailing:
            parts.pop()
        return cls(path, parts, encoding, eol, trailing, True)

    def to_text(self) -> str:
        if not self.lines:
            return ""
        return self.eol.join(self.lines) + (self.eol if self.trailing_newline else "")

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_bytes(self.to_text().encode(self.encoding))
        self.exists = True


@dataclass(frozen=True)
class Block:
    index: int
    tag: str  # "replace" | "delete" (sólo izquierda) | "insert" (sólo derecha)
    i1: int
    i2: int
    j1: int
    j2: int
    row_start: int  # primera fila en la vista alineada
    row_count: int


@dataclass(frozen=True)
class Row:
    left: Optional[str]  # None = fila de relleno
    right: Optional[str]
    block: Optional[int]  # índice de bloque, None si es igual


def compute_diff(a: list[str], b: list[str]) -> tuple[list[Row], list[Block]]:
    """Devuelve filas alineadas lado a lado y la lista de bloques distintos."""
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    rows: list[Row] = []
    blocks: list[Block] = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            rows.extend(Row(a[i], b[j], None) for i, j in zip(range(i1, i2), range(j1, j2)))
            continue
        n = max(i2 - i1, j2 - j1)
        idx = len(blocks)
        blocks.append(Block(idx, tag, i1, i2, j1, j2, len(rows), n))
        for k in range(n):
            rows.append(
                Row(a[i1 + k] if i1 + k < i2 else None,
                    b[j1 + k] if j1 + k < j2 else None, idx)
            )
    return rows, blocks


def apply_block(a: list[str], b: list[str], block: Block, direction: str) -> None:
    """Copia un bloque. direction: "ltr" (izq -> der) o "rtl" (der -> izq)."""
    if direction == "ltr":
        b[block.j1:block.j2] = a[block.i1:block.i2]
    elif direction == "rtl":
        a[block.i1:block.i2] = b[block.j1:block.j2]
    else:
        raise ValueError(direction)
