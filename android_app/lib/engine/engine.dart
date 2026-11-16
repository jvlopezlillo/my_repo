import 'dart:convert';
import 'dart:typed_data';
import 'content.dart';
import 'document.dart';
import 'fonts.dart';
import 'objects.dart';
import 'std_widths.dart';
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
  final int fieldNum; // >0: campo de formulario (nº de objeto del widget)
  const SpanInfo(this.page, this.runIndex, this.text, this.x0, this.y0, this.x1, this.y1,
      this.fontName, this.size, this.bold, this.italic, this.horizontal, this.color, this.source,
      [this.fieldNum = 0]);

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
    final fields = _fieldSpans(page);
    if (fields.isEmpty) return out;
    out.removeWhere((sp) {
      final cx = (sp.x0 + sp.x1) / 2, cy = (sp.y0 + sp.y1) / 2;
      return fields.any((f) => cx >= f.x0 && cx <= f.x1 && cy >= f.y0 && cy <= f.y1);
    });
    return [...out, ...fields];
  }

  // ---------------------------------------------------------------- formularios
  Map<String, dynamic>? _acroFormDr() {
    final root = doc.dictOf(doc.trailer['Root']);
    final af = doc.dictOf(root?['AcroForm']);
    return doc.dictOf(af?['DR']);
  }

  /// Campos de texto de una línea con valor.
  List<SpanInfo> _fieldSpans(int page) {
    final p = _pages[page];
    final annots = doc.resolve(p.dict['Annots']);
    if (annots is! List) return [];
    final b = p.box;
    final out = <SpanInfo>[];
    for (final a in annots) {
      if (a is! PdfRef) continue;
      final d = doc.dictOf(a);
      if (d == null || doc.resolve(d['Subtype']) != const PdfName('Widget')) continue;
      final inh = _inheritedField(d);
      if ((doc.resolve(inh['FT']) as PdfName?)?.name != 'Tx') continue;
      final ff = (doc.resolve(inh['Ff']) as num?)?.toInt() ?? 0;
      if (ff & (1 << 12) != 0 || ff & (1 << 24) != 0) continue; // multilínea / comb
      final v = _pdfTextString(doc.resolve(inh['V']));
      if (v == null || v.isEmpty) continue;
      final ap = doc.resolve(doc.dictOf(d['AP'])?['N']);
      if (ap is! PdfStream) continue;
      final info = _apInfo(ap);
      if (info == null) continue;
      final r = (doc.resolve(d['Rect']) as List).map((e) => (doc.resolve(e) as num).toDouble()).toList();
      final x0 = r[0] < r[2] ? r[0] : r[2], x1 = r[0] < r[2] ? r[2] : r[0];
      final y0 = r[1] < r[3] ? r[1] : r[3], y1 = r[1] < r[3] ? r[3] : r[1];
      final n = info.name.toLowerCase();
      out.add(SpanInfo(
        page, -1, v, x0 - b[0], b[3] - y1, x1 - b[0], b[3] - y0, info.name, info.size,
        n.contains('bold') || n.endsWith('bo') || n.contains('bd'),
        n.contains('ital') || n.contains('obli') || n.endsWith('it') || n.endsWith('ob'),
        true, info.color, 'campo de formulario (se conserva su fuente y tamaño)', a.num));
    }
    return out;
  }

  Map<String, dynamic> _inheritedField(Map<String, dynamic> d) {
    final m = <String, dynamic>{};
    var cur = d;
    int guard = 0;
    while (guard++ < 8) {
      for (final k in ['FT', 'Ff', 'V']) {
        if (cur[k] != null) m.putIfAbsent(k, () => cur[k]);
      }
      final par = doc.dictOf(cur['Parent']);
      if (par == null) break;
      cur = par;
    }
    return m;
  }

  static String? _pdfTextString(dynamic v) {
    if (v is! PdfString) return null;
    final b = v.bytes;
    if (b.length >= 2 && b[0] == 0xFE && b[1] == 0xFF) {
      final u = <int>[];
      for (int i = 2; i + 1 < b.length; i += 2) {
        u.add((b[i] << 8) | b[i + 1]);
      }
      return String.fromCharCodes(u);
    }
    return String.fromCharCodes(b.map((c) => c >= 0x80 && c <= 0x9F ? (winAnsiChar(c).codeUnitAt(0)) : c));
  }

  ({String name, double size, List<double> color})? _apInfo(PdfStream ap) {
    final data = latin1.decode(doc.decodeStream(ap));
    final tfs = RegExp(r'/(\S+)\s+([\d.]+)\s+Tf').allMatches(data).toList();
    if (tfs.isEmpty) return null;
    var name = tfs.last.group(1)!;
    final size = double.parse(tfs.last.group(2)!);
    final dr = _acroFormDr();
    final f = doc.dictOf(doc.dictOf(dr?['Font'])?[name]);
    final bf = f == null ? null : doc.resolve(f['BaseFont']);
    if (bf is PdfName) name = bf.name;
    final m = RegExp(r'([\d.]+)\s+([\d.]+)\s+([\d.]+)\s+rg').firstMatch(data);
    final col = m == null ? [0.0, 0.0, 0.0] : [for (int i = 1; i <= 3; i++) double.parse(m.group(i)!)];
    return (name: name, size: size, color: col);
  }

  static double _stdTextWidth(String text, bool bold, bool serif, bool mono, double size) {
    if (mono) return text.length * 0.6 * size;
    final t = stdWidths[serif ? (bold ? 'tibo' : 'tiro') : (bold ? 'hebo' : 'helv')]!;
    double w = 0;
    for (final r in text.runes) {
      final c = winAnsiCode(String.fromCharCode(r));
      w += (c != null && c >= 32 && c < 256) ? t[c - 32] : 500;
    }
    return w * size / 1000;
  }

  Uint8List _applyField(EditCheck chk) {
    final s = chk.span;
    final ref = PdfRef(s.fieldNum, doc.genOf(s.fieldNum));
    final w = doc.dictOf(ref)!;
    final ap = doc.resolve(doc.dictOf(w['AP'])!['N']) as PdfStream;
    final apRef = (doc.dictOf(w['AP'])!['N']) as PdfRef;
    var data = latin1.decode(doc.decodeStream(ap));
    // anchura disponible (BBox) y tamaño: solo se reduce si el texto no cabe
    final bbox = doc.resolve(ap.dict['BBox']);
    final bw = bbox is List ? (doc.resolve(bbox[2]) as num).toDouble() - (doc.resolve(bbox[0]) as num).toDouble() : 1e9;
    final n = s.fontName.toLowerCase();
    final serif = n.contains('times') || n.startsWith('ti');
    final mono = n.contains('cour') || n.startsWith('co');
    var size = s.size;
    final width = _stdTextWidth(chk.newText, s.bold, serif, mono, size);
    final avail = bw - 4;
    if (width > avail && avail > 0) size = (size * avail / width).clamp(4.0, size);
    final esc = StringBuffer();
    for (final r in chk.newText.runes) {
      final c = winAnsiCode(String.fromCharCode(r))!;
      if (c == 0x28 || c == 0x29 || c == 0x5C) {
        esc.write('\\${String.fromCharCode(c)}');
      } else if (c < 32 || c > 126) {
        esc.write('\\${c.toRadixString(8).padLeft(3, '0')}');
      } else {
        esc.write(String.fromCharCode(c));
      }
    }
    final tj = RegExp(r'\((?:\\.|[^\\)])*\)\s*Tj').allMatches(data).toList();
    if (tj.isEmpty) throw PdfEditException('El campo tiene una estructura no soportada.');
    data = data.substring(0, tj.first.start) + '($esc) Tj' + data.substring(tj.last.end);
    if (size != s.size) {
      final tfs = RegExp(r'/(\S+)\s+([\d.]+)\s+Tf').allMatches(data).toList();
      final m = tfs.last;
      data = data.substring(0, m.start) + '/${m.group(1)} ${_numStr(size)} Tf' + data.substring(m.end);
    }
    final value = PdfString(Uint8List.fromList([0xFE, 0xFF, for (final u in chk.newText.codeUnits) ...[u >> 8, u & 255]]));
    final objs = <int, dynamic>{
      apRef.num: makeStream(ap.dict, Uint8List.fromList(latin1.encode(data))),
      ref.num: {...w, 'V': value},
    };
    final par = w['Parent'];
    if (par is PdfRef) {
      final pd = doc.dictOf(par);
      if (pd != null && pd['V'] != null) objs[par.num] = {...pd, 'V': value};
    }
    return appendRevision(doc, objs);
  }

  EditCheck check(SpanInfo s, String newText) {
    if (s.fieldNum > 0) {
      final bad = <String>{};
      for (final r in newText.runes) {
        final ch = String.fromCharCode(r);
        if (ch.trim().isNotEmpty && winAnsiCode(ch) == null) bad.add(ch);
      }
      return EditCheck(s, newText, bad.toList()..sort(), bad.toList()..sort());
    }
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
    if (chk.span.fieldNum > 0) {
      if (!chk.ok) {
        throw PdfEditException('Un campo de formulario solo admite caracteres Latin-1: ${chk.missing.join(' ')}');
      }
      return _applyField(chk);
    }
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

    final prefer = _chooseCodes(chk.span.page, run, chk.newText);
    for (final r in chk.newText.runes) {
      final ch = String.fromCharCode(r);
      if (f.canEncode(ch)) {
        flushFallback();
        nativeHex += f.encodeHex(ch, prefer);
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

  /// Cuando varios códigos dan el mismo carácter (fuentes que mezclan pesos), elige el
  /// del propio texto y, si no está, el que más se usa junto a los glifos de este texto.
  Map<String, int> _chooseCodes(int page, TextRun run, String text) {
    final f = run.font;
    final out = <String, int>{};
    final own = <String, int>{};
    for (final g in f.decode(_runBytes(run))) {
      own.putIfAbsent(g.text, () => g.code);
    }
    List<TextRun>? all;
    for (final r in text.runes) {
      final ch = String.fromCharCode(r);
      if (out.containsKey(ch)) continue;
      final cands = f.candidates[ch];
      if (cands == null || cands.length < 2) continue;
      if (own.containsKey(ch)) {
        out[ch] = own[ch]!;
        continue;
      }
      all ??= [for (int i = 0; i < pageCount; i++) if (pageSupported(i)) ..._data(i).runs.where((x) => identical(x.font.dict, f.dict) || x.font.baseFont == f.baseFont)];
      int best = -1, bestCode = cands.first;
      for (final c in cands) {
        int score = 0;
        for (final x in all) {
          if (identical(x, run) || !x.codes.contains(c)) continue;
          score += x.codes.intersection(run.codes).length;
        }
        if (score > best) {
          best = score;
          bestCode = c;
        }
      }
      out[ch] = bestCode;
    }
    return out;
  }

  Uint8List _runBytes(TextRun run) {
    final b = BytesBuilder();
    for (final a in run.op.args) {
      if (a is PdfString) {
        b.add(a.bytes);
      } else if (a is List) {
        for (final e in a) {
          if (e is PdfString) b.add(e.bytes);
        }
      }
    }
    return b.toBytes();
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
