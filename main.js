// Modules to control application life and create native browser window
const { app, BrowserWindow, ipcMain, dialog } = require('electron')
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')
const spliceWatcher = require('./splice-watcher')

// ---------------------------------------------------------------------------
// Splice folder sync (desktop only)
// Watches the user's local Splice folder and pushes newly downloaded samples
// to the renderer. Local files only - no Splice API involved.
// ---------------------------------------------------------------------------

const MAX_SAMPLE_READ_BYTES = 64 * 1024 * 1024 // refuse to buffer files over 64 MB

let mainWindow = null
let watcher = null
let spliceSettings = null

function settingsFilePath () {
  return path.join(app.getPath('userData'), 'splice-sync.json')
}

function loadSpliceSettings () {
  const defaults = {
    enabled: false,
    folder: spliceWatcher.defaultSpliceFolder(process.platform, os.homedir())
  }
  try {
    const raw = JSON.parse(fs.readFileSync(settingsFilePath(), 'utf8'))
    return {
      enabled: raw.enabled === true,
      folder: typeof raw.folder === 'string' && raw.folder.length > 0 ? raw.folder : defaults.folder
    }
  } catch (err) {
    return defaults
  }
}

function saveSpliceSettings (settings) {
  try {
    fs.writeFileSync(settingsFilePath(), JSON.stringify(settings, null, 2))
  } catch (err) {
    console.error('splice-sync: failed to persist settings:', String(err))
  }
}

function stopSpliceWatcher () {
  if (watcher !== null) {
    watcher.stop()
    watcher = null
  }
}

// Returns true when the watcher is running afterwards.
function applySpliceSettings () {
  stopSpliceWatcher()
  if (!spliceSettings.enabled) return false
  watcher = spliceWatcher.createSpliceWatcher({
    folder: spliceSettings.folder,
    onNewSamples: (samples) => {
      if (mainWindow !== null && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('splice:new-samples', samples)
      }
    }
  })
  const ok = watcher.start()
  if (!ok) {
    console.error('splice-sync: cannot watch folder:', spliceSettings.folder)
    stopSpliceWatcher()
  }
  return ok
}

function registerSpliceIpc () {
  ipcMain.handle('splice:get-settings', () => {
    return { enabled: spliceSettings.enabled, folder: spliceSettings.folder, watching: watcher !== null }
  })

  ipcMain.handle('splice:set-settings', (event, next) => {
    spliceSettings = {
      enabled: next && next.enabled === true,
      folder: next && typeof next.folder === 'string' && next.folder.length > 0
        ? next.folder
        : spliceSettings.folder
    }
    saveSpliceSettings(spliceSettings)
    const watching = applySpliceSettings()
    return { enabled: spliceSettings.enabled, folder: spliceSettings.folder, watching }
  })

  ipcMain.handle('splice:choose-folder', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Choose your Splice folder',
      defaultPath: spliceSettings.folder,
      properties: ['openDirectory']
    })
    if (result.canceled || result.filePaths.length === 0) return null
    return result.filePaths[0]
  })

  // Reads sample bytes for the renderer. Restricted to files inside the
  // configured Splice folder so the bridge cannot be used as a generic
  // file reader.
  ipcMain.handle('splice:read-file', (event, filePath) => {
    if (typeof filePath !== 'string') throw new Error('invalid path')
    const resolved = path.resolve(filePath)
    const root = path.resolve(spliceSettings.folder) + path.sep
    if (!resolved.startsWith(root)) throw new Error('path outside the Splice folder')
    const stat = fs.statSync(resolved)
    if (!stat.isFile()) throw new Error('not a file')
    if (stat.size > MAX_SAMPLE_READ_BYTES) throw new Error('file too large')
    return { name: path.basename(resolved), bytes: fs.readFileSync(resolved) }
  })
}

function createWindow () {
  // Create the browser window.
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    title: "EP-133 Sample Tool",
    icon: path.join(__dirname + 'icon.ico'),
    webPreferences: {
      //devTools: false,
      preload: path.join(__dirname, 'preload.js')
    }
  })
  // and load the index.html of the app.
  //mainWindow.setMenuBarVisibility(false)
  mainWindow.loadFile('data/index.html')
  mainWindow.webContents.session.setPermissionRequestHandler((webContents, permission, callback, details) => {
    console.log('Permission request:', permission);

    if (permission === 'midi' || permission === 'midiSysex') {
      callback(true);
    } else {
      callback(false);
    }
  })

  mainWindow.webContents.session.setPermissionCheckHandler((webContents, permission, requestingOrigin) => {
    console.log('Permission check:', permission);

    if (permission === 'midi' || permission === 'midiSysex') {
      return true;
    }

    return false;
  });
  // Open the DevTools.
  //mainWindow.webContents.openDevTools()
}

// This method will be called when Electron has finished
// initialization and is ready to create browser windows.
// Some APIs can only be used after this event occurs.
app.whenReady().then(() => {
  spliceSettings = loadSpliceSettings()
  registerSpliceIpc()
  createWindow()
  applySpliceSettings()

  app.on('activate', function () {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', function () {
  stopSpliceWatcher()
  app.quit()
})

// In this file you can include the rest of your app's specific main process
// code. You can also put them in separate files and require them here.
