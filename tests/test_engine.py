import os, sys, tempfile
import pymupdf
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
os.environ["PDFTEXTEDIT_HOME"] = tempfile.mkdtemp()
from tests.make_samples import make
from pdftextedit.engine import PdfEditor, MissingGlyphsError
import pdftextedit.engine as eng


def fresh():
    d = tempfile.mkdtemp()
    p = os.path.join(d, "s.pdf")
    make(p)
    e = PdfEditor(); e.open(p)
    return e, d


def by_text(e, t):
    return next(s for s in e.spans(0) if s.text.startswith(t))


def test_embedded_font_is_used_when_not_installed(monkeypatch=None):
    e, _ = fresh()
    e.index.entries = []; e.index._loaded = True        # simula fuente NO instalada
    s = by_text(e, "Bold")
    c = e.check_edit(s, "Bold lode")
    assert c.ok and c.primary.kind == "embedded", (c.primary, c.missing)
    e.apply_edit(c)
    n = by_text(e, "Bold lode")
    assert n.font.startswith("LiberationSerif-Bold") or "Bold" in n.font
    assert n.size == s.size and n.color == s.color and n.bold


def test_missing_glyph_blocks_then_equivalent_allowed():
    e, _ = fresh()
    e.index.entries = []; e.index._loaded = True
    s = by_text(e, "Italic")
    c = e.check_edit(s, "Italic sans línea")        # 'í' no está en el subconjunto
    assert not c.ok and "í" in c.missing
    try:
        e.apply_edit(c); assert False
    except MissingGlyphsError:
        pass
    e.apply_edit(c, allow_equivalent=True)
    txt = e.doc[0].get_text()
    assert "línea" in txt


def test_installed_font_covers_missing_glyph():
    e, _ = fresh()
    s = by_text(e, "Italic")
    c = e.check_edit(s, "Italic sans línea")
    assert c.ok and c.primary.kind == "installed"
    e.apply_edit(c)
    n = by_text(e, "Italic sans l")
    assert "Italic" in n.font and n.italic


def test_user_font_install_and_other_text_untouched():
    e, _ = fresh()
    before = [s.text for s in e.spans(0)]
    s = by_text(e, "Hello")
    c = e.check_edit(s, "Adiós mundo")
    e.apply_edit(c, allow_equivalent=True)
    after = [s.text for s in e.spans(0)]
    assert sorted(after) == sorted(["Adiós mundo"] + before[1:])
    assert e.undo() and sorted(s.text for s in e.spans(0)) == sorted(before)


def test_standard_helvetica():
    e, _ = fresh()
    s = by_text(e, "Standard")
    c = e.check_edit(s, "Texto estándar")
    assert c.ok, c.missing
    e.apply_edit(c)
    assert "Texto estándar" in e.doc[0].get_text()


def test_save_roundtrip():
    e, d = fresh()
    s = by_text(e, "Hello")
    e.apply_edit(e.check_edit(s, "Changed"))
    out = os.path.join(d, "o.pdf"); e.save(out)
    assert "Changed" in pymupdf.open(out)[0].get_text()
