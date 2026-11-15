import 'dart:convert';
import 'dart:io' show zlib;
import 'dart:typed_data';
import 'document.dart';
import 'objects.dart';

String _num(num v) {
  if (v is int) return '$v';
  var s = v.toStringAsFixed(6);
  if (s.contains('.')) s = s.replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '');
  return s == '-0' ? '0' : s;
}

void serialize(BytesBuilder b, dynamic v) {
  void w(String s) => b.add(latin1.encode(s));
  if (v == null) {
    w('null');
  } else if (v is bool) {
    w(v ? 'true' : 'false');
  } else if (v is num) {
    w(_num(v));
  } else if (v is PdfName) {
    final sb = StringBuffer('/');
    for (final c in latin1.encode(v.name)) {
      final reg = c > 32 && c < 127 && !'()<>[]{}/%#'.contains(String.fromCharCode(c));
      sb.write(reg ? String.fromCharCode(c) : '#${c.toRadixString(16).toUpperCase().padLeft(2, '0')}');
    }
    w(sb.toString());
  } else if (v is PdfRef) {
    w('${v.num} ${v.gen} R');
  } else if (v is PdfString) {
    final sb = StringBuffer('<');
    for (final c in v.bytes) {
      sb.write(c.toRadixString(16).toUpperCase().padLeft(2, '0'));
    }
    sb.write('>');
    w(sb.toString());
  } else if (v is List) {
    w('[');
    for (int i = 0; i < v.length; i++) {
      if (i > 0) w(' ');
      serialize(b, v[i]);
    }
    w(']');
  } else if (v is Map) {
    w('<<');
    for (final e in v.entries) {
      serialize(b, PdfName(e.key as String));
      w(' ');
      serialize(b, e.value);
      w('\n');
    }
    w('>>');
  } else {
    throw PdfEditException('No se puede serializar ${v.runtimeType}');
  }
}

/// Crea un stream FlateDecode.
PdfStream makeStream(Map<String, dynamic> base, Uint8List data) {
  final z = Uint8List.fromList(zlib.encode(data));
  final d = {...base}
    ..remove('DecodeParms')
    ..remove('DP')
    ..remove('DL');
  d['Filter'] = const PdfName('FlateDecode');
  d['Length'] = z.length;
  return PdfStream(d, z);
}

/// Añade una actualización incremental con los objetos [objs] (número -> objeto/PdfStream).
Uint8List appendRevision(PdfDoc doc, Map<int, dynamic> objs) {
  final out = BytesBuilder();
  out.add(doc.bytes);
  if (doc.bytes.isNotEmpty && doc.bytes.last != 10) out.add([10]);
  final offsets = <int, int>{};
  final nums = objs.keys.toList()..sort();
  for (final n in nums) {
    offsets[n] = out.length;
    out.add(latin1.encode('$n ${doc.genOf(n)} obj\n'));
    final o = objs[n];
    if (o is PdfStream) {
      serialize(out, o.dict);
      out.add(latin1.encode('\nstream\n'));
      out.add(o.raw);
      out.add(latin1.encode('\nendstream'));
    } else {
      serialize(out, o);
    }
    out.add(latin1.encode('\nendobj\n'));
  }
  final xrefOff = out.length;
  final tr = <String, dynamic>{};
  for (final k in ['Root', 'Info', 'ID']) {
    if (doc.trailer[k] != null) tr[k] = doc.trailer[k];
  }
  var size = doc.size;
  for (final n in nums) {
    if (n + 1 > size) size = n + 1;
  }
  if (!doc.usesXrefStream) {
    final sb = StringBuffer('xref\n');
    if (doc.rebuilt) {
      final all = doc.allOffsets();
      for (final e in offsets.entries) {
        all[e.key] = e.value;
      }
      final keys = all.keys.toList()..sort();
      sb.write('0 1\n0000000000 65535 f \n');
      for (final n in keys) {
        sb.write('$n 1\n${all[n]!.toString().padLeft(10, '0')} 00000 n \n');
      }
    } else {
      for (final n in nums) {
        sb.write('$n 1\n${offsets[n]!.toString().padLeft(10, '0')} ${doc.genOf(n).toString().padLeft(5, '0')} n \n');
      }
    }
    tr['Size'] = size;
    if (!doc.rebuilt) tr['Prev'] = doc.lastStartXref;
    out.add(latin1.encode(sb.toString()));
    out.add(latin1.encode('trailer\n'));
    serialize(out, tr);
    out.add(latin1.encode('\nstartxref\n$xrefOff\n%%EOF\n'));
  } else {
    final xn = size;
    final entries = <int, int>{...offsets, xn: xrefOff};
    final keys = entries.keys.toList()..sort();
    final data = BytesBuilder();
    final index = <int>[];
    for (final n in keys) {
      index..add(n)..add(1);
      data.add([1, (entries[n]! >> 24) & 255, (entries[n]! >> 16) & 255, (entries[n]! >> 8) & 255,
        entries[n]! & 255, (n == xn ? 0 : doc.genOf(n)) >> 8, (n == xn ? 0 : doc.genOf(n)) & 255]);
    }
    final d = <String, dynamic>{
      'Type': const PdfName('XRef'),
      'Size': xn + 1,
      'W': [1, 4, 2],
      'Index': index,
      'Prev': doc.lastStartXref,
      ...tr,
    };
    final st = makeStream(d, data.toBytes());
    out.add(latin1.encode('$xn 0 obj\n'));
    serialize(out, st.dict);
    out.add(latin1.encode('\nstream\n'));
    out.add(st.raw);
    out.add(latin1.encode('\nendstream\nendobj\nstartxref\n$xrefOff\n%%EOF\n'));
  }
  return out.toBytes();
}
