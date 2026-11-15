import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:pdfrx/pdfrx.dart';

import 'engine/engine.dart';
import 'engine/objects.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const PdfTextEditApp());
}

class PdfTextEditApp extends StatelessWidget {
  const PdfTextEditApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'PDF Text Edit',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(colorSchemeSeed: const Color(0xFF0B6BCB), useMaterial3: true),
        home: const HomePage(),
      );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  PdfTextEngine? _engine;
  String _name = '';
  int _version = 0;
  bool _dirty = false;
  final List<Uint8List> _undo = [];
  final List<Uint8List> _redo = [];

  void _load(Uint8List bytes, {String? name}) {
    try {
      final e = PdfTextEngine(bytes);
      setState(() {
        _engine = e;
        if (name != null) _name = name;
        _version++;
      });
    } catch (ex) {
      _msg('No se pudo abrir el PDF: $ex');
    }
  }

  void _msg(String m) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m), duration: const Duration(seconds: 5)));

  Future<void> _open() async {
    final r = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: ['pdf']);
    if (r.isEmpty) return;
    final bytes = await r.first.readAsBytes();
    _undo.clear();
    _redo.clear();
    _dirty = false;
    _load(bytes, name: r.first.name);
  }

  Future<void> _save() async {
    final e = _engine;
    if (e == null) return;
    final base = _name.replaceAll(RegExp(r'\.pdf$', caseSensitive: false), '');
    final uri = await FilePicker.saveFile(
        fileName: '$base-editado.pdf', bytes: e.bytes, mimeType: 'application/pdf');
    if (uri != null) {
      setState(() => _dirty = false);
      _msg('Guardado');
    }
  }

  void _commit(Uint8List newBytes) {
    _undo.add(_engine!.bytes);
    _redo.clear();
    _dirty = true;
    _load(newBytes);
  }

  void _undoOp() {
    if (_undo.isEmpty) return;
    _redo.add(_engine!.bytes);
    _load(_undo.removeLast());
  }

  void _redoOp() {
    if (_redo.isEmpty) return;
    _undo.add(_engine!.bytes);
    _load(_redo.removeLast());
  }

  Future<void> _edit(SpanInfo s) async {
    final e = _engine!;
    final text = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => EditSheet(span: s),
    );
    if (text == null || text == s.text || text.trim().isEmpty || !mounted) return;
    final chk = e.check(s, text);
    try {
      if (chk.ok) {
        _commit(e.apply(chk));
        return;
      }
      final go = await showDialog<bool>(context: context, builder: (_) => MissingFontDialog(chk));
      if (go == true) {
        _commit(e.apply(chk, allowEquivalent: true));
        _msg('Se usó una fuente estándar equivalente para: ${chk.missing.join(' ')}');
      }
    } on PdfEditException catch (ex) {
      _msg(ex.message);
    } catch (ex) {
      _msg('Error al editar: $ex');
    }
  }

  @override
  Widget build(BuildContext context) {
    final e = _engine;
    return Scaffold(
      appBar: AppBar(
        title: Text(e == null ? 'PDF Text Edit' : _name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(icon: const Icon(Icons.folder_open), tooltip: 'Abrir', onPressed: _open),
          if (e != null) ...[
            IconButton(icon: const Icon(Icons.undo), tooltip: 'Deshacer', onPressed: _undo.isEmpty ? null : _undoOp),
            IconButton(icon: const Icon(Icons.redo), tooltip: 'Rehacer', onPressed: _redo.isEmpty ? null : _redoOp),
            IconButton(icon: Icon(Icons.save, color: _dirty ? Colors.orange : null), tooltip: 'Guardar', onPressed: _save),
          ],
        ],
      ),
      body: e == null
          ? Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                const Icon(Icons.picture_as_pdf, size: 72),
                const SizedBox(height: 12),
                const Text('Abre un PDF y toca un texto para editarlo'),
                const SizedBox(height: 16),
                FilledButton.icon(onPressed: _open, icon: const Icon(Icons.folder_open), label: const Text('Abrir PDF')),
              ]),
            )
          : PdfViewer.data(
              e.bytes,
              sourceName: 'doc-$_version',
              key: ValueKey(_version),
              params: PdfViewerParams(
                pageOverlaysBuilder: (context, pageRect, page) {
                  final idx = page.pageNumber - 1;
                  if (idx >= e.pageCount || !e.pageSupported(idx)) return const [];
                  final size = e.pageSize(idx);
                  final k = pageRect.width / size[0];
                  return [
                    for (final s in e.spans(idx))
                      Positioned(
                        left: s.x0 * k,
                        top: s.y0 * k,
                        width: (s.x1 - s.x0) * k,
                        height: (s.y1 - s.y0) * k,
                        child: PdfOverlayInteractionRegion(
                          onTap: (_) {
                            _edit(s);
                            return true;
                          },
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.blue.withValues(alpha: 0.07),
                              border: Border.all(color: Colors.blue.withValues(alpha: 0.35), width: 0.6),
                            ),
                          ),
                        ),
                      ),
                  ];
                },
              ),
            ),
    );
  }
}

class EditSheet extends StatefulWidget {
  final SpanInfo span;
  const EditSheet({super.key, required this.span});
  @override
  State<EditSheet> createState() => _EditSheetState();
}

class _EditSheetState extends State<EditSheet> {
  late final TextEditingController _c = TextEditingController(text: widget.span.text);

  @override
  Widget build(BuildContext context) {
    final s = widget.span;
    final col = Color.fromARGB(255, (s.color[0] * 255).round(), (s.color[1] * 255).round(), (s.color[2] * 255).round());
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, MediaQuery.of(context).viewInsets.bottom + 16),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Fuente: ${s.fontName}', style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Row(children: [
          Text('${s.size.toStringAsFixed(1)} pt · ${s.styleLabel} · '),
          Container(width: 14, height: 14, color: col),
        ]),
        Text(s.source, style: Theme.of(context).textTheme.bodySmall),
        if (!s.horizontal)
          const Text('Texto no horizontal: el resultado puede no ser exacto.', style: TextStyle(color: Colors.orange)),
        const SizedBox(height: 12),
        TextField(
          controller: _c,
          autofocus: true,
          decoration: const InputDecoration(border: OutlineInputBorder(), labelText: 'Nuevo texto'),
          onSubmitted: (v) => Navigator.pop(context, v),
        ),
        const SizedBox(height: 12),
        Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancelar')),
          const SizedBox(width: 8),
          FilledButton(onPressed: () => Navigator.pop(context, _c.text), child: const Text('Reemplazar')),
        ]),
      ]),
    );
  }
}

class MissingFontDialog extends StatelessWidget {
  final EditCheck check;
  const MissingFontDialog(this.check, {super.key});
  @override
  Widget build(BuildContext context) {
    final blocked = check.unsupported.isNotEmpty;
    return AlertDialog(
      title: const Text('La fuente original no tiene esas letras'),
      content: SingleChildScrollView(
        child: Text(
          'La fuente «${check.span.fontName}» incrustada en el PDF no contiene: '
          '${check.missing.join(' ')}\n\n'
          'Los PDF suelen incluir solo las letras que usan. Para que el texto quede idéntico '
          'hay que conseguir esa fuente (instalarla/elegir su archivo .ttf/.otf: aún no disponible '
          'en la versión Android).\n\n'
          '${blocked ? 'Además, estos caracteres no existen ni en una fuente estándar: ${check.unsupported.join(' ')}. Edita el texto para quitarlos.' : 'Como último recurso puedo usar una fuente estándar equivalente (mismo estilo y tamaño) solo para esos caracteres.'}',
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
        if (!blocked)
          FilledButton(
              onPressed: () => Navigator.pop(context, true), child: const Text('Usar fuente equivalente')),
      ],
    );
  }
}
