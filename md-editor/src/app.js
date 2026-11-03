(() => {
  'use strict';
  const $ = (s) => document.querySelector(s);
  const editor = $('#editor');
  const preview = $('#preview');

  const DEFAULT_DOC = `# ¡Bienvenido a MD Editor!

Escribe en la izquierda y mira el resultado **al instante** a la derecha.

## Qué puedes hacer

- Usar la barra de iconos para dar formato
- Atajos: **Ctrl+B** negrita, **Ctrl+I** cursiva, **Ctrl+K** enlace
- [x] Listas de tareas
- [ ] Tablas, citas, código…

> Las citas se ven así.

\`\`\`js
console.log('Hola, Markdown');
\`\`\`

| Columna 1 | Columna 2 |
| --------- | --------- |
| Dato      | Dato      |
`;

  /* ---------- Estado del documento ---------- */
  let filePath = null;
  let savedContent = '';
  let baseUrl = '';

  const safeStorage = {
    get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignorar */ } },
  };

  function isDirty() { return editor.value !== savedContent; }
  function baseName() { return filePath ? filePath.split(/[\\/]/).pop() : 'Sin título'; }

  function refreshTitle() {
    const dirty = isDirty();
    const name = baseName() + (dirty ? ' •' : '');
    $('#filename').textContent = name;
    window.api.setTitle(name + ' — MD Editor');
    window.api.setDirty(dirty);
  }

  /* ---------- Vista previa ---------- */
  const URI_RE = /^(?:(?:https?|file|mailto|tel):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;
  marked.setOptions({ gfm: true, breaks: false });

  function renderHtml(md) {
    return DOMPurify.sanitize(marked.parse(md), { ALLOWED_URI_REGEXP: URI_RE });
  }

  function render() {
    preview.innerHTML = renderHtml(editor.value);
    preview.querySelectorAll('li > input[type="checkbox"]').forEach((cb) => cb.parentElement.classList.add('task'));
    if (baseUrl) {
      preview.querySelectorAll('img').forEach((img) => {
        const src = img.getAttribute('src') || '';
        if (src && !/^(?:[a-z][a-z0-9+.\-]*:|\/\/)/i.test(src)) {
          try { img.src = new URL(src, baseUrl).href; } catch { /* src inválido */ }
        }
      });
    }
    syncScroll();
    updateStats();
  }

  let raf = 0;
  function scheduleRender() {
    cancelAnimationFrame(raf);
    raf = requestAnimationFrame(render);
  }

  function syncScroll() {
    const max = editor.scrollHeight - editor.clientHeight;
    const ratio = max > 0 ? editor.scrollTop / max : 0;
    preview.scrollTop = ratio * (preview.scrollHeight - preview.clientHeight);
  }

  function updateStats() {
    const text = editor.value;
    const words = (text.trim().match(/\S+/g) || []).length;
    $('#stats').textContent = `${words} palabras · ${text.length} caracteres`;
    const pos = editor.selectionStart;
    const before = text.slice(0, pos);
    const line = before.split('\n').length;
    const col = pos - before.lastIndexOf('\n');
    $('#cursor').textContent = `Ln ${line}, Col ${col}`;
  }

  /* ---------- Utilidades de edición ---------- */
  // execCommand mantiene el historial de deshacer (Ctrl+Z) del textarea
  function replaceRange(start, end, text, selStart, selEnd) {
    editor.focus();
    editor.setSelectionRange(start, end);
    if (!document.execCommand('insertText', false, text)) {
      editor.setRangeText(text, start, end, 'end');
      editor.dispatchEvent(new Event('input'));
    }
    if (selStart !== undefined) editor.setSelectionRange(selStart, selEnd ?? selStart);
    updateStats();
  }

  function wrap(before, after, placeholder) {
    const v = editor.value;
    const s = editor.selectionStart, e = editor.selectionEnd;
    const sel = v.slice(s, e);
    // ya envuelto fuera de la selección → quitar marcas
    if (v.slice(s - before.length, s) === before && v.slice(e, e + after.length) === after) {
      replaceRange(s - before.length, e + after.length, sel, s - before.length, s - before.length + sel.length);
      return;
    }
    // ya envuelto dentro de la selección → quitar marcas
    if (sel.length >= before.length + after.length && sel.startsWith(before) && sel.endsWith(after)) {
      const inner = sel.slice(before.length, sel.length - after.length);
      replaceRange(s, e, inner, s, s + inner.length);
      return;
    }
    const inner = sel || placeholder;
    replaceRange(s, e, before + inner + after, s + before.length, s + before.length + inner.length);
  }

  function lineBlock() {
    const v = editor.value;
    const start = v.lastIndexOf('\n', editor.selectionStart - 1) + 1;
    let end = v.indexOf('\n', editor.selectionEnd);
    if (end === -1) end = v.length;
    return { start, end, lines: v.slice(start, end).split('\n') };
  }

  function transformLines(fn) {
    const { start, end, lines } = lineBlock();
    const collapsed = editor.selectionStart === editor.selectionEnd;
    const out = fn(lines).join('\n');
    // sin selección: cursor al final de la línea; con selección: se mantiene el bloque seleccionado
    replaceRange(start, end, out, collapsed ? start + out.length : start, start + out.length);
  }

  const LIST_RE = /^(\s*)(?:[-*+]|\d+\.)\s+(?:\[[ xX]\]\s+)?/;

  function heading(level) {
    const mark = '#'.repeat(level) + ' ';
    transformLines((lines) => {
      const allSame = lines.every((l) => (!l.trim() && lines.length > 1) || l.startsWith(mark));
      return lines.map((l) => {
        if (!l.trim() && lines.length > 1) return l;
        const plain = l.replace(/^#{1,6}\s+/, '');
        return allSame ? plain : mark + plain;
      });
    });
  }

  function list(kind) {
    const test = { ul: /^\s*[-*+]\s+(?!\[[ xX]\])/, ol: /^\s*\d+\.\s+/, task: /^\s*[-*+]\s+\[[ xX]\]\s+/ }[kind];
    transformLines((lines) => {
      const allOn = lines.every((l) => (!l.trim() && lines.length > 1) || test.test(l));
      let n = 0;
      return lines.map((l) => {
        if (!l.trim() && lines.length > 1) return l;
        const plain = l.replace(LIST_RE, '$1');
        if (allOn) return plain;
        n++;
        const indent = plain.match(/^\s*/)[0];
        const body = plain.slice(indent.length);
        const marker = kind === 'ol' ? `${n}. ` : kind === 'task' ? '- [ ] ' : '- ';
        return indent + marker + body;
      });
    });
  }

  function quote() {
    transformLines((lines) => {
      const allOn = lines.every((l) => l.startsWith('> '));
      return lines.map((l) => (allOn ? l.slice(2) : '> ' + l));
    });
  }

  function codeBlock() {
    const v = editor.value;
    const s = editor.selectionStart, e = editor.selectionEnd;
    const sel = v.slice(s, e);
    const pre = s > 0 && v[s - 1] !== '\n' ? '\n' : '';
    const post = e < v.length && v[e] !== '\n' ? '\n' : '';
    const body = sel || 'código';
    const text = `${pre}\`\`\`\n${body}\n\`\`\`${post}`;
    const bodyStart = s + pre.length + 4;
    replaceRange(s, e, text, bodyStart, bodyStart + body.length);
  }

  function link() {
    const s = editor.selectionStart, e = editor.selectionEnd;
    const sel = editor.value.slice(s, e) || 'texto del enlace';
    const text = `[${sel}](https://)`;
    const urlStart = s + sel.length + 3;
    replaceRange(s, e, text, urlStart, urlStart + 8);
  }

  // saltos de línea necesarios para que el bloque quede separado por una línea en blanco
  function blankBefore(pos) {
    const b = editor.value.slice(0, pos);
    if (!b || b.endsWith('\n\n')) return '';
    return b.endsWith('\n') ? '\n' : '\n\n';
  }

  function table() {
    const t = '| Columna 1 | Columna 2 | Columna 3 |\n| --------- | --------- | --------- |\n|           |           |           |\n';
    const s = editor.selectionStart;
    const pre = blankBefore(s);
    replaceRange(s, editor.selectionEnd, pre + t, s + pre.length + 2, s + pre.length + 11);
  }

  function hr() {
    const s = editor.selectionStart;
    replaceRange(s, editor.selectionEnd, blankBefore(s) + '---\n\n');
  }

  /* ---------- Modal (imagen) ---------- */
  const modal = $('#modal');
  let modalOk = null;
  function openModal(title, bodyHtml, onOk) {
    $('#modal-title').textContent = title;
    $('#modal-body').innerHTML = bodyHtml;
    modalOk = onOk;
    modal.classList.remove('hidden');
    const first = $('#modal-body input');
    if (first) first.focus();
  }
  function closeModal() { modal.classList.add('hidden'); modalOk = null; editor.focus(); }
  $('#modal-cancel').onclick = closeModal;
  $('#modal-ok').onclick = () => { const f = modalOk; if (f) f(); closeModal(); };
  modal.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeModal();
    if (e.key === 'Enter' && e.target.tagName === 'INPUT') $('#modal-ok').click();
  });

  function imageDialog() {
    const sel = editor.value.slice(editor.selectionStart, editor.selectionEnd);
    openModal('Insertar imagen', `
      <label for="img-url">Dirección (URL) o archivo</label>
      <div class="row">
        <input id="img-url" placeholder="https://… o elige un archivo" />
        <button id="img-pick" type="button">Elegir archivo…</button>
      </div>
      <label for="img-alt">Texto alternativo</label>
      <input id="img-alt" placeholder="Descripción de la imagen" />`, () => {
      const url = $('#img-url').value.trim();
      if (!url) return;
      const alt = $('#img-alt').value.trim() || 'imagen';
      const target = /[\s()]/.test(url) ? `<${url}>` : url;
      const s = editor.selectionStart, e = editor.selectionEnd;
      replaceRange(s, e, `![${alt}](${target})`);
    });
    $('#img-alt').value = sel;
    $('#img-pick').onclick = async () => {
      const r = await window.api.pickImage(filePath);
      if (!r) return;
      $('#img-url').value = r.ref;
      if (!$('#img-alt').value) $('#img-alt').value = r.name;
    };
  }

  /* ---------- Acciones de la barra ---------- */
  const actions = {
    bold: () => wrap('**', '**', 'negrita'),
    italic: () => wrap('*', '*', 'cursiva'),
    strike: () => wrap('~~', '~~', 'tachado'),
    h1: () => heading(1), h2: () => heading(2), h3: () => heading(3),
    ul: () => list('ul'), ol: () => list('ol'), task: () => list('task'),
    quote, code: () => wrap('`', '`', 'código'), codeblock: codeBlock,
    link, image: imageDialog, table, hr,
  };
  $('#toolbar').addEventListener('mousedown', (e) => e.preventDefault()); // no robar el foco
  $('#toolbar').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-act]');
    if (b) { actions[b.dataset.act](); editor.focus(); }
  });

  /* ---------- Archivos ---------- */
  async function setDoc(path, content) {
    filePath = path;
    savedContent = content;
    editor.value = content;
    baseUrl = await window.api.baseUrl(path);
    render();
    refreshTitle();
  }

  function confirmDiscard() {
    return !isDirty() || window.confirm('Hay cambios sin guardar. ¿Descartarlos?');
  }

  async function save(saveAs) {
    const r = await window.api.saveFile(filePath, editor.value, saveAs);
    if (!r) return;
    filePath = r.path;
    savedContent = editor.value;
    baseUrl = await window.api.baseUrl(filePath);
    render();
    refreshTitle();
  }

  const fileActions = {
    new: async () => { if (confirmDiscard()) await setDoc(null, ''); editor.focus(); },
    open: async () => {
      if (!confirmDiscard()) return;
      const r = await window.api.openFile();
      if (r) await setDoc(r.path, r.content);
    },
    save: () => save(false),
    saveas: () => save(true),
    html: async () => {
      const title = baseName().replace(/\.[^.]+$/, '');
      const doc = `<!DOCTYPE html>\n<html lang="es"><head><meta charset="UTF-8"><title>${title.replace(/[<&]/g, '')}</title>
<style>body{max-width:800px;margin:2em auto;padding:0 1em;font:16px/1.7 "Segoe UI",sans-serif;color:#1f2328}
img{max-width:100%}pre,code{background:#eff1f3;border-radius:4px}code{padding:.15em .4em}pre{padding:12px 16px;overflow:auto}pre code{padding:0}
blockquote{margin-left:0;padding:0 1em;color:#656d76;border-left:4px solid #d0d7de}table{border-collapse:collapse}
th,td{border:1px solid #d0d7de;padding:6px 13px}h1,h2{border-bottom:1px solid #d0d7de;padding-bottom:.3em}</style></head>
<body>\n${renderHtml(editor.value)}\n</body></html>`;
      await window.api.exportHtml(doc, title);
    },
  };
  document.querySelector('.file-actions').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-file]');
    if (b) fileActions[b.dataset.file]();
  });

  /* ---------- Teclado ---------- */
  editor.addEventListener('keydown', (e) => {
    if (e.key === 'Tab' && !e.ctrlKey && !e.altKey) {
      e.preventDefault();
      if (e.shiftKey) {
        transformLines((lines) => lines.map((l) => l.replace(/^( {1,2}|\t)/, '')));
      } else if (editor.selectionStart !== editor.selectionEnd && editor.value.slice(editor.selectionStart, editor.selectionEnd).includes('\n')) {
        transformLines((lines) => lines.map((l) => '  ' + l));
      } else {
        replaceRange(editor.selectionStart, editor.selectionEnd, '  ');
      }
      return;
    }
    // Enter dentro de una lista → continuar la lista
    if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && editor.selectionStart === editor.selectionEnd) {
      const { start, end, lines } = lineBlock();
      const line = lines[0];
      const m = line.match(/^(\s*)([-*+]|\d+\.)\s+(\[[ xX]\]\s+)?/);
      const col = editor.selectionStart - start;
      if (m && lines.length === 1 && col >= m[0].length) {
        e.preventDefault();
        if (line.slice(m[0].length).trim() === '') {
          replaceRange(start, end, '', start);        // línea vacía: salir de la lista
        } else {
          const marker = /\d/.test(m[2]) ? parseInt(m[2], 10) + 1 + '.' : m[2];
          replaceRange(editor.selectionStart, editor.selectionEnd, `\n${m[1]}${marker} ${m[3] ? '[ ] ' : ''}`);
        }
      }
    }
  });

  document.addEventListener('keydown', (e) => {
    if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
    const k = e.key.toLowerCase();
    const map = {
      b: actions.bold, i: actions.italic, k: actions.link,
      s: () => save(e.shiftKey), o: fileActions.open, n: fileActions.new,
    };
    if (map[k]) { e.preventDefault(); map[k](); }
  });

  /* ---------- Eventos del editor ---------- */
  let draftTimer = 0;
  editor.addEventListener('input', () => {
    scheduleRender();
    refreshTitle();
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => safeStorage.set('md-draft', JSON.stringify({ path: filePath, text: editor.value })), 400);
  });
  editor.addEventListener('scroll', syncScroll);
  ['keyup', 'click', 'select'].forEach((ev) => editor.addEventListener(ev, updateStats));

  /* ---------- Divisor redimensionable ---------- */
  const divider = $('#divider');
  const edPane = $('#editor-pane');
  divider.addEventListener('pointerdown', (e) => {
    divider.setPointerCapture(e.pointerId);
    divider.classList.add('drag');
  });
  divider.addEventListener('pointermove', (e) => {
    if (!divider.classList.contains('drag')) return;
    const box = $('#split').getBoundingClientRect();
    const pct = Math.min(80, Math.max(20, ((e.clientX - box.left) / box.width) * 100));
    edPane.style.flex = `0 0 ${pct}%`;
  });
  divider.addEventListener('pointerup', () => divider.classList.remove('drag'));

  /* ---------- Tema ---------- */
  function applyTheme(t) { document.documentElement.dataset.theme = t; safeStorage.set('md-theme', t); }
  applyTheme(safeStorage.get('md-theme') ||
    (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
  $('#theme').onclick = () => applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');

  /* ---------- Inicio ---------- */
  (async () => {
    let draft = null;
    try { draft = JSON.parse(safeStorage.get('md-draft') || 'null'); } catch { /* ignorar */ }
    if (draft && !draft.path && typeof draft.text === 'string') {
      await setDoc(null, '');
      editor.value = draft.text;     // borrador sin guardar de la sesión anterior
      render();
      refreshTitle();
    } else {
      await setDoc(null, DEFAULT_DOC);
      savedContent = DEFAULT_DOC;
      refreshTitle();
    }
    editor.focus();
  })();
})();
