const { JSDOM } = require('jsdom');
const fs = require('fs');
const assert = require('assert');
const script = require('./load_script')();

function page(html, W = 2400, H = 1080) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true, url: 'https://site.example/page' });
  const w = dom.window;
  Object.defineProperty(w, 'innerWidth', { value: W, configurable: true, writable: true });
  Object.defineProperty(w, 'innerHeight', { value: H, configurable: true, writable: true });
  const calls = [];
  w.CastBridge = { onMedia() {}, onStream() {}, onFs: (ok) => calls.push(['onFs', ok]) };
  w.eval(script);
  return { dom, w, d: w.document, calls };
}
const html = `<html style="color:red"><body style="margin:8px"><header id="h">cabecera</header>
 <div id="player" style="overflow:hidden;transform:translateX(0)"><span id="before">a</span>
 <video id="v" style="width:300px" controls></video><span id="after">b</span></div>
 <div id="ads">anuncio</div></body></html>`;
let ok = 0;
const check = (name, fn) => { fn(); ok++; console.log('  ✓', name); };

// ---- matemática de los formatos
{
  const { w } = page(html);
  const box = w.__castFS.box;
  check('Original: llena la pantalla y respeta la proporción (contain)', () => {
    assert.deepStrictEqual(JSON.parse(JSON.stringify(box('fit', 2400, 1080))), { w: 2400, h: 1080, left: 0, top: 0, fit: 'contain' });
  });
  check('16:9 en pantalla 20:9: barras a los lados', () => {
    const b = box('r169', 2400, 1080);
    assert.strictEqual(b.h, 1080); assert.strictEqual(b.w, 1920); assert.strictEqual(b.left, 240); assert.strictEqual(b.fit, 'fill');
  });
  check('4:3', () => { const b = box('r43', 2400, 1080); assert.strictEqual(b.w, 1440); assert.strictEqual(b.left, 480); });
  check('19,5:9', () => { const b = box('r195', 2400, 1080); assert.strictEqual(b.h, 1080); assert.ok(Math.abs(b.w - 2340) < 1e-9); assert.ok(Math.abs(b.left - 30) < 1e-9); });
  check('20:9 en pantalla 20:9 llena todo', () => { const b = box('r209', 2400, 1080); assert.ok(Math.abs(b.w - 2400) < 1e-6 && Math.abs(b.h - 1080) < 1e-6); });
  check('Estirar: llena la pantalla deformando (fill)', () => { const b = box('stretch', 2400, 1080); assert.strictEqual(b.w, 2400); assert.strictEqual(b.fit, 'fill'); });
  check('Rellenar: llena la pantalla recortando (cover)', () => { const b = box('fill', 2400, 1080); assert.strictEqual(b.h, 1080); assert.strictEqual(b.fit, 'cover'); });
  check('4:3 en pantalla casi cuadrada (4:3) llena todo', () => { const b = box('r43', 1440, 1080); assert.strictEqual(b.w, 1440); assert.strictEqual(b.h, 1080); });
  check('16:9 en vertical: barras arriba y abajo', () => {
    const b = box('r169', 1080, 2400);
    assert.strictEqual(b.w, 1080); assert.ok(Math.abs(b.h - 607.5) < 1e-9); assert.ok(Math.abs(b.top - 896.25) < 1e-9);
  });
}

// ---- entrar, cambiar formato y salir
{
  const { w, d, calls } = page(html);
  const v = d.getElementById('v');
  const parent = v.parentNode, next = v.nextSibling;
  check('entrar: el vídeo sale a la raíz, a pantalla completa y sin controles de la web', () => {
    assert.strictEqual(w.__castFS.enter('r169'), true);
    assert.strictEqual(v.parentNode, d.body);
    assert.strictEqual(v.style.getPropertyValue('position'), 'fixed');
    assert.strictEqual(v.style.getPropertyPriority('position'), 'important');
    assert.strictEqual(v.style.getPropertyValue('width'), '1920px');
    assert.strictEqual(v.style.getPropertyValue('left'), '240px');
    assert.strictEqual(v.style.getPropertyValue('object-fit'), 'fill');
    assert.strictEqual(v.controls, false);
    assert.deepStrictEqual(calls, [['onFs', true]]);
    assert.ok(d.querySelector('style').textContent.includes('visibility:hidden'));
  });
  check('cambiar a Rellenar y a Estirar', () => {
    w.__castFS.mode('fill');
    assert.strictEqual(v.style.getPropertyValue('object-fit'), 'cover');
    assert.strictEqual(v.style.getPropertyValue('width'), '2400px');
    w.__castFS.mode('stretch');
    assert.strictEqual(v.style.getPropertyValue('object-fit'), 'fill');
  });
  check('girar el móvil recalcula el formato', () => {
    w.innerWidth = 1080; w.innerHeight = 2400;
    w.__castFS.mode('r169');
    w.dispatchEvent(new w.Event('resize'));
    assert.strictEqual(v.style.getPropertyValue('width'), '1080px');
    w.innerWidth = 2400; w.innerHeight = 1080;
    w.dispatchEvent(new w.Event('resize'));
    assert.strictEqual(v.style.getPropertyValue('width'), '1920px');
  });
  check('salir: todo vuelve a su sitio y a su estilo original', () => {
    w.__castFS.exit();
    assert.strictEqual(v.parentNode, parent);
    assert.strictEqual(v.nextSibling, next);
    assert.strictEqual(v.getAttribute('style'), 'width:300px');
    assert.strictEqual(v.controls, true);
    assert.strictEqual(d.documentElement.getAttribute('style'), 'color:red');
    assert.strictEqual(d.body.getAttribute('style'), 'margin:8px');
    assert.strictEqual(d.querySelectorAll('style').length, 0);
    assert.strictEqual(v.classList.contains('__cast_fs_v'), false);
  });
  check('se puede repetir entrar/salir', () => {
    assert.strictEqual(w.__castFS.enter('fit'), true);
    assert.strictEqual(v.parentNode, d.body);
    w.__castFS.exit();
    assert.strictEqual(v.parentNode, parent);
  });
}

// ---- sin vídeo
{
  const { w, calls } = page('<html><body><p>nada</p></body></html>');
  check('sin vídeo: no hace nada y no avisa', () => {
    assert.strictEqual(w.__castFS.enter('fit'), false);
    assert.deepStrictEqual(calls, []);
  });
}

// ---- elige el vídeo que se está reproduciendo
{
  const { w, d } = page('<html><body><video id="a"></video><video id="b"></video></body></html>');
  Object.defineProperty(d.getElementById('b'), 'paused', { value: false });
  check('con dos vídeos se queda con el que se reproduce', () => {
    w.__castFS.enter('fit');
    assert.strictEqual(d.getElementById('b').style.getPropertyValue('position'), 'fixed');
    assert.strictEqual(d.getElementById('a').style.getPropertyValue('position'), '');
    w.__castFS.exit();
  });
}

// ---- controles: pausa y saltos
{
  const { w, d } = page(html);
  const v = d.getElementById('v');
  let paused = true, time = 50; const log = [];
  Object.defineProperty(v, 'paused', { get: () => paused });
  Object.defineProperty(v, 'duration', { get: () => 100 });
  Object.defineProperty(v, 'currentTime', { get: () => time, set: (t) => { time = t; log.push('t=' + t); } });
  v.play = () => { paused = false; log.push('play'); };
  v.pause = () => { paused = true; log.push('pause'); };
  check('pausa/reanuda y saltos de ±10 s (sin salirse del vídeo)', () => {
    w.__castFS.cmd('toggle'); w.__castFS.cmd('toggle');
    w.__castFS.cmd('seek', -10); w.__castFS.cmd('seek', 60); w.__castFS.cmd('seek', -500);
    assert.deepStrictEqual(log, ['play', 'pause', 't=40', 't=100', 't=0']);
  });
}

// ---- vídeo dentro de un iframe: la página que lo contiene expande el iframe
{
  const { w, d } = page(`<html style="color:blue"><body><div id="wrap" style="overflow:hidden;transform:scale(1)">
     <div id="inner" style="clip-path:inset(0)"><iframe id="fr" style="width:300px;height:200px"></iframe></div></div></body></html>`);
  const fr = d.getElementById('fr');
  check('el iframe con vídeo se expande y los contenedores dejan de recortarlo', () => {
    w.dispatchEvent(new w.MessageEvent('message', { data: { __castFSUp: 1 }, source: fr.contentWindow }));
    assert.strictEqual(fr.style.getPropertyValue('position'), 'fixed');
    assert.strictEqual(fr.style.getPropertyValue('width'), '100vw');
    assert.strictEqual(d.getElementById('wrap').style.getPropertyValue('overflow'), 'visible');
    assert.strictEqual(d.getElementById('wrap').style.getPropertyValue('transform'), 'none');
    assert.strictEqual(d.getElementById('inner').style.getPropertyValue('clip-path'), 'none');
  });
  check('al salir, el iframe y sus contenedores quedan como estaban', () => {
    w.__castFS.exit();
    assert.strictEqual(fr.getAttribute('style'), 'width:300px;height:200px');
    assert.strictEqual(d.getElementById('wrap').getAttribute('style'), 'overflow:hidden;transform:scale(1)');
    assert.strictEqual(d.getElementById('inner').getAttribute('style'), 'clip-path:inset(0)');
  });
}
console.log(`\n${ok} comprobaciones OK`);
