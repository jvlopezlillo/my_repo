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
    field_xref: int = 0  # >0: campo de formulario (widget) en vez de texto de la página

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
    font: Optional[pymupdf.Font]
    name: str
    kind: str          # native | embedded | installed | user | equivalent | builtin
    path: Optional[str] = None
    native: Optional["NativeFont"] = None

    def has(self, ch: str) -> bool:
        return self.native.has(ch) if self.native else self.font.has_glyph(ord(ch))

    def missing(self, chars: set) -> set:
        return {c for c in chars if not self.has(c)}

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


def winansi_ok(ch: str) -> bool:
    try:
        ch.encode("cp1252")
        return True
    except UnicodeEncodeError:
        return False


def _visible(text: str) -> set[str]:
    return {c for c in text if not c.isspace()}


def _covers(font, chars: set[str]) -> set[str]:
    """Devuelve el subconjunto de `chars` que la fuente NO tiene."""
    if isinstance(font, FontChoice):
        return font.missing(chars)
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


class NativeFont:
    """Reutiliza *la propia fuente del PDF* (mismo objeto, mismos glifos) escribiendo
    códigos directamente en el contenido de la página. Es la opción más fiel: funciona con
    cualquier fuente incrustada (TrueType, CFF, Type1) que tenga ToUnicode."""

    def __init__(self, doc, xref: int, res_name: str):
        self.doc, self.xref, self.res_name = doc, xref, res_name
        self.name = res_name
        self.ok = False
        self.u2c: dict[str, int] = {}
        self.widths: dict[int, float] = {}
        self.dw = 1000.0
        self.two_byte = False
        try:
            self._load()
        except Exception:
            self.ok = False

    # -- carga
    def _load(self):
        d = self.doc
        subtype = d.xref_get_key(self.xref, "Subtype")[1]
        kind, val = d.xref_get_key(self.xref, "ToUnicode")
        if kind != "xref":
            return
        tu = parse_tounicode(d.xref_stream(int(val.split()[0])))
        for code, txt in tu.items():
            if len(txt) == 1 and txt != "\ufffd":
                self.u2c.setdefault(txt, code)
        if subtype == "/Type0":
            if d.xref_get_key(self.xref, "Encoding")[1] not in ("/Identity-H",):
                return
            self.two_byte = True
            k, v = d.xref_get_key(self.xref, "DescendantFonts")
            desc = int(re.search(r"(\d+) 0 R", v).group(1)) if k != "null" else None
            if desc is None:
                return
            k, v = d.xref_get_key(desc, "DW")
            self.dw = float(v) if k != "null" else 1000.0
            k, v = d.xref_get_key(desc, "W")
            if k == "xref":
                v = d.xref_object(int(v.split()[0]), compressed=False)
            if k != "null":
                self._parse_w(v)
        elif subtype in ("/TrueType", "/Type1", "/MMType1"):
            self.two_byte = False
            k, v = d.xref_get_key(self.xref, "FirstChar")
            first = int(v) if k != "null" else 0
            k, v = d.xref_get_key(self.xref, "Widths")
            if k == "xref":
                v = d.xref_object(int(v.split()[0]), compressed=False)
            if k != "null":
                for i, w in enumerate(re.findall(r"[-\d.]+", v)):
                    self.widths[first + i] = float(w)
            k, v = d.xref_get_key(self.xref, "FontDescriptor")
            self.dw = 0.0
            # solo códigos de 1 byte
            self.u2c = {u: c for u, c in self.u2c.items() if c < 256}
        else:
            return
        self.ok = bool(self.u2c)

    def _parse_w(self, v: str):
        toks = re.findall(r"\[|\]|[-\d.]+", v)
        i = 0
        # saltar corchete externo
        if toks and toks[0] == "[":
            toks = toks[1:]
        while i < len(toks):
            if toks[i] == "]":
                i += 1; continue
            a = int(float(toks[i])); i += 1
            if i < len(toks) and toks[i] == "[":
                i += 1
                c = a
                while i < len(toks) and toks[i] != "]":
                    self.widths[c] = float(toks[i]); c += 1; i += 1
                i += 1
            elif i + 1 < len(toks):
                b = int(float(toks[i])); w = float(toks[i + 1]); i += 2
                for c in range(a, b + 1):
                    self.widths[c] = w

    # -- uso
    def has(self, ch: str) -> bool:
        return ch in self.u2c

    def width(self, text: str, size: float) -> float:
        return sum(self.widths.get(self.u2c.get(c, -1), self.dw) for c in text) * size / 1000.0

    def encode(self, text: str) -> str:
        n = 4 if self.two_byte else 2
        return "".join(format(self.u2c[c], f"0{n}X") for c in text)


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
        fields = self._field_spans(page_no)
        if fields:
            rects = [pymupdf.Rect(f.bbox) for f in fields]
            out = [sp for sp in out
                   if not any(r.contains(pymupdf.Rect(sp.bbox).tl + (pymupdf.Rect(sp.bbox).br - pymupdf.Rect(sp.bbox).tl) * 0.5)
                              for r in rects)]
            out += fields
        return out

    # ------------------------------------------------------------ formularios
    _STD_FONT_NAMES = {"helv": "helv", "hebo": "hebo", "heob": "hebo", "heit": "heit", "hebi": "hebi",
                       "tiro": "tiro", "tibo": "tibo", "tiit": "tiit", "tibi": "tibi",
                       "cour": "cour", "cobo": "cobo", "coit": "coit", "cobi": "cobi"}

    def _field_spans(self, page_no: int) -> list[Span]:
        page = self.doc[page_no]
        out = []
        for w in page.widgets():
            if w.field_type_string != "Text" or w.field_value in (None, ""):
                continue
            if w.field_flags & (1 << 12) or w.field_flags & (1 << 24):   # multilínea / comb
                continue
            info = self._field_ap_info(w.xref)
            if info is None:
                continue
            name, size, color = info
            r = w.rect
            out.append(Span(page_no, str(w.field_value), tuple(r), (r.x0 + 2, r.y1 - 4),
                            name, size, self._flags_for(name), color, True, w.xref))
        return out

    @staticmethod
    def _flags_for(name: str) -> int:
        n = name.lower()
        fl = 0
        if "bold" in n or n.endswith("bo") or "bd" in n: fl |= FLAG_BOLD
        if "ital" in n or "obli" in n or n.endswith("it") or n.endswith("ob"): fl |= FLAG_ITALIC
        if "times" in n or n.startswith("ti"): fl |= FLAG_SERIF
        if "cour" in n or n.startswith("co"): fl |= FLAG_MONO
        return fl

    def _field_ap_info(self, xref: int):
        """(nombre de fuente, tamaño, color) del aspecto actual del campo."""
        k, v = self.doc.xref_get_key(xref, "AP/N")
        if k != "xref":
            return None
        stream = self.doc.xref_stream(int(v.split()[0])) or b""
        tfs = re.findall(rb"/(\S+)\s+([\d.]+)\s+Tf", stream)
        if not tfs:
            return None
        res, size = tfs[-1]
        size = float(size)
        base = res.decode("latin1")
        # nombre real (BaseFont) desde /DR del AcroForm
        try:
            cat = self.doc.pdf_catalog()
            kk, af = self.doc.xref_get_key(cat, "AcroForm")
            afx = int(af.split()[0]) if kk == "xref" else None
            if afx:
                kf, fv = self.doc.xref_get_key(afx, f"DR/Font/{base}")
                if kf == "xref":
                    kb, bv = self.doc.xref_get_key(int(fv.split()[0]), "BaseFont")
                    if kb == "name":
                        base = bv.lstrip("/")
        except Exception:
            pass
        m = re.search(rb"([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+rg", stream)
        col = tuple(float(x) for x in m.groups()) if m else (0.0, 0.0, 0.0)
        return base, size, col

    def span_at(self, page_no: int, x: float, y: float) -> Optional[Span]:
        best = None
        for s in self.spans(page_no):
            r = pymupdf.Rect(s.bbox)
            if r.contains(pymupdf.Point(x, y)):
                if best is None or r.get_area() < pymupdf.Rect(best.bbox).get_area():
                    best = s
        return best

    # ------------------------------------------------------------ fuentes
    def _native_font(self, span: Span) -> Optional[FontChoice]:
        page = self.doc[span.page]
        for xref, _e, _t, basefont, name, _enc, ref in page.get_fonts(full=True):
            if ref != 0 or strip_subset(basefont) != span.font:
                continue   # solo fuentes de los recursos de la página
            key = ("n", xref, name)
            if key not in self._emb_cache:
                nf = NativeFont(self.doc, xref, name)
                self._emb_cache[key] = nf if nf.ok else None
            nf = self._emb_cache[key]
            if nf:
                return FontChoice(None, span.font, "native", native=nf)
        return None

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
            if not c.missing(chars):
                return c
        return min(cands, key=lambda c: len(c.missing(chars)))

    # ------------------------------------------------------------ comprobar
    def check_edit(self, span: Span, new_text: str, user_font: Optional[str] = None) -> EditCheck:
        need = _visible(new_text)
        chk = EditCheck(span, new_text, None)
        if span.field_xref:
            f = load_font(builtin=builtin_for(span.bold, span.italic, span.serif, span.mono))
            chk.primary = FontChoice(f, span.font + " (campo de formulario)", "builtin")
            chk.missing = sorted(c for c in need if winansi_ok(c) is False)
            chk.notes.append("Campo de formulario: se conserva su fuente y tamaño; "
                             "solo admite caracteres Latin-1/WinAnsi.")
            return chk
        if not span.horizontal:
            chk.notes.append("Texto no horizontal: la edición puede no ser exacta.")
        cands: list[FontChoice] = []
        if user_font:
            u = self._user_font(user_font)
            if u:
                cands.append(u)
        nat = self._native_font(span)
        if nat:
            cands.append(nat)
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
            miss = c.missing(need)
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
        if span.field_xref:
            if chk.missing:
                raise MissingGlyphsError(chk)
            self._snapshot()
            self._apply_field(span, text)
            self.dirty = True
            return
        page = self.doc[span.page]

        # ¿qué fuente para cada carácter?
        def font_for(ch: str) -> FontChoice:
            if chk.primary and (ch.isspace() or chk.primary.has(ch)):
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
        native_ops: list[str] = []
        inv = ~page.transformation_matrix
        for run, fc in runs:
            if fc.native:
                pos = self._native_run(native_ops, inv, pos, run, fc, span)
            else:
                pos = self._append(tw, pos, run, fc.font, span.size)
        tw.write_text(page, color=span.color)
        if native_ops:
            self._add_native_content(page, native_ops, span.color,
                                     {fc.native for _t, fc in runs if fc.native})
        self.dirty = True

    def _apply_field(self, span: Span, text: str):
        """Cambia el valor de un campo de formulario y su aspecto, conservando
        la fuente, el tamaño (reduciéndolo solo si no cabe) y la posición."""
        doc, x = self.doc, span.field_xref
        kind, v = doc.xref_get_key(x, "AP/N")
        n = int(v.split()[0])
        stream = doc.xref_stream(n)
        _kb, bbox = doc.xref_get_key(n, "BBox")
        bw = float(bbox.strip("[] ").split()[2]) if bbox and bbox != "null" else 1e9
        font = load_font(builtin=builtin_for(span.bold, span.italic, span.serif, span.mono))
        size = span.size
        width = font.text_length(text, fontsize=size)
        avail = bw - 4
        if width > avail > 0:                      # como un visor con tamaño automático
            size = max(size * avail / width, 4.0)
        raw = text.encode("cp1252")
        esc = (raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")
               .replace(b"\r", b"\\r").replace(b"\n", b"\\n"))
        tj = list(re.finditer(rb"\((?:\\.|[^\\)])*\)\s*Tj", stream))
        done = bool(tj)
        # el aspecto puede trocear el texto en varios Tj/Td: se sustituye todo el tramo por uno solo
        new = (stream[:tj[0].start()] + b"(" + esc + b") Tj" + stream[tj[-1].end():]) if tj else stream
        tfs = list(re.finditer(rb"/(\S+)\s+([\d.]+)\s+Tf", new))
        if tfs and size != span.size:
            m = tfs[-1]
            new = new[:m.start(2)] + (f"{size:.4f}").encode() + new[m.end(2):]
        if not done:                                # estructura inesperada: regenerar
            for w in self.doc[span.page].widgets():
                if w.xref == x:
                    w.field_value = text
                    w.update()
            return
        doc.update_stream(n, new)
        doc.xref_set_key(x, "V", pymupdf.get_pdf_str(text))
        kp, pv = doc.xref_get_key(x, "Parent")
        if kp == "xref" and doc.xref_get_key(int(pv.split()[0]), "V")[0] != "null":
            doc.xref_set_key(int(pv.split()[0]), "V", pymupdf.get_pdf_str(text))

    @staticmethod
    def _native_run(ops, inv, pos, run, fc, span):
        nf = fc.native
        seg = ""
        def flush(seg, pos):
            if not seg:
                return pos
            p = pymupdf.Point(pos) * inv
            ops.append(f"BT /{nf.res_name} {span.size:.4f} Tf 1 0 0 1 {p.x:.4f} {p.y:.4f} Tm "
                       f"<{nf.encode(seg)}> Tj ET")
            return pymupdf.Point(pos.x + nf.width(seg, span.size), pos.y)
        for ch in run:
            if ch.isspace() and not nf.has(ch):
                pos = flush(seg, pos); seg = ""
                pos = pymupdf.Point(pos.x + span.size * 0.28, pos.y)
            else:
                seg += ch
        return flush(seg, pos)

    def _add_native_content(self, page, ops, color, natives):
        # la redacción puede haber retirado de los recursos una fuente que ya no se usaba
        for nf in natives:
            have = {n for _x, _e, _t, _b, n, _en, ref in page.get_fonts(full=True) if ref == 0}
            if nf.res_name not in have:
                self.doc.xref_set_key(page.xref, f"Resources/Font/{nf.res_name}", f"{nf.xref} 0 R")
        page.wrap_contents()
        r, g, b = color
        data = ("q\n" + f"{r:.4f} {g:.4f} {b:.4f} rg\n" + "\n".join(ops) + "\nQ\n").encode()
        x = self.doc.get_new_xref()
        self.doc.update_object(x, "<<>>")
        self.doc.update_stream(x, data)
        arr = " ".join(f"{c} 0 R" for c in page.get_contents() + [x])
        self.doc.xref_set_key(page.xref, "Contents", f"[{arr}]")

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
