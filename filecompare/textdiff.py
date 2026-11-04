"""Documentos de texto y diff por bloques (sin dependencias de GUI)."""

from __future__ import annotations

import difflib
import re
from collections import Counter
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
            return cls(path, [], encoding, eol, True, True)
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


def normalize_line(s: str) -> str:
    return " ".join(s.split())


# --- alineación -------------------------------------------------------------------------------
SIM_THRESHOLD = 0.5  # parecido mínimo para emparejar dos líneas distintas
BLANK_SCORE = 0.02  # emparejar dos líneas en blanco apenas puntúa: nunca debe pesar más que una línea real
MAX_GAP_CELLS = 160_000  # por encima, se empareja por posición (la alineación por parecido es O(n·m))
_WORDS = re.compile(r"\w+|[^\w\s]")


def _line_tokens(key: str) -> Counter:
    return Counter(_WORDS.findall(key))


def _similarity(ka: str, kb: str, ta: Counter, tb: Counter) -> float:
    """0 = no emparejables; 1 = iguales. Las líneas en blanco no se parecen a nada salvo a otra en blanco."""
    a_blank, b_blank = not ka.strip(), not kb.strip()
    if a_blank or b_blank:
        return BLANK_SCORE if a_blank and b_blank else 0.0
    if ka == kb or normalize_line(ka) == normalize_line(kb):
        return 1.0  # iguales salvo espacios: se emparejan (incluidas líneas como "}" o "]")
    if not (ta.keys() & tb.keys()):
        return 0.0
    score = 2 * sum((ta & tb).values()) / (sum(ta.values()) + sum(tb.values()))
    # las líneas sólo de símbolos ("}," frente a "]") no tienen palabras: sólo se emparejan si son iguales
    if not any(w[0].isalnum() or w[0] == "_" for w in ta) or not any(w[0].isalnum() or w[0] == "_" for w in tb):
        return 0.0
    return score if score >= SIM_THRESHOLD else 0.0


# fila "cruda": (texto izquierdo | None, texto derecho | None, ¿igual?)
_Raw = tuple


def _align_gap(a, b, ka, kb, i1, i2, j1, j2, out: list) -> None:
    """Alinea a[i1:i2] con b[j1:j2] (zona sin líneas iguales) emparejando las líneas más parecidas."""
    n, m = i2 - i1, j2 - j1
    if n == 0 or m == 0:
        out.extend((a[i], None, False) for i in range(i1, i2))
        out.extend((None, b[j], False) for j in range(j1, j2))
        return
    if n * m > MAX_GAP_CELLS:  # zona enorme: por posición
        for k in range(max(n, m)):
            lt = a[i1 + k] if k < n else None
            rt = b[j1 + k] if k < m else None
            out.append((lt, rt, lt is not None and rt is not None and ka[i1 + k] == kb[j1 + k]))
        return
    ta = [_line_tokens(ka[i]) for i in range(i1, i2)]
    tb = [_line_tokens(kb[j]) for j in range(j1, j2)]
    sim = [[_similarity(ka[i1 + i], kb[j1 + j], ta[i], tb[j]) for j in range(m)] for i in range(n)]
    best = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        row, prev, srow = best[i], best[i - 1], sim[i - 1]
        for j in range(1, m + 1):
            v = max(prev[j], row[j - 1])
            s = srow[j - 1]
            if s > 0 and prev[j - 1] + s > v:
                v = prev[j - 1] + s
            row[j] = v
    pairs = []
    i, j = n, m
    while i > 0 and j > 0:
        s = sim[i - 1][j - 1]
        if s > 0 and abs(best[i][j] - (best[i - 1][j - 1] + s)) < 1e-9:
            pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif abs(best[i][j] - best[i - 1][j]) < 1e-9:
            i -= 1
        else:
            j -= 1
    pairs.reverse()
    pi = pj = 0
    for pa, pb in pairs + [(n, m)]:
        out.extend((a[i1 + k], None, False) for k in range(pi, pa))  # primero lo sólo-izquierda...
        out.extend((None, b[j1 + k], False) for k in range(pj, pb))  # ...y luego lo sólo-derecha
        if pa < n:
            out.append((a[i1 + pa], b[j1 + pb], ka[i1 + pa] == kb[j1 + pb]))
        pi, pj = pa + 1, pb + 1


def compute_diff(
    a: list[str], b: list[str], ignore_ws: bool = False
) -> tuple[list[Row], list[Block]]:
    """Devuelve filas alineadas lado a lado y la lista de bloques distintos.

    1. Se buscan las líneas idénticas (anclas) ignorando las líneas en blanco, para que éstas no
       condicionen el alineamiento.
    2. Entre anclas, las líneas se emparejan por parecido (conservando el orden); lo que no se
       parece a nada queda como sólo-izquierda o sólo-derecha.
    Con `ignore_ws`, las líneas que sólo difieren en espacios en blanco se consideran iguales.
    """
    ka = [normalize_line(x) for x in a] if ignore_ws else list(a)
    kb = [normalize_line(x) for x in b] if ignore_ws else list(b)
    nb_a = [i for i, k in enumerate(ka) if k.strip()]
    nb_b = [j for j, k in enumerate(kb) if k.strip()]
    sm = difflib.SequenceMatcher(None, [ka[i] for i in nb_a], [kb[j] for j in nb_b], autojunk=False)
    anchors: list[tuple[int, int]] = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            anchors.extend((nb_a[i1 + k], nb_b[j1 + k]) for k in range(i2 - i1))

    raw: list[_Raw] = []
    pi = pj = 0
    for ai, bj in anchors + [(len(a), len(b))]:
        _align_gap(a, b, ka, kb, pi, ai, pj, bj, raw)
        if ai < len(a):
            raw.append((a[ai], b[bj], True))
        pi, pj = ai + 1, bj + 1

    rows: list[Row] = []
    blocks: list[Block] = []
    i = j = 0
    cur: Optional[list[int]] = None  # [fila inicial, i1, j1, nº de filas]

    def close():
        nonlocal cur
        if cur is not None:
            start, i1, j1, count = cur
            tag = "replace" if i > i1 and j > j1 else ("delete" if i > i1 else "insert")
            blocks.append(Block(len(blocks), tag, i1, i, j1, j, start, count))
            cur = None

    for lt, rt, equal in raw:
        if equal:
            close()
            rows.append(Row(lt, rt, None))
            i += 1
            j += 1
            continue
        if cur is None:
            cur = [len(rows), i, j, 0]
        rows.append(Row(lt, rt, len(blocks)))
        cur[3] += 1
        i += lt is not None
        j += rt is not None
    close()
    return rows, blocks


def apply_block(a: list[str], b: list[str], block: Block, direction: str) -> None:
    """Copia un bloque. direction: "ltr" (izq -> der) o "rtl" (der -> izq)."""
    if direction == "ltr":
        b[block.j1:block.j2] = a[block.i1:block.i2]
    elif direction == "rtl":
        a[block.i1:block.i2] = b[block.j1:block.j2]
    else:
        raise ValueError(direction)


_TOKEN = re.compile(r"\w+|\s+|[^\w\s]")
MAX_INLINE = 4000


def inline_spans(a: str, b: str) -> tuple[list[tuple[int, int]], list[tuple[int, int]]]:
    """Rangos (inicio, fin) de caracteres distintos dentro de dos líneas, por palabras."""
    if a == b or len(a) + len(b) > MAX_INLINE:
        return [], []
    ta, tb = _TOKEN.findall(a), _TOKEN.findall(b)

    def offsets(tokens):
        pos, out = 0, []
        for t in tokens:
            out.append(pos)
            pos += len(t)
        out.append(pos)
        return out

    oa, ob = offsets(ta), offsets(tb)
    sa: list[tuple[int, int]] = []
    sb: list[tuple[int, int]] = []
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, ta, tb, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        if i2 > i1:
            sa.append((oa[i1], oa[i2]))
        if j2 > j1:
            sb.append((ob[j1], ob[j2]))
    return sa, sb
