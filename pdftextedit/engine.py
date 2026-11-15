"""Motor de edición: localiza texto, identifica su fuente y lo reemplaza."""
from __future__ import annotations

import io
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import pymupdf

from .fonts import (FontEntry, FontIndex, builtin_for, GENERIC, ALIASES, load_font,
                    split_family_style, strip_subset, user_font_dir, norm)

FLAG_ITALIC, FLAG_SERIF, FLAG_MONO, FLAG_BOLD = 2, 4, 8, 16


class MissingGlyphsError(Exception):
    def __init__(self, check: "EditCheck"):
        super().__init__("Faltan caracteres en la fuente original")
        self.check = check


@dataclass
class Span:
    page: int
    text: str
    bbox: tuple
    origin: tuple
    font: str            # nombre de la fuente en el PDF (sin prefijo de subconjunto)
    size: float
    flags: int
    color: tuple         # (r, g, b) 0..1
    horizontal: bool = True

    @property
    def bold(self) -> bool:
        return bool(self.flags & FLAG_BOLD) or split_family_style(self.font)[1]

    @property
    def italic(self) -> bool:
        return bool(self.flags & FLAG_ITALIC) or split_family_style(self.font)[2]

    @property
    def serif(self) -> bool:
        return bool(self.flags & FLAG_SERIF)

    @property
    def mono(self) -> bool:
        return bool(self.flags & FLAG_MONO)

    @property
    def style_label(self) -> str:
        s = [x for x, on in (("Negrita", self.bold), ("Cursiva", self.italic)) if on]
        return ", ".join(s) or "Normal"


@dataclass
class FontChoice:
    font: pymupdf.Font
    name: str
    kind: str          # embedded | installed | user | equivalent | builtin
    path: Optional[str] = None

    @property
    def exact(self) -> bool:
        return self.kind in ("embedded", "installed", "user")


@dataclass
class EditCheck:
    span: Span
    new_text: str
    primary: Optional[FontChoice]       # mejor fuente "misma que el original" encontrada
    missing: list[str] = field(default_factory=list)   # caracteres que primary no tiene
    notes: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.primary is not None and not self.missing

    @property
    def font_found(self) -> bool:
        return self.primary is not None

    @property
    def search_query(self) -> str:
        return f"{self.span.font} font download"


def _visible(text: str) -> set[str]:
    return {c for c in text if not c.isspace()}


def _covers(font: pymupdf.Font, chars: set[str]) -> set[str]:
    """Devuelve el subconjunto de `chars` que la fuente NO tiene."""
    return {c for c in chars if not font.has_glyph(ord(c))}


_BFCHAR = re.compile(rb"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>")
_BFRANGE = re.compile(rb"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f]+>|\[[^\]]*\])")


def parse_tounicode(data: bytes) -> dict[int, str]:
    """Interpreta un CMap ToUnicode: código de carácter -> texto Unicode."""
    out: dict[int, str] = {}

    def u(hexs: bytes) -> str:
        return bytes.fromhex(hexs.decode()).decode("utf-16-be", "ignore")
    for blk in re.findall(rb"beginbfchar(.*?)endbfchar", data, re.S):
        for a, b in _BFCHAR.findall(blk):
            out[int(a, 16)] = u(b)
    for blk in re.findall(rb"beginbfrange(.*?)endbfrange", data, re.S):
        for a, b, c in _BFRANGE.findall(blk):
            lo, hi = int(a, 16), int(b, 16)
            if c.startswith(b"["):
                for i, h in enumerate(re.findall(rb"<([0-9A-Fa-f]+)>", c)):
                    out[lo + i] = u(h)
            else:
                base = u(c.strip(b"<>"))
                for i in range(hi - lo + 1):
                    out[lo + i] = base[:-1] + chr(ord(base[-1]) + i) if base else ""
    return out


def font_with_unicode_cmap(doc: "pymupdf.Document", xref: int) -> Optional[bytes]:
    """Reconstruye un cmap Unicode->glifo para una fuente incrustada (subconjunto)
    usando el ToUnicode del PDF, para poder escribir con la misma fuente."""
    from fontTools.ttLib import TTFont, newTable
    from fontTools.ttLib.tables._c_m_a_p import CmapSubtable
    try:
        _n, _e, _t, buf = doc.extract_font(xref)
        if not buf:
            return None
        tt = TTFont(io.BytesIO(bytes(buf)))
    except Exception:
        return None
    if "cmap" not in tt and "glyf" not in tt and "CFF " not in tt:
        return None
    kind, val = doc.xref_get_key(xref, "ToUnicode")
    if kind != "xref":
        return None
    try:
        tu = parse_tounicode(doc.xref_stream(int(val.split()[0])))
    except Exception:
        return None
    if not tu:
        return None
    order = tt.getGlyphOrder()
    subtype = doc.xref_get_key(xref, "Subtype")[1]
    code2gid: dict[int, int] = {}
    if subtype == "/Type0":
        # Identity-H: código == CID == GID (caso habitual con CIDToGIDMap /Identity)
        for code in tu:
            code2gid[code] = code
    else:
        tables = tt["cmap"].tables if "cmap" in tt else []
        gmap = {}
        for t in tables:
            gmap[(t.platformID, t.platEncID)] = t.cmap
        for code in tu:
            name = None
            for key, off in (((3, 0), 0xF000), ((3, 0), 0), ((1, 0), 0), ((3, 1), 0)):
                m = gmap.get(key)
                if m and (code + off) in m:
                    name = m[code + off]
                    break
            if name is not None and name in order:
                code2gid[code] = order.index(name)
    cm: dict[int, str] = {}
    for code, txt in tu.items():
        gid = code2gid.get(code)
        if gid is None or gid >= len(order) or len(txt) != 1:
            continue
        if gid == 0:
            continue
        cm.setdefault(ord(txt), order[gid])
    if not cm:
        return None
    new = newTable("cmap")
    new.tableVersion = 0
    st = CmapSubtable.newSubtable(4)
    st.platformID, st.platEncID, st.language = 3, 1, 0
    st.cmap = {k: v for k, v in cm.items() if k <= 0xFFFF}
    new.tables = [st]
    tt["cmap"] = new
    out = io.BytesIO()
    try:
        tt.save(out)
    except Exception:
        return None
    return out.getvalue()


class PdfEditor:
    MAX_UNDO = 30

    def __init__(self, extra_font_dirs: Optional[list[Path]] = None):
        self.doc: Optional[pymupdf.Document] = None
        self.path: Optional[str] = None
        self.index = FontIndex(extra_font_dirs)
        self._undo: list[bytes] = []
        self._redo: list[bytes] = []
        self.dirty = False
        self._emb_cache: dict[int, Optional[pymupdf.Font]] = {}

    # ------------------------------------------------------------ documento
    def open(self, path: str):
        self.doc = pymupdf.open(path)
        self.path = path
        self._reset_state()

    def open_bytes(self, data: bytes):
        self.doc = pymupdf.open(stream=data, filetype="pdf")
        self.path = None
        self._reset_state()

    def _reset_state(self):
        self._undo.clear(); self._redo.clear(); self._emb_cache.clear()
        self.dirty = False

    @property
    def page_count(self) -> int:
        return self.doc.page_count if self.doc else 0

    def render(self, page_no: int, zoom: float = 1.5) -> pymupdf.Pixmap:
        page = self.doc[page_no]
        return page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False)

    def save(self, path: Optional[str] = None):
        path = path or self.path
        if not path:
            raise ValueError("No hay ruta de destino")
        same = self.path and Path(path).resolve() == Path(self.path).resolve()
        if same:
            data = self.doc.tobytes(garbage=3, deflate=True)
            self.doc.close()
            Path(path).write_bytes(data)
            self.doc = pymupdf.open(path)
        else:
            self.doc.save(path, garbage=3, deflate=True)
            self.path = path
        self.dirty = False

    # ------------------------------------------------------------ lectura
    def spans(self, page_no: int) -> list[Span]:
        page = self.doc[page_no]
        d = page.get_text("dict", flags=pymupdf.TEXT_PRESERVE_WHITESPACE | pymupdf.TEXT_MEDIABOX_CLIP)
        out: list[Span] = []
        for b in d["blocks"]:
            if b.get("type") != 0:
                continue
            for ln in b["lines"]:
                dx, dy = ln["dir"]
                for s in ln["spans"]:
                    if not s["text"].strip():
                        continue
                    c = s["color"]
                    col = (((c >> 16) & 255) / 255, ((c >> 8) & 255) / 255, (c & 255) / 255)
                    out.append(Span(page_no, s["text"], tuple(s["bbox"]), tuple(s["origin"]),
                                    strip_subset(s["font"]), s["size"], s["flags"], col,
                                    horizontal=abs(dy) < 1e-3 and dx > 0))
        return out

    def span_at(self, page_no: int, x: float, y: float) -> Optional[Span]:
        best = None
        for s in self.spans(page_no):
            r = pymupdf.Rect(s.bbox)
            if r.contains(pymupdf.Point(x, y)):
                if best is None or r.get_area() < pymupdf.Rect(best.bbox).get_area():
                    best = s
        return best

    # ------------------------------------------------------------ fuentes
    def _embedded_font(self, span: Span) -> Optional[FontChoice]:
        page = self.doc[span.page]
        for xref, ext, _t, basefont, _n, _enc in page.get_fonts(full=False):
            if strip_subset(basefont) != span.font:
                continue
            if xref not in self._emb_cache:
                f = None
                try:
                    for buf in (font_with_unicode_cmap(self.doc, xref),
                                self.doc.extract_font(xref)[3]):
                        if not buf:
                            continue
                        f = load_font(buffer=bytes(buf))
                        # válida solo si realmente resuelve los glifos del texto original
                        if f and not _covers(f, _visible(span.text)):
                            break
                        f = None
                except Exception:
                    f = None
                self._emb_cache[xref] = f
            f = self._emb_cache[xref]
            if f is not None:
                return FontChoice(f, span.font, "embedded")
        return None

    def _installed_font(self, span: Span) -> Optional[FontChoice]:
        e = self.index.find_exact(span.font, span.bold, span.italic)
        if not e:
            return None
        f = load_font(path=e.path)
        return FontChoice(f, e.full or e.family, "installed", e.path) if f else None

    _STD = {"helvetica": (False, False), "arial": (False, False), "times": (True, False),
            "timesnewroman": (True, False), "courier": (False, True), "couriernew": (False, True)}

    def _standard_font(self, span: Span) -> Optional[FontChoice]:
        """Fuentes estándar no incrustadas (Helvetica/Times/Courier y sus alias):
        el visor usa la métrica equivalente integrada, igual que hacemos aquí."""
        fam, _b, _i = split_family_style(span.font)
        if fam not in self._STD:
            return None
        serif, mono = self._STD[fam]
        f = load_font(builtin=builtin_for(span.bold, span.italic, serif, mono))
        return FontChoice(f, span.font + " (estándar)", "builtin") if f else None

    def _user_font(self, path: str) -> Optional[FontChoice]:
        f = load_font(path=path)
        return FontChoice(f, Path(path).stem, "user", path) if f else None

    def install_font_file(self, src: str) -> str:
        """Copia una fuente a la carpeta de fuentes de la app y reindexa."""
        dst = user_font_dir() / Path(src).name
        if Path(src).resolve() != dst.resolve():
            shutil.copy2(src, dst)
        self.index.load(force=True)
        return str(dst)

    def equivalent_font(self, span: Span, chars: Optional[set[str]] = None) -> FontChoice:
        """Fuente parecida (mismo estilo) que cubra `chars` (o la que más cubra)."""
        chars = chars or set()
        fam, _b, _i = split_family_style(span.font)
        fam_list = list(ALIASES.get(fam, []))
        kind = "mono" if span.mono else "serif" if span.serif else "sans"
        fam_list += GENERIC[kind]
        cands: list[FontChoice] = []
        e = self.index.find_family(fam_list, span.bold, span.italic)
        if e:
            f = load_font(path=e.path)
            if f:
                cands.append(FontChoice(f, e.full or e.family, "equivalent", e.path))
        bf = load_font(builtin=builtin_for(span.bold, span.italic, span.serif, span.mono))
        if bf:
            cands.append(FontChoice(bf, "Base-14", "builtin"))
        # Universal (cobertura amplia) por si falta algún carácter raro
        for fb in ("DejaVuSans", "NotoSans", "Arial Unicode"):
            e = self.index.find_family([norm(fb)], span.bold, span.italic)
            if e:
                f = load_font(path=e.path)
                if f:
                    cands.append(FontChoice(f, e.full or e.family, "equivalent", e.path))
        for c in cands:
            if not _covers(c.font, chars):
                return c
        return min(cands, key=lambda c: len(_covers(c.font, chars)))

    # ------------------------------------------------------------ comprobar
    def check_edit(self, span: Span, new_text: str, user_font: Optional[str] = None) -> EditCheck:
        need = _visible(new_text)
        chk = EditCheck(span, new_text, None)
        if not span.horizontal:
            chk.notes.append("Texto no horizontal: la edición puede no ser exacta.")
        cands: list[FontChoice] = []
        if user_font:
            u = self._user_font(user_font)
            if u:
                cands.append(u)
        emb = self._embedded_font(span)
        if emb:
            cands.append(emb)
        inst = self._installed_font(span)
        if inst:
            cands.append(inst)
        std = self._standard_font(span)
        if std:
            cands.append(std)
        if not cands:
            chk.missing = sorted(need)
            chk.notes.append(f"La fuente '{span.font}' no está incrustada ni instalada.")
            return chk
        best, best_missing = None, None
        for c in cands:
            miss = _covers(c.font, need)
            if not miss:
                chk.primary = c
                return chk
            if best is None or len(miss) < len(best_missing):
                best, best_missing = c, miss
        chk.primary = best
        chk.missing = sorted(best_missing)
        return chk

    # ------------------------------------------------------------ aplicar
    def _snapshot(self):
        self._undo.append(self.doc.tobytes())
        if len(self._undo) > self.MAX_UNDO:
            self._undo.pop(0)
        self._redo.clear()

    def undo(self) -> bool:
        if not self._undo:
            return False
        self._redo.append(self.doc.tobytes())
        self._restore(self._undo.pop())
        return True

    def redo(self) -> bool:
        if not self._redo:
            return False
        self._undo.append(self.doc.tobytes())
        self._restore(self._redo.pop())
        return True

    def _restore(self, data: bytes):
        self.doc.close()
        self.doc = pymupdf.open(stream=data, filetype="pdf")
        self._emb_cache.clear()
        self.dirty = True

    def apply_edit(self, chk: EditCheck, allow_equivalent: bool = False):
        """Aplica la edición. Si faltan glifos y no se permite equivalente -> error."""
        if not chk.ok and not allow_equivalent:
            raise MissingGlyphsError(chk)
        span, text = chk.span, chk.new_text
        page = self.doc[span.page]

        # ¿qué fuente para cada carácter?
        def font_for(ch: str) -> FontChoice:
            if chk.primary and (ch.isspace() or chk.primary.font.has_glyph(ord(ch))):
                return chk.primary
            return self.equivalent_font(span, {ch})
        runs: list[tuple[str, FontChoice]] = []
        for ch in text:
            fc = font_for(ch)
            if runs and runs[-1][1] is fc:
                runs[-1] = (runs[-1][0] + ch, fc)
            else:
                runs.append((ch, fc))

        self._snapshot()
        # 1) borrar el texto antiguo (sin tocar imágenes ni gráficos)
        r = pymupdf.Rect(span.bbox)
        h = r.height
        rr = pymupdf.Rect(r.x0 + 0.3, r.y0 + h * 0.2, r.x1 - 0.3, r.y1 - h * 0.2)
        page.add_redact_annot(rr, fill=False)
        page.apply_redactions(images=pymupdf.PDF_REDACT_IMAGE_NONE,
                              graphics=pymupdf.PDF_REDACT_LINE_ART_NONE)
        # 2) escribir el nuevo en el mismo origen, con la misma fuente/tamaño/color
        tw = pymupdf.TextWriter(page.rect)
        pos = pymupdf.Point(*span.origin)
        for run, fc in runs:
            pos = self._append(tw, pos, run, fc.font, span.size)
        tw.write_text(page, color=span.color)
        self.dirty = True

    @staticmethod
    def _append(tw: pymupdf.TextWriter, pos: pymupdf.Point, text: str, font: pymupdf.Font,
                size: float) -> pymupdf.Point:
        # los espacios sin glifo se saltan avanzando a mano
        buf = ""
        for ch in text:
            if ch.isspace() and not font.has_glyph(ord(ch)):
                if buf:
                    _, pos = PdfEditor._tw_append(tw, pos, buf, font, size)
                    buf = ""
                pos = pymupdf.Point(pos.x + size * 0.28, pos.y)
            else:
                buf += ch
        if buf:
            _, pos = PdfEditor._tw_append(tw, pos, buf, font, size)
        return pos

    @staticmethod
    def _tw_append(tw, pos, text, font, size):
        rect, last = tw.append(pos, text, font=font, fontsize=size)
        return rect, last
