import 'dart:convert';
import 'dart:typed_data';
import 'document.dart';
import 'objects.dart';

/// Interpreta un CMap ToUnicode: código -> texto.
Map<int, String> parseToUnicode(Uint8List data) {
  final s = latin1.decode(data);
  final out = <int, String>{};
  String u(String hex) {
    final units = <int>[];
    for (int i = 0; i + 3 < hex.length + 1; i += 4) {
      final chunk = hex.substring(i, i + 4 > hex.length ? hex.length : i + 4);
      units.add(int.parse(chunk.padRight(4, '0'), radix: 16));
    }
    return String.fromCharCodes(units);
  }

  for (final blk in RegExp(r'beginbfchar(.*?)endbfchar', dotAll: true).allMatches(s)) {
    for (final m in RegExp(r'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>').allMatches(blk.group(1)!)) {
      out[int.parse(m.group(1)!, radix: 16)] = u(m.group(2)!);
    }
  }
  for (final blk in RegExp(r'beginbfrange(.*?)endbfrange', dotAll: true).allMatches(s)) {
    final re = RegExp(r'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(<[0-9A-Fa-f]+>|\[[^\]]*\])');
    for (final m in re.allMatches(blk.group(1)!)) {
      final lo = int.parse(m.group(1)!, radix: 16);
      final hi = int.parse(m.group(2)!, radix: 16);
      final dst = m.group(3)!;
      if (dst.startsWith('[')) {
        int i = 0;
        for (final h in RegExp(r'<([0-9A-Fa-f]+)>').allMatches(dst)) {
          out[lo + i++] = u(h.group(1)!);
        }
      } else {
        final base = u(dst.substring(1, dst.length - 1));
        if (base.isEmpty) continue;
        final head = base.substring(0, base.length - 1);
        final last = base.codeUnitAt(base.length - 1);
        for (int i = 0; i <= hi - lo; i++) {
          out[lo + i] = head + String.fromCharCode(last + i);
        }
      }
    }
  }
  return out;
}

// WinAnsi (cp1252) 0x80..0x9F
const _cp1252 = <int, int>{
  0x80: 0x20AC, 0x82: 0x201A, 0x83: 0x0192, 0x84: 0x201E, 0x85: 0x2026, 0x86: 0x2020,
  0x87: 0x2021, 0x88: 0x02C6, 0x89: 0x2030, 0x8A: 0x0160, 0x8B: 0x2039, 0x8C: 0x0152,
  0x8E: 0x017D, 0x91: 0x2018, 0x92: 0x2019, 0x93: 0x201C, 0x94: 0x201D, 0x95: 0x2022,
  0x96: 0x2013, 0x97: 0x2014, 0x98: 0x02DC, 0x99: 0x2122, 0x9A: 0x0161, 0x9B: 0x203A,
  0x9C: 0x0153, 0x9E: 0x017E, 0x9F: 0x0178,
};

int? winAnsiCode(String ch) {
  final u = ch.codeUnitAt(0);
  if (u < 0x80 || (u >= 0xA0 && u <= 0xFF)) return u;
  for (final e in _cp1252.entries) {
    if (e.value == u) return e.key;
  }
  return null;
}

String winAnsiChar(int code) => String.fromCharCode(_cp1252[code] ?? code);

class FontGlyph {
  final int code;
  final String text;
  final double width; // en milésimas de em
  const FontGlyph(this.code, this.text, this.width);
}

class PdfFont {
  final String resName;
  final PdfRef? ref;
  final Map<String, dynamic> dict;
  String baseFont = '';
  bool twoByte = false;
  bool embedded = false;
  bool hasToUnicode = false;
  bool bold = false, italic = false, serif = false, mono = false;
  final Map<int, String> toUni = {};
  final Map<String, int> uniToCode = {};
  /// Todos los códigos que dan el mismo carácter (p. ej. un «9» normal y otro negrita).
  final Map<String, List<int>> candidates = {};
  final Map<int, double> widths = {};
  double defaultWidth = 500;
  bool supported = false;

  PdfFont(PdfDoc doc, this.resName, this.ref, this.dict) {
    try {
      _load(doc);
    } catch (_) {
      supported = false;
    }
  }

  String get displayName => baseFont.replaceFirst(RegExp(r'^[A-Z]{6}\+'), '');

  void _load(PdfDoc doc) {
    final subtype = (doc.resolve(dict['Subtype']) as PdfName?)?.name ?? '';
    baseFont = (doc.resolve(dict['BaseFont']) as PdfName?)?.name ?? '';
    final tu = doc.resolve(dict['ToUnicode']);
    if (tu is PdfStream) {
      toUni.addAll(parseToUnicode(doc.decodeStream(tu)));
      hasToUnicode = toUni.isNotEmpty;
    }
    Map<String, dynamic>? desc;
    if (subtype == 'Type0') {
      final enc = doc.resolve(dict['Encoding']);
      if (enc is! PdfName || (enc.name != 'Identity-H')) {
        supported = false;
        return;
      }
      twoByte = true;
      final df = doc.resolve(dict['DescendantFonts']);
      final d0 = doc.dictOf(df is List ? df.first : df);
      if (d0 == null) return;
      desc = doc.dictOf(d0['FontDescriptor']);
      final dw = doc.resolve(d0['DW']);
      defaultWidth = dw is num ? dw.toDouble() : 1000;
      _parseW(doc, doc.resolve(d0['W']));
    } else if (subtype == 'TrueType' || subtype == 'Type1' || subtype == 'MMType1') {
      desc = doc.dictOf(dict['FontDescriptor']);
      final fc = (doc.resolve(dict['FirstChar']) as num?)?.toInt() ?? 0;
      final w = doc.resolve(dict['Widths']);
      if (w is List) {
        for (int i = 0; i < w.length; i++) {
          final v = doc.resolve(w[i]);
          if (v is num) widths[fc + i] = v.toDouble();
        }
      }
      final mw = desc == null ? null : doc.resolve(desc['MissingWidth']);
      defaultWidth = mw is num ? mw.toDouble() : (w is List ? 0 : 556);
      if (!hasToUnicode) _simpleEncoding(doc);
    } else {
      supported = false;
      return;
    }
    if (desc != null) {
      embedded = desc.containsKey('FontFile') || desc.containsKey('FontFile2') || desc.containsKey('FontFile3');
      final fl = (doc.resolve(desc['Flags']) as num?)?.toInt() ?? 0;
      mono = fl & 1 != 0;
      serif = fl & 2 != 0;
      italic = fl & 64 != 0;
      bold = fl & 262144 != 0;
    }
    final n = displayName.toLowerCase();
    if (n.contains('bold') || n.contains('black') || n.contains('heavy') || RegExp(r'[-,]bd').hasMatch(n)) bold = true;
    if (n.contains('italic') || n.contains('oblique') || RegExp(r'[-,]it($|[^a-z])').hasMatch(n)) italic = true;
    if (n.contains('times') || n.contains('serif') && !n.contains('sans')) serif = true;
    if (n.contains('courier') || n.contains('mono')) mono = true;
    for (final e in toUni.entries) {
      if (e.value.length == 1 && e.value != '�') {
        uniToCode.putIfAbsent(e.value, () => e.key);
        candidates.putIfAbsent(e.value, () => []).add(e.key);
      }
    }
    if (!twoByte) {
      uniToCode.removeWhere((_, c) => c > 255);
      for (final l in candidates.values) {
        l.removeWhere((c) => c > 255);
      }
      candidates.removeWhere((_, l) => l.isEmpty);
    }
    supported = uniToCode.isNotEmpty;
  }

  void _simpleEncoding(PdfDoc doc) {
    // Sin ToUnicode: codificación WinAnsi/estándar (Latin-1) y solo códigos con ancho conocido.
    for (int c = 32; c < 256; c++) {
      final known = widths.isEmpty || (widths[c] ?? 0) > 0;
      if (known) toUni[c] = winAnsiChar(c);
    }
  }

  void _parseW(PdfDoc doc, dynamic w) {
    if (w is! List) return;
    int i = 0;
    while (i < w.length) {
      final a = doc.resolve(w[i]);
      if (a is! num) {
        i++;
        continue;
      }
      if (i + 1 < w.length && doc.resolve(w[i + 1]) is List) {
        final l = doc.resolve(w[i + 1]) as List;
        for (int k = 0; k < l.length; k++) {
          final v = doc.resolve(l[k]);
          if (v is num) widths[a.toInt() + k] = v.toDouble();
        }
        i += 2;
      } else if (i + 2 < w.length) {
        final b = doc.resolve(w[i + 1]);
        final v = doc.resolve(w[i + 2]);
        if (b is num && v is num) {
          for (int c = a.toInt(); c <= b.toInt(); c++) {
            widths[c] = v.toDouble();
          }
        }
        i += 3;
      } else {
        break;
      }
    }
  }

  double widthOf(int code) => widths[code] ?? defaultWidth;

  /// Decodifica los bytes de una cadena en glifos.
  List<FontGlyph> decode(Uint8List s) {
    final out = <FontGlyph>[];
    if (twoByte) {
      for (int i = 0; i + 1 < s.length; i += 2) {
        final c = (s[i] << 8) | s[i + 1];
        out.add(FontGlyph(c, toUni[c] ?? '�', widthOf(c)));
      }
    } else {
      for (final c in s) {
        out.add(FontGlyph(c, toUni[c] ?? String.fromCharCode(c), widthOf(c)));
      }
    }
    return out;
  }

  bool canEncode(String ch) => uniToCode.containsKey(ch);

  /// Cadena hexadecimal con los códigos de [text] (todos deben ser codificables).
  String encodeHex(String text, [Map<String, int>? prefer]) {
    final n = twoByte ? 4 : 2;
    final sb = StringBuffer();
    for (final r in text.runes) {
      final ch = String.fromCharCode(r);
      final code = prefer?[ch] ?? uniToCode[ch]!;
      sb.write(code.toRadixString(16).toUpperCase().padLeft(n, '0'));
    }
    return sb.toString();
  }

  double textWidth(String text) {
    double w = 0;
    for (final r in text.runes) {
      final c = uniToCode[String.fromCharCode(r)];
      w += c == null ? defaultWidth : widthOf(c);
    }
    return w;
  }
}
