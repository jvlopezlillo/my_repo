const fs = require('fs');
const calls = [];
global.window = global;
global.location = { href: 'https://site.example/page' };
global.CastBridge = { onStream: (u, k, p) => calls.push([u, k, p]), onMedia() {} };
global.document = { addEventListener() {}, querySelectorAll() { return []; } };
window.frames = [];
window.top = window;
window.addEventListener = () => {};

// --- XHR falso
class FakeXHR {
  constructor() { this.listeners = {}; }
  open(m, u) { this._u = u; }
  addEventListener(n, f) { this.listeners[n] = f; }
  getResponseHeader(h) { return this._ct || null; }
  send() { setTimeout(() => this.listeners.load && this.listeners.load.call(this), 0); }
}
global.XMLHttpRequest = FakeXHR;

// --- fetch falso
const bodies = {
  'https://site.example/api/getstream?id=1': { ct: 'text/plain', body: '#EXTM3U\n#EXT-X-VERSION:3\n' },
  'https://site.example/api/mpd?id=2': { ct: 'application/xml', body: '<?xml version="1.0"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">' },
  'https://site.example/api/user': { ct: 'application/json', body: '{"a":1}' },
  'https://site.example/api/page': { ct: 'text/plain', body: 'hola mundo' },
  'https://cdn.example/video/clip.ts': { ct: 'application/octet-stream', body: '#EXTM3U-no-soy-un-ts' },
  'https://site.example/real.m3u8': { ct: 'application/vnd.apple.mpegurl', body: 'x' },
};
function mkResponse(url) {
  const b = bodies[url];
  const mk = () => ({
    url, headers: { get: h => h === 'content-type' ? b.ct : null },
    clone: () => mk(),
    body: { getReader: () => { let done = false; return {
      read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: Buffer.from(b.body) })),
      cancel: () => {} }; } },
  });
  return mk();
}
global.fetch = (u) => Promise.resolve(mkResponse(u));

eval(require('./load_script')());

(async () => {
  for (const u of Object.keys(bodies)) await fetch(u);
  // XHR: texto
  const x1 = new XMLHttpRequest(); x1.open('GET', '/x/playlist?token=1'); x1.responseType = 'text';
  x1.responseText = '#EXTM3U\n#EXTINF:4,\na.ts'; x1._ct = 'text/plain'; x1.send();
  // XHR: arraybuffer con una lista dentro
  const x2 = new XMLHttpRequest(); x2.open('GET', 'https://site.example/bin/manifest'); x2.responseType = 'arraybuffer';
  const buf = Buffer.from('<MPD type="dynamic">'); x2.response = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.length); x2._ct = 'application/octet-stream'; x2.send();
  // XHR: un trozo .ts en arraybuffer no se mira
  const x3 = new XMLHttpRequest(); x3.open('GET', 'https://cdn.example/s/1.ts'); x3.responseType = 'arraybuffer';
  const b3 = Buffer.from('#EXTM3U falso'); x3.response = b3.buffer.slice(b3.byteOffset, b3.byteOffset + b3.length); x3.send();
  await new Promise(r => setTimeout(r, 50));
  console.log(JSON.stringify(calls, null, 1));
  const urls = calls.map(c => c[0] + ' → ' + c[1]);
  const expect = [
    'https://site.example/api/getstream?id=1 → hls',
    'https://site.example/api/mpd?id=2 → dash',
    'https://site.example/real.m3u8 → hls',
    'https://site.example/x/playlist?token=1 → hls',
    'https://site.example/bin/manifest → dash',
  ];
  const ok = expect.every(e => urls.includes(e)) && urls.length === expect.length && calls.every(c => c[2] === 'https://site.example/page');
  console.log(ok ? 'COMPORTAMIENTO OK' : 'FALLO: ' + JSON.stringify(urls));
  process.exit(ok ? 0 : 1);
})();
