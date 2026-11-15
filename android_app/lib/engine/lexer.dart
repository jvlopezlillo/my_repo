import 'dart:convert';
import 'dart:typed_data';
import 'objects.dart';

bool _isWs(int c) => c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
bool _isDelim(int c) =>
    c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E || c == 0x5B || c == 0x5D ||
    c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25;
bool _isDigit(int c) => c >= 0x30 && c <= 0x39;

class Lexer {
  final Uint8List b;
  int pos;
  Lexer(this.b, [this.pos = 0]);

  bool get eof => pos >= b.length;

  void skipWs() {
    while (pos < b.length) {
      final c = b[pos];
      if (_isWs(c)) {
        pos++;
      } else if (c == 0x25) {
        while (pos < b.length && b[pos] != 10 && b[pos] != 13) {
          pos++;
        }
      } else {
        break;
      }
    }
  }

  /// Lee el siguiente objeto. Devuelve [PdfKeyword] para palabras clave.
  /// Devuelve `null` también para el literal `null`; usa [eof] para distinguir fin.
  dynamic readObject({bool refs = true}) {
    skipWs();
    if (eof) return null;
    final c = b[pos];
    if (c == 0x2F) return _readName();
    if (c == 0x28) return _readLiteralString();
    if (c == 0x3C) {
      if (pos + 1 < b.length && b[pos + 1] == 0x3C) return _readDict(refs);
      return _readHexString();
    }
    if (c == 0x5B) return _readArray(refs);
    if (c == 0x5D || c == 0x3E || c == 0x29 || c == 0x7B || c == 0x7D) {
      pos++;
      return PdfKeyword(String.fromCharCode(c));
    }
    final tok = _readToken();
    final n = _tryNumber(tok);
    if (n != null) {
      if (refs && n is int && n >= 0) {
        final save = pos;
        skipWs();
        if (pos < b.length && _isDigit(b[pos])) {
          final t2 = _readToken();
          final g = int.tryParse(t2);
          if (g != null) {
            skipWs();
            if (pos < b.length && b[pos] == 0x52 /* R */ &&
                (pos + 1 >= b.length || _isWs(b[pos + 1]) || _isDelim(b[pos + 1]))) {
              pos++;
              return PdfRef(n, g);
            }
          }
        }
        pos = save;
      }
      return n;
    }
    if (tok == 'true') return true;
    if (tok == 'false') return false;
    if (tok == 'null') return null;
    return PdfKeyword(tok);
  }

  String _readToken() {
    final s = pos;
    while (pos < b.length && !_isWs(b[pos]) && !_isDelim(b[pos])) {
      pos++;
    }
    if (pos == s) pos++; // evita bucles infinitos con bytes raros
    return latin1.decode(b.sublist(s, pos));
  }

  num? _tryNumber(String t) {
    if (t.isEmpty) return null;
    final c = t.codeUnitAt(0);
    if (!(_isDigit(c) || c == 0x2B || c == 0x2D || c == 0x2E)) return null;
    final i = int.tryParse(t);
    if (i != null) return i;
    final d = double.tryParse(t);
    if (d != null) return d;
    // casos raros como "--5" o "1.2.3": se trunca
    final m = RegExp(r'^[+-]*\d*\.?\d*').firstMatch(t);
    final s = m?.group(0)?.replaceAll(RegExp(r'^[+-]+'), t.startsWith('-') ? '-' : '');
    return s == null || s.isEmpty ? null : (double.tryParse(s) ?? 0);
  }

  PdfName _readName() {
    pos++; // '/'
    final out = <int>[];
    while (pos < b.length && !_isWs(b[pos]) && !_isDelim(b[pos])) {
      if (b[pos] == 0x23 && pos + 2 < b.length) {
        final h = int.tryParse(String.fromCharCodes(b.sublist(pos + 1, pos + 3)), radix: 16);
        if (h != null) {
          out.add(h);
          pos += 3;
          continue;
        }
      }
      out.add(b[pos++]);
    }
    return PdfName(latin1.decode(out));
  }

  PdfString _readLiteralString() {
    pos++;
    final out = <int>[];
    int depth = 1;
    while (pos < b.length) {
      final c = b[pos++];
      if (c == 0x5C) {
        if (pos >= b.length) break;
        final e = b[pos++];
        switch (e) {
          case 0x6E: out.add(10); break;
          case 0x72: out.add(13); break;
          case 0x74: out.add(9); break;
          case 0x62: out.add(8); break;
          case 0x66: out.add(12); break;
          case 0x0D:
            if (pos < b.length && b[pos] == 0x0A) pos++;
            break;
          case 0x0A: break;
          default:
            if (e >= 0x30 && e <= 0x37) {
              int v = e - 0x30;
              for (int i = 0; i < 2 && pos < b.length && b[pos] >= 0x30 && b[pos] <= 0x37; i++) {
                v = v * 8 + (b[pos++] - 0x30);
              }
              out.add(v & 255);
            } else {
              out.add(e);
            }
        }
      } else if (c == 0x28) {
        depth++;
        out.add(c);
      } else if (c == 0x29) {
        depth--;
        if (depth == 0) break;
        out.add(c);
      } else {
        out.add(c);
      }
    }
    return PdfString(Uint8List.fromList(out));
  }

  PdfString _readHexString() {
    pos++;
    final digits = <int>[];
    while (pos < b.length && b[pos] != 0x3E) {
      final c = b[pos++];
      final v = int.tryParse(String.fromCharCode(c), radix: 16);
      if (v != null) digits.add(v);
    }
    pos++;
    if (digits.length.isOdd) digits.add(0);
    final out = Uint8List(digits.length ~/ 2);
    for (int i = 0; i < out.length; i++) {
      out[i] = digits[2 * i] * 16 + digits[2 * i + 1];
    }
    return PdfString(out);
  }

  List<dynamic> _readArray(bool refs) {
    pos++;
    final out = <dynamic>[];
    while (true) {
      skipWs();
      if (eof) break;
      if (b[pos] == 0x5D) {
        pos++;
        break;
      }
      final o = readObject(refs: refs);
      if (o is PdfKeyword && (o.word == 'endobj' || o.word == 'endstream')) break;
      out.add(o);
    }
    return out;
  }

  Map<String, dynamic> _readDict(bool refs) {
    pos += 2;
    final out = <String, dynamic>{};
    while (true) {
      skipWs();
      if (eof) break;
      if (b[pos] == 0x3E && pos + 1 < b.length && b[pos + 1] == 0x3E) {
        pos += 2;
        break;
      }
      final k = readObject(refs: refs);
      if (k is! PdfName) {
        if (k is PdfKeyword && (k.word == 'endobj' || k.word == 'stream')) break;
        continue;
      }
      skipWs();
      if (!eof && b[pos] == 0x3E && pos + 1 < b.length && b[pos + 1] == 0x3E) {
        out[k.name] = null;
        continue;
      }
      out[k.name] = readObject(refs: refs);
    }
    return out;
  }
}
