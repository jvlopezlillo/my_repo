"""Indexado de fuentes del sistema / del usuario y búsqueda de equivalentes."""
from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional

import pymupdf

SUBSET_RE = re.compile(r"^[A-Z]{6}\+")
FONT_EXTS = {".ttf", ".otf", ".ttc"}

# Carpeta donde la app guarda las fuentes que instala el usuario.
def user_font_dir() -> Path:
    base = os.environ.get("PDFTEXTEDIT_HOME") or str(Path.home() / ".pdftextedit")
    p = Path(base) / "fonts"
    p.mkdir(parents=True, exist_ok=True)
    return p


def system_font_dirs() -> list[Path]:
    dirs: list[Path] = []
    if sys.platform.startswith("win"):
        win = os.environ.get("WINDIR", r"C:\Windows")
        dirs.append(Path(win) / "Fonts")
        local = os.environ.get("LOCALAPPDATA")
        if local:
            dirs.append(Path(local) / "Microsoft" / "Windows" / "Fonts")
    elif sys.platform == "darwin":
        dirs += [Path("/System/Library/Fonts"), Path("/Library/Fonts"),
                 Path.home() / "Library" / "Fonts"]
    else:
        dirs += [Path("/usr/share/fonts"), Path("/usr/local/share/fonts"),
                 Path.home() / ".fonts", Path.home() / ".local/share/fonts"]
    dirs.append(user_font_dir())
    return [d for d in dirs if d.exists()]


def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def strip_subset(name: str) -> str:
    return SUBSET_RE.sub("", name or "")


STYLE_WORDS = [
    "bolditalic", "boldoblique", "semibold", "demibold", "extrabold", "bold",
    "italic", "oblique", "regular", "roman", "normal", "book", "medium",
    "light", "black", "heavy", "thin", "condensed", "narrow",
    "bd", "it", "bi", "psmt", "mt", "ps",
]


def split_family_style(pdf_name: str) -> tuple[str, bool, bool]:
    """'ABCDEF+Arial-BoldMT' -> ('arial', bold=True, italic=False)."""
    n = norm(strip_subset(pdf_name))
    bold = "bold" in n or n.endswith("bd") or "black" in n or "heavy" in n or "demi" in n
    italic = "italic" in n or "oblique" in n or n.endswith("it") and not n.endswith("bit")
    fam = n
    changed = True
    while changed:
        changed = False
        for w in STYLE_WORDS:
            if fam.endswith(w) and len(fam) > len(w) + 1:
                fam = fam[: -len(w)]
                changed = True
    return fam, bold, italic


# Equivalencias típicas (familia original -> familias libres / parecidas).
ALIASES: dict[str, list[str]] = {
    "arial": ["liberationsans", "arimo", "helvetica", "nimbussans", "freesans", "dejavusans"],
    "helvetica": ["arial", "liberationsans", "arimo", "nimbussans", "freesans"],
    "timesnewroman": ["liberationserif", "tinos", "times", "nimbusroman", "freeserif", "dejavuserif"],
    "times": ["timesnewroman", "liberationserif", "tinos", "nimbusroman", "freeserif"],
    "couriernew": ["liberationmono", "cousine", "courier", "nimbusmono", "freemono", "dejavusansmono"],
    "courier": ["couriernew", "liberationmono", "cousine", "freemono"],
    "calibri": ["carlito", "lato", "opensans", "dejavusans"],
    "cambria": ["caladea", "dejavuserif"],
    "verdana": ["dejavusans"],
    "tahoma": ["dejavusans"],
    "segoeui": ["opensans", "dejavusans"],
    "georgia": ["dejavuserif", "liberationserif"],
    "consolas": ["dejavusansmono", "liberationmono"],
    "garamond": ["ebgaramond", "liberationserif"],
}
GENERIC = {
    "sans": ["liberationsans", "arimo", "arial", "dejavusans", "notosans", "inter", "freesans"],
    "serif": ["liberationserif", "tinos", "timesnewroman", "dejavuserif", "notoserif", "freeserif"],
    "mono": ["liberationmono", "cousine", "couriernew", "dejavusansmono", "freemono"],
}


@dataclass
class FontEntry:
    path: str
    index: int
    family: str
    style: str
    full: str
    ps: str
    bold: bool
    italic: bool

    @property
    def nfamily(self) -> str:
        return norm(self.family)


class FontIndex:
    """Índice (con caché en disco) de todas las fuentes instaladas."""

    def __init__(self, extra_dirs: Optional[list[Path]] = None):
        self.entries: list[FontEntry] = []
        self.extra_dirs = extra_dirs or []
        self._loaded = False

    # ---- construcción ----
    def _cache_path(self) -> Path:
        return user_font_dir().parent / "font_index.json"

    def load(self, force: bool = False) -> "FontIndex":
        if self._loaded and not force:
            return self
        cache: dict[str, dict] = {}
        cp = self._cache_path()
        if cp.exists() and not force:
            try:
                cache = json.loads(cp.read_text(encoding="utf-8"))
            except Exception:
                cache = {}
        new_cache: dict[str, dict] = {}
        entries: list[FontEntry] = []
        for d in system_font_dirs() + list(self.extra_dirs):
            for root, _dirs, files in os.walk(d):
                for fn in files:
                    if Path(fn).suffix.lower() not in FONT_EXTS:
                        continue
                    p = os.path.join(root, fn)
                    try:
                        mt = os.path.getmtime(p)
                    except OSError:
                        continue
                    c = cache.get(p)
                    if c and c.get("mtime") == mt:
                        new_cache[p] = c
                    else:
                        c = {"mtime": mt, "faces": self._read_faces(p)}
                        new_cache[p] = c
                    for f in c["faces"]:
                        entries.append(FontEntry(**f))
        try:
            cp.write_text(json.dumps(new_cache), encoding="utf-8")
        except Exception:
            pass
        self.entries = entries
        self._loaded = True
        return self

    @staticmethod
    def _read_faces(path: str) -> list[dict]:
        from fontTools.ttLib import TTFont, TTCollection
        faces = []
        try:
            if path.lower().endswith(".ttc"):
                fonts = list(TTCollection(path, lazy=True).fonts)
            else:
                fonts = [TTFont(path, lazy=True, fontNumber=0)]
            for i, f in enumerate(fonts):
                nm = f["name"]
                def g(*ids):
                    for nid in ids:
                        r = nm.getName(nid, 3, 1, 0x409) or nm.getName(nid, 1, 0, 0) \
                            or nm.getDebugName(nid)
                        if r:
                            return str(r)
                    return ""
                family = g(16, 1)
                style = g(17, 2)
                full = g(4)
                ps = g(6)
                bold = italic = False
                try:
                    os2 = f["OS/2"]
                    bold = os2.usWeightClass >= 600 or bool(os2.fsSelection & 0x20)
                    italic = bool(os2.fsSelection & 0x01)
                except Exception:
                    pass
                try:
                    ms = f["head"].macStyle
                    bold = bold or bool(ms & 1)
                    italic = italic or bool(ms & 2)
                except Exception:
                    pass
                faces.append(dict(path=path, index=i, family=family, style=style,
                                  full=full, ps=ps, bold=bold, italic=italic))
        except Exception:
            pass
        return faces

    # ---- consultas ----
    def find_exact(self, pdf_name: str, bold: bool, italic: bool) -> Optional[FontEntry]:
        """Misma fuente (mismo nombre PostScript/completo o misma familia y estilo)."""
        self.load()
        raw = norm(strip_subset(pdf_name))
        for e in self.entries:
            if raw and raw in (norm(e.ps), norm(e.full)):
                return e
        fam, nb, ni = split_family_style(pdf_name)
        bold = bold or nb
        italic = italic or ni
        cands = [e for e in self.entries if e.nfamily == fam]
        # nombre de familia que contenga el de PDF (p.ej. "Arial Nova" no debe casar con "arial")
        return self._best_style(cands, bold, italic)

    def find_family(self, nfamily_list: list[str], bold: bool, italic: bool) -> Optional[FontEntry]:
        self.load()
        for fam in nfamily_list:
            cands = [e for e in self.entries if e.nfamily == fam]
            r = self._best_style(cands, bold, italic)
            if r:
                return r
        return None

    @staticmethod
    def _best_style(cands: list[FontEntry], bold: bool, italic: bool) -> Optional[FontEntry]:
        if not cands:
            return None
        def score(e: FontEntry):
            s = 0
            s += 2 if e.bold == bold else 0
            s += 2 if e.italic == italic else 0
            # preferir la cara "normal" frente a Light/Condensed etc.
            st = norm(e.style)
            if st in ("", "regular", "book", "roman", "bold", "italic", "bolditalic", "normal"):
                s += 1
            return s
        best = max(cands, key=score)
        if best.bold != bold or best.italic != italic:
            return None  # no hay ese estilo exacto
        return best


_font_cache: dict[tuple, pymupdf.Font] = {}


def load_font(path: Optional[str] = None, index: int = 0, builtin: Optional[str] = None,
              buffer: Optional[bytes] = None) -> Optional[pymupdf.Font]:
    key = (path, index, builtin, id(buffer) if buffer is not None else None)
    if key in _font_cache:
        return _font_cache[key]
    try:
        if builtin:
            f = pymupdf.Font(builtin)
        elif buffer is not None:
            f = pymupdf.Font(fontbuffer=buffer)
        else:
            f = pymupdf.Font(fontfile=path)
    except Exception:
        return None
    _font_cache[key] = f
    return f


def builtin_for(bold: bool, italic: bool, serif: bool, mono: bool) -> str:
    """Fuentes base-14 de MuPDF (siempre disponibles, cobertura Latin-1)."""
    if mono:
        return {(0, 0): "cour", (1, 0): "cobo", (0, 1): "coit", (1, 1): "cobi"}[(int(bold), int(italic))]
    if serif:
        return {(0, 0): "tiro", (1, 0): "tibo", (0, 1): "tiit", (1, 1): "tibi"}[(int(bold), int(italic))]
    return {(0, 0): "helv", (1, 0): "hebo", (0, 1): "heit", (1, 1): "hebi"}[(int(bold), int(italic))]
