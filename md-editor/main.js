const { app, BrowserWindow, dialog, ipcMain, Menu } = require('electron');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

let win;
let dirty = false;

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 700,
    minHeight: 450,
    title: 'MD Editor',
    backgroundColor: '#ffffff',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  Menu.setApplicationMenu(null);
  win.loadFile(path.join(__dirname, 'src', 'index.html'));

  // Abrir enlaces externos en el navegador, nunca dentro de la app
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) require('electron').shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e) => e.preventDefault());

  win.on('close', (e) => {
    if (!dirty) return;
    const r = dialog.showMessageBoxSync(win, {
      type: 'question',
      buttons: ['Cancelar', 'Salir sin guardar'],
      defaultId: 0,
      cancelId: 0,
      message: 'Hay cambios sin guardar. ¿Salir de todos modos?',
    });
    if (r === 0) e.preventDefault();
  });
}

const mdFilters = [
  { name: 'Markdown', extensions: ['md', 'markdown', 'txt'] },
  { name: 'Todos los archivos', extensions: ['*'] },
];

ipcMain.handle('file:open', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters: mdFilters });
  if (r.canceled) return null;
  const p = r.filePaths[0];
  return { path: p, content: fs.readFileSync(p, 'utf8') };
});

ipcMain.handle('file:save', async (_e, { filePath, content, saveAs }) => {
  let target = filePath;
  if (!target || saveAs) {
    const r = await dialog.showSaveDialog(win, {
      defaultPath: target || 'documento.md',
      filters: mdFilters,
    });
    if (r.canceled) return null;
    target = r.filePath;
  }
  fs.writeFileSync(target, content, 'utf8');
  return { path: target };
});

ipcMain.handle('file:exportHtml', async (_e, { html, name }) => {
  const r = await dialog.showSaveDialog(win, {
    defaultPath: (name || 'documento') + '.html',
    filters: [{ name: 'HTML', extensions: ['html'] }],
  });
  if (r.canceled) return null;
  fs.writeFileSync(r.filePath, html, 'utf8');
  return { path: r.filePath };
});

ipcMain.handle('image:pick', async (_e, { docPath }) => {
  const r = await dialog.showOpenDialog(win, {
    properties: ['openFile'],
    filters: [{ name: 'Imágenes', extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'] }],
  });
  if (r.canceled) return null;
  const p = r.filePaths[0];
  let ref;
  if (docPath) {
    // ruta relativa al documento, con "/" y codificada para Markdown
    ref = path.relative(path.dirname(docPath), p).split(path.sep).map(encodeURIComponent).join('/');
  } else {
    ref = pathToFileURL(p).href;
  }
  return { ref, name: path.basename(p, path.extname(p)), url: pathToFileURL(p).href };
});

ipcMain.handle('doc:baseUrl', (_e, docPath) =>
  docPath ? pathToFileURL(path.dirname(docPath) + path.sep).href : '');

ipcMain.on('doc:dirty', (_e, v) => { dirty = !!v; });
ipcMain.on('doc:title', (_e, t) => { if (win) win.setTitle(t); });

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
