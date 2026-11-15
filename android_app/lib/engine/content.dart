import 'dart:math' as math;
import 'dart:typed_data';
import 'fonts.dart';
import 'lexer.dart';
import 'objects.dart';

class Op {
  final String name;
  final List<dynamic> args;
  final int start; // primer byte del primer operando
  final int end; // byte siguiente al operador
  const Op(this.name, this.args, this.start, this.end);
}

bool _ws(int c) => c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;

/// Trocea un content stream en operaciones, recordando su rango de bytes.
List<Op> tokenizeContent(Uint8List data) {
  final lx = Lexer(data);
  final ops = <Op>[];
  var args = <dynamic>[];
  int start = -1;
  while (true) {
    lx.skipWs();
    if (lx.eof) break;
    if (start < 0) start = lx.pos;
    final o = lx.readObject(refs: false);
    if (o is PdfKeyword) {
      if (o.word == 'BI') {
        // imagen en línea: saltar hasta EI
        while (!lx.eof) {
          final t = lx.readObject(refs: false);
          if (t is PdfKeyword && t.word == 'ID') break;
        }
        int p = lx.pos + 1;
        while (p < data.length - 1) {
          if (data[p] == 0x45 && data[p + 1] == 0x49 &&
              (p == 0 || _ws(data[p - 1])) && (p + 2 >= data.length || _ws(data[p + 2]))) {
            break;
          }
          p++;
        }
        lx.pos = math.min(p + 2, data.length);
        ops.add(Op('BI', const [], start, lx.pos));
      } else {
        ops.add(Op(o.word, args, start, lx.pos));
      }
      args = <dynamic>[];
      start = -1;
    } else {
      args.add(o);
    }
  }
  return ops;
}

class TextRun {
  final int opIndex;
  final Op op;
  final String text;
  final PdfFont font;
  final String fontRes;
  final double fontSize; // operando de Tf
  final double effSize; // tamaño real en unidades de usuario
  final List<double> origin; // x, y en espacio de usuario PDF
  final List<double> bbox; // x0, y0, x1, y1 (PDF, y hacia arriba)
  final List<double> color;
  final bool horizontal;
  final Set<int> codes; // códigos de glifo usados en este texto
  const TextRun(this.opIndex, this.op, this.text, this.font, this.fontRes, this.fontSize,
      this.effSize, this.origin, this.bbox, this.color, this.horizontal, this.codes);
}

List<double> _mul(List<double> m, List<double> n) => [
      m[0] * n[0] + m[1] * n[2],
      m[0] * n[1] + m[1] * n[3],
      m[2] * n[0] + m[3] * n[2],
      m[2] * n[1] + m[3] * n[3],
      m[4] * n[0] + m[5] * n[2] + n[4],
      m[4] * n[1] + m[5] * n[3] + n[5],
    ];

class _GS {
  List<double> ctm = [1, 0, 0, 1, 0, 0];
  List<double> color = [0, 0, 0];
  PdfFont? font;
  String fontRes = '';
  double size = 0, tc = 0, tw = 0, th = 1, tl = 0, rise = 0;
  _GS copy() {
    final g = _GS();
    g.ctm = [...ctm];
    g.color = [...color];
    g.font = font;
    g.fontRes = fontRes;
    g.size = size; g.tc = tc; g.tw = tw; g.th = th; g.tl = tl; g.rise = rise;
    return g;
  }
}

double _n(dynamic v) => v is num ? v.toDouble() : 0;

/// Recorre las operaciones y devuelve los textos mostrados.
List<TextRun> extractRuns(List<Op> ops, PdfFont? Function(String res) fontFor) {
  final runs = <TextRun>[];
  var gs = _GS();
  final stack = <_GS>[];
  var tm = <double>[1, 0, 0, 1, 0, 0];
  var tlm = <double>[1, 0, 0, 1, 0, 0];

  void setLine(double tx, double ty) {
    tlm = _mul([1, 0, 0, 1, tx, ty], tlm);
    tm = [...tlm];
  }

  void show(int idx, Op op, List<dynamic> items) {
    final f = gs.font;
    if (f == null || !f.supported) {
      // avanzar sin registrar no es fiable; se ignora
      return;
    }
    final sb = StringBuffer();
    final codes = <int>{};
    double tx = 0;
    for (final it in items) {
      if (it is PdfString) {
        for (final g in f.decode(it.bytes)) {
          sb.write(g.text);
          codes.add(g.code);
          tx += (g.width / 1000 * gs.size + gs.tc + (!f.twoByte && g.code == 32 ? gs.tw : 0)) * gs.th;
        }
      } else if (it is num) {
        tx -= it / 1000 * gs.size * gs.th;
      }
    }
    final t = _mul(tm, gs.ctm);
    final fs = gs.size;
    final r = gs.rise;
    final pts = <List<double>>[
      [0, r - 0.25 * fs], [tx, r - 0.25 * fs], [tx, r + 0.85 * fs], [0, r + 0.85 * fs]
    ].map((p) => [t[0] * p[0] + t[2] * p[1] + t[4], t[1] * p[0] + t[3] * p[1] + t[5]]).toList();
    final xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
    final horiz = t[1].abs() < 1e-4 && t[2].abs() < 1e-4 && t[0] > 0 && t[3] > 0;
    runs.add(TextRun(
        idx, op, sb.toString(), f, gs.fontRes, fs,
        fs.abs() * math.sqrt(t[2] * t[2] + t[3] * t[3]),
        [t[2] * r + t[4], t[3] * r + t[5]],
        [xs.reduce(math.min), ys.reduce(math.min), xs.reduce(math.max), ys.reduce(math.max)],
        [...gs.color], horiz, codes));
    tm = _mul([1, 0, 0, 1, tx, 0], tm);
  }

  for (int i = 0; i < ops.length; i++) {
    final op = ops[i];
    final a = op.args;
    switch (op.name) {
      case 'q': stack.add(gs.copy()); break;
      case 'Q': if (stack.isNotEmpty) gs = stack.removeLast(); break;
      case 'cm':
        if (a.length >= 6) gs.ctm = _mul(a.take(6).map(_n).toList(), gs.ctm);
        break;
      case 'BT':
        tm = [1, 0, 0, 1, 0, 0]; tlm = [1, 0, 0, 1, 0, 0];
        break;
      case 'Tf':
        if (a.length >= 2 && a[0] is PdfName) {
          gs.fontRes = (a[0] as PdfName).name;
          gs.font = fontFor(gs.fontRes);
          gs.size = _n(a[1]);
        }
        break;
      case 'Tc': if (a.isNotEmpty) gs.tc = _n(a[0]); break;
      case 'Tw': if (a.isNotEmpty) gs.tw = _n(a[0]); break;
      case 'Tz': if (a.isNotEmpty) gs.th = _n(a[0]) / 100; break;
      case 'TL': if (a.isNotEmpty) gs.tl = _n(a[0]); break;
      case 'Ts': if (a.isNotEmpty) gs.rise = _n(a[0]); break;
      case 'Td': if (a.length >= 2) setLine(_n(a[0]), _n(a[1])); break;
      case 'TD':
        if (a.length >= 2) {
          gs.tl = -_n(a[1]);
          setLine(_n(a[0]), _n(a[1]));
        }
        break;
      case 'Tm':
        if (a.length >= 6) {
          tm = a.take(6).map(_n).toList();
          tlm = [...tm];
        }
        break;
      case 'T*': setLine(0, -gs.tl); break;
      case 'Tj': if (a.isNotEmpty) show(i, op, [a[0]]); break;
      case 'TJ': if (a.isNotEmpty && a[0] is List) show(i, op, a[0] as List); break;
      case "'":
        setLine(0, -gs.tl);
        if (a.isNotEmpty) show(i, op, [a[0]]);
        break;
      case '"':
        if (a.length >= 3) {
          gs.tw = _n(a[0]); gs.tc = _n(a[1]);
          setLine(0, -gs.tl);
          show(i, op, [a[2]]);
        }
        break;
      case 'g': if (a.isNotEmpty) { final v = _n(a[0]); gs.color = [v, v, v]; } break;
      case 'rg': if (a.length >= 3) gs.color = a.take(3).map(_n).toList(); break;
      case 'k':
        if (a.length >= 4) {
          final c = _n(a[0]), m = _n(a[1]), y = _n(a[2]), k = _n(a[3]);
          gs.color = [(1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k)];
        }
        break;
      case 'sc':
      case 'scn':
        final nums = a.whereType<num>().map((e) => e.toDouble()).toList();
        if (nums.length == 1) gs.color = [nums[0], nums[0], nums[0]];
        if (nums.length == 3) gs.color = nums;
        break;
    }
  }
  return runs;
}
