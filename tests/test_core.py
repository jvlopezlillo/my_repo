from pathlib import Path

from filecompare.core import (Status, compare_dirs, copy_path, iter_nodes,
                              parse_ignore, summarize)
from filecompare.textdiff import (BinaryFileError, TextDocument, apply_block,
                                  compute_diff)
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


def test_copy_dir_merges_and_replaces_type(tmp_path):
    L, R = make(tmp_path)
    copy_path(L, R, [".git"])
    assert (R / "diff.txt").read_text() == "abc"
    assert (R / "newdir/f.txt").exists()
    assert (R / "only_r.txt").exists()  # lo extra en destino se conserva
    assert (R / "mix").is_file()  # carpeta reemplazada por fichero
    assert not (R / ".git").exists()
    s = summarize(compare_dirs(L, R, [".git"]))
    assert s[Status.DIFFERENT] == 0 and s[Status.LEFT_ONLY] == 0


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
