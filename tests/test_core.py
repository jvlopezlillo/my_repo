from pathlib import Path

from filecompare.core import (Cancelled, Progress, Status, compare_dirs, copy_path,
                              count_files, files_equal, iter_nodes, parse_ignore, summarize)
from filecompare.textdiff import (BinaryFileError, TextDocument, apply_block,
                                  compute_diff, inline_spans)
import pytest


def w(p: Path, text: str):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)


def by_rel(root):
    return {n.rel: n for n in iter_nodes(root)}


def make(tmp_path):
    L, R = tmp_path / "L", tmp_path / "R"
    w(L / "same.txt", "a\n")
    w(R / "same.txt", "a\n")
    w(L / "diff.txt", "abc")
    w(R / "diff.txt", "abd")  # mismo tamaño, distinto contenido
    w(L / "only_l.txt", "x")
    w(R / "only_r.txt", "y")
    w(L / "sub/ok.txt", "1")
    w(R / "sub/ok.txt", "1")
    w(L / "sub2/f.txt", "1")
    w(R / "sub2/f.txt", "2")
    w(L / "newdir/f.txt", "1")
    w(L / ".git/config", "ignored")
    (R / "mix").mkdir()
    w(L / "mix", "file")
    return L, R


def test_compare_dirs(tmp_path):
    L, R = make(tmp_path)
    n = by_rel(compare_dirs(L, R, parse_ignore(".git")))
    assert n["same.txt"].status is Status.IDENTICAL
    assert n["diff.txt"].status is Status.DIFFERENT
    assert n["only_l.txt"].status is Status.LEFT_ONLY
    assert n["only_r.txt"].status is Status.RIGHT_ONLY
    assert n["sub"].status is Status.IDENTICAL
    assert n["sub2"].status is Status.DIFFERENT
    assert n["newdir"].status is Status.LEFT_ONLY
    assert n["newdir/f.txt"].status is Status.LEFT_ONLY
    assert n["mix"].status is Status.TYPE_MISMATCH
    assert ".git" not in n


def test_summary(tmp_path):
    L, R = make(tmp_path)
    s = summarize(compare_dirs(L, R, [".git"]))
    assert s[Status.DIFFERENT] == 2 and s[Status.IDENTICAL] == 2
    assert s[Status.LEFT_ONLY] == 2 and s[Status.RIGHT_ONLY] == 1


def test_copy_file_overwrites(tmp_path):
    w(tmp_path / "a.txt", "new")
    w(tmp_path / "b/a.txt", "old")
    copy_path(tmp_path / "a.txt", tmp_path / "b/a.txt")
    assert (tmp_path / "b/a.txt").read_text() == "new"
    copy_path(tmp_path / "a.txt", tmp_path / "deep/er/a.txt")
    assert (tmp_path / "deep/er/a.txt").read_text() == "new"


def test_copy_dir_mirrors_exactly(tmp_path):
    L, R = make(tmp_path)
    w(R / ".git/keep", "ignorado: se conserva")
    copy_path(L, R, [".git"])
    assert (R / "diff.txt").read_text() == "abc"
    assert (R / "newdir/f.txt").exists()
    assert not (R / "only_r.txt").exists()  # lo extra en destino se elimina
    assert (R / "mix").is_file()  # carpeta reemplazada por fichero
    assert (R / ".git/keep").exists()  # lo ignorado ni se copia ni se borra
    assert not (L / ".git").joinpath("keep").exists()
    s = summarize(compare_dirs(L, R, [".git"]))
    assert s[Status.DIFFERENT] == s[Status.LEFT_ONLY] == s[Status.RIGHT_ONLY] == 0


def test_copy_removes_extra_subdirs(tmp_path):
    w(tmp_path / "S/a.txt", "1")
    w(tmp_path / "D/a.txt", "0")
    w(tmp_path / "D/extra/deep/x.txt", "x")
    copy_path(tmp_path / "S", tmp_path / "D")
    assert sorted(p.name for p in (tmp_path / "D").iterdir()) == ["a.txt"]


def test_ignore_whitespace_and_eol(tmp_path):
    a, b, c = tmp_path / "a", tmp_path / "b", tmp_path / "c"
    a.write_bytes(b"x = 1\nif y:\n\treturn  2\n")
    b.write_bytes(b"x = 1\r\nif y:\r\n\treturn  2\r\n")  # sólo CRLF
    c.write_bytes(b"x   =   1  \nif y:\n    return 2\n")  # sólo espacios
    assert not files_equal(a, b) and not files_equal(a, c)
    assert files_equal(a, b, ignore_eol=True) and not files_equal(a, c, ignore_eol=True)
    assert files_equal(a, c, ignore_ws=True) and files_equal(a, b, ignore_ws=True)
    d = tmp_path / "d"
    d.write_bytes(b"x = 2\n")
    assert not files_equal(a, d, ignore_ws=True)
    # carpetas
    w(tmp_path / "L/f.txt", "a  b\n")
    w(tmp_path / "R/f.txt", "a b\n")
    n = by_rel(compare_dirs(tmp_path / "L", tmp_path / "R"))
    assert n["f.txt"].status is Status.DIFFERENT
    n = by_rel(compare_dirs(tmp_path / "L", tmp_path / "R", ignore_ws=True))
    assert n["f.txt"].status is Status.IDENTICAL


def test_progress_and_cancel(tmp_path):
    L, R = make(tmp_path)
    seen = []
    p = Progress(lambda d, t: seen.append((d, t)))
    compare_dirs(L, R, [".git"], progress=p)
    assert p.total == count_files(L, [".git"]) + count_files(R, [".git"])
    assert p.done == p.total and seen[-1] == (p.total, p.total)
    p = Progress()
    p.cancel()
    with pytest.raises(Cancelled):
        compare_dirs(L, R, progress=p)
    with pytest.raises(Cancelled):
        copy_path(L, tmp_path / "X", progress=p)


def test_textdoc_roundtrip(tmp_path):
    for raw in (b"a\r\nb\r\n", b"a\nb", b"", b"\xef\xbb\xbfhola\n", b"caf\xe9\n"):
        p = tmp_path / "t.txt"
        p.write_bytes(raw)
        d = TextDocument.load(p)
        assert d.to_text().encode(d.encoding) == raw


def test_textdoc_binary_and_missing(tmp_path):
    p = tmp_path / "b.bin"
    p.write_bytes(b"\x00\x01\x02")
    with pytest.raises(BinaryFileError):
        TextDocument.load(p)
    d = TextDocument.load(tmp_path / "new/x.txt")
    assert not d.exists and d.lines == []
    d.lines = ["hi"]
    d.save()
    assert (tmp_path / "new/x.txt").read_text() == "hi\n"


def test_diff_blocks_and_copy():
    a = ["1", "2", "3", "4", "5"]
    b = ["1", "X", "Y", "3", "5", "6"]
    rows, blocks = compute_diff(a, b)
    assert [x.tag for x in blocks] == ["replace", "delete", "insert"]
    assert len(rows) == 1 + 2 + 1 + 1 + 1 + 1
    assert rows[blocks[0].row_start].left == "2"
    assert rows[blocks[1].row_start].right is None  # relleno

    a2, b2 = list(a), list(b)
    apply_block(a2, b2, blocks[0], "ltr")
    assert b2 == ["1", "2", "3", "5", "6"] or b2[:2] == ["1", "2"]

    # copiar todos los bloques izq->der (de atrás hacia delante) iguala ficheros
    a2, b2 = list(a), list(b)
    for blk in reversed(blocks):
        apply_block(a2, b2, blk, "ltr")
    assert a2 == b2 == a
    a2, b2 = list(a), list(b)
    for blk in reversed(blocks):
        apply_block(a2, b2, blk, "rtl")
    assert a2 == b2 == b


def test_diff_ignore_ws():
    a, b = ["x = 1", "y"], ["x   =  1 ", "z"]
    _, blocks = compute_diff(a, b)
    assert len(blocks) == 1 and blocks[0].i1 == 0  # distinto sin ignorar
    rows, blocks = compute_diff(a, b, ignore_ws=True)
    assert len(blocks) == 1 and blocks[0].i1 == 1
    assert rows[0].left == "x = 1" and rows[0].right == "x   =  1 "  # se muestra el original


def test_inline_spans():
    sa, sb = inline_spans("el gato negro", "el perro negro")
    assert [("el gato negro")[s:e] for s, e in sa] == ["gato"]
    assert [("el perro negro")[s:e] for s, e in sb] == ["perro"]
    assert inline_spans("igual", "igual") == ([], [])


def test_delete_path(tmp_path):
    from filecompare.core import delete_path
    w(tmp_path / "d/a/b.txt", "1")
    w(tmp_path / "d/c.txt", "2")
    p = Progress()
    delete_path(tmp_path / "d", p)
    assert not (tmp_path / "d").exists() and p.done == 2
    w(tmp_path / "f.txt", "x")
    delete_path(tmp_path / "f.txt")
    assert not (tmp_path / "f.txt").exists()


def _flat(root):
    return {n.rel: (n.status, n.left is not None, n.right is not None) for n in iter_nodes(root)}


def test_refresh_path_matches_full_compare(tmp_path):
    from filecompare.core import copy_path as cp, delete_path, refresh_path
    L, R = make(tmp_path)
    ign = [".git"]
    root = compare_dirs(L, R, ign)
    # copiar fichero, carpeta izq-only (crea carpeta en la derecha), borrar en un lado y en ambos
    cp(L / "diff.txt", R / "diff.txt")
    refresh_path(root, "diff.txt", L, R, ign)
    assert _flat(root) == _flat(compare_dirs(L, R, ign))
    cp(L / "newdir", R / "newdir", ign)
    refresh_path(root, "newdir", L, R, ign)
    assert _flat(root) == _flat(compare_dirs(L, R, ign))
    delete_path(R / "sub2" / "f.txt")
    refresh_path(root, "sub2/f.txt", L, R, ign)
    assert _flat(root) == _flat(compare_dirs(L, R, ign))
    delete_path(L / "only_l.txt")
    refresh_path(root, "only_l.txt", L, R, ign)
    assert "only_l.txt" not in _flat(root)
    assert _flat(root) == _flat(compare_dirs(L, R, ign))
    # copiar un fichero dentro de una carpeta que sólo existe a la izquierda crea la carpeta padre
    w(L / "solo/inner/a.txt", "1")
    root = compare_dirs(L, R, ign)
    cp(L / "solo/inner/a.txt", R / "solo/inner/a.txt")
    refresh_path(root, "solo/inner/a.txt", L, R, ign)
    assert _flat(root) == _flat(compare_dirs(L, R, ign))
    with pytest.raises(KeyError):
        refresh_path(root, "no/existe/x.txt", L, R, ign)
