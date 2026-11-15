import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:pdf_text_edit/engine/engine.dart';

SpanInfo find(PdfTextEngine e, String start) =>
    e.spans(0).firstWhere((s) => s.text.startsWith(start));

void main() {
  final sample = File('test/fixtures/sample.pdf').readAsBytesSync();

  test('lee textos con fuente, tamaño, estilo y color', () {
    final e = PdfTextEngine(sample);
    expect(e.pageCount, 1);
    final b = find(e, 'Bold');
    expect(b.fontName, contains('Bold'));
    expect(b.bold, isTrue);
    expect(b.size, closeTo(18, 0.01));
    expect(b.color[0], closeTo(0.6, 0.01));
    final i = find(e, 'Italic');
    expect(i.italic, isTrue);
  });

  test('reemplaza con la fuente incrustada y deja el resto intacto', () {
    final e = PdfTextEngine(sample);
    final s = find(e, 'Bold');
    final c = e.check(s, 'Bold lode');
    expect(c.ok, isTrue, reason: c.missing.join());
    final out = e.apply(c);
    final e2 = PdfTextEngine(out);
    final texts = e2.spans(0).map((x) => x.text).toList();
    expect(texts, contains('Bold lode'));
    expect(texts, contains('Hello world from a PDF'));
    expect(texts, isNot(contains('Bold title here')));
    final n = find(e2, 'Bold lode');
    expect(n.size, closeTo(18, 0.01));
    expect(n.fontName, s.fontName);
  });

  test('avisa si falta un carácter y permite fuente equivalente', () {
    final e = PdfTextEngine(sample);
    final s = find(e, 'Italic');
    final c = e.check(s, 'Italic sans línea');
    expect(c.ok, isFalse);
    expect(c.missing, contains('í'));
    expect(() => e.apply(c), throwsA(anything));
    final out = e.apply(c, allowEquivalent: true);
    final e2 = PdfTextEngine(out);
    expect(e2.spans(0).map((x) => x.text).join('|'), contains('í'));
  });

  test('varias ediciones seguidas (revisiones incrementales)', () {
    var bytes = sample;
    for (final t in ['Uno', 'Dos', 'Tres']) {
      final e = PdfTextEngine(bytes);
      final s = find(e, t == 'Uno' ? 'Hello' : (t == 'Dos' ? 'Bold' : 'Italic'));
      bytes = e.apply(e.check(s, t));
    }
    final texts = PdfTextEngine(bytes).spans(0).map((x) => x.text).toList();
    expect(texts, containsAll(['Uno', 'Dos', 'Tres']));
    File('/tmp/dart_multi.pdf').writeAsBytesSync(bytes);
  });

  test('PDF con xref stream y object streams (+ fuente equivalente)', () {
    final b = File('test/fixtures/sample_objstm.pdf').readAsBytesSync();
    final e = PdfTextEngine(b);
    expect(e.doc.usesXrefStream, isTrue);
    final s = find(e, 'Italic');
    final c = e.check(s, 'Italic línea');
    final out = e.apply(c, allowEquivalent: true);
    final texts = PdfTextEngine(out).spans(0).map((x) => x.text).join('|');
    expect(texts, contains('í'));
    File('/tmp/dart_objstm.pdf').writeAsBytesSync(out);
  });

  final inv = Platform.environment['INVOICE'];
  test('factura real con fuente CID/CFF', () {
    final e = PdfTextEngine(File(inv!).readAsBytesSync());
    final s = find(e, '61,19');
    final c = e.check(s, '99,99 €');
    expect(c.ok, isTrue, reason: c.missing.join());
    var out = e.apply(c);
    final e2 = PdfTextEngine(out);
    final s2 = find(e2, 'Hola,');
    out = e2.apply(e2.check(s2, 'Hola, María'));
    File(Platform.environment['OUT'] ?? '/tmp/dart_inv.pdf').writeAsBytesSync(out);
    final texts = PdfTextEngine(out).spans(0).map((x) => x.text).toList();
    expect(texts, contains('99,99 €'));
    expect(texts, contains('Hola, María'));
  }, skip: inv == null);
}
