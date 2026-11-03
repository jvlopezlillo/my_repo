const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  openFile: () => ipcRenderer.invoke('file:open'),
  saveFile: (filePath, content, saveAs) => ipcRenderer.invoke('file:save', { filePath, content, saveAs }),
  exportHtml: (html, name) => ipcRenderer.invoke('file:exportHtml', { html, name }),
  pickImage: (docPath) => ipcRenderer.invoke('image:pick', { docPath }),
  baseUrl: (docPath) => ipcRenderer.invoke('doc:baseUrl', docPath),
  setDirty: (v) => ipcRenderer.send('doc:dirty', v),
  setTitle: (t) => ipcRenderer.send('doc:title', t),
});
