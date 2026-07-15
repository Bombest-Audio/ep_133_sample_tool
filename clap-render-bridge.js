// CLAP offline render bridge (desktop only).
// Spawns the standalone tools/clap-render CLI per operation so third-party
// plugin code runs fully out of process (see docs/adr-001-clap-hosting.md).
const { spawn } = require('node:child_process')
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')

const MAX_RENDER_READ_BYTES = 64 * 1024 * 1024
const RENDER_TIMEOUT_MS = 60 * 1000

// Dev-tree location of the CLI. Packaging the binary into the app bundle is
// a follow-up; in dev it is built with:
//   cd tools/clap-render && cmake -B build && cmake --build build
function binaryPath (appDir) {
  const name = process.platform === 'win32' ? 'clap-render.exe' : 'clap-render'
  return path.join(appDir, 'tools', 'clap-render', 'build', name)
}

function isAvailable (appDir) {
  try {
    fs.accessSync(binaryPath(appDir), fs.constants.X_OK)
    return true
  } catch (err) {
    return false
  }
}

// Runs the CLI and resolves { code, stdout, stderr }. Rejects only on spawn
// failure or timeout; a non-zero exit is reported through `code` so callers
// can surface the CLI's stderr message.
function run (appDir, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(binaryPath(appDir), args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    const timer = setTimeout(() => {
      child.kill('SIGKILL')
      reject(new Error('clap-render timed out after ' + (RENDER_TIMEOUT_MS / 1000) + 's'))
    }, RENDER_TIMEOUT_MS)
    child.stdout.on('data', (d) => { stdout += d })
    child.stderr.on('data', (d) => { stderr += d })
    child.on('error', (err) => { clearTimeout(timer); reject(err) })
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, stdout, stderr }) })
  })
}

// stderr from the CLI is multi-line; the last "clap-render:" line is the
// actual failure message.
function failureMessage (stderr) {
  const lines = String(stderr).trim().split('\n').filter((l) => l.indexOf('clap-render:') === 0)
  return lines.length > 0 ? lines[lines.length - 1] : String(stderr).trim() || 'clap-render failed'
}

async function listParams (appDir, pluginPath) {
  const result = await run(appDir, ['--plugin', pluginPath, '--list-params'])
  if (result.code !== 0) throw new Error(failureMessage(result.stderr))
  return result.stdout.split('\n').filter((l) => l.length > 0).map((line) => {
    const cols = line.split('\t')
    return {
      id: cols[0],
      name: cols[1],
      min: Number(cols[2]),
      max: Number(cols[3]),
      defaultValue: Number(cols[4])
    }
  })
}

// params: array of { id, value }. Returns { name, bytes } of the rendered WAV.
async function render (appDir, pluginPath, wavPath, params) {
  const outPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'clap-render-')), 'out.wav')
  const args = ['--plugin', pluginPath, '--in', wavPath, '--out', outPath]
  for (const p of params) {
    args.push('--param', String(p.id) + '=' + String(p.value))
  }
  try {
    const result = await run(appDir, args)
    if (result.code !== 0) throw new Error(failureMessage(result.stderr))
    const stat = fs.statSync(outPath)
    if (stat.size > MAX_RENDER_READ_BYTES) throw new Error('rendered file too large')
    const base = path.basename(wavPath, path.extname(wavPath))
    return { name: base + ' (clap).wav', bytes: fs.readFileSync(outPath) }
  } finally {
    try {
      fs.rmSync(path.dirname(outPath), { recursive: true, force: true })
    } catch (err) {
      console.error('clap-render: temp cleanup failed:', String(err))
    }
  }
}

module.exports = { isAvailable, listParams, render, binaryPath }
