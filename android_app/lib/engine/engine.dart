import 'dart:convert';
import 'dart:typed_data';
import 'content.dart';
import 'document.dart';
import 'fonts.dart';
import 'objects.dart';
import 'writer.dart';

class SpanInfo {
  final int page;
  final int runIndex;
  final String text;
  /// Caja en puntos, origen arriba-izquierda de la página.
  final double x0, y0, x1, y1;
  final String fontName;
  final double size;
  final bool bold, italic, horizontal;
  final List<double> color; // 0..1
  final String source; // texto explicativo de la fuente
  const SpanInfo(this.page, this.runIndex, this.text, this.x0, this.y0, this.x1, this.y1,
      this.fontName, this.size, this.bold, this.italic, this.horizontal, this.color, this.source);

  String get styleLabel {
    final s = [if (bold) 'Negrita', if (italic) 'Cursiva'];
    return s.isEmpty ? 'Normal' : s.join(', ');
  }
}

class EditCheck {
  final SpanInfo span;
  final String newText;
  final List<String> missing; // caracteres que la fuente original no tiene
  final List<String> unsupported; // ni siquiera una fuente estándar los tiene
  const EditCheck(this.span, this.newText, this.missing, this.unsupported);
  bool get ok => missing.isEmpty;
}

class _PageData {
  final PdfPageInfo page;
  final List<PdfRef> streamRefs;
  final List<Uint8List> streams;
  final List<int> offsets; // inicio de cada stream en el texto concatenado
  final Uint8List all;
  final List<Op> ops;
  final List<TextRun> runs;
  _PageData(this.page, this.streamRefs, this.streams, this.offsets, this.all, this.ops, this.runs);
}

class PdfTextEngine {
  final Uint8List bytes;
  final PdfDoc doc;
  late final List<PdfPageInfo> _pages;
  final Map<int, _PageData> _cache = {};
  final Map<String, PdfFont?> _fontCache = {};

  PdfTextEngine(this.bytes) : doc = PdfDoc(bytes) {
    _pages = doc.pages();
  }

  int get pageCount => _pages.length;

  /// Tamaño de la página en puntos.
  List<double> pageSize(int i) {
    final b = _pages[i].box;
    return [b[2] - b[0], b[3] - b[1]];
  }

  bool pageSupported(int i) => _pages[i].rotate == 0;

  _PageData _data(int i) => _cache.putIfAbsent(i, () {
        final p = _pages[i];
        final refs = p.contentRefs;
        final streams = <Uint8List>[];
        final offsets = <int>[];
        final all = BytesBuilder();
        for (final r in refs) {
          final s = doc.resolve(r);
          Uint8List d = Uint8List(0);
          if (s is PdfStream) {
            try {
              d = doc.decodeStream(s);
            } catch (_) {}
          }
          offsets.add(all.length);
          streams.add(d);
          all.add(d);
          all.add([10]);
        }
        final data = all.toBytes();
        final ops = tokenizeContent(data);
        final fonts = doc.dictOf(p.resources['Font']) ?? {};
        final runs = extractRuns(ops, (res) {
          final key = '$i/$res';
          return _fontCache.putIfAbsent(key, () {
            final v = fonts[res];
            final d = doc.dictOf(v);
            return d == null ? null : PdfFont(doc, res, v is PdfRef ? v : null, d);
          });
        });
        return _PageData(p, refs, streams, offsets, data, ops, runs);
      });

  List<SpanInfo> spans(int page) {
    if (!pageSupported(page)) return [];
    final d = _data(page);
    final b = d.page.box;
    final out = <SpanInfo>[];
    for (int k = 0; k < d.runs.length; k++) {
      final r = d.runs[k];
      if (r.text.trim().isEmpty) continue;
      out.add(SpanInfo(
        page, k, r.text, r.bbox[0] - b[0], b[3] - r.bbox[3], r.bbox[2] - b[0], b[3] - r.bbox[1],
        r.font.displayName, r.effSize, r.font.bold, r.font.italic, r.horizontal, r.color,
        r.font.embedded ? 'incrustada en el PDF (se reutiliza la misma)' : 'fuente del PDF (no incrustada)',
      ));
    }
    return out;
  }

  EditCheck check(SpanInfo s, String newText) {
    final run = _data(s.page).runs[s.runIndex];
    final missing = <String>{};
    final unsupported = <String>{};
    for (final r in newText.runes) {
      final ch = String.fromCharCode(r);
      if (ch.trim().isEmpty) continue; // los espacios se simulan si faltan
      if (!run.font.canEncode(ch)) {
        missing.add(ch);
        if (winAnsiCode(ch) == null) unsupported.add(ch);
      }
    }
    return EditCheck(s, newText, missing.toList()..sort(), unsupported.toList()..sort());
  }

  static String _fallbackBase(PdfFont f) {
    final b = f.bold, i = f.italic;
    if (f.mono) return b ? (i ? 'Courier-BoldOblique' : 'Courier-Bold') : (i ? 'Courier-Oblique' : 'Courier');
    if (f.serif) return b ? (i ? 'Times-BoldItalic' : 'Times-Bold') : (i ? 'Times-Italic' : 'Times-Roman');
    return b ? (i ? 'Helvetica-BoldOblique' : 'Helvetica-Bold') : (i ? 'Helvetica-Oblique' : 'Helvetica');
  }

  /// Devuelve los bytes del PDF con el texto reemplazado (como nueva revisión incremental).
  Uint8List apply(EditCheck chk, {bool allowEquivalent = false}) {
    if (!chk.ok && !allowEquivalent) {
      throw PdfEditException('Faltan caracteres en la fuente original: ${chk.missing.join(' ')}');
    }
    if (chk.unsupported.isNotEmpty) {
      throw PdfEditException('Caracteres sin fuente disponible: ${chk.unsupported.join(' ')}');
    }
    final pd = _data(chk.span.page);
    final run = pd.runs[chk.span.runIndex];
    final f = run.font;
    final fs = _numStr(run.fontSize);

    // ---- construir el operador de reemplazo
    String? fbName;
    final parts = <String>[]; // operadores
    final cur = StringBuffer(); // elementos del TJ en curso
    void flushTj() {
      if (cur.isNotEmpty) {
        parts.add('[$cur] TJ');
        cur.clear();
      }
    }

    String nativeHex = '';
    void flushNative() {
      if (nativeHex.isNotEmpty) {
        if (cur.isNotEmpty) cur.write(' ');
        cur.write('<$nativeHex>');
        nativeHex = '';
      }
    }

    String fbHex = '';
    void flushFallback() {
      if (fbHex.isEmpty) return;
      flushNative();
      flushTj();
      fbName ??= _freeFontName(pd);
      parts.add('/$fbName $fs Tf');
      parts.add('[<$fbHex>] TJ');
      parts.add('/${run.fontRes} $fs Tf');
      fbHex = '';
    }

    for (final r in chk.newText.runes) {
      final ch = String.fromCharCode(r);
      if (f.canEncode(ch)) {
        flushFallback();
        nativeHex += f.encodeHex(ch);
      } else if (ch.trim().isEmpty) {
        flushFallback();
        flushNative();
        cur.write(' -280'); // hueco equivalente a un espacio
      } else {
        flushNative();
        fbHex += winAnsiCode(ch)!.toRadixString(16).toUpperCase().padLeft(2, '0');
      }
    }
    flushFallback();
    flushNative();
    flushTj();
    final newOp = parts.join('\n');

    // ---- localizar el stream que contiene el operador
    final op = run.op;
    int si = 0;
    for (int i = 0; i < pd.offsets.length; i++) {
      if (pd.offsets[i] <= op.start) si = i;
    }
    final base = pd.offsets[si];
    final orig = pd.streams[si];
    final nb = BytesBuilder()
      ..add(orig.sublist(0, op.start - base))
      ..add(latin1.encode(newOp))
      ..add(orig.sublist(op.end - base));
    final objs = <int, dynamic>{};
    final oldStream = doc.resolve(pd.streamRefs[si]) as PdfStream;
    objs[pd.streamRefs[si].num] = makeStream(oldStream.dict, nb.toBytes());

    if (fbName != null) {
      final newNum = doc.size;
      objs[newNum] = {
        'Type': const PdfName('Font'),
        'Subtype': const PdfName('Type1'),
        'BaseFont': PdfName(_fallbackBase(f)),
        'Encoding': const PdfName('WinAnsiEncoding'),
      };
      final res = {...pd.page.resources};
      final fonts = {...(doc.dictOf(res['Font']) ?? <String, dynamic>{})};
      fonts[fbName!] = PdfRef(newNum, 0);
      res['Font'] = fonts;
      final pageDict = {...pd.page.dict, 'Resources': res};
      objs[pd.page.ref.num] = pageDict;
    }
    return appendRevision(doc, objs);
  }

  String _freeFontName(_PageData pd) {
    final fonts = doc.dictOf(pd.page.resources['Font']) ?? {};
    int i = 0;
    while (fonts.containsKey('PTEfb$i')) {
      i++;
    }
    return 'PTEfb$i';
  }

  static String _numStr(double v) {
    var s = v.toStringAsFixed(4);
    s = s.replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '');
    return s;
  }
}
