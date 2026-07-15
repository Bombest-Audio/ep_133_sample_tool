'use strict';

// Splice folder watcher for the Electron main process.
//
// Watches a local Splice sample folder (read-only, no Splice API) and reports
// newly downloaded audio files. Pure helpers are exported separately from the
// watcher factory so they can be unit-checked with plain node.

const fs = require('fs');
const path = require('path');

// Audio formats the EP-133 web app can import.
const SAMPLE_EXTENSIONS = ['.wav', '.mp3', '.aiff', '.aif', '.flac', '.ogg'];

// Splice (and browsers/CDN clients generally) write partial downloads under
// temp names, then rename to the final name when complete.
const IGNORED_SUFFIXES = ['.part', '.partial', '.crdownload', '.download', '.tmp'];

/**
 * True when the file name has a supported audio extension.
 */
function isSampleFile(name) {
  if (typeof name !== 'string' || name.length === 0) return false;
  const ext = path.extname(name).toLowerCase();
  return SAMPLE_EXTENSIONS.indexOf(ext) !== -1;
}

/**
 * True for names that should never be surfaced: hidden files, editor/OS
 * artifacts, and partial-download temp files.
 */
function isIgnoredFile(name) {
  if (typeof name !== 'string' || name.length === 0) return true;
  const base = path.basename(name);
  if (base.charAt(0) === '.' || base.charAt(0) === '~') return true;
  const lower = base.toLowerCase();
  return IGNORED_SUFFIXES.some(function (suffix) {
    return lower.endsWith(suffix);
  });
}

/**
 * Platform default for the local Splice folder.
 * macOS: ~/Splice. Windows: the per-user Documents\Splice folder. Other
 * platforms fall back to ~/Splice; the user can point it elsewhere.
 */
function defaultSpliceFolder(platform, homeDir) {
  if (platform === 'win32') return path.join(homeDir, 'Documents', 'Splice');
  return path.join(homeDir, 'Splice');
}

/**
 * Collects items and flushes them as one batch after `delayMs` of quiet.
 * Deduplicates by string identity while a batch is pending.
 */
function createDebouncedCollector(delayMs, flush) {
  let pending = [];
  let timer = null;

  function add(item) {
    if (pending.indexOf(item) === -1) pending.push(item);
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(function () {
      timer = null;
      const batch = pending;
      pending = [];
      flush(batch);
    }, delayMs);
  }

  function cancel() {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    pending = [];
  }

  return { add: add, cancel: cancel };
}

/**
 * Recursively lists sample files under `dir`. Defensive: unreadable entries
 * are skipped, missing dirs return an empty list. Depth-capped so a
 * pathological symlink loop cannot hang the app.
 */
function scanDirectory(dir, maxDepth) {
  const depth = typeof maxDepth === 'number' ? maxDepth : 6;
  const results = [];
  if (depth < 0) return results;
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (err) {
    return results;
  }
  for (const entry of entries) {
    if (isIgnoredFile(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push.apply(results, scanDirectory(full, depth - 1));
    } else if (entry.isFile() && isSampleFile(entry.name)) {
      results.push(full);
    }
  }
  return results;
}

/**
 * Creates a watcher over `folder`. New sample files (not present at start)
 * are reported via `onNewSamples([{ path, name, size }])` after a debounce
 * window. Uses fs.watch recursive, which is supported on macOS and Windows.
 *
 * Returns { start, stop }. `start` returns true on success, false when the
 * folder does not exist or cannot be watched.
 */
function createSpliceWatcher(options) {
  const folder = options.folder;
  const onNewSamples = options.onNewSamples;
  const debounceMs = typeof options.debounceMs === 'number' ? options.debounceMs : 750;

  let watcher = null;
  let seen = null;
  let collector = null;

  function flush(paths) {
    const fresh = [];
    for (const p of paths) {
      let stat;
      try {
        stat = fs.statSync(p);
      } catch (err) {
        continue; // vanished between event and flush (temp file renamed away)
      }
      if (!stat.isFile() || seen.has(p)) continue;
      seen.add(p);
      fresh.push({ path: p, name: path.basename(p), size: stat.size });
    }
    if (fresh.length > 0) onNewSamples(fresh);
  }

  function handleEvent(eventType, filename) {
    if (!filename) return;
    const name = filename.toString();
    if (isIgnoredFile(name) || !isSampleFile(name)) return;
    collector.add(path.join(folder, name));
  }

  function start() {
    stop();
    try {
      if (!fs.statSync(folder).isDirectory()) return false;
    } catch (err) {
      return false;
    }
    seen = new Set(scanDirectory(folder));
    collector = createDebouncedCollector(debounceMs, flush);
    try {
      watcher = fs.watch(folder, { recursive: true }, handleEvent);
    } catch (err) {
      collector.cancel();
      collector = null;
      return false;
    }
    watcher.on('error', function () {
      stop();
    });
    return true;
  }

  function stop() {
    if (watcher !== null) {
      watcher.close();
      watcher = null;
    }
    if (collector !== null) {
      collector.cancel();
      collector = null;
    }
    seen = null;
  }

  return { start: start, stop: stop };
}

module.exports = {
  SAMPLE_EXTENSIONS: SAMPLE_EXTENSIONS,
  IGNORED_SUFFIXES: IGNORED_SUFFIXES,
  isSampleFile: isSampleFile,
  isIgnoredFile: isIgnoredFile,
  defaultSpliceFolder: defaultSpliceFolder,
  createDebouncedCollector: createDebouncedCollector,
  scanDirectory: scanDirectory,
  createSpliceWatcher: createSpliceWatcher
};
