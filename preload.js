/**
 * The preload script runs before `index.html` is loaded
 * in the renderer. It has access to web APIs as well as
 * Electron's renderer process modules and some polyfilled
 * Node.js functions.
 *
 * https://www.electronjs.org/docs/latest/tutorial/sandbox
 */
const { contextBridge, ipcRenderer } = require('electron')

// Splice folder sync bridge. contextIsolation is on (Electron default), so
// the page only ever sees this frozen API - no ipcRenderer, no Node.
// Consumed by the panel code in data/custom.js (window.spliceSync).
contextBridge.exposeInMainWorld('spliceSync', {
  getSettings: () => ipcRenderer.invoke('splice:get-settings'),
  setSettings: (settings) => ipcRenderer.invoke('splice:set-settings', {
    enabled: settings && settings.enabled === true,
    folder: settings && typeof settings.folder === 'string' ? settings.folder : ''
  }),
  chooseFolder: () => ipcRenderer.invoke('splice:choose-folder'),
  readFile: (filePath) => ipcRenderer.invoke('splice:read-file', String(filePath)),
  // Replace semantics: registering a new callback drops any previous one,
  // so repeated calls cannot accumulate ipcRenderer listeners.
  onNewSamples: (callback) => {
    if (typeof callback !== 'function') return
    ipcRenderer.removeAllListeners('splice:new-samples')
    ipcRenderer.on('splice:new-samples', (event, samples) => callback(samples))
  }
})
