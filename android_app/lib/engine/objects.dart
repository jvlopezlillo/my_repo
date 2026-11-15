import 'dart:typed_data';

/// Objetos básicos de PDF. Los diccionarios son Map<String, dynamic> (clave sin '/').
class PdfName {
  final String name;
  const PdfName(this.name);
  @override
  bool operator ==(Object other) => other is PdfName && other.name == name;
  @override
  int get hashCode => name.hashCode;
  @override
  String toString() => '/$name';
}

class PdfRef {
  final int num;
  final int gen;
  const PdfRef(this.num, this.gen);
  @override
  bool operator ==(Object other) => other is PdfRef && other.num == num && other.gen == gen;
  @override
  int get hashCode => num * 31 + gen;
  @override
  String toString() => '$num $gen R';
}

class PdfString {
  final Uint8List bytes;
  const PdfString(this.bytes);
}

/// Operador de un content stream (o palabra clave suelta).
class PdfKeyword {
  final String word;
  const PdfKeyword(this.word);
  @override
  String toString() => word;
}

class PdfStream {
  final Map<String, dynamic> dict;
  final Uint8List raw;
  const PdfStream(this.dict, this.raw);
}

class PdfEditException implements Exception {
  final String message;
  PdfEditException(this.message);
  @override
  String toString() => message;
}
