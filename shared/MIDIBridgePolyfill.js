// =============================================================================
// Multi-platform MIDI bridge polyfill
//
// Overrides navigator.requestMIDIAccess() and routes MIDI I/O through the
// host platform's native MIDI APIs.
//
// Supported platforms:
//   JUCE     – window.__JUCE__.invoke() / addEventListener('midiIn', ...)
//   Android  – window.EP133Bridge.getMidiDevices() / .sendMidi()
//   iOS      – window.webkit.messageHandlers.midibridge.postMessage()
//   Browser  – falls through to native Web MIDI API (Electron / Chrome)
// =============================================================================
(function () {
  'use strict';

  // midiListeners[portId] = [handler, ...]
  var midiListeners = {};

  // iOS async callback resolution
  var pendingCallbacks = {};
  var callbackCounter = 0;

  // Stored reference to last MIDIAccess for statechange notifications
  var lastMIDIAccess = null;
  var lastOptions = null;
  var stateChangeListeners = [];

  // ---- Platform detection ----

  function detectPlatform() {
    if (typeof window.__JUCE__ !== 'undefined') return 'juce';
    if (typeof window.EP133Bridge !== 'undefined') return 'android';
    if (window.webkit && window.webkit.messageHandlers &&
        window.webkit.messageHandlers.midibridge) return 'ios';
    return null;
  }

  // ---- Native bridge abstraction ----

  function getNativeDevices() {
    var platform = detectPlatform();

    if (platform === 'juce') {
      return window.__JUCE__.invoke('getMidiDevices');
    }

    if (platform === 'android') {
      var json = window.EP133Bridge.getMidiDevices();
      return Promise.resolve(JSON.parse(json));
    }

    if (platform === 'ios') {
      return new Promise(function (resolve) {
        var cbId = '_cb_' + (++callbackCounter);
        pendingCallbacks[cbId] = resolve;
        window.webkit.messageHandlers.midibridge.postMessage({
          action: 'getMidiDevices',
          callbackId: cbId
        });
      });
    }

    return null;
  }

  function sendNativeMidi(portId, data) {
    var platform = detectPlatform();

    if (platform === 'juce') {
      window.__JUCE__.invoke('sendMidi', portId, Array.from(data));
      return;
    }

    if (platform === 'android') {
      window.EP133Bridge.sendMidi(portId, JSON.stringify(Array.from(data)));
      return;
    }

    if (platform === 'ios') {
      window.webkit.messageHandlers.midibridge.postMessage({
        action: 'sendMidi',
        portId: portId,
        data: Array.from(data)
      });
      return;
    }
  }

  // ---- iOS callback resolution (called from Swift) ----

  window.__ep133_resolveCallback = function (callbackId, resultJSON) {
    var cb = pendingCallbacks[callbackId];
    if (cb) {
      delete pendingCallbacks[callbackId];
      cb(typeof resultJSON === 'string' ? JSON.parse(resultJSON) : resultJSON);
    }
  };

  // ---- Incoming MIDI from native (called from Swift / Kotlin / JUCE) ----

  window.__ep133_onMidiIn = function (portId, dataArray) {
    var listeners = midiListeners[portId] || [];
    var msg = {
      data: new Uint8Array(dataArray),
      target: { id: portId }
    };
    for (var i = 0; i < listeners.length; ++i) {
      try { listeners[i](msg); } catch (e) { console.error(e); }
    }
  };

  // ---- Install the polyfill ----

  function installBridge() {
    var platform = detectPlatform();

    // JUCE pushes incoming MIDI via its own event system
    if (platform === 'juce') {
      window.__JUCE__.addEventListener('midiIn', function (event) {
        window.__ep133_onMidiIn(event.portId, event.data);
      });
    }

    console.log('[EP133] MIDI bridge installed (' + platform + ')');

    // Override the Web MIDI API
    navigator.requestMIDIAccess = function (options) {
      return getNativeDevices().then(function (devices) {
        var inputs  = new Map();
        var outputs = new Map();

        (devices.inputs || []).forEach(function (d) {
          midiListeners[d.id] = midiListeners[d.id] || [];
          var port = {
            id:           d.id,
            name:         d.name,
            manufacturer: '',
            state:        'connected',
            connection:   'open',
            type:         'input',
            addEventListener: function (type, fn) {
              if (type === 'midimessage') {
                midiListeners[d.id] = midiListeners[d.id] || [];
                midiListeners[d.id].push(fn);
              }
            },
            removeEventListener: function (type, fn) {
              if (type === 'midimessage') {
                midiListeners[d.id] = (midiListeners[d.id] || []).filter(
                  function (f) { return f !== fn; });
              }
            }
          };
          Object.defineProperty(port, 'onmidimessage', {
            get: function () {
              return (midiListeners[d.id] || [])[0] || null;
            },
            set: function (fn) {
              midiListeners[d.id] = fn ? [fn] : [];
            }
          });
          inputs.set(d.id, port);
        });

        (devices.outputs || []).forEach(function (d) {
          outputs.set(d.id, {
            id:           d.id,
            name:         d.name,
            manufacturer: '',
            state:        'connected',
            connection:   'open',
            type:         'output',
            send: function (data) {
              sendNativeMidi(d.id, data);
            },
            clear: function () {},
            addEventListener:    function () {},
            removeEventListener: function () {}
          });
        });

        var access = {
          inputs:       inputs,
          outputs:      outputs,
          sysexEnabled: !!(options && options.sysex),
          onstatechange: null,
          addEventListener: function (type, fn) {
            if (type === 'statechange') stateChangeListeners.push(fn);
          },
          removeEventListener: function (type, fn) {
            if (type === 'statechange') {
              stateChangeListeners = stateChangeListeners.filter(function (f) { return f !== fn; });
            }
          }
        };

        lastMIDIAccess = access;
        lastOptions = options;
        return access;
      });
    };
  }

  // ---- Device change notification (called from native code) ----

  window.__ep133_onDevicesChanged = function () {
    console.log('[EP133] Devices changed, re-querying...');
    // Re-call requestMIDIAccess to rebuild device maps, then fire statechange
    if (navigator.requestMIDIAccess) {
      navigator.requestMIDIAccess(lastOptions || {}).then(function (newAccess) {
        // Copy the statechange handler from the old access
        if (lastMIDIAccess && lastMIDIAccess.onstatechange) {
          newAccess.onstatechange = lastMIDIAccess.onstatechange;
        }
        lastMIDIAccess = newAccess;

        // Fire statechange on the new access object
        var evt = { port: null };
        if (newAccess.onstatechange) {
          try { newAccess.onstatechange(evt); } catch (e) { console.error(e); }
        }
        for (var i = 0; i < stateChangeListeners.length; i++) {
          try { stateChangeListeners[i](evt); } catch (e) { console.error(e); }
        }
      });
    }
  };

  // ---- Initialization with retry ----

  function tryInstall() {
    var platform = detectPlatform();
    if (platform) {
      installBridge();
      return true;
    }
    return false;
  }

  if (!tryInstall()) {
    var attempts = 0;
    var timer = setInterval(function () {
      if (tryInstall() || ++attempts >= 50) {
        clearInterval(timer);
        if (attempts >= 50) {
          console.log('[EP133] No native bridge detected, using Web MIDI API');
        }
      }
    }, 100);
  }
})();
