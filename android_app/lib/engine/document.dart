import 'dart:convert';
import 'dart:io' show zlib;
import 'dart:typed_data';
import 'lexer.dart';
import 'objects.dart';

class _XEntry {
  final int type; // 0 libre, 1 offset, 2 dentro de objstm
  final int a; // offset o nº de objstm
  final int b; // gen o índice
  const _XEntry(this.type, this.a, this.b);
}

class PdfDoc {
  final Uint8List bytes;
  final Map<int, _XEntry> _xref = {};
  final Map<int, dynamic> _cache = {};
  final Map<int, Map<int, dynamic>> _objStmCache = {};
  Map<String, dynamic> trailer = {};
  bool usesXrefStream = false;
  int lastStartXref = 0;
  bool rebuilt = false;

  PdfDoc(this.bytes) {
    try {
      _readXrefChain();
    } catch (_) {
      _xref.clear();
      _cache.clear();
    }
    if (_xref.isEmpty || trailer['Root'] == null) {
      _rebuild();
    }
    if (trailer.containsKey('Encrypt')) {
      throw PdfEditException('El PDF está cifrado: aún no soportado en Android.');
    }
  }

  int genOf(int n) {
    final e = _xref[n];
    return (e != null && e.type == 1) ? e.b : 0;
  }

  /// Desplazamientos de todos los objetos "normales" (para reconstruir una xref completa).
  Map<int, int> allOffsets() => {
        for (final e in _xref.entries)
          if (e.value.type == 1) e.key: e.value.a
      };

  int get size => (trailer['Size'] as num?)?.toInt() ?? (_xref.keys.fold<int>(0, (a, b) => a > b ? a : b) + 1);

  // ---------------------------------------------------------------- xref
  void _readXrefChain() {
    final tail = latin1.decode(bytes.sublist(bytes.length > 2048 ? bytes.length - 2048 : 0));
    final i = tail.lastIndexOf('startxref');
    if (i < 0) throw PdfEditException('sin startxref');
    final off = int.parse(RegExp(r'\d+').firstMatch(tail.substring(i + 9))!.group(0)!);
    lastStartXref = off;
    final seen = <int>{};
    int? next = off;
    bool first = true;
    while (next != null && seen.add(next)) {
      final lx = Lexer(bytes, next);
      lx.skipWs();
      Map<String, dynamic> tr;
      if (_startsWith(lx.pos, 'xref')) {
        lx.pos += 4;
        tr = _readXrefTable(lx);
        final xs = tr['XRefStm'];
        if (xs is num) {
          _readXrefStream(xs.toInt());
        }
        if (first) usesXrefStream = false;
      } else {
        tr = _readXrefStream(lx.pos);
        if (first) usesXrefStream = true;
      }
      if (first) {
        trailer = tr;
        first = false;
      } else {
        for (final e in tr.entries) {
          trailer.putIfAbsent(e.key, () => e.value);
        }
      }
      final p = tr['Prev'];
      next = p is num ? p.toInt() : null;
    }
  }

  bool _startsWith(int p, String s) {
    for (int i = 0; i < s.length; i++) {
      if (p + i >= bytes.length || bytes[p + i] != s.codeUnitAt(i)) return false;
    }
    return true;
  }

  Map<String, dynamic> _readXrefTable(Lexer lx) {
    while (true) {
      lx.skipWs();
      if (_startsWith(lx.pos, 'trailer')) {
        lx.pos += 7;
        break;
      }
      final start = lx.readObject(refs: false);
      final count = lx.readObject(refs: false);
      if (start is! int || count is! int) throw PdfEditException('xref corrupta');
      for (int i = 0; i < count; i++) {
        final off = lx.readObject(refs: false);
        final gen = lx.readObject(refs: false);
        final t = lx.readObject(refs: false);
        if (off is! int || gen is! int || t is! PdfKeyword) throw PdfEditException('xref corrupta');
        final n = start + i;
        _xref.putIfAbsent(n, () => t.word == 'n' ? _XEntry(1, off, gen) : const _XEntry(0, 0, 0));
      }
    }
    final d = lx.readObject();
    if (d is! Map<String, dynamic>) throw PdfEditException('trailer inválido');
    return d;
  }

  Map<String, dynamic> _readXrefStream(int offset) {
    final obj = _readIndirectAt(offset);
    if (obj is! PdfStream) throw PdfEditException('xref stream inválido');
    final d = obj.dict;
    final data = decodeStream(obj);
    final w = (d['W'] as List).map((e) => (e as num).toInt()).toList();
    final size = (d['Size'] as num).toInt();
    final index = d['Index'] != null
        ? (d['Index'] as List).map((e) => (e as num).toInt()).toList()
        : [0, size];
    final rowLen = w[0] + w[1] + w[2];
    int p = 0;
    int field(int len) {
      int v = 0;
      for (int i = 0; i < len; i++) {
        v = (v << 8) | data[p++];
      }
      return v;
    }

    for (int s = 0; s + 1 < index.length; s += 2) {
      for (int k = 0; k < index[s + 1]; k++) {
        if (p + rowLen > data.length) break;
        final t = w[0] == 0 ? 1 : field(w[0]);
        final a = field(w[1]);
        final b = field(w[2]);
        _xref.putIfAbsent(index[s] + k, () => _XEntry(t, a, b));
      }
    }
    return d;
  }

  /// Reconstruye el índice escaneando "n g obj" (PDF dañado o sin xref válida).
  void _rebuild() {
    rebuilt = true;
    _xref.clear();
    _cache.clear();
    final s = latin1.decode(bytes);
    for (final m in RegExp(r'(?<![0-9])(\d+)\s+(\d+)\s+obj\b').allMatches(s)) {
      _xref[int.parse(m.group(1)!)] = _XEntry(1, m.start, int.parse(m.group(2)!));
    }
    for (final m in RegExp(r'trailer\s*<<').allMatches(s)) {
      final d = Lexer(bytes, m.end - 2).readObject();
      if (d is Map<String, dynamic> && d['Root'] != null) trailer = {...trailer, ...d};
    }
    if (trailer['Root'] == null) {
      // PDF con xref stream: buscar /Type /Catalog
      for (final e in _xref.keys.toList()) {
        final o = getObject(PdfRef(e, 0));
        final d = o is PdfStream ? o.dict : o;
        if (d is Map && d['Type'] == const PdfName('XRef') && d['Root'] != null) {
          trailer = {...trailer, ...d.cast<String, dynamic>()};
        }
      }
    }
    if (trailer['Root'] == null) throw PdfEditException('No es un PDF válido');
  }

  // ---------------------------------------------------------------- objetos
  dynamic _readIndirectAt(int offset) {
    final lx = Lexer(bytes, offset);
    final n = lx.readObject(refs: false);
    final g = lx.readObject(refs: false);
    final kw = lx.readObject(refs: false);
    if (n is! int || g is! int || kw is! PdfKeyword || kw.word != 'obj') {
      throw PdfEditException('objeto inválido en $offset');
    }
    final o = lx.readObject();
    if (o is Map<String, dynamic>) {
      lx.skipWs();
      if (_startsWith(lx.pos, 'stream')) {
        lx.pos += 6;
        if (lx.pos < bytes.length && bytes[lx.pos] == 13) lx.pos++;
        if (lx.pos < bytes.length && bytes[lx.pos] == 10) lx.pos++;
        final start = lx.pos;
        int? len;
        final l = o['Length'];
        if (l is num) {
          len = l.toInt();
        } else if (l is PdfRef) {
          final r = resolve(l);
          if (r is num) len = r.toInt();
        }
        bool ok = false;
        if (len != null && start + len <= bytes.length) {
          final l2 = Lexer(bytes, start + len)..skipWs();
          ok = _startsWith(l2.pos, 'endstream');
        }
        if (!ok) {
          // buscar endstream
          int e = _indexOf('endstream', start);
          if (e < 0) e = bytes.length;
          int end = e;
          if (end > start && bytes[end - 1] == 10) end--;
          if (end > start && bytes[end - 1] == 13) end--;
          len = end - start;
        }
        return PdfStream(o, Uint8List.sublistView(bytes, start, start + len!));
      }
    }
    return o;
  }

  int _indexOf(String s, int from) {
    final pat = s.codeUnits;
    for (int i = from; i <= bytes.length - pat.length; i++) {
      int j = 0;
      while (j < pat.length && bytes[i + j] == pat[j]) {
        j++;
      }
      if (j == pat.length) return i;
    }
    return -1;
  }

  dynamic getObject(PdfRef r) {
    if (_cache.containsKey(r.num)) return _cache[r.num];
    final e = _xref[r.num];
    dynamic o;
    if (e == null || e.type == 0) {
      o = null;
    } else if (e.type == 1) {
      try {
        o = _readIndirectAt(e.a);
      } catch (_) {
        o = null;
      }
    } else {
      o = _fromObjStm(e.a, r.num);
    }
    _cache[r.num] = o;
    return o;
  }

  dynamic _fromObjStm(int stmNum, int objNum) {
    var m = _objStmCache[stmNum];
    if (m == null) {
      m = {};
      final s = getObject(PdfRef(stmNum, 0));
      if (s is PdfStream) {
        final data = decodeStream(s);
        final n = (s.dict['N'] as num).toInt();
        final first = (s.dict['First'] as num).toInt();
        final lx = Lexer(data);
        final pairs = <List<int>>[];
        for (int i = 0; i < n; i++) {
          pairs.add([(lx.readObject(refs: false) as num).toInt(), (lx.readObject(refs: false) as num).toInt()]);
        }
        for (final p in pairs) {
          m[p[0]] = Lexer(data, first + p[1]).readObject();
        }
      }
      _objStmCache[stmNum] = m;
    }
    return m[objNum];
  }

  dynamic resolve(dynamic v) {
    int guard = 0;
    while (v is PdfRef && guard++ < 32) {
      v = getObject(v);
    }
    return v;
  }

  Map<String, dynamic>? dictOf(dynamic v) {
    v = resolve(v);
    if (v is PdfStream) return v.dict;
    if (v is Map<String, dynamic>) return v;
    return null;
  }

  // ---------------------------------------------------------------- streams
  Uint8List decodeStream(PdfStream s) {
    var data = s.raw;
    final f = resolve(s.dict['Filter']);
    final filters = f == null ? <dynamic>[] : (f is List ? f : [f]);
    final pr = resolve(s.dict['DecodeParms'] ?? s.dict['DP']);
    final parms = pr == null ? <dynamic>[] : (pr is List ? pr : [pr]);
    for (int i = 0; i < filters.length; i++) {
      final name = (resolve(filters[i]) as PdfName).name;
      final p = i < parms.length ? dictOf(parms[i]) : null;
      switch (name) {
        case 'FlateDecode':
        case 'Fl':
          data = _inflate(data);
          data = _predictor(data, p);
          break;
        case 'ASCIIHexDecode':
        case 'AHx':
          final hex = latin1.decode(data).replaceAll(RegExp(r'[^0-9A-Fa-f]'), '');
          final out = Uint8List(hex.length ~/ 2);
          for (int k = 0; k < out.length; k++) {
            out[k] = int.parse(hex.substring(2 * k, 2 * k + 2), radix: 16);
          }
          data = out;
          break;
        case 'ASCII85Decode':
        case 'A85':
          data = _ascii85(data);
          break;
        default:
          throw PdfEditException('Filtro no soportado: $name');
      }
    }
    return data;
  }

  Uint8List _ascii85(Uint8List d) {
    final out = <int>[];
    final grp = <int>[];
    int i = 0;
    if (d.length >= 2 && d[0] == 0x3C && d[1] == 0x7E) i = 2;
    for (; i < d.length; i++) {
      final c = d[i];
      if (c == 0x7E) break; // '~>'
      if (c == 0x7A && grp.isEmpty) {
        out.addAll([0, 0, 0, 0]);
        continue;
      }
      if (c < 0x21 || c > 0x75) continue;
      grp.add(c - 33);
      if (grp.length == 5) {
        int v = 0;
        for (final g in grp) {
          v = v * 85 + g;
        }
        out.addAll([(v >> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255]);
        grp.clear();
      }
    }
    if (grp.length > 1) {
      final n = grp.length;
      while (grp.length < 5) {
        grp.add(84);
      }
      int v = 0;
      for (final g in grp) {
        v = v * 85 + g;
      }
      final b4 = [(v >> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255];
      out.addAll(b4.sublist(0, n - 1));
    }
    return Uint8List.fromList(out);
  }

  Uint8List _inflate(Uint8List d) {
    try {
      return Uint8List.fromList(zlib.decode(d));
    } catch (_) {
      // flujo truncado/corrupto: intentar descomprimir lo posible
      final sink = <int>[];
      final conv = zlib.decoder.startChunkedConversion(
          ChunkedConversionSink<List<int>>.withCallback((chunks) {
        for (final c in chunks) {
          sink.addAll(c);
        }
      }));
      try {
        conv.add(d);
        conv.close();
      } catch (_) {}
      return Uint8List.fromList(sink);
    }
  }

  Uint8List _predictor(Uint8List d, Map<String, dynamic>? p) {
    final pred = (p?['Predictor'] as num?)?.toInt() ?? 1;
    if (pred < 10) return d;
    final cols = (p?['Columns'] as num?)?.toInt() ?? 1;
    final colors = (p?['Colors'] as num?)?.toInt() ?? 1;
    final bpc = (p?['BitsPerComponent'] as num?)?.toInt() ?? 8;
    final bpp = (colors * bpc + 7) ~/ 8;
    final row = (cols * colors * bpc + 7) ~/ 8;
    final rows = d.length ~/ (row + 1);
    final out = Uint8List(rows * row);
    for (int r = 0; r < rows; r++) {
      final ft = d[r * (row + 1)];
      final src = r * (row + 1) + 1;
      final dst = r * row;
      for (int i = 0; i < row; i++) {
        final x = d[src + i];
        final a = i >= bpp ? out[dst + i - bpp] : 0;
        final b = r > 0 ? out[dst - row + i] : 0;
        final c = (r > 0 && i >= bpp) ? out[dst - row + i - bpp] : 0;
        int v;
        switch (ft) {
          case 1: v = x + a; break;
          case 2: v = x + b; break;
          case 3: v = x + ((a + b) >> 1); break;
          case 4:
            final pp = a + b - c;
            final pa = (pp - a).abs(), pb = (pp - b).abs(), pc = (pp - c).abs();
            v = x + ((pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c));
            break;
          default: v = x;
        }
        out[dst + i] = v & 255;
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- páginas
  List<PdfPageInfo> pages() {
    final root = dictOf(trailer['Root']);
    final out = <PdfPageInfo>[];
    final seen = <int>{};
    void walk(dynamic node, Map<String, dynamic> inh, PdfRef? ref) {
      final d = dictOf(node);
      if (d == null) return;
      if (ref != null && !seen.add(ref.num)) return;
      final inherited = {...inh};
      for (final k in ['Resources', 'MediaBox', 'CropBox', 'Rotate']) {
        if (d[k] != null) inherited[k] = d[k];
      }
      final kids = resolve(d['Kids']);
      if (kids is List && d['Type'] != const PdfName('Page')) {
        for (final k in kids) {
          walk(k, inherited, k is PdfRef ? k : null);
        }
      } else if (ref != null) {
        out.add(PdfPageInfo(ref, d, inherited, this));
      }
    }
    if (root != null) walk(root['Pages'], {}, null);
    return out;
  }
}

class PdfPageInfo {
  final PdfRef ref;
  final Map<String, dynamic> dict;
  final Map<String, dynamic> inherited;
  final PdfDoc doc;
  PdfPageInfo(this.ref, this.dict, this.inherited, this.doc);

  List<double> _box(String key) {
    final v = doc.resolve(inherited[key]);
    if (v is List && v.length >= 4) {
      final n = v.map((e) => (doc.resolve(e) as num).toDouble()).toList();
      return [
        n[0] < n[2] ? n[0] : n[2],
        n[1] < n[3] ? n[1] : n[3],
        n[0] < n[2] ? n[2] : n[0],
        n[1] < n[3] ? n[3] : n[1]
      ];
    }
    return [];
  }

  /// Caja visible [x0, y0, x1, y1] en coordenadas PDF.
  List<double> get box {
    final c = _box('CropBox');
    if (c.isNotEmpty) return c;
    final m = _box('MediaBox');
    return m.isNotEmpty ? m : [0, 0, 612, 792];
  }

  int get rotate => ((doc.resolve(inherited['Rotate']) as num?)?.toInt() ?? 0) % 360;

  Map<String, dynamic> get resources => doc.dictOf(inherited['Resources']) ?? {};

  /// Referencias a los content streams de la página, en orden.
  List<PdfRef> get contentRefs {
    final c = dict['Contents'];
    final r = doc.resolve(c);
    if (r is List) return r.whereType<PdfRef>().toList();
    if (c is PdfRef) return [c];
    return [];
  }
}
